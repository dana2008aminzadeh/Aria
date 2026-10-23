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


class TransferOptionsTest(unittest.TestCase):
    def test_parallel_fragments_chunks_and_io_buffer_are_enabled(self):
        opts = aria_dl._base_opts({"fragment_concurrency": 6})

        self.assertEqual(6, opts["concurrent_fragment_downloads"])
        self.assertEqual(aria_dl.HTTP_CHUNK_SIZE, opts["http_chunk_size"])
        self.assertEqual(aria_dl.IO_BUFFER_SIZE, opts["buffersize"])
        self.assertEqual(5, opts["fragment_retries"])

    def test_fragment_concurrency_is_clamped(self):
        self.assertEqual(1, aria_dl._base_opts({"fragment_concurrency": 0})[
            "concurrent_fragment_downloads"])
        self.assertEqual(aria_dl.MAX_FRAGMENT_CONCURRENCY, aria_dl._base_opts(
            {"fragment_concurrency": 99})["concurrent_fragment_downloads"])
        self.assertEqual(aria_dl.DEFAULT_FRAGMENT_CONCURRENCY, aria_dl._base_opts(
            {"fragment_concurrency": "invalid"})["concurrent_fragment_downloads"])


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


if __name__ == "__main__":
    unittest.main()
