"""
AllDown 5.4.0 - extraction engine.

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
    configure_diag(path)              -> json   (diag build: file every engine event is logged to)
"""

import html as html_lib
from html.parser import HTMLParser
import json
import os
import re
import sys
import threading
import time
import traceback
import urllib.parse
import urllib.request

try:  # Android has no system CA bundle: yt-dlp/certifi provide one.
    import certifi

    os.environ.setdefault("SSL_CERT_FILE", certifi.where())
    os.environ.setdefault("REQUESTS_CA_BUNDLE", certifi.where())
except Exception:  # pragma: no cover - optional
    pass

import yt_dlp  # noqa: E402  (must come after the SSL env vars)
from yt_dlp.networking import Request  # noqa: E402
from yt_dlp.networking._urllib import UrllibRH  # noqa: E402

ENGINE_VERSION = "5.4.0"

# --------------------------------------------------------------------------- diagnostics
#
# Active only in the diag build: Kotlin passes {"diag": true, "diag_log_path": ...} in the
# options of every call (and calls configure_diag(path) once after the runtime boots).
# The python side then appends its own lines to the very same session file Kotlin writes,
# so the tester ends up with ONE interleaved timeline: UI -> queue -> extraction (incl.
# yt-dlp's verbose log) -> download -> FFmpeg. Without a diag path every helper below is a
# no-op and yt-dlp keeps its quiet production options.

_DIAG_PATH = ""
_DIAG_LOCK = threading.Lock()

# A bare "key" substring would also hide harmless content ids such as Pornhub's
# "viewkey", so key-like names are matched by the exact forms in _is_sensitive_key.
_SENSITIVE_PARTS = (
    "token", "sig", "sign", "auth", "secret", "session", "password",
    "passwd", "pwd", "cookie", "credential", "policy", "hmac", "bearer",
)
_USERINFO_RE = re.compile(r"://[^/@\s]+@")
_PARAM_RE = re.compile(r"([?&][^=&#\s]+)=([^&#\s]*)")
_BARE_ASSIGN_RE = re.compile(r"\b([A-Za-z][A-Za-z0-9_-]{1,40})=([^\s&;]+)")
_HEADER_SECRET_RE = re.compile(
    r"\b(authorization|cookie|set-cookie|x-api-key)\s*[:=]\s*(?:Bearer\s+)?[^\s;]+", re.I)


def _is_sensitive_key(key):
    lower = key.lower()
    if lower == "key" or "apikey" in lower or "api_key" in lower:
        return True
    if lower.endswith("_key") or lower.endswith("-key"):
        return True
    return any(part in lower for part in _SENSITIVE_PARTS)


def _redact_url(url):
    """Masks userinfo + signed-url tokens; host/path/content ids stay readable."""
    if not url:
        return url or ""
    result = _USERINFO_RE.sub("://***@", str(url))

    def mask(match):
        key = match.group(1)[1:]
        return match.group(1) + "=***" if _is_sensitive_key(key) else match.group(0)

    return _PARAM_RE.sub(mask, result)


def _redact_text(text):
    if text is None:
        return ""
    result = _HEADER_SECRET_RE.sub(lambda m: m.group(1) + ": ***", str(text))
    result = _redact_url(result)

    def mask_bare(match):
        return match.group(1) + "=***" if _is_sensitive_key(match.group(1)) else match.group(0)

    # Secrets also appear mid-sentence in yt-dlp errors ("... token=abc ..."), not only
    # after a "?" in a url.
    return _BARE_ASSIGN_RE.sub(mask_bare, result)


def _redact_proxy(proxy):
    value = (proxy or "").strip() if isinstance(proxy, str) else ""
    return _USERINFO_RE.sub("://***@", value) if value else "(none)"


def _diag_ts():
    now = time.time()
    return time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(now)) + ".%03d" % int(
        (now - int(now)) * 1000)


def _diag_write(text):
    path = _DIAG_PATH
    if not path:
        return
    try:
        with _DIAG_LOCK:
            with open(path, "a", encoding="utf-8", errors="replace") as fh:
                fh.write(text if text.endswith("\n") else text + "\n")
    except Exception:
        pass  # diagnostics must never break the download it is describing


def _dlog(tag, message, level="I"):
    if not _DIAG_PATH:
        return
    line = "%s %s/py-%s [py-%s]: %s" % (
        _diag_ts(), level, tag, threading.current_thread().name[:20],
        _redact_text(message).replace("\n", " "))
    _diag_write(line)


def _dlog_tb(tag, context):
    """Logs the *real* exception (the user only sees _human_error's friendly summary)."""
    if not _DIAG_PATH:
        return
    block = "%s E/py-%s [py]: %s\n%s" % (
        _diag_ts(), tag, context, _redact_text(traceback.format_exc()).rstrip())
    _diag_write(block)


