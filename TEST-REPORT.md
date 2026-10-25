# گزارش تست — آریا دانلودر 2.2.2

تاریخ بررسی: **2026-10-24**

این گزارش فقط نتیجهٔ تست‌های واقعاً اجراشده را ثبت می‌کند و موفقیت شبکهٔ زنده یا تبدیل native روی گوشی را از روی تست واحد نتیجه نمی‌گیرد.

## نتیجهٔ CI نسخهٔ دیباگ (Diag) — 5.4.0-diag.1

تاریخ بررسی: **2026-10-25**

- Pull Request: https://github.com/dana2008aminzadeh/Aria/pull/14
- Run موفق: https://github.com/dana2008aminzadeh/Aria/actions/runs/40223398111
- وضعیت: **موفق** (۳ دقیقه و ۴ ثانیه)

| مرحله | نتیجه |
|---|---|
| موتور Python (`app/src/test/python`) | **۴۴ تست موفق** (۸ تست جدید `DiagnosticsTest`؛ محلی و در CI) |
| Kotlin/JVM/Robolectric (`testDebugUnitTest`) | **موفق** (از جملهٔ `DiagLogTest` جدید برای ریداکت رمزها) |
| `lintDebug` | **موفق** |
| `assembleDebug` | **موفق** |
| `assembleDiag` | **موفق** |
| آپلود `alldown-diag-apk` | **موفق؛ Artifact شمارهٔ `12609898842`، ۳۹٬۰۶۴٬۳۱۰ بایت، تا 2026-11-08** |

پوشش جدید: ماسک‌شدن توکن/رمز در URL و متن آزاد بدون پنهان‌شدن شناسه‌هایی مثل `viewkey`،
یکسان‌ماندن opts یوتیوب‌دی‌ال‌پی (`yt-dlp`) در حالت بدون دیاگ، فعال‌شدن Verbose و لاگر
تشخیصی فقط با `diag_log_path`، و ثبت Traceback واقعی پشت پیام‌های خطای کاربر.
**نصب روی گوشی و دانلود زنده هنوز تست نشده است** — همین نسخه برای همان تست ساخته شده و
لاگ برگشتی مبنای رفع باگ‌های بعدی است (راهنما: `DIAGNOSTICS.md`).

## نتیجهٔ CI پیاده‌سازی 2.2.2

- Pull Request: https://github.com/dana2008aminzadeh/Aria/pull/4
- Run: https://github.com/dana2008aminzadeh/Aria/actions/runs/40091527169
- Commit: `936393d5c08eec5d9d80f03b96c4a05cc1bacf70`
- وضعیت: **موفق**

| مرحله | نتیجه |
|---|---|
| Kotlin/JVM/Robolectric | **۱۵۰ تست موفق، ۰ خطا** |
| موتور Python | **۲۶ تست موفق، ۰ خطا** |
| `lintDebug` | **موفق** |
| `assembleDebug` | **موفق** |
| آپلود `aria-debug-apk` | **موفق؛ artifact فشرده ۳۸٬۹۶۷٬۰۰۰ بایت** |

مجموع تست‌های خودکار: **۱۷۶ تست موفق**. اجرای محلی Python نیز با `yt-dlp 2026.10.21` و `certifi 2026.10.23` موفق بود. Artifact با شناسهٔ `12559143298` و SHA-256 فشردهٔ `bfbb36d45a04b241129446f72fd73a6004508f77e19e8e98bd7b47a8788767d3` تا 2026-10-31 در Run بالا در دسترس است.

## پوشش جدید 2.2.2

- عبور درخواست دارای Extension امتیاز `impersonate` از handler سازگار Android بدون ادعای TLS fingerprint جعلی؛ تست واقعی ارسال چنین درخواستی و حفظ اولویت handler واقعی.
- حفظ فرمت‌های Progressive در Pornhub/Aparat و جلوگیری از شکست پیش از شبکه در نبود native curl-impersonate.
- تبدیل لینک Post/Reel/IGTV اینستاگرام به Canonical URL بدون ساخت Shortcode از لینک `/share/` یا Audio.
- fallback عمومی Instagram برای Probe، Resolve و Download؛ خواندن Meta و `video_versions`، Decode ایمن URL، رد Scheme غیر HTTPS و حفظ Format ID سازگار با انتخاب Exact.
- توقف فوری هنگام Cancel بدون شروع درخواست شبکهٔ دوم و جداسازی پیام Rate Limit از نیاز قطعی به Login.
- fallback متادیتای `oEmbed` برای آهنگ تکی Spotify، یافتن Entity در Wrapper تغییرکرده، Artist Array و `durationMs`.
- انتقال خطای واقعی Search به هشدار Spotify به‌جای تبدیل همهٔ خطاها به «آهنگ پیدا نشد».
- استفاده از Header پیش‌فرض به‌روز yt-dlp به‌جای UA قدیمی برنامه، Chunk شانزده‌مگابایتی، Buffer یک‌مگابایتی، ۶ Fragment همزمان و تست نبود Rate Limit مصنوعی.

## پوشش حفظ‌شده 2.2.1

- دریافت چند نتیجهٔ جست‌وجو به‌جای انتخاب کورکورانهٔ نتیجهٔ اول برای Spotify.
- امتیازدهی و مقایسهٔ عنوان، هنرمند، مدت و نام کانال؛ رد نتیجهٔ مبهم یا ناسازگار با هشدار فارسی به‌جای دانلود آهنگ اشتباه.
- کنترل سازگاری نسخه‌های Cover، Karaoke، Remix، Live، Acoustic، Instrumental، sped-up، slowed و reverb.
- جست‌وجوی دقیق‌تر تکمیلی با نام تمام هنرمندان، حفظ نام‌هایی مانند `AC/DC` و `With` در ابتدای عنوان، و پشتیبانی از مدت اعشاری.
- تست رگرسیون برای آهنگ اشتباه، اختلاف مدت، نام فارسی/Unicode، نسخه‌های متفاوت، و نتیجهٔ مبهم.

