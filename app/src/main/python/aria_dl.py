"""
AllDown 5.1.0 - extraction engine.

This file is shipped inside the APK and executed by Chaquopy (CPython 3.13 on Android).
It is also run by the plain CPython interpreter in the test suite, so the code that is
tested is exactly the code that runs on the phone.

Only `yt_dlp` (+ optional `certifi`) is required.

Public API (everything is JSON so Kotlin can consume it directly):

    engine_info()                     -> json
    probe(url, options_json)          -> json   (metadata + formats + playlist entries)
    resolve(url, options_json)        -> json   (download plan for a quality preset)
    download(request_json, callback)  -> json   (blocking download, events via callback)
    search(query, limit)              -> json   (used by the Spotify bridge)
"""

import html as html_lib
from html.parser import HTMLParser
import json
import os
import re
import sys
import urllib.parse

try:  # Android has no system CA bundle: yt-dlp/certifi provide one.
    import certifi

    os.environ.setdefault("SSL_CERT_FILE", certifi.where())
    os.environ.setdefault("REQUESTS_CA_BUNDLE", certifi.where())
except Exception:  # pragma: no cover - optional
    pass

import yt_dlp  # noqa: E402  (must come after the SSL env vars)
from yt_dlp.networking import Request  # noqa: E402
from yt_dlp.networking._urllib import UrllibRH  # noqa: E402

ENGINE_VERSION = "5.1.0"

# Conservative defaults that improve throughput without creating dozens of connections.
# HLS/DASH sites (including Aparat and Pornhub) benefit from parallel fragments; regular
# HTTP media benefits from ranged chunks and a larger IO buffer. No rate limiter is set.
DEFAULT_FRAGMENT_CONCURRENCY = 6
MAX_FRAGMENT_CONCURRENCY = 8
HTTP_CHUNK_SIZE = 16 * 1024 * 1024
IO_BUFFER_SIZE = 1024 * 1024
MAX_PLAYLIST_ITEMS = 120


class _AndroidUrllibRH(UrllibRH):
    """Runs requests marked for browser impersonation on Android's available stack.

    curl-cffi is preferred automatically when it is installed. Chaquopy does not provide
    its native curl-impersonate library on every supported device, though, and yt-dlp's
    Pornhub extractor otherwise rejects the request before any network I/O. Accepting the
    extension here restores the ordinary HTTPS behaviour; it does not fake a TLS fingerprint
    or bypass authentication, and Instagram can still tell that real impersonation is absent.
    """

    RH_KEY = "Urllib"
    RH_NAME = "urllib-android-compat"

    def _check_extensions(self, extensions):
        extensions.pop("impersonate", None)
        super()._check_extensions(extensions)


class _AriaYoutubeDL(yt_dlp.YoutubeDL):
    """Installs the Android compatibility handler without disabling real handlers."""

    def build_request_director(self, handlers, preferences=None):
        handlers = [
            _AndroidUrllibRH if handler.RH_KEY == "Urllib" else handler
            for handler in handlers
        ]
        return super().build_request_director(handlers, preferences)


# --------------------------------------------------------------------------- helpers


def _sanitize(text, limit=160):
    if text is None:
        return ""
    text = str(text)
    text = re.sub(r"[\x00-\x1f]", " ", text)
    text = re.sub(r"\s+", " ", text).strip()
    return text[:limit]


def _human_error(exc):
    msg = _sanitize(exc, 400)
    msg = re.sub(r"^ERROR:\s*", "", msg)
    low = msg.lower()
    if "sign in to confirm" in low or "not a bot" in low:
        return "یوتیوب موقتاً این درخواست را مسدود کرده (بررسی ربات). چند دقیقه بعد یا با کوکی دوباره تلاش کنید."
    if "rate-limit" in low or "rate limit" in low or "http error 429" in low:
        return ("سایت دسترسی این IP را موقتاً محدود کرده است. کمی بعد دوباره تلاش کنید؛ "
                "اگر ادامه داشت، شبکه یا پروکسی را تغییر دهید. این خطا به معنای نیاز قطعی به کوکی نیست.")
    if "empty media response" in low:
        return ("اینستاگرام پاسخ عمومی برای این لینک نداد. اگر پست در مرورگر بدون ورود باز می‌شود، "
                "احتمالاً محدودیت موقت IP/شبکه است؛ بعداً یا با پروکسی تلاش کنید. محتوای خصوصی به ورود نیاز دارد.")
    if "login_required" in low or "login required" in low:
        return "این محتوا نیاز به ورود دارد. فایل cookies.txt را از تنظیمات وارد کنید."
    if "unsupported url" in low:
        return "این لینک توسط موتور استخراج پشتیبانی نمی‌شود."
    if "private" in low:
        return "این محتوا خصوصی است یا در دسترس نیست."
    if "geo" in low and "restrict" in low:
        return "این محتوا در منطقه شما محدود شده است."
    if "http error 403" in low:
        return "دسترسی رد شد (۴۰۳). شاید لینک منقضی شده باشد."
    if "http error 404" in low:
        return "محتوا پیدا نشد (۴۰۴)."
    if ("connection refused" in low or "connectionrefusederror" in low or
            "errno 111" in low):
        return ("اتصال به سرور رد شد (Errno 111). ممکن است سایت روی شبکهٔ فعلی "
                "مسدود باشد. اینترنت یا VPN را بررسی کنید، یا یک پروکسی معتبر در "
                "تنظیمات وارد کنید و دوباره تلاش کنید.")
    if ("temporary failure in name resolution" in low or
            "name or service not known" in low or "getaddrinfo failed" in low or
            "nodename nor servname" in low):
        return ("آدرس سرور از طریق DNS پیدا نشد. اتصال اینترنت و DNS/VPN را بررسی "
                "کنید؛ در صورت محدودیت شبکه می‌توانید از پروکسی تنظیمات استفاده کنید.")
    if "network is unreachable" in low or "errno 101" in low:
        return ("مسیر شبکه به این سرور در دسترس نیست. اتصال اینترنت، VPN یا پروکسی "
                "را بررسی کنید و دوباره تلاش کنید.")
    if "timed out" in low or "timeout" in low:
        return ("اتصال به سرور زمان‌بر شد. اینترنت را بررسی کنید؛ اگر سایت محدود "
                "است، از VPN یا پروکسی تنظیمات استفاده کنید.")
    if "certificate verify failed" in low or "ssl certificate" in low:
        return "گواهی امن اتصال تأیید نشد. تاریخ دستگاه، VPN و پروکسی را بررسی کنید."
    if "drm" in low:
        return "این محتوا محافظت‌شده (DRM) است و قابل دانلود نیست."
    return msg or "خطای ناشناخته در استخراج"


