"""Regression tests for the exact Python module packaged inside the Android app.

Run with:
    PYTHONPATH=app/src/main/python python3 -m unittest discover -s app/src/test/python -v
"""

import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from yt_dlp.networking import Request
from yt_dlp.networking.impersonate import ImpersonateTarget


MODULE_PATH = Path(__file__).parents[2] / "main" / "python" / "aria_dl.py"
SPEC = importlib.util.spec_from_file_location("aria_dl", MODULE_PATH)
aria_dl = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(aria_dl)


class FormatSelectionTest(unittest.TestCase):
    def test_aparat_formats_without_codec_fields_are_progressive(self):
        # AparatIE returns rows like these: url/ext/format_id/height, but no codecs.
        info = {"formats": [
            {"format_id": "http-360p", "ext": "mp4", "height": 360},
            {"format_id": "http-720p", "ext": "mp4", "height": 720},
        ]}

        rows = aria_dl._usable_rows(info)

        self.assertEqual(2, len(rows))
        self.assertTrue(all(row["progressive"] for row in rows))
        plan, warning = aria_dl._choose_plan(rows, "best")
        self.assertIsNone(warning)
        self.assertEqual("single", plan["mode"])
        self.assertEqual("http-720p", plan["format_id"])
        self.assertEqual(720, plan["height"])

    def test_pornhub_direct_formats_without_codec_fields_are_not_discarded(self):
        info = {"formats": [
            {"format_id": "240p", "ext": "mp4", "height": 240},
            {"format_id": "480p", "ext": "mp4", "height": 480},
            {"format_id": "1080p", "ext": "mp4", "height": 1080},
        ]}

        rows = aria_dl._usable_rows(info)
        plan, _ = aria_dl._choose_plan(rows, "720")

        self.assertEqual(3, len(rows))
        self.assertEqual("single", plan["mode"])
        # The best progressive rendition at or below the requested ceiling is selected.
        self.assertEqual("480p", plan["format_id"])

    def test_explicit_none_still_distinguishes_audio_and_video_tracks(self):
        info = {"formats": [
            {"format_id": "video", "ext": "mp4", "height": 1080,
             "vcodec": "avc1.640028", "acodec": "none"},
            {"format_id": "audio", "ext": "m4a", "height": 0,
             "vcodec": "none", "acodec": "mp4a.40.2", "abr": 128},
        ]}

        rows = aria_dl._usable_rows(info)
        plan, warning = aria_dl._choose_plan(rows, "best")

        self.assertIsNone(warning)
        self.assertEqual("merge", plan["mode"])
        self.assertEqual("video", plan["video_id"])
        self.assertEqual("audio", plan["audio_id"])

    def test_audio_fallback_does_not_mislabel_mp4_video_as_audio(self):
        rows = aria_dl._usable_rows({"formats": [
            {"format_id": "combined", "ext": "mp4", "height": 480},
        ]})

        plan, warning = aria_dl._choose_plan(rows, "audio")

        self.assertFalse(plan["audio_only"])
        self.assertEqual("mp4", plan["ext"])
        self.assertIn("ترکیبی", warning)


