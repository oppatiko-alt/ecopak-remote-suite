const http = require('http');
const { WebSocketServer } = require('ws');

const PORT = Number(process.env.PORT || 8080);
const AUTH_TOKEN = process.env.ECOPAK_RELAY_TOKEN || '';
const MAX_FRAME_B64 = Number(process.env.MAX_FRAME_B64 || 320000);
const MAX_FRAME_BYTES = Number(process.env.MAX_FRAME_BYTES || 120000);
const FRAME_FANOUT_FPS = Math.max(1, Number(process.env.FRAME_FANOUT_FPS || 18));
const REMOTE_BUFFER_MAX = Math.max(0, Number(process.env.REMOTE_BUFFER_MAX || 65536));
const UPTIME_STARTED_AT = Date.now();
const FRAME_MAGIC = 0x45;

const sessions = new Map();
const metrics = {
  framesIn: 0,
  framesOut: 0,
  framesDroppedOversize: 0,
  framesDroppedBackpressure: 0,
  commandsIn: 0,
  commandsOut: 0,
};

function getSession(sessionId) {
  let s = sessions.get(sessionId);
  if (!s) {
    s = { bridge: null, remotes: new Set(), latestFrame: null };
    sessions.set(sessionId, s);
  }
  return s;
}

function cleanSession(sessionId) {
  const s = sessions.get(sessionId);
  if (!s) return;
  if (!s.bridge && s.remotes.size === 0) {
    sessions.delete(sessionId);
  }
}

function sendJson(ws, payload) {
  if (ws.readyState === ws.OPEN) {
    try {
      ws.send(JSON.stringify(payload));
    } catch {
      // ignore send failures
    }
  }
}