def _diag_from_options(options):
    """Adopts the diag path from one call's options (if any). Returns True when active."""
    global _DIAG_PATH
    try:
        path = (options or {}).get("diag_log_path")
    except AttributeError:
        path = None
    if isinstance(path, str) and path:
        _DIAG_PATH = path
        return True
    return bool(_DIAG_PATH)


def configure_diag(path):
    """Called once by Kotlin after the runtime boots (diag build only)."""
    global _DIAG_PATH
    _DIAG_PATH = path or ""
    if _DIAG_PATH:
        _dlog("boot", "python diag configured engine=%s yt_dlp=%s python=%s path=%s" % (
            ENGINE_VERSION, getattr(yt_dlp.version, "__version__", "?"),
            "%d.%d.%d" % sys.version_info[:3], _DIAG_PATH))
    return json.dumps({"ok": True, "diag": bool(_DIAG_PATH)}, ensure_ascii=False)


class _DiagLogger:
    """yt-dlp logger that mirrors *everything* (debug included) into the diag file."""

    def __init__(self, tag="yt-dlp"):
        self.tag = tag

    def debug(self, msg):
        # yt-dlp prefixes webpage-download lines with "[debug] "; keep the text as-is.
        _dlog(self.tag, msg, "D")

    def info(self, msg):
        _dlog(self.tag, msg, "I")

    def warning(self, msg):
        _dlog(self.tag, msg, "W")

    def error(self, msg):
        _dlog(self.tag, msg, "E")


# Conservative defaults that improve throughput without creating dozens of connections.
# HLS/DASH sites (including Aparat and Pornhub) benefit from parallel fragments; regular
# HTTP media benefits from ranged chunks and a larger IO buffer. No rate limiter is set.
DEFAULT_FRAGMENT_CONCURRENCY = 8
MAX_FRAGMENT_CONCURRENCY = 16
HTTP_CHUNK_SIZE = 16 * 1024 * 1024
IO_BUFFER_SIZE = 1024 * 1024

# One extraction is shared by probe -> resolve -> download for a few minutes. Sites with
# aggressive anti-bot checks (Pornhub above all) intermittently reject brand-new
# sessions, so re-fetching the page for every phase made first attempts fail randomly.
_INFO_CACHE = {}
_INFO_CACHE_TTL = 600
_INFO_CACHE_MAX = 32


def _cache_get(url):
    entry = _INFO_CACHE.get(url)
    if not entry:
        return None
    stored_at, info = entry
    if time.time() - stored_at > _INFO_CACHE_TTL:
        _INFO_CACHE.pop(url, None)
        return None
    return info


def _cache_put(url, info):
    if not isinstance(info, dict) or info.get("_type") == "playlist":
        return
    _INFO_CACHE[url] = (time.time(), info)
    while len(_INFO_CACHE) > _INFO_CACHE_MAX:
        oldest = min(_INFO_CACHE, key=lambda key: _INFO_CACHE[key][0])
        _INFO_CACHE.pop(oldest, None)


def _cache_invalidate(url):
    _INFO_CACHE.pop(url, None)


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
    if _diag_from_options(options):
        # Verbose yt-dlp: extractor arguments, requested urls, format sorting, retries,
        # warnings that production mode silences - all land in the diag file.
        opts.update({
            "quiet": False,
            "no_warnings": False,
            "verbose": True,
            "logger": _DiagLogger("yt-dlp"),
        })
        _dlog("opts", "yt-dlp opts fragments=%s proxy=%s ckFile=%s ua_override=%s" % (
            fragment_concurrency, _redact_proxy(opts.get("proxy")),
            bool(opts.get("cookiefile")), bool(opts.get("http_headers"))))
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
    _dlog("instagram", "public embed fallback for shortcode=%s" % shortcode)
    embed_url = "https://www.instagram.com/p/%s/embed/captioned/" % shortcode
    request = Request(embed_url, headers={
        "Referer": "https://www.instagram.com/p/%s/" % shortcode,
        "Accept": "text/html,application/xhtml+xml",
    })
    response = ydl.urlopen(request)
    try:
        final_path = urllib.parse.urlsplit(response.url).path
        if final_path.startswith("/accounts/login"):
            _dlog("instagram", "embed redirected to login -> fallback unavailable", "W")
            return None
        page = response.read().decode("utf-8", "replace")
    finally:
        response.close()
    parsed = _parse_instagram_public_embed(page, shortcode, url)
    _dlog("instagram", "embed page %d chars parsed=%s formats=%s" % (
        len(page), parsed is not None,
        len((parsed or {}).get("formats") or []) if isinstance(parsed, dict) else "?"))
    return parsed


_SHORT_SHARE_HOSTS = ("pin.it",)


def _needs_redirect_resolution(url):
    """App share links that only match yt-dlp's generic extractor.

    pin.it short links and Reddit /s/ share tokens carry no post id at all, so the
    dedicated extractors never see them until the redirect is followed.
    """
    try:
        parsed = urllib.parse.urlparse(url)
    except Exception:
        return False
    host = (parsed.hostname or "").lower()
    if host in _SHORT_SHARE_HOSTS:
        return True
    return host.endswith("reddit.com") and "/s/" in (parsed.path or "")


