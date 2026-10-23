# گزارش تست — آریا دانلودر 2.2.0

تاریخ بررسی: **2026-10-23**

این گزارش فقط نتیجهٔ تست‌های واقعاً اجراشده را ثبت می‌کند و موفقیت شبکهٔ زنده یا تبدیل native روی گوشی را از روی تست واحد نتیجه نمی‌گیرد.

## نتیجهٔ CI کد نهایی

- Pull Request: https://github.com/dana2008aminzadeh/Aria/pull/2
- Run: https://github.com/dana2008aminzadeh/Aria/actions/runs/40000995642
- Commit: `4f3593ff55bfa1538e5c7b06765c6e9efc840f9d`
- وضعیت: **موفق**

| مرحله | نتیجه |
|---|---|
| Kotlin/JVM/Robolectric | **۱۳۸ تست موفق، ۰ خطا** |
| موتور Python | **۱۹ تست موفق، ۰ خطا** |
| `lintDebug` | **موفق** |
| `assembleDebug` | **موفق** |
| آپلود `aria-debug-apk` | **موفق؛ artifact فشرده ۳۸٬۹۵۱٬۰۴۶ بایت** |

مجموع تست‌های خودکار: **۱۵۷ تست موفق**. اجرای محلی Python نیز با `yt-dlp 2026.10.21` و `certifi 2026.10.23` موفق بود.

## پوشش جدید 2.2.0

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

1. **Pornhub روی شبکهٔ کاربر:** خطای گزارش‌شده پیش از دریافت صفحه و شمارش فرمت بوده است. پیام شبکه و مسیر پروکسی پیاده‌سازی شده‌اند، اما بدون اجرای روی همان دستگاه/شبکه نمی‌توان ادعای دانلود موفق کرد.
2. **اجرای native FFmpeg:** CI ساخت APK، Lint و آرگومان‌های خالص JVM را تأیید می‌کند؛ تبدیل واقعی MP3/FLAC و Merge فرمت‌های مختلف باید روی Android فیزیکی آزمایش شود.
3. **دانلود زنده YouTube، Spotify و Aparat:** تست‌های رگرسیون بدون مصرف محتوای واقعی انجام شده‌اند و موفقیت فعلی سرویس‌ها یا تغییرات ضدربات را تضمین نمی‌کنند.
4. **سرعت واقعی:** هیچ Benchmark شبکه‌ای انجام نشده و عدد سرعت تضمین‌شده‌ای وجود ندارد.
5. **Release امضاشده:** CI فایل Debug می‌سازد؛ کلید و رمز امضا عمداً در Git نیست.

## اجرای مجدد

```bash
python3 -m pip install 'yt-dlp==2026.10.21' 'certifi==2026.10.23'
PYTHONPATH=app/src/main/python python3 -m unittest discover -s app/src/test/python -v

./gradlew testDebugUnitTest lintDebug assembleDebug
```
