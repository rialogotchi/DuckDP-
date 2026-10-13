# Duck DPI – Android (rootsuz, gömülü yerel VPN)

Web arayüzündeki ByeDPI mantığının Android karşılığı. **Root gerekmez.**

```
Uygulamalar (Discord, Roblox, tarayıcı…) ──► TUN (VpnService, 0.0.0.0/0 + ::/0)
        ──► hev-socks5-tunnel (tun2socks: TCP → CONNECT, UDP → UDP ASSOCIATE)
        ──► ByeDPI / ciadpi  (127.0.0.1:1080, ":dpi" işleminde)
        ──► gerçek ağ   (uygulamanın kendisi VPN dışında → döngü olmaz)
```

* Tüm TCP **ve UDP** tünellenir (Discord ses, Roblox oyun trafiği, QUIC); protokol/uygulama ayrımı yok.
* "Sunucu" yok: VPN tamamen cihaz üzerinde, dışarıya ek bir bağlantı kurmaz, IP adresinizi değiştirmez.
* ByeDPI'nin hazır stratejileri (`assets/strategies.list`) ve **Strateji bul** testi (Discord, Roblox, YouTube) var.

## APK üretmek

**Telefondan / bilgisayarsız:** projeyi GitHub'a yükleyin → *Actions → android-apk → Run workflow* →
çıkan `duckdpi-debug-apk` dosyasını indirip kurun (bilinmeyen kaynaklara izin gerekir).

**Bilgisayarda:**
```sh
./android/fetch-native.sh          # byedpi + hev-socks5-tunnel kaynaklarını indirir
cd android && gradle assembleDebug # JDK 17, Android SDK 34, NDK 26.1.10909125
```
(Android Studio ile `android/` klasörünü açmak da yeterli; wrapper'ı Studio üretir.)

## Bilmeniz gerekenler

* ByeDPI **IP engelini aşmaz** ve trafiği şifrelemez; SNI/DPI tabanlı engellerde işe yarar. DNS zehirlemesi varsa
  Android *Ayarlar → Özel DNS*'i (DoT) açın ya da uygulamada DNS'i değiştirin.
* UDP için ByeDPI'nin SOCKS5 UDP ASSOCIATE desteği kullanılır (`-Ku -a1`). Bir oyun/ses uygulaması hâlâ bağlanmıyorsa
  *Strateji bul*'u çalıştırın veya komut satırından `-a`/`-Ku` seçeneklerini değiştirin.
* Başka bir VPN ile aynı anda çalışmaz (Android'de tek VPN aktif olur).
* Bu kod burada derlenip bir cihazda denenmedi (ağ yok); ilk derlemede küçük düzeltmeler gerekebilir.