function safeParse(raw) {
  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

function closeWith(ws, code, reason) {
  try {
    ws.close(code, reason);
  } catch {
    // ignore
  }
}

function isAuthorized(token) {
  if (!AUTH_TOKEN) return true;
  return token === AUTH_TOKEN;
}

function notifyViewerCount(session) {
  if (!session || !session.bridge) return;
  sendJson(session.bridge, {
    type: 'viewer_count',
    count: session.remotes.size,
  });
}

function parseBinaryFramePacket(raw) {
  const buf = Buffer.isBuffer(raw) ? raw : Buffer.from(raw);
  if (buf.length < 13) return null;
  if (buf[0] !== FRAME_MAGIC) return null;
  if (buf.length > MAX_FRAME_BYTES) return null;

  const ts = Number(buf.readBigInt64BE(1));
  const width = buf.readUInt16BE(9);
  const height = buf.readUInt16BE(11);
  const jpegBytes = buf.subarray(13);
  if (jpegBytes.length === 0) return null;

  return {
    kind: 'binary',
    data: Buffer.from(buf),
    ts,
    width,
    height,
  };
}

const server = http.createServer((req, res) => {
  if (req.url === '/health') {
    res.statusCode = 200;
    res.setHeader('Content-Type', 'application/json; charset=utf-8');
    res.end(
      JSON.stringify({
        ok: true,
        sessions: sessions.size,
        clients: wss.clients.size,
        uptimeSec: Math.floor((Date.now() - UPTIME_STARTED_AT) / 1000),
        ...metrics,
      }),
    );
    return;
  }

  res.statusCode = 404;
  res.end('Not found');
});

const wss = new WebSocketServer({ server, maxPayload: 2 * 1024 * 1024 });

wss.on('connection', (ws) => {
  ws.meta = {
    role: null,
    sessionId: null,
    isAlive: true,
  };

  ws.on('pong', () => {
    ws.meta.isAlive = true;
  });

  ws.on('message', (raw, isBinary) => {
    const role = ws.meta.role;
    const sessionId = ws.meta.sessionId;

    if (isBinary) {
      if (role !== 'bridge' || !sessionId) {
        return;
      }
      const session = sessions.get(sessionId);
      if (!session) {
        return;
      }

      const binaryFrame = parseBinaryFramePacket(raw);
      if (!binaryFrame) {
        metrics.framesDroppedOversize += 1;
        return;
      }

      metrics.framesIn += 1;
      session.latestFrame = binaryFrame;
      return;
    }

    const msg = safeParse(raw.toString());
    if (!msg || typeof msg.type !== 'string') {
      sendJson(ws, { type: 'error', message: 'invalid_json' });
      return;
    }

    if (msg.type === 'register_bridge') {
      const bridgeSessionId = String(msg.sessionId || '').trim();
      const token = String(msg.token || '');
      if (!bridgeSessionId) {
        sendJson(ws, { type: 'error', message: 'session_required' });
        return;
      }
      if (!isAuthorized(token)) {
        sendJson(ws, { type: 'error', message: 'auth_failed' });
        closeWith(ws, 4001, 'auth_failed');
        return;
      }

      const session = getSession(bridgeSessionId);
      if (session.bridge && session.bridge !== ws) {
        sendJson(session.bridge, { type: 'info', message: 'bridge_replaced' });
        closeWith(session.bridge, 4000, 'bridge_replaced');
      }

      ws.meta.role = 'bridge';
      ws.meta.sessionId = bridgeSessionId;
      session.bridge = ws;
      sendJson(ws, { type: 'registered', role: 'bridge', sessionId: bridgeSessionId });
      notifyViewerCount(session);

      for (const remote of session.remotes) {
        sendJson(remote, { type: 'bridge_status', connected: true });
      }
      return;
    }

    if (msg.type === 'register_remote') {
      const remoteSessionId = String(msg.sessionId || '').trim();
      const token = String(msg.token || '');
      if (!remoteSessionId) {
        sendJson(ws, { type: 'error', message: 'session_required' });
        return;
      }
      if (!isAuthorized(token)) {
        sendJson(ws, { type: 'error', message: 'auth_failed' });
        closeWith(ws, 4001, 'auth_failed');
        return;
      }

      const session = getSession(remoteSessionId);
      ws.meta.role = 'remote';
      ws.meta.sessionId = remoteSessionId;
      session.remotes.add(ws);
      sendJson(ws, {
        type: 'registered',
        role: 'remote',
        sessionId: remoteSessionId,
        bridgeConnected: Boolean(session.bridge),
      });
      notifyViewerCount(session);
      return;
    }

    if (!role || !sessionId) {
      sendJson(ws, { type: 'error', message: 'register_first' });
      return;
    }

    const session = sessions.get(sessionId);
    if (!session) {
      sendJson(ws, { type: 'error', message: 'session_not_found' });
      return;
    }

    if (msg.type === 'command') {
      if (role !== 'remote') {
        sendJson(ws, { type: 'error', message: 'only_remote_can_send_command' });
        return;
      }
      metrics.commandsIn += 1;
      if (!session.bridge) {
        sendJson(ws, { type: 'error', message: 'bridge_offline' });
        return;
      }
      sendJson(session.bridge, {
        type: 'command',
        command: String(msg.command || ''),
        ts: Date.now(),
      });
      metrics.commandsOut += 1;
      return;
    }

    if (msg.type === 'video_frame') {
      if (role !== 'bridge') {
        sendJson(ws, { type: 'error', message: 'only_bridge_can_stream' });
        return;
      }

      const frame = String(msg.jpegBase64 || '');
      if (!frame) {
        return;
      }
      if (frame.length > MAX_FRAME_B64) {
        metrics.framesDroppedOversize += 1;
        return;
      }

      metrics.framesIn += 1;
      session.latestFrame = {
        kind: 'json',
        payload: {
          type: 'video_frame',
          jpegBase64: frame,
          ts: Number(msg.ts || Date.now()),
          width: Number(msg.width || 0),
          height: Number(msg.height || 0),
        },
      };
      return;
    }

    if (msg.type === 'telemetry') {
      if (role !== 'bridge') {
        return;
      }
      const payload = {
        type: 'telemetry',
        ts: Number(msg.ts || Date.now()),
        battery: Number(msg.battery || 0),
        status: String(msg.status || ''),
      };
      for (const remote of session.remotes) {
        sendJson(remote, payload);
      }
      return;
    }

    sendJson(ws, { type: 'error', message: 'unknown_type' });
  });

  ws.on('close', () => {
    const { role, sessionId } = ws.meta;
    if (!role || !sessionId) return;

    const session = sessions.get(sessionId);
    if (!session) return;

    if (role === 'bridge' && session.bridge === ws) {
      session.bridge = null;
      session.latestFrame = null;
      for (const remote of session.remotes) {
        sendJson(remote, { type: 'bridge_status', connected: false });
      }
    }

    if (role === 'remote') {
      session.remotes.delete(ws);
      notifyViewerCount(session);
    }

    cleanSession(sessionId);
  });
});

const frameFanoutInterval = setInterval(() => {
  for (const session of sessions.values()) {
    if (!session.latestFrame) continue;
    if (session.remotes.size === 0) continue;

    const frame = session.latestFrame;
    session.latestFrame = null;

    for (const remote of session.remotes) {
      if (remote.readyState !== remote.OPEN) continue;
      if (remote.bufferedAmount > REMOTE_BUFFER_MAX) {
        metrics.framesDroppedBackpressure += 1;
        continue;
      }

      try {
        if (frame.kind === 'binary') {
          remote.send(frame.data, { binary: true });
        } else {
          sendJson(remote, frame.payload);
        }
        metrics.framesOut += 1;
      } catch {
        // ignore send errors
      }
    }
  }
}, Math.max(20, Math.round(1000 / FRAME_FANOUT_FPS)));

const heartbeatInterval = setInterval(() => {
  wss.clients.forEach((ws) => {
    if (!ws.meta.isAlive) {
      closeWith(ws, 1001, 'heartbeat_timeout');
      return;
    }
    ws.meta.isAlive = false;
    ws.ping();
  });
}, 15000);

wss.on('close', () => {
  clearInterval(frameFanoutInterval);
  clearInterval(heartbeatInterval);
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`ECOPAK relay listening on :${PORT}`);
});