def _base_opts(options):
    try:
        fragment_concurrency = int(options.get(
            "fragment_concurrency", DEFAULT_FRAGMENT_CONCURRENCY))
    except (TypeError, ValueError):
        fragment_concurrency = DEFAULT_FRAGMENT_CONCURRENCY
    fragment_concurrency = max(1, min(MAX_FRAGMENT_CONCURRENCY, fragment_concurrency))
    opts = {
        "quiet": True,
        "no_warnings": True,
        "noprogress": True,
        "noplaylist": False,
        "socket_timeout": 30,
        "retries": 5,
        "fragment_retries": 5,
        "ignoreerrors": False,
        "no_color": True,
        "trim_file_name": 120,
        "extractor_retries": 3,
        "concurrent_fragment_downloads": fragment_concurrency,
        "http_chunk_size": HTTP_CHUNK_SIZE,
        "buffersize": IO_BUFFER_SIZE,
    }
    cookies = options.get("cookies_path")
    if cookies and os.path.exists(cookies):
        opts["cookiefile"] = cookies
    ua = options.get("user_agent")
    if ua:
        opts["http_headers"] = {"User-Agent": ua}
    proxy = options.get("proxy")
    if isinstance(proxy, str) and proxy.strip():
        # yt-dlp passes this to both webpage extraction and media/fragment requests.
        # Empty strings must not override the process/network defaults.
        opts["proxy"] = proxy.strip()
    return opts


# --------------------------------------------------------------------------- formats

_AVC = re.compile(r"^(avc|h264)", re.I)
_AAC = re.compile(r"^(mp4a|aac)", re.I)


def _int_value(value):
    try:
        return int(value) if value else None
    except (TypeError, ValueError, OverflowError):
        return None


def _kind(f):
    """Classifies a yt-dlp format without discarding unknown codecs.

    A missing codec is not the explicit string ``"none"``. Aparat and Pornhub
    commonly return progressive MP4 rows with no codec fields at all.
    """
    ext = str(f.get("ext") or "").lower()
    raw_vcodec = f.get("vcodec")
    raw_acodec = f.get("acodec")
    vcodec = str(raw_vcodec).lower() if raw_vcodec is not None else ""
    acodec = str(raw_acodec).lower() if raw_acodec is not None else ""
    has_video = vcodec != "none"
    has_audio = acodec != "none"
    is_mp4 = ext in ("mp4", "m4a", "m4v")
    avc = bool(_AVC.match(vcodec)) and ext == "mp4"
    aac = bool(_AAC.match(acodec)) and ext in ("m4a", "mp4")
    exact_size = _int_value(f.get("filesize"))
    approximate_size = _int_value(f.get("filesize_approx"))
    return {
        "id": str(f.get("format_id") or ""),
        "ext": ext,
        "width": _int_value(f.get("width")) or 0,
        "height": _int_value(f.get("height")) or 0,
        "fps": round(f["fps"], 2) if f.get("fps") else None,
        "abr": round(f["abr"], 1) if f.get("abr") else None,
        "vbr": round(f["vbr"], 1) if f.get("vbr") else None,
        "tbr": round(f["tbr"], 1) if f.get("tbr") else None,
        "filesize": exact_size or approximate_size,
        "size_approx": not exact_size and bool(approximate_size),
        "has_size": bool(exact_size or approximate_size),
        "quality": f.get("quality") or 0,
        "has_drm": bool(f.get("has_drm")),
        "has_video": has_video,
        "has_audio": has_audio,
        "audio_only": has_audio and not has_video,
        "video_only": has_video and not has_audio,
        "progressive": has_video and has_audio,
        "avc": avc,
        "aac": aac,
        "mp4": is_mp4,
        "note": _sanitize(f.get("format_note") or "", 80),
        "vcodec": vcodec if vcodec and vcodec != "none" else None,
        "acodec": acodec if acodec and acodec != "none" else None,
        "language": _sanitize(f.get("language") or "", 40),
        "dynamic_range": _sanitize(f.get("dynamic_range") or "", 30),
        "asr": _int_value(f.get("asr")),
        "audio_channels": _int_value(f.get("audio_channels")),
    }