class ExactFormatSelectionTest(unittest.TestCase):
    def setUp(self):
        self.info = {"formats": [
            {"format_id": "137", "ext": "mp4", "height": 1080, "width": 1920,
             "vcodec": "avc1.640028", "acodec": "none", "tbr": 4500},
            {"format_id": "248", "ext": "webm", "height": 1080, "width": 1920,
             "vcodec": "vp09.00.10.08", "acodec": "none", "fps": 30},
            {"format_id": "313", "ext": "webm", "height": 2160, "width": 3840,
             "vcodec": "vp09.00.10.08", "acodec": "none"},
            {"format_id": "140", "ext": "m4a", "vcodec": "none",
             "acodec": "mp4a.40.2", "abr": 129},
            {"format_id": "251", "ext": "webm", "vcodec": "none",
             "acodec": "opus", "abr": 160, "language": "en"},
            {"format_id": "18", "ext": "mp4", "height": 360,
             "vcodec": "avc1.42001E", "acodec": "mp4a.40.2"},
            {"format_id": "story", "ext": "mhtml", "format_note": "storyboard"},
        ]}

    def test_public_formats_keep_non_avc_and_full_metadata(self):
        rows = aria_dl._usable_rows(self.info)
        public = aria_dl._public_formats(rows)
        by_id = {row["id"]: row for row in public}

        self.assertEqual(6, len(public))
        self.assertIn("313", by_id)
        self.assertEqual("vp09.00.10.08", by_id["248"]["vcodec"])
        self.assertEqual(1920, by_id["248"]["width"])
        self.assertTrue(by_id["248"]["video_only"])
        self.assertEqual("opus", by_id["251"]["acodec"])
        self.assertEqual("en", by_id["251"]["language"])
        self.assertNotIn("story", by_id)

    def test_exact_vp9_opus_pair_uses_webm(self):
        rows = aria_dl._usable_rows(self.info)
        plan, warning = aria_dl._choose_plan(rows, "best", {
            "selected_format_id": "248", "selected_audio_id": "251",
            "output_kind": "video", "output_ext": "",
        })

        self.assertIsNone(warning)
        self.assertEqual("merge", plan["mode"])
        self.assertEqual("248", plan["video_id"])
        self.assertEqual("251", plan["audio_id"])
        self.assertEqual("webm", plan["ext"])
        self.assertEqual("merge", plan["postprocess"])

    def test_audio_output_from_pair_does_not_download_video(self):
        rows = aria_dl._usable_rows(self.info)
        plan, _ = aria_dl._choose_plan(rows, "best", {
            "selected_format_id": "313", "selected_audio_id": "251",
            "output_kind": "audio", "output_ext": "mp3", "audio_bitrate": 192,
        })

        self.assertEqual("single", plan["mode"])
        self.assertEqual("251", plan["format_id"])
        self.assertNotIn("video_id", plan)
        self.assertTrue(plan["audio_only"])
        self.assertEqual("audio", plan["postprocess"])
        self.assertEqual(192, plan["audio_bitrate"])

    def test_progressive_container_change_is_real_remux_plan(self):
        rows = aria_dl._usable_rows(self.info)
        plan, _ = aria_dl._choose_plan(rows, "best", {
            "selected_format_id": "18", "output_kind": "video", "output_ext": "mkv",
        })

        self.assertEqual("single", plan["mode"])
        self.assertEqual("18", plan["format_id"])
        self.assertEqual("mkv", plan["ext"])
        self.assertEqual("remux", plan["postprocess"])

    def test_original_audio_stays_untouched(self):
        rows = aria_dl._usable_rows(self.info)
        plan, _ = aria_dl._choose_plan(rows, "best", {
            "selected_format_id": "140", "output_kind": "audio", "output_ext": "",
        })

        self.assertEqual("m4a", plan["ext"])
        self.assertEqual("none", plan["postprocess"])
        self.assertEqual("140", plan["format_id"])

    def test_exact_selection_never_silently_falls_back(self):
        rows = aria_dl._usable_rows(self.info)
        plan, error = aria_dl._choose_plan(rows, "best", {
            "selected_format_id": "removed", "output_kind": "video",
        })
        self.assertIsNone(plan)
        self.assertIn("انتخاب‌شده", error)

    def test_incompatible_container_is_rejected(self):
        rows = aria_dl._usable_rows(self.info)
        plan, error = aria_dl._choose_plan(rows, "best", {
            "selected_format_id": "137", "selected_audio_id": "140",
            "output_kind": "video", "output_ext": "webm",
        })
        self.assertIsNone(plan)
        self.assertIn("سازگار", error)