def _follow_redirect(ydl, url):
    try:
        resp = ydl.urlopen(Request(url, headers={
            "User-Agent": "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36",
        }))
        try:
            final = resp.geturl()
        except Exception:
            final = getattr(resp, "url", None)
        try:
            resp.close()
        except Exception:
            pass
        _dlog("extract", "redirect %s -> %s" % (_redact_url(url), _redact_url(final)))
        return final if isinstance(final, str) and final.startswith("http") else None
    except Exception:
        _dlog_tb("extract", "redirect resolution failed for %s" % _redact_url(url))
        return None


def _image_direct_url(info, rows):
    """A downloadable image for image-only posts (Pinterest pins, Reddit/TikTok photos).

    Video/audio extractors return no formats for these, which the app used to report
    as "no format found" even though the image itself is right there.
    """
    if rows:
        return None
    candidates = []
    direct = info.get("url")
    if isinstance(direct, str):
        candidates.append((0, direct))
    for thumb in info.get("thumbnails") or []:
        if isinstance(thumb, dict) and thumb.get("url"):
            area = (thumb.get("width") or 0) * (thumb.get("height") or 0)
            candidates.append((area, thumb["url"]))
    if info.get("thumbnail"):
        candidates.append((0, info["thumbnail"]))
    for _, candidate in sorted(candidates, reverse=True):
        ext = candidate.split("?")[0].rsplit(".", 1)[-1].lower() if "." in candidate else ""
        if ext in ("jpg", "jpeg", "png", "webp", "gif", "avif"):
            return candidate
    return None


def _extract_info(ydl, url, download=False):
    """Uses yt-dlp first, then Instagram's public embed only as a no-login fallback."""
    _dlog("extract", "extract_info download=%s url=%s" % (download, _redact_url(url)))
    if _needs_redirect_resolution(url):
        final = _follow_redirect(ydl, url)
        if final and final != url:
            try:
                return ydl.extract_info(final, download=download)
            except Exception as exc:
                if exc.__class__.__name__ in ("_Cancelled", "DownloadCancelled"):
                    raise
    try:
        return ydl.extract_info(url, download=download)
    except Exception as exc:
        if exc.__class__.__name__ in ("_Cancelled", "DownloadCancelled"):
            raise
        if not _instagram_shortcode(url):
            raise
        _dlog("extract", "official extractor failed (%s: %s); trying instagram embed" % (
            exc.__class__.__name__, _sanitize(exc, 250)), "W")
        try:
            fallback = _instagram_public_embed(ydl, url)
        except Exception:
            _dlog_tb("extract", "instagram embed fallback crashed")
            fallback = None
        if fallback is None:
            _dlog("extract", "instagram embed fallback empty; original error wins", "W")
            raise
        _dlog("extract", "instagram embed fallback produced a result")
        return ydl.process_ie_result(fallback, download=download)


def _extract(url, options, download=False, flat_playlist=False):
    _diag_from_options(options)
    if not download:
        cached = _cache_get(url)
        if cached is not None:
            _dlog("extract", "info CACHE HIT url=%s extractor=%s" % (
                _redact_url(url), cached.get("extractor_key") or cached.get("extractor")))
            return cached
    _dlog("extract", "extract start download=%s flat=%s url=%s" % (
        download, flat_playlist, _redact_url(url)))
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
    started = time.time()
    with _AriaYoutubeDL(opts) as ydl:
        info = _extract_info(ydl, url, download=False)
        if info is None:
            raise RuntimeError("محتوایی پیدا نشد")
        info = ydl.sanitize_info(info)
        if not download:
            _cache_put(url, info)
        _dlog("extract", "extract ok in %.1fs type=%s extractor=%s title=%r entries=%s "
              "formats=%s requested=%s webpage=%s" % (
                  time.time() - started, info.get("_type"),
                  info.get("extractor_key") or info.get("extractor"),
                  _sanitize(info.get("title") or "", 120),
                  len(info.get("entries") or []) if info.get("_type") == "playlist" else "-",
                  len(info.get("formats") or []),
                  [r.get("format_id") for r in (info.get("requested_formats") or [])],
                  _redact_url(info.get("webpage_url") or "")))
        return info


def _first_entry(info):
    if info.get("_type") == "playlist":
        entries = [e for e in (info.get("entries") or []) if e]
        if not entries:
            raise RuntimeError("پلی‌لیست خالی است")
        return entries[0]
    return info