def _usable_rows(info):
    """Returns every non-DRM audio/video rendition exposed by the extractor.

    Storyboard/image rows are not media downloads. Low resolutions, non-AVC codecs,
    WebM, unknown sizes and separate tracks are deliberately retained for the UI.
    """
    rows = []
    seen = set()
    for f in info.get("formats") or []:
        k = _kind(f)
        if not k["id"] or k["id"] in seen:
            continue
        if k["ext"] in ("mhtml", "none", "") or "storyboard" in k["note"].lower():
            continue
        if not k["has_video"] and not k["has_audio"]:
            continue
        if k["has_drm"]:
            continue
        seen.add(k["id"])
        rows.append(k)
    return rows


def _best_video_pair(rows, limit_height):
    """Best traditionally remuxable (H.264 mp4) video at/below a preset ceiling."""
    candidates = [r for r in rows if r["video_only"] and r["avc"]]
    if limit_height:
        within = [r for r in candidates if r["height"] <= limit_height]
        candidates = within or candidates
    if not candidates:
        return None
    candidates.sort(key=lambda r: (r["height"], 1 if r["has_size"] else 0,
                                   r.get("fps") or 0, r["tbr"] or 0, r["quality"] or 0),
                    reverse=True)
    return candidates[0]


def _best_audio(rows):
    """Best audio track; m4a/AAC remains the backwards-compatible preset default."""
    audio = [r for r in rows if r["audio_only"]]
    if not audio:
        return None
    m4a = [r for r in audio if r["aac"] or r["ext"] == "m4a"]
    pool = list(m4a or audio)
    pool.sort(key=lambda r: (r["quality"] or 0, 1 if r["has_size"] else 0,
                             r["abr"] or r["tbr"] or 0), reverse=True)
    return pool[0]


def _best_progressive(rows, limit_height):
    cands = [r for r in rows if r["progressive"]]
    if limit_height:
        within = [r for r in cands if r["height"] <= limit_height]
        cands = within or cands
    if not cands:
        return None
    cands.sort(key=lambda r: (r["height"], 1 if r["has_size"] else 0,
                              r.get("fps") or 0, r["tbr"] or 0), reverse=True)
    return cands[0]


def _row(rows, format_id):
    wanted = str(format_id or "")
    return next((r for r in rows if r["id"] == wanted), None)


def _codec_starts(row, key, prefixes):
    codec = (row.get(key) or "").lower()
    return not codec or codec.startswith(prefixes)


def _mp4_compatible(video, audio=None):
    # FFmpeg can stream-copy these codecs into MP4. MKV remains the universal fallback.
    video_ok = _codec_starts(video, "vcodec", (
        "avc", "h264", "hev", "hvc", "h265", "vp09", "vp9", "av01", "av1",
        "mp4v", "mpeg4"))
    if not video_ok:
        return False
    if audio is None:
        return True
    return _codec_starts(audio, "acodec", (
        "mp4a", "aac", "ac-3", "ec-3", "alac", "mp3", "opus", "flac"))


def _webm_compatible(video, audio=None):
    video_ok = _codec_starts(video, "vcodec", ("vp8", "vp08", "vp9", "vp09", "av1", "av01"))
    if not video_ok or not video.get("vcodec"):
        return False
    if audio is None:
        return True
    return _codec_starts(audio, "acodec", ("opus", "vorbis")) and bool(audio.get("acodec"))


def _default_container(video, audio=None, single=False):
    if single:
        return video["ext"] or "mp4"
    if _webm_compatible(video, audio) and (
            video["ext"] == "webm" or (audio and audio["ext"] in ("webm", "ogg", "opus"))):
        return "webm"
    if _mp4_compatible(video, audio):
        return "mp4"
    if _webm_compatible(video, audio):
        return "webm"
    return "mkv"


def _validate_container(video, audio, ext):
    if ext == "mkv":
        return True
    if ext == "mp4":
        return _mp4_compatible(video, audio)
    if ext == "webm":
        return _webm_compatible(video, audio)
    return False


def _base_plan(mode, source, ext, output_kind, postprocess):
    plan = {
        "mode": mode,
        "ext": ext,
        "source_ext": source["ext"],
        "audio_only": output_kind == "audio",
        "output_kind": output_kind,
        "postprocess": postprocess,
        "audio_bitrate": 0,
        "filesize": source["filesize"],
        "height": source["height"] if source["has_video"] else 0,
        "fps": source.get("fps") if source["has_video"] else None,
    }
    if mode == "single":
        plan["format_id"] = source["id"]
    return plan