class TransferOptionsTest(unittest.TestCase):
    def test_parallel_fragments_chunks_and_io_buffer_are_enabled(self):
        opts = aria_dl._base_opts({"fragment_concurrency": 6})

        self.assertEqual(6, opts["concurrent_fragment_downloads"])
        self.assertEqual(aria_dl.HTTP_CHUNK_SIZE, opts["http_chunk_size"])
        self.assertEqual(aria_dl.IO_BUFFER_SIZE, opts["buffersize"])
        self.assertEqual(5, opts["fragment_retries"])

    def test_proxy_is_trimmed_and_blank_proxy_is_ignored(self):
        opts = aria_dl._base_opts({"proxy": "  socks5://127.0.0.1:1080  "})
        self.assertEqual("socks5://127.0.0.1:1080", opts["proxy"])
        self.assertNotIn("proxy", aria_dl._base_opts({"proxy": "   "}))

    def test_fragment_concurrency_is_clamped(self):
        self.assertEqual(1, aria_dl._base_opts({"fragment_concurrency": 0})[
            "concurrent_fragment_downloads"])
        self.assertEqual(aria_dl.MAX_FRAGMENT_CONCURRENCY, aria_dl._base_opts(
            {"fragment_concurrency": 99})["concurrent_fragment_downloads"])
        self.assertEqual(aria_dl.DEFAULT_FRAGMENT_CONCURRENCY, aria_dl._base_opts(
            {"fragment_concurrency": "invalid"})["concurrent_fragment_downloads"])

    def test_no_artificial_download_rate_limit_is_configured(self):
        opts = aria_dl._base_opts({})
        self.assertNotIn("ratelimit", opts)
        self.assertNotIn("throttledratelimit", opts)


class AndroidNetworkCompatibilityTest(unittest.TestCase):
    def test_impersonation_marked_request_can_use_standard_android_https(self):
        with aria_dl._AriaYoutubeDL(aria_dl._base_opts({})) as ydl:
            # Do not pretend that urllib supplies a browser TLS fingerprint. If curl-cffi
            # is available it remains a separate, preferred handler.
            self.assertFalse(ydl._impersonate_target_available(ImpersonateTarget()))
            response = ydl.urlopen(Request(
                "data:text/plain,aria",
                extensions={"impersonate": ImpersonateTarget()},
            ))
            try:
                self.assertEqual(b"aria", response.read())
            finally:
                response.close()


class InstagramPublicFallbackTest(unittest.TestCase):
    def test_public_meta_video_is_parsed_and_html_entities_are_decoded(self):
        page = """
            <html><head>
            <meta content="A public reel" property="og:title">
            <meta property="og:video:secure_url"
                  content="https://cdn.example.com/reel.mp4?x=1&amp;y=2">
            <meta property="og:video:width" content="720">
            <meta property="og:video:height" content="1280">
            </head></html>
        """
        info = aria_dl._parse_instagram_public_embed(
            page, "abc", "https://www.instagram.com/reel/abc/")

        self.assertEqual("A public reel", info["title"])
        self.assertEqual("ig-public", info["formats"][0]["format_id"])
        self.assertEqual("https://cdn.example.com/reel.mp4?x=1&y=2",
                         info["formats"][0]["url"])
        rows = aria_dl._usable_rows(info)
        self.assertTrue(rows[0]["progressive"])
        self.assertEqual(1280, rows[0]["height"])

    def test_video_versions_keep_quality_and_stable_format_ids(self):
        page = r'''
            <script>{"video_versions":[
              {"type":101,"width":640,"height":360,
               "url":"https:\/\/cdn.example.com\/low.mp4?a=1\u0026b=2"},
              {"type":102,"width":720,"height":1280,
               "url":"https:\/\/cdn.example.com\/high.mp4"}
            ]}</script>
        '''
        info = aria_dl._parse_instagram_public_embed(
            page, "abc", "https://www.instagram.com/reel/abc/")

        self.assertEqual(["102", "101"], [f["format_id"] for f in info["formats"]])
        self.assertEqual("https://cdn.example.com/low.mp4?a=1&b=2",
                         info["formats"][1]["url"])
        selected, error = aria_dl._choose_plan(aria_dl._usable_rows(info), "best", {
            "selected_format_id": "102", "output_kind": "video",
        })
        self.assertIsNone(error)
        self.assertEqual("102", selected["format_id"])

    def test_failed_normal_extraction_uses_public_embed_for_probe_and_download(self):
        page = b'''<meta property="og:video" content="https://cdn.example.com/reel.mp4">'''
        response = mock.Mock()
        response.url = "https://www.instagram.com/p/abc/embed/captioned/"
        response.read.return_value = page
        ydl = mock.Mock()
        ydl.extract_info.side_effect = RuntimeError("Instagram sent an empty media response")
        ydl.urlopen.return_value = response
        ydl.process_ie_result.side_effect = lambda info, download: {**info, "downloaded": download}

        for download in (False, True):
            info = aria_dl._extract_info(
                ydl, "https://www.instagram.com/reel/abc/?igsh=share", download)
            self.assertEqual(download, info["downloaded"])
            self.assertEqual("ig-public", info["formats"][0]["format_id"])
        response.close.assert_called()

    def test_cancellation_never_starts_another_network_request(self):
        ydl = mock.Mock()
        ydl.extract_info.side_effect = aria_dl._Cancelled()
        with self.assertRaises(aria_dl._Cancelled):
            aria_dl._extract_info(ydl, "https://www.instagram.com/reel/abc/", True)
        ydl.urlopen.assert_not_called()

    def test_share_tokens_audio_pages_and_other_hosts_do_not_trigger_fallback(self):
        self.assertIsNone(aria_dl._instagram_shortcode(
            "https://www.instagram.com/share/reel/token"))
        self.assertIsNone(aria_dl._instagram_shortcode(
            "https://www.instagram.com/reels/audio/123"))
        self.assertIsNone(aria_dl._instagram_shortcode(
            "https://example.com/reel/abc"))
        self.assertEqual("abc", aria_dl._instagram_shortcode(
            "https://instagr.am/reel/abc/?igsh=share"))


