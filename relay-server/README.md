# Relay Server

## Start

```bash
npm install
npm start
```

## Env

- `PORT` default: `8080`
- `ECOPAK_RELAY_TOKEN` optional shared token
- `MAX_FRAME_B64` default: `650000`

## WebSocket messages

- `register_bridge`: `{type, sessionId, token}`
- `register_remote`: `{type, sessionId, token}`
- `command`: `{type:"command", command:"a"}`
- `video_frame`: `{type:"video_frame", jpegBase64, ts, width, height}`
- `telemetry`: `{type:"telemetry", battery, status, ts}`