def _exact_plan(rows, options):
    """Builds a plan for the exact per-item selection persisted by Android.

    No automatic fallback to another format is allowed here: a disappeared format id
    produces an actionable error instead of silently downloading a different quality.
    """
    selected = _row(rows, options.get("selected_format_id"))
    if selected is None:
        return None, "فرمت انتخاب‌شده دیگر ارائه نمی‌شود؛ لینک را دوباره اضافه و کیفیت را انتخاب کنید."

    audio_id = options.get("selected_audio_id")
    audio = _row(rows, audio_id) if audio_id else None
    if audio_id and audio is None:
        return None, "ردیف صوتی انتخاب‌شده دیگر در دسترس نیست؛ کیفیت را دوباره انتخاب کنید."
    if audio is not None and not audio["audio_only"]:
        return None, "ردیف انتخاب‌شده برای صدا، یک فرمت صوتی خالص نیست."
    if selected["audio_only"] and audio is not None:
        return None, "برای یک فرمت صوتی نمی‌توان ردیف صوتی دوم انتخاب کرد."
    if selected["progressive"] and audio is not None:
        return None, "این فرمت از قبل صدا دارد و به ردیف صوتی جدا نیاز ندارد."

    output_kind = str(options.get("output_kind") or "auto").lower()
    if output_kind == "auto":
        output_kind = "audio" if selected["audio_only"] else "video"
    if output_kind not in ("video", "audio", "video_only"):
        return None, "نوع خروجی انتخاب‌شده معتبر نیست."
    try:
        audio_bitrate = max(0, min(512, int(options.get("audio_bitrate") or 0)))
    except (TypeError, ValueError):
        audio_bitrate = 0
    requested_ext = str(options.get("output_ext") or "").lower().lstrip(".")
    selection_label = _sanitize(options.get("selection_label") or "", 140)

    if output_kind == "audio":
        # If a separate audio track was selected, do not download and then discard the
        # video. This makes MP3/M4A extraction from DASH items dramatically cheaper.
        source = audio or selected
        if not source["has_audio"]:
            return None, "این فرمت صدا ندارد؛ یک ردیف صوتی یا فرمت ترکیبی انتخاب کنید."
        target = requested_ext or (
            source["ext"] if source["audio_only"] and source["ext"] else "m4a")
        if target not in ("mp3", "m4a", "aac", "opus", "ogg", "flac", "wav",
                          "webm", "mp4"):
            return None, "پسوند خروجی صوتی پشتیبانی نمی‌شود."
        if target in ("mp4", "webm") and not source["audio_only"]:
            return None, "برای استخراج صدا یک پسوند صوتی مانند MP3 یا M4A انتخاب کنید."
        direct = source["audio_only"] and target == source["ext"] and audio_bitrate == 0
        plan = _base_plan("single", source, target, "audio", "none" if direct else "audio")
        plan["audio_bitrate"] = audio_bitrate
        plan["note"] = selection_label or (target.upper() + (
            " %d kb/s" % audio_bitrate if audio_bitrate else ""))
        plan["height"] = 0
        return plan, None

    if selected["audio_only"]:
        return None, "برای خروجی ویدیویی باید یک فرمت دارای تصویر انتخاب شود."
    if output_kind == "video_only" and selected["progressive"]:
        return None, "حذف صدا از این فرمت ترکیبی پشتیبانی نمی‌شود؛ فرمت فقط‌تصویر انتخاب کنید."

    warning = None
    merge_audio = None
    if selected["video_only"] and output_kind != "video_only":
        merge_audio = audio or _best_audio(rows)
        if merge_audio is None:
            warning = "برای این فرمت صدا پیدا نشد؛ نسخهٔ فقط‌تصویر دانلود می‌شود."
    mode = "merge" if merge_audio is not None else "single"
    target = requested_ext or _default_container(
        selected, merge_audio, single=(mode == "single"))
    if target not in ("mp4", "mkv", "webm") and target != selected["ext"]:
        return None, "پسوند خروجی ویدیویی پشتیبانی نمی‌شود."
    if target in ("mp4", "mkv", "webm") and not _validate_container(selected, merge_audio, target):
        return None, "کدک‌های انتخاب‌شده با این ظرف خروجی سازگار نیست؛ MKV را امتحان کنید."

    postprocess = "merge" if mode == "merge" else (
        "remux" if target != selected["ext"] else "none")
    plan = _base_plan(mode, selected, target,
                      "video_only" if output_kind == "video_only" or not merge_audio and selected["video_only"] else "video",
                      postprocess)
    if merge_audio is not None:
        plan["video_id"] = selected["id"]
        plan["audio_id"] = merge_audio["id"]
        plan.pop("format_id", None)
        plan["filesize"] = ((selected["filesize"] or 0) + (merge_audio["filesize"] or 0)) or None
    plan["note"] = selection_label or (
        ("%dp" % selected["height"]) if selected["height"] else selected["note"] or selected["id"])
    if output_kind == "video_only":
        plan["note"] = (selection_label or plan["note"]) + " • بدون صدا"
    return plan, warning


