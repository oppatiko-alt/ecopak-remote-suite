# Kurulum ve Test

## 1) Relay Server

```bash
cd C:\apk_work\ecopak_remote_suite\relay-server
npm install
set ECOPAK_RELAY_TOKEN=toprak-123
npm start
```

Sağlık kontrolü:
- `http://SUNUCU_IP:8080/health`

## 2) Bridge App (Robot Üstündeki Telefon)

- APK: `bridge-app-debug.apk`
- Server URL: `wss://ecopakremote.onrender.com` (varsayılan)
- Session ID: örn `ecopak-demo`
- Token: `6em44j45` (varsayılan)
- BT MAC: ECOPAK Bluetooth MAC adresi

Sonra:
1. `Server Connect`
2. `BT Connect`

## 3) Remote App (Operatör)

- APK: `remote-app-debug.apk`
- Aynı Server URL, Session ID, Token girilir.
- `Connect` ile bağlanılır.
- Görüntü geldikten sonra kontrol tuşları ile komut gönderilir.

## Komut Eşlemesi
- Forward: `a`
- Backward: `b`
- Left: `e`
- Right: `f`
- Stop: `c`

## Güvenlik
- Mutlaka token kullanın.
- Sunucuyu TLS arkasına koyun (`wss://`).
- İleriki adım: tek kullanımlık oturum anahtarı + kullanıcı doğrulama.
