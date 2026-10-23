"""
Aria Downloader 2.0 - extraction engine.

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

import json
import os
import re
import sys

try:  # Android has no system CA bundle: yt-dlp/certifi provide one.
    import certifi

    os.environ.setdefault("SSL_CERT_FILE", certifi.where())
    os.environ.setdefault("REQUESTS_CA_BUNDLE", certifi.where())
except Exception:  # pragma: no cover - optional
    pass

import yt_dlp  # noqa: E402  (must come after the SSL env vars)

ENGINE_VERSION = "2.1.0"

# Conservative defaults that improve throughput without creating dozens of connections.
# HLS/DASH sites (including Aparat and Pornhub) benefit from parallel fragments; regular
# HTTP media benefits from ranged chunks and a larger IO buffer.
DEFAULT_FRAGMENT_CONCURRENCY = 4
MAX_FRAGMENT_CONCURRENCY = 8
HTTP_CHUNK_SIZE = 8 * 1024 * 1024
IO_BUFFER_SIZE = 256 * 1024
MAX_PLAYLIST_ITEMS = 120

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
    if "empty media response" in low or "login_required" in low or "login required" in low:
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
    if "timed out" in low or "timeout" in low:
        return "اتصال به سرور زمان‌بر شد. اتصال اینترنت را بررسی کنید."
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
    if options.get("proxy"):
        opts["proxy"] = options["proxy"]
    return opts


# --------------------------------------------------------------------------- formats

_AVC = re.compile(r"^(avc|h264)", re.I)
_AAC = re.compile(r"^(mp4a|aac)", re.I)


def _kind(f):
    """Classifies a yt-dlp format dict for our purposes.

    A missing codec is not the same as the explicit string ``"none"``. Aparat and
    Pornhub commonly return progressive MP4 rows with no codec fields at all. Treating
    those missing fields as ``none`` used to discard every downloadable format.
    This mirrors yt-dlp's own semantics: unknown codecs belong to a progressive row
    until the extractor explicitly marks a track as audio-only or video-only.
    """
    ext = (f.get("ext") or "").lower()
    raw_vcodec = f.get("vcodec")
    raw_acodec = f.get("acodec")
    vcodec = str(raw_vcodec).lower() if raw_vcodec is not None else ""
    acodec = str(raw_acodec).lower() if raw_acodec is not None else ""
    has_video = vcodec != "none"
    has_audio = acodec != "none"
    is_mp4 = ext in ("mp4", "m4a", "m4v")
    avc = bool(_AVC.match(vcodec)) and ext == "mp4"
    aac = bool(_AAC.match(acodec)) and ext in ("m4a", "mp4")
    return {
        "id": str(f.get("format_id") or ""),
        "ext": ext,
        "height": f.get("height") or 0,
        "fps": round(f["fps"], 2) if f.get("fps") else None,
        "abr": round(f["abr"], 1) if f.get("abr") else None,
        "tbr": round(f["tbr"], 1) if f.get("tbr") else None,
        "filesize": int(f["filesize"]) if f.get("filesize") else (
            int(f["filesize_approx"]) if f.get("filesize_approx") else None),
        "has_size": bool(f.get("filesize") or f.get("filesize_approx")),
        "quality": f.get("quality") or 0,
        "has_drm": bool(f.get("has_drm")),
        "has_video": has_video,
        "has_audio": has_audio,
        "audio_only": has_audio and not has_video,
        "progressive": has_video and has_audio,
        "avc": avc,
        "aac": aac,
        "mp4": is_mp4,
        "note": _sanitize(f.get("format_note") or "", 40),
        "vcodec": vcodec if vcodec and vcodec != "none" else None,
        "acodec": acodec if acodec and acodec != "none" else None,
    }


def _usable_rows(info):
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
        if k["has_video"] and k["height"] and k["height"] < 144:
            continue
        if k["has_drm"]:
            continue
        seen.add(k["id"])
        rows.append(k)
    return rows


def _best_video_pair(rows, limit_height):
    """Best remuxable (H.264 mp4) video track at or below `limit_height`."""
    candidates = [r for r in rows if r["has_video"] and not r["has_audio"] and r["avc"]]
    if limit_height:
        within = [r for r in candidates if r["height"] <= limit_height]
        candidates = within or candidates
    if not candidates:
        return None
    # Prefer standard streams whose size is known: some premium/SABR renditions report no
    # size and often need extra tokens, which makes the download fail halfway.
    candidates.sort(key=lambda r: (r["height"], 1 if r["has_size"] else 0,
                                   r.get("fps") or 0, r["tbr"] or 0, r["quality"] or 0),
                    reverse=True)
    return candidates[0]


def _best_audio(rows):
    """Best audio track; m4a/AAC preferred because it can be muxed into mp4."""
    audio = [r for r in rows if r["audio_only"]]
    if not audio:
        return None
    m4a = [r for r in audio if r["aac"] or r["ext"] == "m4a"]
    pool = m4a or audio
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


def _choose_plan(rows, preset):
    """
    Returns (plan, warning).

    plan modes:
      single  -> download one already muxed file
      merge   -> download video + audio separately, then remux into mp4 on the device
      audio   -> audio only
    """
    preset = (preset or "best").lower()
    if preset in ("audio", "music", "mp3"):
        a = _best_audio(rows)
        if a:
            ext = "m4a" if (a["aac"] or a["ext"] == "m4a") else (a["ext"] or "m4a")
            return {
                "mode": "single",
                "format_id": a["id"],
                "ext": ext,
                "audio_only": True,
                "filesize": a["filesize"],
                "note": "صدا",
            }, None
        p = _best_progressive(rows, None)
        if p:
            # Without ffmpeg we cannot strip the video track. Keep the valid combined
            # file, but do not mislabel an MP4 video as an audio-only download.
            is_audio_container = p["ext"] in ("m4a", "mp3", "aac", "ogg", "opus", "flac", "wav")
            return {"mode": "single", "format_id": p["id"], "ext": p["ext"],
                    "audio_only": is_audio_container, "filesize": p["filesize"],
                    "note": "صدا" if is_audio_container else "ویدیوی ترکیبی"}, \
                "فایل صوتی جدا پیدا نشد؛ نسخهٔ ترکیبی با صدا دانلود می‌شود."
        return None, "فرمت صوتی پیدا نشد."

    heights = {"2160": 2160, "1440": 1440, "1080": 1080, "720": 720, "480": 480, "360": 360}
    limit = heights.get(preset, 0) or 0

    video = _best_video_pair(rows, limit)
    audio = _best_audio(rows)
    if video and audio:
        return {
            "mode": "merge",
            "video_id": video["id"],
            "audio_id": audio["id"],
            "height": video["height"],
            "fps": video.get("fps"),
            "ext": "mp4",
            "filesize": ((video["filesize"] or 0) + (audio["filesize"] or 0)) or None,
            "note": "%dp%s" % (video["height"], ("%d" % video["fps"]) if video.get("fps") else ""),
        }, None

    prog = _best_progressive(rows, limit)
    if prog:
        note = None
        if limit and prog["height"] and prog["height"] + 1 < limit:
            note = "بالاترین کیفیت موجود %dp است (سایت فرمت جداگانه تصویر/صدا نمی‌دهد)." % prog["height"]
        return {
            "mode": "single",
            "format_id": prog["id"],
            "height": prog["height"],
            "fps": prog.get("fps"),
            "ext": prog["ext"],
            "filesize": prog["filesize"],
            "note": prog["note"] or ("%dp" % (prog["height"] or 0)),
        }, note

    if video and not audio:  # silent video
        return {
            "mode": "single",
            "format_id": video["id"],
            "height": video["height"],
            "ext": video["ext"],
            "filesize": video["filesize"],
            "note": "ویدیو بدون صدا",
        }, "این محتوا فایل صوتی جدا ندارد."

    return None, "فرمت قابل دانلودی پیدا نشد."


def _public_formats(rows):
    """Format rows for the UI (quality picker)."""
    out = []
    for r in rows:
        if not (r["progressive"] or r["audio_only"] or r["avc"]):
            continue
        out.append({
            "id": r["id"],
            "ext": r["ext"],
            "height": r["height"] or None,
            "fps": r["fps"],
            "abr": r["abr"],
            "tbr": r["tbr"],
            "filesize": r["filesize"],
            "audio_only": r["audio_only"],
            "progressive": r["progressive"],
            "note": r["note"],
        })
    out.sort(key=lambda r: (0 if r["audio_only"] else 1, r["height"] or 0, r["filesize"] or 0),
             reverse=True)
    return out


# --------------------------------------------------------------------------- extraction


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
    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=False)
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
        plan, warning = _choose_plan(rows, preset)
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

        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=True)
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


def search(query, limit=1):
    """Resolves a text query to stream urls (used by the Spotify bridge)."""
    try:
        opts = _base_opts({"noplaylist": True})
        opts.update({"skip_download": True, "extract_flat": True})
        with yt_dlp.YoutubeDL(opts) as ydl:
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