def _choose_plan(rows, preset, options=None):
    """Returns (plan, warning), using exact selection or a legacy preset.

    Legacy presets intentionally retain their previous YouTube/Spotify choices. Exact
    selections support every public codec/container and real FFmpeg post-processing.
    """
    options = options or {}
    if options.get("selected_format_id"):
        return _exact_plan(rows, options)

    preset = (preset or "best").lower()
    if preset in ("audio", "music", "mp3"):
        a = _best_audio(rows)
        if a:
            ext = "m4a" if (a["aac"] or a["ext"] == "m4a") else (a["ext"] or "m4a")
            plan = _base_plan("single", a, ext, "audio", "none")
            plan["note"] = "صدا"
            return plan, None
        p = _best_progressive(rows, None)
        if p:
            is_audio_container = p["ext"] in ("m4a", "mp3", "aac", "ogg", "opus", "flac", "wav")
            plan = _base_plan("single", p, p["ext"],
                              "audio" if is_audio_container else "video", "none")
            plan["note"] = "صدا" if is_audio_container else "ویدیوی ترکیبی"
            return plan, "فایل صوتی جدا پیدا نشد؛ نسخهٔ ترکیبی با صدا دانلود می‌شود."
        return None, "فرمت صوتی پیدا نشد."

    heights = {"2160": 2160, "1440": 1440, "1080": 1080,
               "720": 720, "480": 480, "360": 360}
    limit = heights.get(preset, 0) or 0
    video = _best_video_pair(rows, limit)
    audio = _best_audio(rows)
    if video and audio:
        plan = _base_plan("merge", video, "mp4", "video", "merge")
        plan["video_id"] = video["id"]
        plan["audio_id"] = audio["id"]
        plan["filesize"] = ((video["filesize"] or 0) + (audio["filesize"] or 0)) or None
        plan["note"] = "%dp%s" % (video["height"], ("%d" % video["fps"]) if video.get("fps") else "")
        return plan, None

    prog = _best_progressive(rows, limit)
    if prog:
        note = None
        if limit and prog["height"] and prog["height"] + 1 < limit:
            note = "بالاترین کیفیت موجود %dp است (سایت فرمت جداگانه تصویر/صدا نمی‌دهد)." % prog["height"]
        plan = _base_plan("single", prog, prog["ext"], "video", "none")
        plan["note"] = prog["note"] or ("%dp" % (prog["height"] or 0))
        return plan, note

    if video and not audio:
        plan = _base_plan("single", video, video["ext"], "video_only", "none")
        plan["note"] = "ویدیو بدون صدا"
        return plan, "این محتوا فایل صوتی جدا ندارد."
    return None, "فرمت قابل دانلودی پیدا نشد."


def _public_formats(rows):
    """Complete media rows for the per-item quality/output picker."""
    out = []
    for r in rows:
        out.append({key: r.get(key) for key in (
            "id", "ext", "width", "height", "fps", "abr", "vbr", "tbr",
            "filesize", "size_approx", "audio_only", "video_only", "progressive",
            "has_audio", "has_video", "note", "vcodec", "acodec", "language",
            "dynamic_range", "asr", "audio_channels")})
    out.sort(key=lambda r: (r["audio_only"], -(r["height"] or 0),
                            -(r["tbr"] or r["abr"] or 0), r["id"]))
    return out


# --------------------------------------------------------------------------- extraction


class _InstagramMetaParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.meta = {}

    def handle_starttag(self, tag, attrs):
        if tag.lower() != "meta":
            return
        values = {str(key).lower(): value for key, value in attrs if key}
        key = values.get("property") or values.get("name")
        content = values.get("content")
        if key and content and key.lower() not in self.meta:
            self.meta[key.lower()] = content


def _instagram_shortcode(url):
    """Returns a real post shortcode; share tokens and audio pages are not posts."""
    try:
        parsed = urllib.parse.urlsplit(url)
        host = (parsed.hostname or "").lower().removeprefix("www.")
        if host not in ("instagram.com", "instagr.am"):
            return None
        parts = [part for part in parsed.path.split("/") if part]
        if not parts or parts[0].lower() == "share":
            return None
        for index, part in enumerate(parts[:-1]):
            if part.lower() not in ("p", "reel", "reels", "tv"):
                continue
            shortcode = parts[index + 1]
            if part.lower() == "reels" and shortcode.lower() == "audio":
                return None
            if re.fullmatch(r"[A-Za-z0-9_-]{1,128}", shortcode):
                return shortcode
    except (TypeError, ValueError):
        pass
    return None


def _clean_instagram_media_url(value):
    if not isinstance(value, str):
        return None
    value = html_lib.unescape(value).strip()
    try:
        parsed = urllib.parse.urlsplit(value)
    except ValueError:
        return None
    if parsed.scheme.lower() != "https" or not parsed.hostname:
        return None
    return value


def _instagram_text_variants(page):
    """Embedded data appears both as JSON and as HTML/JSON escaped strings."""
    decoded = html_lib.unescape(page)
    variants = [decoded]
    if '\\"' in decoded:
        variants.append(decoded.replace('\\"', '"'))
    return variants


def _instagram_json_strings(text, key):
    pattern = re.compile(r'"' + re.escape(key) + r'"\s*:\s*("(?:\\.|[^"\\])*")')
    for match in pattern.finditer(text):
        try:
            value = json.loads(match.group(1))
        except (TypeError, ValueError):
            continue
        if isinstance(value, str):
            yield value


