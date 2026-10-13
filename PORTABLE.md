# Duck DPI – taşınabilir Linux paketi (ByeDPI + PocketBase + gömülü Java + Jetty)

Tek dosya, kurulum yok, **root gerekmez**: `duckdns-linux-amd64.run` (ya da arm64). İçinde şunlar var:
Java 17 runtime (Temurin JRE), Jetty 9.4, `duckdns.war`, gerekli `.jar` bağımlılıkları, PocketBase,
**ByeDPI (`ciadpi`)**, varsayılan config dosyaları ve başlatıcı script.

Bu sürümde DDNS servisi (alan adı / IP güncelleme) tamamen kaldırıldı; yerine [ByeDPI](https://github.com/hufrea/byedpi)
geldi: DPI (derin paket denetimi) tabanlı engelleri aşmak için, ilk paketleri bölen / karıştıran / sahte paket ekleyen
yerel bir **SOCKS5 proxy**. Android'deki [ByeByeDPI](https://github.com/romanvht/ByeByeDPI) uygulamasının kullandığı motorun aynısıdır.
Paketleme, kullanıcı yönetimi, PocketBase verisi, giriş sayfası ve başlatıcı aynen kaldı (komutun adı hâlâ `duckdns`).

## Root gerekmez – neden?

`ciadpi` sıradan bir kullanıcı programıdır: yüksek olmayan bir portta (varsayılan `127.0.0.1:1080`) dinler.
VPN/TUN arayüzü, iptables/nftables kuralı, raw socket ya da capability kullanılmaz. Hangi uygulamanın (tarayıcı, Telegram, curl…)
proxy'den geçeceğini siz seçersiniz. Web arayüzü de proxy'yi yine aynı kullanıcı olarak başlatır.

## Paketi üretmek

```sh
packaging/build.sh          # bulunduğunuz makinenin mimarisi (uname -m)
packaging/build.sh amd64    # x86-64  → dist/duckdns-linux-amd64.run
packaging/build.sh arm64    # aarch64 → dist/duckdns-linux-arm64.run
```

Build makinesinde JDK 8 veya üstü (`javac`, `jar`), `curl`, `tar`, `unzip`, **`make` ve bir C derleyicisi** ve internet gerekir
(Jetty, JRE, PocketBase ve ByeDPI kaynağı `.build-cache/` içine indirilir). ByeDPI, ByeByeDPI'nin yaptığı gibi kaynaktan derlenir
(`github.com/hufrea/byedpi`); derlenmiş ikili `.build-cache/ciadpi-<mimari>` olarak saklanır (yeniden derlemek için silin).

| Değişken | Anlamı |
|---|---|
| `BYEDPI_REF` | indirilecek dal / etiket / commit (varsayılan `main`) |
| `BYEDPI_SRC` | indirmek yerine bu kaynak dizinini kullan |
| `BYEDPI_BIN` | hazır bir `ciadpi` dosyası ver (derleme yok) |
| `BYEDPI_CC`, `BYEDPI_LDFLAGS` | derleyici / bağlayıcı bayrakları. Her Linux'ta çalışan bir ikili için: `BYEDPI_CC=musl-gcc BYEDPI_LDFLAGS=-static` |

Çapraz derleme (örn. amd64 makinede arm64) için `aarch64-linux-gnu-gcc` gerekir ya da `BYEDPI_BIN` verin.
Diğer değişkenler: `JETTY_VERSION`, `JRE_MAJOR`, `PB_VERSION` (en az 0.23), `DUCKDNS_VERSION`.

## Çalıştırmak

```sh
./duckdns-linux-amd64.run            # ön planda çalışır, Ctrl-C hepsini kapatır
./duckdns-linux-amd64.run start      # arka planda  (stop / status / restart de var)
```

İlk çalıştırmada `~/.duckdns` altına açılır ve (hiç kullanıcı yoksa) **rastgele şifreli bir `admin`
hesabı** oluşturup şifreyi ekrana bir kez yazar. Web arayüzü: `http://<ip>:8080`,
PocketBase yönetim paneli: `http://127.0.0.1:8090/_/` (yalnızca bu makineden).
Web uygulaması açılınca DPI proxy'yi de başlatır (ayar: *autostart*), kapanınca durdurur; çökerse kendisi yeniden başlatır.

Sonradan kayıt (sign-up) yok; kullanıcıları yönetici ekler:

```sh
./duckdns-linux-amd64.run user add alice      # şifreyi sorar (en az 8 karakter)
./duckdns-linux-amd64.run user passwd alice
./duckdns-linux-amd64.run user del alice
./duckdns-linux-amd64.run user list
```

## DPI proxy'yi kullanmak

Web arayüzünde giriş yapınca ana sayfada dört bölüm var:

* **proxy** – durum (çalışıyor / durdu / başlatılamadı), SOCKS5 adresi, pid, çalışma süresi; *start / restart / stop*.
* **settings** – dinleme adresi ve port, sahte SNI (`{sni}` yerine geçer), ByeByeDPI'nin strateji listesinden hazır ayarlar (preset),
  ByeDPI komut satırı, "web uygulamasıyla birlikte başlat".
* **test** – seçilen sitelere geçici bir proxy üzerinden istek atar ve kaç tanesinin tam cevap verdiğini sayar
  (ByeByeDPI'deki "proxy test"). *find a strategy* tüm hazır ayarları sırayla dener; sonuçtaki *use* komut satırını forma kopyalar.
* **log** – `ciadpi` çıktısı (dosyada: `~/.duckdns/logs/byedpi.log`).

Sonra uygulamayı `socks5://127.0.0.1:1080` adresine yönlendirin (Firefox, Chromium `--proxy-server`, `curl --socks5-hostname`,
Telegram… ayrıntılar arayüzdeki **setup** sayfasında). Başka bir bilgisayardan kullanmak için SSH tüneli kullanın:
`ssh -N -L 1080:127.0.0.1:1080 kullanici@sunucu`.

> **Güvenlik:** ByeDPI'nin parolası yoktur. Dinleme adresi varsayılan olarak `127.0.0.1`'dir. `0.0.0.0` ya da LAN adresi yaparsanız
> o porta erişebilen herkes proxy'yi kullanabilir (arayüz bir uyarı gösterir) – güvenlik duvarıyla kısıtlayın.
> ByeDPI trafiği şifrelemez, IP adresinizi gizlemez; IP ya da DNS tabanlı engelleri aşmaz.

Komut satırından:

```sh
./duckdns dpi show                          # ayarları göster
./duckdns dpi set port 2080                 # ip | port | args | sni | autostart (on|off)
./duckdns dpi set args "-d1 -s3+s -a1"
./duckdns dpi reset                         # varsayılanlar
./duckdns restart                           # çalışan proxy değişikliği yeniden başlatınca alır
./duckdns byedpi --help                     # paketteki ciadpi'yi elle çalıştır
```

Betikler / cron için **token API** (eski DDNS güncelleme adresinin yerine; token ana sayfada görünür, *recreate token* değiştirir):

```sh
curl "http://SUNUCU:8080/control?token=TOKEN&action=status"    # JSON
curl "http://SUNUCU:8080/control?token=TOKEN&action=restart"   # start | stop | restart  →  OK  ya da  KO: neden
```

Ayarlar bu API ile değiştirilemez (arayüz ya da `./duckdns dpi set`). `environment.properties` içinde `account-read-only=true`
ise web arayüzünden ayar değişikliği, token yenileme ve hesap silme kapanır (start/stop çalışmaya devam eder).

Eski yerel JSON verisi (`~/.duckdns-local/data/accounts.json`) varsa hesaplar içe aktarılır:

```sh
./duckdns-linux-amd64.run import-json ~/.duckdns-local/data
```

(`domains.json` yok sayılır – DDNS kalktı. Eski şifreler, `secrets.properties` içindeki `salt` değeri aynı kaldığı sürece çalışır ve
ilk girişte otomatik olarak PBKDF2'ye yükseltilir. Salt'ı değiştirdiyseniz `user passwd` ile yeni şifre verin.)

Kurulumdan sonra hızlı doğrulama (tüm JSP'ler derleniyor mu, giriş çalışıyor mu, DPI uç noktası ve `ciadpi` bulundu mu):

```sh
packaging/smoke-test.sh http://127.0.0.1:8080 admin 'ADMIN-SIFRESI'
```

## Nerede ne var (`$DUCKDNS_HOME`, varsayılan `~/.duckdns`)

| Yol | İçerik |
|---|---|
| `config/duckdns.conf` | port (`HTTP_PORT`), bind adresi, JVM ayarı, PocketBase modu, isteğe bağlı `DPI_BIN` (başka bir `ciadpi` yolu) |
| `config/environment.properties`, `secrets.properties` | mevcut uygulama ayarları (eskisiyle aynı) |
| `config/pocketbase.properties` | PocketBase adresi + superuser (ilk çalıştırmada otomatik üretilir) |
| `data/pb_data/` | **tüm veri** (PocketBase / SQLite: hesaplar + DPI ayarları) – yedeklemek için bunu kopyalayın |
| `logs/` | `duckdns.log`, `pocketbase.log`, `byedpi.log` |
| `run/` | pid dosyaları (`byedpi.pid`: web uygulaması sert öldürülürse artakalan `ciadpi` bir sonraki başlatmada temizlenir) |
| `app/<build>/` | paketin açılmış hâli (silinebilir, yeniden açılır) |

Harici bir PocketBase kullanmak için `duckdns.conf` içinde `POCKETBASE_MANAGED=false` yapıp
`pocketbase.properties` içine adres + superuser bilgisini yazın (v0.23+).

## Veri modeli (PocketBase)

Uygulama ilk bağlandığında koleksiyonları kendisi oluşturur (API kuralları `null` → sadece superuser):
`duck_accounts` (kullanıcı adı, token, şifre hash'i), `duck_sessions` (yalnızca `dynamo-session-cache=true` ise kullanılır) ve
`dpi_settings` (tek kayıt: ip, port, args, sni, preset, autostart). Eski sürümden kalan `duck_domains` koleksiyonu artık kullanılmaz;
istenirse PocketBase panelinden silinebilir.

## Bu sürümde değişenler

* **DDNS kaldırıldı**: alan adı ekleme/IP güncelleme arayüzü, `/update`, `/nic`, `/v3`, `/gnudip` uç noktaları, DNS önbellek push'u,
  yönlendirici kurulum sayfaları (install, spec, why, faqs, pp, tac) ve ilgili görseller silindi.
* **DPI servisi eklendi**: `org.duckdns.dpi` (süreç yöneticisi, argüman doğrulama, strateji testi, JSON API), `/dpi` ve `/control` servletleri,
  ana sayfada kontrol paneli, `setup` ve `about` sayfaları, `./duckdns dpi …` ve `./duckdns byedpi …` komutları.
* `ciadpi` build sırasında kaynaktan derlenip pakete konur; başlatıcı `DUCKDNS_DPI_BIN`, `DUCKDNS_RUN_DIR`, `DUCKDNS_LOG_DIR` değişkenlerini web uygulamasına verir.
* Web arayüzü artık dış CDN'e (jQuery) bağlı değil; engellenmiş bir ağda da açılır.
* Ana sayfadaki değişiklik isteği (POST) oturuma bağlı CSRF token ister.
* Öncekilerden aynen kalanlar: PocketBase veri katmanı, PBKDF2 şifreler, sign-up yok (`user add`), Servlet 3.0 `web.xml`, `logout.jsp`/`monitor.jsp` düzeltmeleri.

## Testler

```sh
test/run-tests.sh      # DAO + CLI + DPI (argümanlar, süreç yöneticisi, strateji testi, JSON API, servletler); JDK 11+ gerekir
```

Testler gerçek `ciadpi` yerine `FakeCiadpi` (küçük bir SOCKS5 yönlendirici) ve yerel bir TLS sunucusu kullanır; internet ya da PocketBase ikilisi gerekmez.