class NetworkErrorTest(unittest.TestCase):
    def test_connection_refused_has_actionable_proxy_guidance(self):
        error = RuntimeError(
            "Unable to download webpage: [Errno 111] Connection refused "
            "(caused by ConnectionRefusedError(111, 'Connection refused'))"
        )
        message = aria_dl._human_error(error)
        self.assertIn("Errno 111", message)
        self.assertIn("پروکسی", message)

    def test_dns_failure_is_distinguished(self):
        message = aria_dl._human_error(
            RuntimeError("Temporary failure in name resolution"))
        self.assertIn("DNS", message)


class ExtractionFlowTest(unittest.TestCase):
    def test_playlist_probe_uses_flat_capped_extraction(self):
        playlist = {
            "_type": "playlist",
            "title": "Queue",
            "entries": [{"id": "1", "title": "One", "url": "https://example.com/1"}],
        }
        with mock.patch.object(aria_dl, "_extract", return_value=playlist) as extract:
            result = json.loads(aria_dl.probe("https://example.com/playlist", "{}"))

        self.assertTrue(result["ok"])
        self.assertTrue(result["is_playlist"])
        self.assertEqual(1, len(result["entries"]))
        self.assertTrue(extract.call_args.kwargs["flat_playlist"])

    def test_resolve_returns_aparat_style_plan_as_json(self):
        info = {
            "id": "abc123",
            "title": "Aparat video",
            "formats": [{"format_id": "http-720p", "ext": "mp4", "height": 720}],
        }
        with mock.patch.object(aria_dl, "_extract", return_value=info):
            result = json.loads(aria_dl.resolve(
                "https://www.aparat.com/v/abc123", '{"preset":"best"}'))

        self.assertTrue(result["ok"])
        self.assertEqual("http-720p", result["format_id"])
        self.assertEqual("single", result["mode"])
        self.assertEqual("Aparat video", result["title"])

    def test_resolve_reads_exact_selection_from_options_json(self):
        info = {
            "title": "Exact",
            "formats": [
                {"format_id": "v", "ext": "webm", "height": 1440,
                 "vcodec": "vp09", "acodec": "none"},
                {"format_id": "a", "ext": "webm", "vcodec": "none",
                 "acodec": "opus", "abr": 128},
            ],
        }
        options = json.dumps({
            "preset": "360", "selected_format_id": "v", "selected_audio_id": "a",
            "output_kind": "video", "output_ext": "webm",
            "selection_label": "1440p • WebM",
        })
        with mock.patch.object(aria_dl, "_extract", return_value=info):
            result = json.loads(aria_dl.resolve("https://example.com/v", options))

        self.assertTrue(result["ok"])
        self.assertEqual("v", result["video_id"])
        self.assertEqual("a", result["audio_id"])
        self.assertEqual("webm", result["ext"])
        self.assertEqual("1440p • WebM", result["note"])

    def test_short_share_link_is_resolved_before_extraction(self):
        final = "https://www.pinterest.com/pin/123456789012345678/"
        info = {"id": "1", "title": "Pin", "formats": []}
        ydl = mock.Mock()
        response = mock.Mock()
        response.geturl.return_value = final
        ydl.urlopen.return_value = response
        ydl.extract_info.return_value = info

        result = aria_dl._extract_info(ydl, "https://pin.it/abc123")

        self.assertIs(info, result)
        ydl.extract_info.assert_called_once_with(final, download=False)

    def test_probe_exposes_direct_image_for_image_only_pin(self):
        info = {
            "id": "9", "title": "Photo pin", "extractor": "Pinterest", "formats": [],
            "thumbnails": [
                {"url": "https://i.pinimg.com/236x/photo.jpg", "width": 236, "height": 300},
                {"url": "https://i.pinimg.com/originals/photo.jpg", "width": 1200, "height": 1500},
            ],
        }
        with mock.patch.object(aria_dl, "_extract", return_value=info):
            result = json.loads(aria_dl.probe("https://www.pinterest.com/pin/9/", "{}"))

        self.assertTrue(result["ok"])
        self.assertEqual("https://i.pinimg.com/originals/photo.jpg", result["direct_file_url"])

    def test_reddit_share_link_needs_redirect_resolution(self):
        self.assertTrue(aria_dl._needs_redirect_resolution(
            "https://www.reddit.com/r/pics/s/AbCdEf"))
        self.assertFalse(aria_dl._needs_redirect_resolution(
            "https://www.reddit.com/r/pics/comments/abc123/title/"))


