const http = require('http');
const { WebSocketServer } = require('ws');

const PORT = Number(process.env.PORT || 8080);
const AUTH_TOKEN = process.env.ECOPAK_RELAY_TOKEN || '';
const MAX_FRAME_B64 = Number(process.env.MAX_FRAME_B64 || 650000);

const sessions = new Map();

function getSession(sessionId) {
  let s = sessions.get(sessionId);
  if (!s) {
    s = { bridge: null, remotes: new Set() };
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
    ws.send(JSON.stringify(payload));
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

const server = http.createServer((req, res) => {
  if (req.url === '/health') {
    res.statusCode = 200;
    res.setHeader('Content-Type', 'application/json; charset=utf-8');
    res.end(JSON.stringify({ ok: true, sessions: sessions.size }));
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

  ws.on('message', (raw) => {
    const msg = safeParse(raw.toString());
    if (!msg || typeof msg.type !== 'string') {
      sendJson(ws, { type: 'error', message: 'invalid_json' });
      return;
    }

    if (msg.type === 'register_bridge') {
      const sessionId = String(msg.sessionId || '').trim();
      const token = String(msg.token || '');
      if (!sessionId) {
        sendJson(ws, { type: 'error', message: 'session_required' });
        return;
      }
      if (!isAuthorized(token)) {
        sendJson(ws, { type: 'error', message: 'auth_failed' });
        closeWith(ws, 4001, 'auth_failed');
        return;
      }

      const session = getSession(sessionId);
      if (session.bridge && session.bridge !== ws) {
        sendJson(session.bridge, { type: 'info', message: 'bridge_replaced' });
        closeWith(session.bridge, 4000, 'bridge_replaced');
      }

      ws.meta.role = 'bridge';
      ws.meta.sessionId = sessionId;
      session.bridge = ws;
      sendJson(ws, { type: 'registered', role: 'bridge', sessionId });

      for (const remote of session.remotes) {
        sendJson(remote, { type: 'bridge_status', connected: true });
      }
      return;
    }

    if (msg.type === 'register_remote') {
      const sessionId = String(msg.sessionId || '').trim();
      const token = String(msg.token || '');
      if (!sessionId) {
        sendJson(ws, { type: 'error', message: 'session_required' });
        return;
      }
      if (!isAuthorized(token)) {
        sendJson(ws, { type: 'error', message: 'auth_failed' });
        closeWith(ws, 4001, 'auth_failed');
        return;
      }

      const session = getSession(sessionId);
      ws.meta.role = 'remote';
      ws.meta.sessionId = sessionId;
      session.remotes.add(ws);
      sendJson(ws, {
        type: 'registered',
        role: 'remote',
        sessionId,
        bridgeConnected: Boolean(session.bridge),
      });
      return;
    }

    const role = ws.meta.role;
    const sessionId = ws.meta.sessionId;
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
      if (!session.bridge) {
        sendJson(ws, { type: 'error', message: 'bridge_offline' });
        return;
      }
      sendJson(session.bridge, {
        type: 'command',
        command: String(msg.command || ''),
        ts: Date.now(),
      });
      return;
    }

    if (msg.type === 'video_frame') {
      if (role !== 'bridge') {
        sendJson(ws, { type: 'error', message: 'only_bridge_can_stream' });
        return;
      }

      const frame = String(msg.jpegBase64 || '');
      if (!frame || frame.length > MAX_FRAME_B64) {
        return;
      }

      const payload = {
        type: 'video_frame',
        jpegBase64: frame,
        ts: Number(msg.ts || Date.now()),
        width: Number(msg.width || 0),
        height: Number(msg.height || 0),
      };

      for (const remote of session.remotes) {
        sendJson(remote, payload);
      }
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
      for (const remote of session.remotes) {
        sendJson(remote, { type: 'bridge_status', connected: false });
      }
    }

    if (role === 'remote') {
      session.remotes.delete(ws);
    }

    cleanSession(sessionId);
  });
});

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
  clearInterval(heartbeatInterval);
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`ECOPAK relay listening on :${PORT}`);
});