## پوشش حفظ‌شده 2.2.0

### فهرست و انتخاب فرمت

- حفظ ردیف‌های VP9/AV1/WebM و اطلاعات Codec، FPS، بیت‌ریت، زبان، ابعاد و حجم در Probe.
- انتخاب Exact جفت VP9 + Opus و ساخت WebM، بدون محدودسازی به H.264.
- انتخاب Exact MP4 → MKV با Plan واقعی `remux`.
- رد صریح ظرف ناسازگار و فرمت حذف‌شده؛ سقوط بی‌صدا به کیفیت دیگر انجام نمی‌شود.
- انتخاب صدا از جفت DASH فقط Format ID صدا را برای دانلود برمی‌دارد، نه ویدیو.
- انتخاب جداگانه برای لینک تکی، تمام ورودی‌های پلی‌لیست و نتیجهٔ Spotify در تست‌های LinkIngestor.
- حفظ اولویت AAC/M4A در حالت صوتی و نمایش Opus/دیگر ردیف‌ها به‌عنوان انتخاب واقعی.

### صف و خروجی

- ماندگاری Format ID تصویر/صدا، نوع خروجی، پسوند، بیت‌ریت و Label در Repository و JSON.
- بازیابی انتخاب Exact پس از ذخیره و خواندن صف؛ آیتم‌های قدیمی همچنان مسیر Preset دارند.
- ساخت آرگومان‌های واقعی FFmpeg برای Merge، Remux، MP3 و FLAC؛ مسیرهای دارای فاصله/نقل‌قول به‌صورت Argument Array باقی می‌مانند.
- رد پسوند صوتی نامعتبر به‌جای جعل خروجی با Rename.
- اعمال پسوند خروجی واقعی حتی اگر عنوان رسانه خودش به `.mp4` یا `.mp3` ختم شود.
- تست قبلی ادغام فایل‌های واقعی H.264/AAC با mp4parser همچنان موفق است.

### شبکه و پروکسی

- پیام قابل‌عمل برای `Errno 111 Connection refused` با راهنمای VPN/پروکسی.
- پیام جداگانه برای خرابی DNS.
- ارسال Proxy تریم‌شده به yt-dlp و حذف مقدار خالی از Options.
- اعتبارسنجی HTTP(S)/SOCKS5، افزودن Scheme پیش‌فرض و رد Path/Scheme نامعتبر.
- ماندگاری تنظیم پروکسی در SharedPreferences.

### رگرسیون‌های قبلی

- Aparat و Pornhub بدون فیلدهای Codec همچنان Progressive شناسایی می‌شوند.
- YouTube/Spotify و Presetهای قدیمی، لینک‌های Embed، دانلود مستقیم و Resume، صف، اعلان، نام/MIME و سرویس Foreground در مجموعهٔ قبلی پوشش دارند و سبز هستند.

## محیط بیلد

- Ubuntu GitHub Actions، JDK 17 Temurin، Python 3.13
- Gradle 7.5 / AGP 7.4.2 / Kotlin 1.8.22
- Android compileSdk 33 / minSdk 24 / arm64-v8a
- Chaquopy 17.1.0
- `yt-dlp==2026.10.21` و `certifi==2026.10.23`
- `dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.9` (LGPL؛ باینری از Maven، نه Git)

## مواردی که هنوز باید روی دستگاه تأیید شوند

1. **Pornhub روی شبکهٔ کاربر:** تست‌ها سازگاری درخواست Impersonate-marked و شمارش فرمت را تأیید می‌کنند، اما دسترسی به صفحه/CDN روی همان دستگاه و شبکه آزمایش نشده است؛ بنابراین هنوز ادعای دانلود زندهٔ موفق نمی‌کنیم.
2. **Instagram عمومی و خصوصی:** Parser و مسیر fallback با Fixture و Mock آزموده شده‌اند، نه با پست زندهٔ کاربر. محتوای خصوصی/نیازمند ورود بدون حساب مجاز قابل دسترسی نیست و Rate Limit شبکه نیز باید روی دستگاه بررسی شود.
3. **اجرای native FFmpeg:** CI ساخت APK، Lint و آرگومان‌های خالص JVM را تأیید می‌کند؛ تبدیل واقعی MP3/FLAC و Merge فرمت‌های مختلف باید روی Android فیزیکی آزمایش شود.
4. **دانلود زنده YouTube، Spotify و Aparat:** تست‌های رگرسیون بدون مصرف محتوای واقعی انجام شده‌اند و موفقیت فعلی سرویس‌ها یا تغییرات ضدربات را تضمین نمی‌کنند. آهنگ مشخصی که کاربر قرار است ارسال کند هنوز در دسترس نیست و باید جداگانه بررسی شود.
5. **سرعت واقعی:** هیچ Benchmark شبکه‌ای انجام نشده و عدد سرعت تضمین‌شده‌ای وجود ندارد؛ Chunk/Buffer و اتصال موازی بیشتر، Throttling سرویس یا محدودیت اینترنت را حذف نمی‌کنند.
6. **Release امضاشده:** CI فایل Debug می‌سازد؛ کلید و رمز امضا عمداً در Git نیست.

## اجرای مجدد

```bash
python3 -m pip install 'yt-dlp==2026.10.21' 'certifi==2026.10.23'
PYTHONPATH=app/src/main/python python3 -m unittest discover -s app/src/test/python -v

./gradlew testDebugUnitTest lintDebug assembleDebug
```