class _RangeHandler:
    pass


class ParallelDownloadTest(unittest.TestCase):
    """Runs the parallel downloader against a local range-capable HTTP server."""

    DATA = bytes((i * 37 + 11) % 256 for i in range(3 * 1024 * 1024 + 12345))

    @classmethod
    def setUpClass(cls):
        import http.server
        import threading as _threading

        data = cls.DATA

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                range_header = self.headers.get("Range")
                if range_header:
                    spec = range_header.split("=", 1)[1]
                    start_s, _, end_s = spec.partition("-")
                    start = int(start_s)
                    end = int(end_s) if end_s else len(data) - 1
                    end = min(end, len(data) - 1)
                    body = data[start:end + 1]
                    self.send_response(206)
                    self.send_header("Content-Range",
                                     "bytes %d-%d/%d" % (start, end, len(data)))
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)
                else:
                    self.send_response(200)
                    self.send_header("Content-Length", str(len(data)))
                    self.end_headers()
                    self.wfile.write(data)

        cls.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        cls.port = cls.server.server_address[1]
        cls.thread = _threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()

    def _fmt(self):
        return {"format_id": "720p", "ext": "mp4", "protocol": "https",
                "url": "http://127.0.0.1:%d/file.mp4" % self.port}

    def test_parallel_download_assembles_exact_bytes(self):
        import tempfile
        events = []
        callback = mock.Mock()
        callback.should_cancel.return_value = False
        callback.on_event.side_effect = lambda payload: events.append(json.loads(payload))
        with tempfile.TemporaryDirectory() as tmp:
            path = aria_dl._parallel_download(
                self._fmt(), {"title": "T", "id": "x1"}, tmp, 4, callback)
            with open(path, "rb") as fh:
                self.assertEqual(self.DATA, fh.read())
            self.assertFalse([n for n in os.listdir(tmp) if ".seg" in n])
        self.assertTrue(any(e["type"] == "progress" and e["downloaded"] == len(self.DATA)
                            for e in events))

    def test_parallel_download_resumes_partial_segments(self):
        import tempfile
        callback = mock.Mock()
        callback.should_cancel.return_value = False
        total = len(self.DATA)
        seg_count = max(1, min(4, total // (1024 * 1024)))
        base, remainder = divmod(total, seg_count)
        ranges = []
        cursor = 0
        for i in range(seg_count):
            size = base + (1 if i < remainder else 0)
            ranges.append((cursor, cursor + size - 1))
            cursor += size
        with tempfile.TemporaryDirectory() as tmp:
            dest = os.path.join(tmp, aria_dl._output_name({"title": "T", "id": "x1"},
                                                          self._fmt()))
            # first segment complete, second segment half present
            s0, e0 = ranges[0]
            with open(dest + ".seg00", "wb") as fh:
                fh.write(self.DATA[s0:e0 + 1])
            s1, e1 = ranges[1]
            half = (e1 - s1 + 1) // 2
            with open(dest + ".seg01", "wb") as fh:
                fh.write(self.DATA[s1:s1 + half])
            path = aria_dl._parallel_download(
                self._fmt(), {"title": "T", "id": "x1"}, tmp, 4, callback)
            with open(path, "rb") as fh:
                self.assertEqual(self.DATA, fh.read())

    def test_candidate_selection(self):
        info = {"formats": [
            {"format_id": "hls-720", "ext": "mp4", "protocol": "m3u8_native",
             "url": "https://cdn.example.com/x.m3u8"},
            {"format_id": "720p", "ext": "mp4", "protocol": "https",
             "url": "https://cdn.example.com/x.mp4"},
        ]}
        self.assertIsNone(aria_dl._parallel_candidate(info, "hls-720"))
        self.assertEqual("720p", aria_dl._parallel_candidate(info, "720p")["format_id"])
        self.assertIsNone(aria_dl._parallel_candidate(info, "missing"))
        self.assertIsNone(aria_dl._parallel_candidate(info, None))


class RetryAndCacheTest(unittest.TestCase):
    def test_cache_roundtrip_and_playlist_exclusion(self):
        aria_dl._INFO_CACHE.clear()
        aria_dl._cache_put("https://x/v", {"_type": "video", "id": "1"})
        self.assertEqual("1", aria_dl._cache_get("https://x/v")["id"])
        aria_dl._cache_put("https://x/p", {"_type": "playlist", "id": "2"})
        self.assertIsNone(aria_dl._cache_get("https://x/p"))
        aria_dl._cache_invalidate("https://x/v")
        self.assertIsNone(aria_dl._cache_get("https://x/v"))

    def test_transient_classification(self):
        self.assertTrue(aria_dl._is_transient_error(RuntimeError("HTTP Error 403")))
        self.assertTrue(aria_dl._is_transient_error(RuntimeError("urlopen error timed out")))
        self.assertFalse(aria_dl._is_transient_error(RuntimeError("This video is DRM protected")))
        self.assertFalse(aria_dl._is_transient_error(RuntimeError("Private video")))

    def test_download_retries_transient_then_succeeds(self):
        request = {"url": "https://example.com/v", "out_dir": "/tmp"}
        with mock.patch.object(
                aria_dl, "_download_once",
                side_effect=[RuntimeError("HTTP Error 403: Forbidden"),
                             {"ok": True, "files": []}]) as once, \
             mock.patch.object(aria_dl, "_sleep_cancellable", lambda s, cb: None), \
             mock.patch.object(aria_dl, "_cache_invalidate") as invalidate:
            result = json.loads(aria_dl.download(json.dumps(request), None))
        self.assertTrue(result["ok"])
        self.assertEqual(2, once.call_count)
        invalidate.assert_called_with("https://example.com/v")

    def test_download_does_not_retry_non_transient(self):
        request = {"url": "https://example.com/v", "out_dir": "/tmp"}
        with mock.patch.object(
                aria_dl, "_download_once",
                side_effect=RuntimeError("This content is DRM protected")) as once, \
             mock.patch.object(aria_dl, "_sleep_cancellable", lambda s, cb: None):
            result = json.loads(aria_dl.download(json.dumps(request), None))
        self.assertFalse(result["ok"])
        self.assertEqual(1, once.call_count)



class DiagnosticsTest(unittest.TestCase):
    """The diag build must record the *real* errors while never leaking secrets."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.log = os.path.join(self.tmp.name, "alldown-diag.log")
        self.previous = aria_dl._DIAG_PATH
        aria_dl._DIAG_PATH = ""

    def tearDown(self):
        aria_dl._DIAG_PATH = self.previous
        self.tmp.cleanup()

    def read_log(self):
        return Path(self.log).read_text(encoding="utf-8")

    def test_redact_url_masks_tokens_but_keeps_content_ids(self):
        self.assertEqual(
            "https://cdn.example.com/v.mp4?token=***&v=abc",
            aria_dl._redact_url("https://cdn.example.com/v.mp4?token=sekrit&v=abc"))
        self.assertEqual(
            "https://www.pornhub.com/view_video.php?viewkey=ph123",
            aria_dl._redact_url("https://www.pornhub.com/view_video.php?viewkey=ph123"))
        self.assertEqual(
            "https://***@example.com/a?X-Amz-Signature=***&x=1",
            aria_dl._redact_url("https://user:pass@example.com/a?X-Amz-Signature=deadbeef&x=1"))

    def test_redact_text_masks_headers(self):
        out = aria_dl._redact_text("Authorization: Bearer abc123 Cookie: sessionid=zz")
        self.assertNotIn("Bearer", out)
        self.assertNotIn("abc123", out)
        self.assertNotIn("sessionid=zz", out)

    def test_redact_proxy_keeps_endpoint_hides_password(self):
        self.assertEqual("http://***@127.0.0.1:8080",
                         aria_dl._redact_proxy("http://dana:secret@127.0.0.1:8080"))
        self.assertEqual("(none)", aria_dl._redact_proxy(""))

    def test_base_opts_stay_quiet_without_diag(self):
        opts = aria_dl._base_opts({})
        self.assertTrue(opts["quiet"])
        self.assertTrue(opts["no_warnings"])
        self.assertNotIn("logger", opts)
        self.assertNotIn("verbose", opts)

    def test_base_opts_become_verbose_with_diag_path(self):
        opts = aria_dl._base_opts({"diag": True, "diag_log_path": self.log})
        self.assertFalse(opts["quiet"])
        self.assertTrue(opts["verbose"])
        self.assertIsInstance(opts["logger"], aria_dl._DiagLogger)
        opts["logger"].warning("requested token=shouldnotleak page")
        content = self.read_log()
        self.assertIn("py-yt-dlp", content)
        self.assertIn("token=***", content)
        self.assertNotIn("shouldnotleak", content)

    def test_configure_diag_writes_boot_line(self):
        result = json.loads(aria_dl.configure_diag(self.log))
        self.assertTrue(result["diag"])
        self.assertIn("python diag configured", self.read_log())

    def test_failed_probe_logs_real_traceback_not_just_human_error(self):
        aria_dl._DIAG_PATH = self.log
        boom = RuntimeError("extractor exploded token=leakme here")
        with mock.patch.object(aria_dl, "_extract", side_effect=boom):
            result = json.loads(aria_dl.probe("https://example.com/watch?v=diagtest"))
        self.assertFalse(result["ok"])  # user still gets the friendly message
        content = self.read_log()
        self.assertIn("probe FAILED", content)
        self.assertIn("extractor exploded", content)   # ...but the log keeps the real one
        self.assertIn("Traceback", content)
        self.assertNotIn("leakme", content)

    def test_download_logs_every_attempt(self):
        aria_dl._DIAG_PATH = self.log
        request = {"url": "https://example.com/v", "out_dir": "/tmp",
                   "diag_log_path": self.log}
        with mock.patch.object(
                aria_dl, "_download_once",
                side_effect=[RuntimeError("HTTP Error 403: Forbidden"),
                             {"ok": True, "files": [{"path": "/tmp/a.mp4", "size": 42}]}]), \
             mock.patch.object(aria_dl, "_sleep_cancellable", lambda s, cb: None), \
             mock.patch.object(aria_dl, "_cache_invalidate"):
            result = json.loads(aria_dl.download(json.dumps(request), None))
        self.assertTrue(result["ok"])
        content = self.read_log()
        self.assertIn("attempt 1 FAILED", content)
        self.assertIn("download() ok on attempt 2", content)
        self.assertIn("a.mp4(42B)", content)


if __name__ == "__main__":
    unittest.main()