def _instagram_video_versions(text):
    marker = re.compile(r'"video_versions"\s*:\s*')
    for match in marker.finditer(text):
        try:
            value, _ = json.JSONDecoder().raw_decode(text, match.end())
        except (TypeError, ValueError):
            continue
        if isinstance(value, list):
            return [item for item in value if isinstance(item, dict)]
    return []


def _instagram_format(format_id, url, width=0, height=0, item=None):
    item = item or {}
    path = urllib.parse.urlsplit(url).path.lower()
    ext = path.rsplit(".", 1)[-1] if "." in path.rsplit("/", 1)[-1] else "mp4"
    if ext not in ("mp4", "m4v", "mov", "webm"):
        ext = "mp4"
    safe_id = re.sub(r"[^A-Za-z0-9_.-]+", "-", str(format_id or "ig-public"))
    return {
        "format_id": safe_id,
        "url": url,
        "ext": ext,
        "width": _int_value(item.get("width")) or _int_value(width) or 0,
        "height": _int_value(item.get("height")) or _int_value(height) or 0,
        "vcodec": item.get("video_codec"),
        "acodec": "none" if item.get("has_audio") is False else None,
    }


def _parse_instagram_public_embed(page, shortcode, webpage_url):
    """Builds a normal yt-dlp info dict from a public embed, with stable format IDs."""
    parser = _InstagramMetaParser()
    try:
        parser.feed(page)
    except Exception:
        # HTMLParser is forgiving, but a malformed document must not crash the fallback.
        pass
    meta = parser.meta
    formats = []
    seen = set()

    def add(format_id, url, width=0, height=0, item=None):
        url = _clean_instagram_media_url(url)
        if not url or url in seen:
            return
        seen.add(url)
        formats.append(_instagram_format(format_id, url, width, height, item))

    for text in _instagram_text_variants(page):
        for index, item in enumerate(_instagram_video_versions(text)):
            height = _int_value(item.get("height")) or 0
            # InstagramIE also uses id/type directly; retaining it keeps an earlier exact
            # quality selection valid if the normal path fails only at download time.
            identity = item.get("id") or item.get("type") or "ig-%d-%s" % (index, height)
            add(identity, item.get("url"), item=item)
        for key in ("video_url", "videoUrl"):
            for url in _instagram_json_strings(text, key):
                add("ig-public", url, meta.get("og:video:width", 0),
                    meta.get("og:video:height", 0))
        if formats:
            break

    if not formats:
        for key in ("og:video:secure_url", "og:video", "twitter:player:stream"):
            add("ig-public", meta.get(key), meta.get("og:video:width", 0),
                meta.get("og:video:height", 0))

    if not formats:
        # Some documents expose only contentUrl. Accept it only when it is visibly media,
        # not an image or a link to another HTML page.
        for text in _instagram_text_variants(page):
            for url in _instagram_json_strings(text, "contentUrl"):
                path = urllib.parse.urlsplit(html_lib.unescape(url)).path.lower()
                if path.endswith((".mp4", ".m4v", ".mov", ".webm")):
                    add("ig-public", url)
            if formats:
                break

    if not formats:
        return None
    formats.sort(key=lambda row: (row["height"], row["width"]), reverse=True)
    duration = meta.get("og:video:duration") or meta.get("video:duration")
    try:
        duration = float(duration) if duration else None
    except (TypeError, ValueError):
        duration = None
    return {
        "id": shortcode,
        "title": _sanitize(meta.get("og:title") or "Instagram " + shortcode),
        "webpage_url": webpage_url,
        "original_url": webpage_url,
        "extractor": "InstagramPublicEmbed",
        "extractor_key": "InstagramPublicEmbed",
        "duration": duration,
        "formats": formats,
        "http_headers": {"Referer": "https://www.instagram.com/"},
    }


def _instagram_public_embed(ydl, url):
    shortcode = _instagram_shortcode(url)
    if not shortcode:
        return None
    embed_url = "https://www.instagram.com/p/%s/embed/captioned/" % shortcode
    request = Request(embed_url, headers={
        "Referer": "https://www.instagram.com/p/%s/" % shortcode,
        "Accept": "text/html,application/xhtml+xml",
    })
    response = ydl.urlopen(request)
    try:
        final_path = urllib.parse.urlsplit(response.url).path
        if final_path.startswith("/accounts/login"):
            return None
        page = response.read().decode("utf-8", "replace")
    finally:
        response.close()
    return _parse_instagram_public_embed(page, shortcode, url)


def _extract_info(ydl, url, download=False):
    """Uses yt-dlp first, then Instagram's public embed only as a no-login fallback."""
    try:
        return ydl.extract_info(url, download=download)
    except Exception as exc:
        if exc.__class__.__name__ in ("_Cancelled", "DownloadCancelled"):
            raise
        if not _instagram_shortcode(url):
            raise
        try:
            fallback = _instagram_public_embed(ydl, url)
        except Exception:
            fallback = None
        if fallback is None:
            raise
        return ydl.process_ie_result(fallback, download=download)