def probe(url, options_json="{}"):
    """Metadata + playlist entries + list of usable formats."""
    started = time.time()
    try:
        options = json.loads(options_json or "{}")
        _diag_from_options(options)
        _dlog("probe", "probe start url=%s" % _redact_url(url))
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
            _dlog("probe", "probe playlist ok in %.1fs entries=%d title=%r" % (
                time.time() - started, len(cleaned), _sanitize(info.get("title") or "", 120)))
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
        _dlog("probe", "probe ok in %.1fs usableRows=%d rawFormats=%d directImage=%s" % (
            time.time() - started, len(rows), len(info.get("formats") or []),
            _redact_url(_image_direct_url(info, rows) or "")))
        for row in rows:
            _dlog("probe", " row id=%s ext=%s %sx%s fps=%s size=%s%s audioOnly=%s prog=%s "
                  "vOnly=%s vcodec=%s acodec=%s tbr=%s drm=%s note=%r" % (
                      row.get("id"), row.get("ext"), row.get("width"), row.get("height"),
                      row.get("fps"), row.get("filesize"), "~" if row.get("size_approx") else "",
                      row.get("audio_only"), row.get("progressive"), row.get("video_only"),
                      row.get("vcodec"), row.get("acodec"), row.get("tbr"), row.get("has_drm"),
                      _sanitize(row.get("note") or "", 80)), "D")
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
            "direct_file_url": _image_direct_url(info, rows),
            "formats": _public_formats(rows),
            "best_height": max([r["height"] or 0 for r in rows if r["has_video"]] or [0]),
            "has_audio_only": any(r["audio_only"] for r in rows),
        }, ensure_ascii=False)
    except Exception as exc:  # noqa: BLE001
        _dlog("probe", "probe FAILED in %.1fs url=%s human=%r" % (
            time.time() - started, _redact_url(url), _human_error(exc)), "E")
        _dlog_tb("probe", "probe traceback")
        return json.dumps({"ok": False, "error": _human_error(exc)}, ensure_ascii=False)


def resolve(url, options_json="{}"):
    """Turns (url, preset) into a concrete download plan, validating availability."""
    started = time.time()
    try:
        options = json.loads(options_json or "{}")
        _diag_from_options(options)
        preset = options.get("preset") or "best"
        _dlog("resolve", "resolve start preset=%s selected=%s/%s output=%s/%s abr=%s url=%s" % (
            preset, options.get("selected_format_id") or "-", options.get("selected_audio_id") or "-",
            options.get("output_kind") or "-", options.get("output_ext") or "-",
            options.get("audio_bitrate") or 0, _redact_url(url)))
        info = _first_entry(_extract(url, options, download=False))
        rows = _usable_rows(info)
        if not rows:
            _dlog("resolve", "resolve FAILED: no usable rows (rawFormats=%d)" % len(
                info.get("formats") or []), "E")
            return json.dumps({"ok": False, "error": "فرمت قابل دانلودی پیدا نشد."},
                              ensure_ascii=False)
        plan, warning = _choose_plan(rows, preset, options)
        _dlog("resolve", "chosen plan=%s warning=%r" % (
            {k: plan.get(k) for k in ("mode", "format_id", "video_id", "audio_id", "ext",
                                      "source_ext", "height", "filesize", "postprocess",
                                      "output_kind", "audio_only")} if plan else None, warning))
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
        _dlog("resolve", "resolve ok in %.1fs" % (time.time() - started))
        return json.dumps(plan, ensure_ascii=False)
    except Exception as exc:  # noqa: BLE001
        _dlog("resolve", "resolve FAILED in %.1fs url=%s human=%r" % (
            time.time() - started, _redact_url(url), _human_error(exc)), "E")
        _dlog_tb("resolve", "resolve traceback")
        return json.dumps({"ok": False, "error": _human_error(exc)}, ensure_ascii=False)


# --------------------------------------------------------------------------- parallel HTTP
#
# CDNs such as Pornhub's throttle a single connection hard (a few hundred KB/s),
# while the same file over several ranged connections saturates the link. yt-dlp's
# built-in HTTP downloader is single-connection, so progressive formats (direct
# MP4/WebM/M4A files) are fetched here with parallel Range segments whenever the
# server supports them. Anything unusual falls back to yt-dlp untouched.


class _ParallelUnsupported(Exception):
    pass


def _parallel_candidate(info, format_id):
    """Returns the format dict when it is a plain progressive HTTP file, else None."""
    if not format_id:
        return None
    for fmt in info.get("formats") or []:
        if str(fmt.get("format_id")) != str(format_id):
            continue
        url = fmt.get("url") or ""
        protocol = str(fmt.get("protocol") or "").lower()
        ext = str(fmt.get("ext") or "").lower()
        if not url.startswith("http"):
            return None
        if protocol not in ("", "http", "https"):
            return None
        if ext in ("m3u8", "mpd") or "dash" in protocol or "m3u8" in protocol:
            return None
        return fmt
    return None


