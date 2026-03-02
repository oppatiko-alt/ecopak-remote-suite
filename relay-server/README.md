# Relay Server

## Start

```bash
npm install
npm start
```

## Env

- `PORT` default: `8080`
- `ECOPAK_RELAY_TOKEN` optional shared token
- `FRAME_FANOUT_FPS` default: `18`
- `REMOTE_BUFFER_MAX` default: `262144`
- `MAX_FRAME_B64` default: `320000`

## WebSocket messages

- `register_bridge`: `{type, sessionId, token}`
- `register_remote`: `{type, sessionId, token}`
- `viewer_count` (server -> bridge): `{type:"viewer_count", count}`
- `command`: `{type:"command", command:"a"}`
- `video_frame`: `{type:"video_frame", jpegBase64, ts, width, height}`
- `telemetry`: `{type:"telemetry", battery, status, ts}`

## Health

- `GET /health` returns:
  - `ok`, `sessions`, `clients`, `uptimeSec`
  - `framesIn`, `framesOut`, `framesDroppedOversize`, `framesDroppedBackpressure`
  - `commandsIn`, `commandsOut`
