# ECOPAK Remote Suite

Bu paket mevcut ECOPAK uygulamasından tamamen ayrıdır.

Bileşenler:
- `relay-server`: internet üzerinden komut + video frame relay
- `bridge-app`: robot üstündeki telefonda çalışır, Bluetooth + kamera yayın
- `remote-app`: operatör uygulaması, canlı görüntü + komut gönderimi

## Hızlı Akış
1. `relay-server` internet erişimli bir sunucuda açılır.
2. Robot telefonundaki `bridge-app`, relay'e bağlanır ve ECOPAK'a Bluetooth ile bağlanır.
3. Operatör tarafındaki `remote-app` aynı session id ile relay'e bağlanır.
4. Komutlar remote -> relay -> bridge -> bluetooth -> ECOPAK olarak akar.
5. Kamera görüntüsü bridge -> relay -> remote olarak akar.

## Not
Windows'ta Android build path sorunu yaşamamak için proje ASCII path altına alındı:
`C:\apk_work\ecopak_remote_suite`