def _parallel_opener(proxy):
    handlers = []
    if isinstance(proxy, str) and proxy.strip():
        handlers.append(urllib.request.ProxyHandler(
            {"http": proxy.strip(), "https": proxy.strip()}))
    try:
        import ssl as _ssl
        try:
            import certifi as _certifi
            context = _ssl.create_default_context(cafile=_certifi.where())
        except Exception:
            context = _ssl.create_default_context()
        handlers.append(urllib.request.HTTPSHandler(context=context))
    except Exception:
        pass
    return urllib.request.build_opener(*handlers)


def _output_name(info, fmt):
    title = str(info.get("title") or "download")
    title = title.encode("utf-8", "ignore")[:120].decode("utf-8", "ignore")
    name = "%s [%s][%s].%s" % (
        title, info.get("id") or "media", fmt.get("format_id") or "fmt",
        str(fmt.get("ext") or "mp4").lower())
    return re.sub(r'[\\/:*?"<>|\x00-\x1f]', "_", name).strip() or "download.mp4"


def _parallel_download(fmt, info, out_dir, connections, callback, proxy=None):
    """Downloads one progressive format with parallel ranged segments.

    Segment files (<dest>.segNN + <dest>.segs.json) survive a failed attempt, so a
    retry resumes every segment instead of starting the file from zero.
    Returns the finished file path.
    """
    url = fmt["url"]
    headers = {}
    for key, value in (fmt.get("http_headers") or {}).items():
        if value:
            headers[str(key)] = str(value)
    headers.setdefault("User-Agent",
                       "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
    headers.setdefault("Accept", "*/*")
    opener = _parallel_opener(proxy)
    dest = os.path.join(out_dir, _output_name(info, fmt))

    def open_range(start, end=None):
        req_headers = dict(headers)
        req_headers["Range"] = "bytes=%d-%s" % (start, "" if end is None else end)
        return opener.open(urllib.request.Request(url, headers=req_headers), timeout=30)

    _dlog("parallel", "probe fmt=%s ext=%s size=%s url=%s" % (
        fmt.get("format_id"), fmt.get("ext"),
        fmt.get("filesize") or fmt.get("filesize_approx"), _redact_url(url)))
    # Probe: one byte tells us whether ranged parallel downloads are possible.
    try:
        probe_resp = open_range(0, 0)
    except Exception as exc:
        _dlog("parallel", "probe request failed: %s" % exc, "W")
        raise RuntimeError(str(exc)) from exc
    status = getattr(probe_resp, "status", None) or probe_resp.getcode()
    _dlog("parallel", "probe HTTP %s range=%r len=%s" % (
        status, probe_resp.headers.get("Content-Range"),
        probe_resp.headers.get("Content-Length")))
    if status == 200:
        _dlog("parallel", "range ignored by server: single-connection streaming")
        # Range ignored: stream this very response as a single-connection download.
        total = int(probe_resp.headers.get("Content-Length") or 0)
        done = 0
        started = time.time()
        with open(dest, "wb") as fh:
            while True:
                if _cancelled(callback):
                    probe_resp.close()
                    raise _Cancelled()
                chunk = probe_resp.read(256 * 1024)
                if not chunk:
                    break
                fh.write(chunk)
                done += len(chunk)
                _event(callback, {
                    "type": "progress", "downloaded": done, "total": total,
                    "speed": int(done / max(1.0, time.time() - started)),
                    "eta": 0, "percent": round(done * 100.0 / total, 1) if total else -1,
                    "filename": os.path.basename(dest)})
        probe_resp.close()
        return dest
    if status != 206:
        probe_resp.close()
        raise RuntimeError("HTTP %s" % status)
    content_range = probe_resp.headers.get("Content-Range") or ""
    probe_resp.close()
    match = re.search(r"/(\d+)\s*$", content_range)
    total = int(match.group(1)) if match else 0
    if total <= 0:
        raise _ParallelUnsupported("unknown total size")

    if os.path.exists(dest) and os.path.getsize(dest) == total:
        return dest

    connections = max(1, min(16, int(connections or 8)))
    seg_count = max(1, min(connections, total // (1024 * 1024)))
    _dlog("parallel", "segmented download total=%d segments=%d dest=%s" % (
        total, seg_count, os.path.basename(dest)))
    base, remainder = divmod(total, seg_count)
    ranges = []
    cursor = 0
    for index in range(seg_count):
        size = base + (1 if index < remainder else 0)
        ranges.append((cursor, cursor + size - 1))
        cursor += size

    meta_path = dest + ".segs.json"
    seg_paths = ["%s.seg%02d" % (dest, i) for i in range(seg_count)]
    progress = [0] * seg_count
    for i, (seg_start, seg_end) in enumerate(ranges):
        if os.path.exists(seg_paths[i]):
            progress[i] = min(os.path.getsize(seg_paths[i]), seg_end - seg_start + 1)
    try:
        with open(meta_path, "w", encoding="utf-8") as fh:
            json.dump({"total": total, "ranges": ranges}, fh)
    except Exception:
        pass

    stop = threading.Event()
    failures = []

    def worker(index):
        seg_start, seg_end = ranges[index]
        seg_len = seg_end - seg_start + 1
        path = seg_paths[index]
        tries = 0
        while not stop.is_set():
            have = os.path.getsize(path) if os.path.exists(path) else 0
            if have > seg_len:
                os.remove(path)
                have = 0
            progress[index] = have
            if have >= seg_len:
                return
            try:
                resp = open_range(seg_start + have, seg_end)
                code = getattr(resp, "status", None) or resp.getcode()
                if code != 206:
                    resp.close()
                    raise _ParallelUnsupported("segment without range support")
                with open(path, "ab") as fh:
                    while not stop.is_set():
                        chunk = resp.read(256 * 1024)
                        if not chunk:
                            break
                        fh.write(chunk)
                        progress[index] += len(chunk)
                resp.close()
                if progress[index] >= seg_len:
                    return
                raise IOError("connection closed early")
            except _ParallelUnsupported:
                failures.append("unsupported")
                stop.set()
                return
            except Exception as exc:  # noqa: BLE001
                tries += 1
                _dlog("parallel", "segment %d try %d failed: %s (have=%s)" % (
                    index, tries, exc, progress[index]), "W")
                if tries > 4:
                    failures.append(str(exc))
                    stop.set()
                    return
                stop.wait(1.5)

    _dlog("parallel", "resume state: %d bytes already in segment files" % sum(progress))
    _last_milestone = [-1]
    threads = [threading.Thread(target=worker, args=(i,), daemon=True)
               for i in range(seg_count)]
    for thread in threads:
        thread.start()
    started = time.time()
    last_done, last_time = sum(progress), started
    while any(thread.is_alive() for thread in threads):
        if _cancelled(callback):
            stop.set()
            for thread in threads:
                thread.join(timeout=5)
            raise _Cancelled()
        time.sleep(0.4)
        done = sum(progress)
        now = time.time()
        speed = int((done - last_done) / max(0.1, now - last_time))
        last_done, last_time = done, now
        _event(callback, {
            "type": "progress", "downloaded": done, "total": total,
            "speed": speed,
            "eta": int((total - done) / speed) if speed else 0,
            "percent": round(done * 100.0 / total, 1),
            "filename": os.path.basename(dest)})
        _step = int(done * 100 / total) // 10 if total else 0
        if _step != _last_milestone[0]:
            _last_milestone[0] = _step
            _dlog("parallel", "progress %d%% (%d/%d) speed=%sB/s" % (
                done * 100 // total if total else 0, done, total, speed), "D")
    for thread in threads:
        thread.join(timeout=5)
    if _cancelled(callback):
        raise _Cancelled()
    if failures:
        _dlog("parallel", "segment failures: %s" % failures[:3], "W")
        if failures[0] == "unsupported":
            raise _ParallelUnsupported("segmented download unsupported")
        raise RuntimeError(failures[0])
    if sum(progress) < total:
        _dlog("parallel", "incomplete: %d of %d bytes in segments" % (sum(progress), total), "W")
        raise RuntimeError("connection closed early")
    _dlog("parallel", "assembling %d segments -> %s" % (seg_count, os.path.basename(dest)))

    with open(dest, "wb") as out:
        for path in seg_paths:
            with open(path, "rb") as fh:
                while True:
                    chunk = fh.read(1024 * 1024)
                    if not chunk:
                        break
                    out.write(chunk)
    if os.path.getsize(dest) != total:
        raise RuntimeError("assembled file size mismatch")
    for path in seg_paths:
        try:
            os.remove(path)
        except OSError:
            pass
    try:
        os.remove(meta_path)
    except OSError:
        pass
    _event(callback, {
        "type": "progress", "downloaded": total, "total": total,
        "speed": 0, "eta": 0, "percent": 100.0,
        "filename": os.path.basename(dest)})
    return dest


_NON_TRANSIENT_TOKENS = (
    "drm", "geo restricted", "geoblocked", "private video", "login required",
    "sign in to confirm", "age-restricted", "age restricted", "not available in your",
)


def _is_transient_error(exc):
    """Most first-attempt media failures are anti-bot/network flakes worth one more go."""
    low = str(exc).lower()
    if any(token in low for token in _NON_TRANSIENT_TOKENS):
        return False
    return True


def _sleep_cancellable(seconds, callback):
    deadline = time.time() + seconds
    while time.time() < deadline:
        if _cancelled(callback):
            raise _Cancelled()
        time.sleep(0.25)


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
        _dlog("yt-dlp", msg, "D")

    def info(self, msg):
        _dlog("yt-dlp", msg, "I")

    def warning(self, msg):
        _dlog("yt-dlp", msg, "W")

    def error(self, msg):
        _dlog("yt-dlp", msg, "E")
        _event(self.callback, {"type": "log", "message": _sanitize(msg, 300)})


def _download_once(request, callback):
    """One download attempt; raises on failure so download() can retry transient ones."""
    _diag_from_options(request)
    url = request.get("url")
    out_dir = request.get("out_dir") or "."
    preset = request.get("preset") or "best"
    format_id = request.get("format_id")
    os.makedirs(out_dir, exist_ok=True)
    _dlog("download", "attempt start fmt=%s preset=%s out=%s url=%s" % (
        format_id or "-", preset, out_dir, _redact_url(url or "")))

    # Fast path: a progressive file (Pornhub MP4, YouTube MP4/WebM, ...) is fetched
    # with parallel ranged segments straight from the already-extracted info, so no
    # extra webpage fetch is needed and single-connection CDN throttling is avoided.
    if format_id:
        fmt = None
        info = None
        try:
            info = _first_entry(_extract(url, request, download=False))
            fmt = _parallel_candidate(info, format_id)
        except Exception:
            fmt = None
        _dlog("download", "parallel candidate for fmt %s: %s" % (
            format_id, "yes ext=" + str(fmt.get("ext")) if fmt is not None else "no"))
        if fmt is not None:
            try:
                connections = 8
                try:
                    connections = int(request.get("fragment_concurrency") or 8)
                except (TypeError, ValueError):
                    pass
                path = _parallel_download(
                    fmt, info, out_dir, connections, callback, request.get("proxy"))
                result = {
                    "ok": True,
                    "title": _sanitize(info.get("title") or "download"),
                    "files": [{"path": path, "size": os.path.getsize(path)}],
                    "duration": info.get("duration"),
                    "thumbnail": info.get("thumbnail"),
                    "uploader": _sanitize(info.get("uploader") or info.get("channel") or ""),
                    "extractor": info.get("extractor_key") or info.get("extractor") or "",
                }
                _event(callback, {"type": "done", "files": result["files"],
                                  "title": result["title"]})
                return result
            except _Cancelled:
                raise
            except Exception:
                # Parallel fetching is an optimisation, never a single point of
                # failure: yt-dlp below re-extracts fresh and downloads its own way.
                # (In production this stayed silent; the diag build records WHY.)
                _dlog_tb("download", "parallel path failed for fmt %s; falling back to yt-dlp" % format_id)
                pass

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
    _dlog("download", "yt-dlp path format selector=%r fragments=%s" % (
        opts.get("format"), opts.get("concurrent_fragment_downloads")))

    _milestone = {"step": -1, "file": ""}

    def hook(d):
        if _cancelled(callback):
            raise _Cancelled()
        status = d.get("status")
        if _DIAG_PATH and status in ("started", "finished", "error"):
            _dlog("download", "hook %s file=%s downloaded=%s total=%s" % (
                status, os.path.basename(str(d.get("filename") or "")),
                d.get("downloaded_bytes"), d.get("total_bytes") or d.get("total_bytes_estimate")))
        if _DIAG_PATH and status == "downloading":
            total_h = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
            done_h = d.get("downloaded_bytes") or 0
            fname_h = str(d.get("filename") or "")
            if fname_h != _milestone["file"]:
                _milestone["file"] = fname_h
                _milestone["step"] = -1
            step_h = int(done_h * 100 / total_h) // 10 if total_h else -1
            if step_h >= 0 and step_h != _milestone["step"]:
                _milestone["step"] = step_h
                _dlog("download", "progress %d%% (%s/%s) speed=%s frag=%s/%s file=%s" % (
                    int(done_h * 100 / total_h), done_h, total_h, int(d.get("speed") or 0),
                    d.get("fragment_index"), d.get("fragment_count"),
                    os.path.basename(fname_h)), "D")
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

    def pp_hook(d):
        _dlog("download", "postprocessor %s status=%s file=%s" % (
            d.get("postprocessor"), d.get("status"),
            os.path.basename(str((d.get("info_dict") or {}).get("filepath") or ""))))

    if _DIAG_PATH:
        opts["postprocessor_hooks"] = [pp_hook]

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
        _dlog("download", "yt-dlp finished but no output file found in %s (dir=%s)" % (
            out_dir, os.listdir(out_dir) if os.path.isdir(out_dir) else "?"), "E")
        raise RuntimeError("فایل خروجی پیدا نشد")
    _dlog("download", "yt-dlp path done files=%s" % (
        ["%s(%dB)" % (os.path.basename(p), os.path.getsize(p)) for p in files],))

    result = {
        "ok": True,
        "title": _sanitize(info.get("title") or "download"),
        "files": [{"path": pth, "size": os.path.getsize(pth)} for pth in files],
        "duration": info.get("duration"),
        "thumbnail": info.get("thumbnail"),
        "uploader": _sanitize(info.get("uploader") or info.get("channel") or ""),
        "extractor": info.get("extractor_key") or info.get("extractor") or "",
    }
    _event(callback, {"type": "done", "files": result["files"], "title": result["title"]})
    return result


def download(request_json, callback=None):
    """
    Downloads exactly one rendition (or a whole single-video url when format_id is null).

    Transient failures (anti-bot 403s, dropped connections, expired signed URLs) are
    retried automatically with a fresh extraction: the first attempt hitting a site's
    bot check no longer means the user has to press retry by hand.

    Events pushed to callback.on_event(json):
        {"type":"start","title":...,"total":...}
        {"type":"progress","downloaded":N,"total":N,"speed":N,"eta":N,"percent":P,"filename":...}
        {"type":"log","message":...}
        {"type":"done","files":[{"path":...,"size":...}]}
        {"type":"error","message":...}
        {"type":"cancelled"}
    """
    started = time.time()
    try:
        request = json.loads(request_json)
    except Exception as exc:  # noqa: BLE001
        return json.dumps({"ok": False, "error": _human_error(exc)}, ensure_ascii=False)
    _diag_from_options(request)
    url = request.get("url")
    _dlog("download", "download() fmt=%s preset=%s url=%s" % (
        request.get("format_id") or "-", request.get("preset") or "best", _redact_url(url or "")))
    last_exc = None
    for attempt in range(3):
        try:
            result = _download_once(request, callback)
            _dlog("download", "download() ok on attempt %d in %.1fs files=%s" % (
                attempt + 1, time.time() - started,
                ["%s(%sB)" % (os.path.basename(f.get("path") or ""), f.get("size"))
                 for f in result.get("files") or []]))
            return json.dumps(result, ensure_ascii=False)
        except _Cancelled:
            _dlog("download", "download() cancelled on attempt %d after %.1fs" % (
                attempt + 1, time.time() - started), "W")
            _event(callback, {"type": "cancelled"})
            return json.dumps({"ok": False, "cancelled": True, "error": "دانلود متوقف شد"},
                              ensure_ascii=False)
        except Exception as exc:  # noqa: BLE001
            if exc.__class__.__name__ == "DownloadCancelled":
                _event(callback, {"type": "cancelled"})
                return json.dumps({"ok": False, "cancelled": True, "error": "دانلود متوقف شد"},
                                  ensure_ascii=False)
            last_exc = exc
            _dlog("download", "attempt %d FAILED (%s: %s) transient=%s human=%r" % (
                attempt + 1, exc.__class__.__name__, _sanitize(exc, 300),
                _is_transient_error(exc), _human_error(exc)), "E")
            _dlog_tb("download", "download attempt %d traceback" % (attempt + 1))
            if attempt >= 2 or not _is_transient_error(exc):
                break
            # Signed URLs / sessions from the failed attempt may be the problem:
            # drop the cached extraction so the retry re-fetches everything fresh.
            _cache_invalidate(url)
            _event(callback, {"type": "log", "message":
                              "تلاش %d ناموفق بود؛ تلاش خودکار دوباره…" % (attempt + 1)})
            try:
                _sleep_cancellable(2.0 * (attempt + 1), callback)
            except _Cancelled:
                _event(callback, {"type": "cancelled"})
                return json.dumps({"ok": False, "cancelled": True,
                                   "error": "دانلود متوقف شد"}, ensure_ascii=False)
    message = _human_error(last_exc)
    _dlog("download", "download() FAILED in %.1fs final=%r detail=%r" % (
        time.time() - started, message, _sanitize(last_exc, 300)), "E")
    _event(callback, {"type": "error", "message": message})
    return json.dumps({"ok": False, "error": message,
                       "detail": _sanitize(last_exc, 400)}, ensure_ascii=False)


# --------------------------------------------------------------------------- misc


def search(query, limit=1, options_json="{}"):
    """Resolves a text query to stream urls (used by the Spotify bridge)."""
    started = time.time()
    try:
        options = json.loads(options_json or "{}")
        _diag_from_options(options)
        _dlog("search", "search start limit=%s query=%r" % (limit, query))
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
        _dlog("search", "search ok in %.1fs hits=%d %s" % (
            time.time() - started, len(entries),
            ["%r dur=%s by %r" % (e["title"], e.get("duration"), e.get("uploader"))
             for e in entries]))
        return json.dumps({"ok": True, "entries": entries}, ensure_ascii=False)
    except Exception as exc:  # noqa: BLE001
        _dlog("search", "search FAILED query=%r human=%r" % (query, _human_error(exc)), "E")
        _dlog_tb("search", "search traceback")
        return json.dumps({"ok": False, "error": _human_error(exc)}, ensure_ascii=False)


def engine_info():
    _dlog("boot", "engine_info engine=%s yt_dlp=%s python=%s diag=%s" % (
        ENGINE_VERSION, getattr(yt_dlp.version, "__version__", "unknown"),
        sys.version.split()[0], bool(_DIAG_PATH)))
    return json.dumps({
        "engine": ENGINE_VERSION,
        "yt_dlp": getattr(yt_dlp.version, "__version__", "unknown"),
        "python": sys.version.split()[0],
    }, ensure_ascii=False)