def _extract(url, options, download=False, flat_playlist=False):
    opts = _base_opts(options)
    probe_only = not download
    opts.update({
        "skip_download": probe_only,
        "noplaylist": False,
    })
    if flat_playlist:
        # Playlist probes only need entry URLs/titles. Fully extracting every video made
        # adding a large YouTube/Spotify-like playlist unnecessarily slow.
        opts.update({
            "extract_flat": "in_playlist",
            "lazy_playlist": True,
            "playlistend": MAX_PLAYLIST_ITEMS,
        })
    with _AriaYoutubeDL(opts) as ydl:
        info = _extract_info(ydl, url, download=False)
        if info is None:
            raise RuntimeError("محتوایی پیدا نشد")
        return ydl.sanitize_info(info)


def _first_entry(info):
    if info.get("_type") == "playlist":
        entries = [e for e in (info.get("entries") or []) if e]
        if not entries:
            raise RuntimeError("پلی‌لیست خالی است")
        return entries[0]
    return info


def probe(url, options_json="{}"):
    """Metadata + playlist entries + list of usable formats."""
    try:
        options = json.loads(options_json or "{}")
        info = _extract(url, options, download=False, flat_playlist=True)

        if info.get("_type") == "playlist":
            entries = [e for e in (info.get("entries") or []) if e]
            cleaned = []
            for e in entries[:MAX_PLAYLIST_ITEMS]:
                cleaned.append({
                    "title": _sanitize(e.get("title") or e.get("id") or "بدون عنوان"),
                    "url": e.get("webpage_url") or e.get("url") or url,
                    "duration": e.get("duration"),
                    "id": e.get("id"),
                    "uploader": _sanitize(e.get("uploader") or e.get("channel") or ""),
                })
            return json.dumps({
                "ok": True,
                "is_playlist": True,
                "title": _sanitize(info.get("title") or "پلی‌لیست"),
                "uploader": _sanitize(info.get("uploader") or info.get("channel") or ""),
                "thumbnail": info.get("thumbnail"),
                "entries_count": info.get("playlist_count") or len(entries),
                "entries": cleaned,
                "webpage_url": info.get("webpage_url") or url,
                "extractor": info.get("extractor_key") or info.get("extractor") or "",
            }, ensure_ascii=False)

        rows = _usable_rows(info)
        return json.dumps({
            "ok": True,
            "is_playlist": False,
            "title": _sanitize(info.get("title") or "بدون عنوان"),
            "uploader": _sanitize(info.get("uploader") or info.get("channel") or ""),
            "duration": info.get("duration"),
            "thumbnail": info.get("thumbnail"),
            "webpage_url": info.get("webpage_url") or url,
            "extractor": info.get("extractor_key") or info.get("extractor") or "",
            "is_live": bool(info.get("is_live")),
            "formats": _public_formats(rows),
            "best_height": max([r["height"] or 0 for r in rows if r["has_video"]] or [0]),
            "has_audio_only": any(r["audio_only"] for r in rows),
        }, ensure_ascii=False)
    except Exception as exc:  # noqa: BLE001
        return json.dumps({"ok": False, "error": _human_error(exc)}, ensure_ascii=False)


def resolve(url, options_json="{}"):
    """Turns (url, preset) into a concrete download plan, validating availability."""
    try:
        options = json.loads(options_json or "{}")
        preset = options.get("preset") or "best"
        info = _first_entry(_extract(url, options, download=False))
        rows = _usable_rows(info)
        if not rows:
            return json.dumps({"ok": False, "error": "فرمت قابل دانلودی پیدا نشد."},
                              ensure_ascii=False)
        plan, warning = _choose_plan(rows, preset, options)
        if not plan:
            return json.dumps({"ok": False, "error": warning or "فرمت قابل دانلودی پیدا نشد."},
                              ensure_ascii=False)
        plan.update({
            "ok": True,
            "title": _sanitize(info.get("title") or "download"),
            "uploader": _sanitize(info.get("uploader") or info.get("channel") or ""),
            "duration": info.get("duration"),
            "thumbnail": info.get("thumbnail"),
            "extractor": info.get("extractor_key") or info.get("extractor") or "",
            "webpage_url": info.get("webpage_url") or url,
            "warning": warning,
        })
        return json.dumps(plan, ensure_ascii=False)
    except Exception as exc:  # noqa: BLE001
        return json.dumps({"ok": False, "error": _human_error(exc)}, ensure_ascii=False)


# --------------------------------------------------------------------------- download


class _Cancelled(Exception):
    pass


def _event(callback, payload):
    if callback is None:
        return
    try:
        callback.on_event(json.dumps(payload, ensure_ascii=False))
    except Exception:
        pass


def _cancelled(callback):
    if callback is None:
        return False
    try:
        return bool(callback.should_cancel())
    except Exception:
        return False


class _Logger:
    def __init__(self, callback):
        self.callback = callback

    def debug(self, msg):
        pass

    def info(self, msg):
        pass

    def warning(self, msg):
        pass

    def error(self, msg):
        _event(self.callback, {"type": "log", "message": _sanitize(msg, 300)})


