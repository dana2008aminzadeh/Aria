"""Regression tests for the exact Python module packaged inside the Android app.

Run with:
    PYTHONPATH=app/src/main/python python3 -m unittest discover -s app/src/test/python -v
"""

import importlib.util
import json
from pathlib import Path
import unittest
from unittest import mock


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


if __name__ == "__main__":
    unittest.main()