def download(request_json, callback=None):
    """
    Downloads exactly one rendition (or a whole single-video url when format_id is null).

    Events pushed to callback.on_event(json):
        {"type":"start","title":...,"total":...}
        {"type":"progress","downloaded":N,"total":N,"speed":N,"eta":N,"percent":P,"filename":...}
        {"type":"log","message":...}
        {"type":"done","files":[{"path":...,"size":...}]}
        {"type":"error","message":...}
        {"type":"cancelled"}
    """
    try:
        request = json.loads(request_json)
        url = request.get("url")
        out_dir = request.get("out_dir") or "."
        preset = request.get("preset") or "best"
        format_id = request.get("format_id")
        os.makedirs(out_dir, exist_ok=True)

        opts = _base_opts(request)
        opts.update({
            # format_id in the template keeps the video and audio renditions of one video
            # in separate files even when both end up with the same extension.
            "outtmpl": os.path.join(out_dir, "%(title).120B [%(id)s][%(format_id)s].%(ext)s"),
            "noplaylist": True,
            "continuedl": True,
            "overwrites": False,
            "logger": _Logger(callback),
            "noprogress": True,
        })
        if format_id:
            opts["format"] = str(format_id)
        elif preset in ("audio", "music", "mp3"):
            opts["format"] = "ba[ext=m4a]/ba[ext=mp4]/ba/b"
        else:
            opts["format"] = "b"

        def hook(d):
            if _cancelled(callback):
                raise _Cancelled()
            status = d.get("status")
            if status == "downloading":
                total = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
                done = d.get("downloaded_bytes") or 0
                speed = d.get("speed") or 0
                eta = d.get("eta") or 0
                _event(callback, {
                    "type": "progress",
                    "downloaded": int(done),
                    "total": int(total),
                    "speed": int(speed) if speed else 0,
                    "eta": int(eta) if eta else 0,
                    "percent": round(done * 100.0 / total, 1) if total else -1,
                    "filename": _sanitize(d.get("filename") or "", 200),
                })

        opts["progress_hooks"] = [hook]

        with _AriaYoutubeDL(opts) as ydl:
            info = _extract_info(ydl, url, download=True)
            if info is None:
                raise RuntimeError("دانلود ناموفق بود")
            info = _first_entry(ydl.sanitize_info(info))

        files = []
        for rd in info.get("requested_downloads") or []:
            path = rd.get("filepath") or rd.get("_filename")
            if path and os.path.exists(path):
                files.append(path)
        if not files:
            path = info.get("filepath") or info.get("_filename")
            if path and os.path.exists(path):
                files.append(path)
        if not files:
            raise RuntimeError("فایل خروجی پیدا نشد")

        result = {
            "ok": True,
            "title": _sanitize(info.get("title") or "download"),
            "files": [{"path": p, "size": os.path.getsize(p)} for p in files],
            "duration": info.get("duration"),
            "thumbnail": info.get("thumbnail"),
            "uploader": _sanitize(info.get("uploader") or info.get("channel") or ""),
            "extractor": info.get("extractor_key") or info.get("extractor") or "",
        }
        _event(callback, {"type": "done", "files": result["files"], "title": result["title"]})
        return json.dumps(result, ensure_ascii=False)

    except _Cancelled:
        _event(callback, {"type": "cancelled"})
        return json.dumps({"ok": False, "cancelled": True, "error": "دانلود متوقف شد"},
                          ensure_ascii=False)
    except Exception as exc:  # noqa: BLE001
        if exc.__class__.__name__ == "DownloadCancelled":
            _event(callback, {"type": "cancelled"})
            return json.dumps({"ok": False, "cancelled": True, "error": "دانلود متوقف شد"},
                              ensure_ascii=False)
        message = _human_error(exc)
        _event(callback, {"type": "error", "message": message})
        return json.dumps({"ok": False, "error": message, "detail": _sanitize(exc, 400)},
                          ensure_ascii=False)


# --------------------------------------------------------------------------- misc


def search(query, limit=1, options_json="{}"):
    """Resolves a text query to stream urls (used by the Spotify bridge)."""
    try:
        options = json.loads(options_json or "{}")
        options["noplaylist"] = True
        opts = _base_opts(options)
        opts.update({"skip_download": True, "extract_flat": True})
        with _AriaYoutubeDL(opts) as ydl:
            info = ydl.extract_info("ytsearch%d:%s" % (max(1, int(limit)), query), download=False)
            info = ydl.sanitize_info(info)
        entries = []
        for e in (info.get("entries") or []) if info else []:
            if not e:
                continue
            entries.append({
                "title": _sanitize(e.get("title") or ""),
                "url": e.get("webpage_url") or e.get("url"),
                "id": e.get("id"),
                "duration": e.get("duration"),
                "uploader": _sanitize(e.get("uploader") or e.get("channel") or ""),
            })
        return json.dumps({"ok": True, "entries": entries}, ensure_ascii=False)
    except Exception as exc:  # noqa: BLE001
        return json.dumps({"ok": False, "error": _human_error(exc)}, ensure_ascii=False)


def engine_info():
    return json.dumps({
        "engine": ENGINE_VERSION,
        "yt_dlp": getattr(yt_dlp.version, "__version__", "unknown"),
        "python": sys.version.split()[0],
    }, ensure_ascii=False)
