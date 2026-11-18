import copy
import json
import io
import subprocess
import sys
import tempfile
import unittest
import urllib.error
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import common
import mvt
import here
import run
import serve
import tomtom

FIXTURES = Path(__file__).parent / "fixtures"


class FeedTests(unittest.TestCase):
    def test_here_fixture(self):
        result = here.convert(json.loads((FIXTURES / "here.json").read_text()))
        self.assertEqual(len(result["features"]), 3)
        self.assertEqual(result["features"][0]["geometry"]["coordinates"][0], [30.52, 50.45])
        self.assertEqual(result["features"][0]["properties"]["speed_ratio"], 0.25)
        self.assertTrue(result["features"][2]["properties"]["closed"])
        self.assertEqual(result["source_updated"], "2026-11-17T08:00:00Z")

    def test_here_reversible_and_invalid(self):
        payload = json.loads((FIXTURES / "here.json").read_text())
        payload["results"][0]["currentFlow"]["traversability"] = "reversibleNotRoutable"
        self.assertTrue(here.convert(payload)["features"][0]["properties"]["closed"])
        payload["results"][0]["currentFlow"].update(traversability="open", freeFlow=0)
        with self.assertRaises(ValueError):
            here.convert(payload)
        self.assertEqual(here.convert({"results": [], "sourceUpdated": payload["sourceUpdated"]})["features"], [])

    def test_tomtom_binary_fixture(self):
        result = tomtom.convert_tile((FIXTURES / "tomtom.pbf").read_bytes(), 598, 345)
        self.assertEqual(len(result), 3)
        self.assertEqual(result[0]["properties"]["speed_ratio"], 0.25)
        self.assertTrue(result[1]["properties"]["closed"])
        self.assertEqual(result[0]["properties"]["direction"], "forward")
        points = result[0]["geometry"]["coordinates"]
        self.assertLess(points[0][0], points[-1][0])
        self.assertGreater(points[0][1], points[-1][1])  # top-left origin, no latitude flip
        self.assertAlmostEqual(points[0][0], 30.234375)
        self.assertAlmostEqual(points[0][1], 50.51342652633955)

    def test_tomtom_empty_and_corrupt(self):
        empty = bytes.fromhex("1a090a05656d7074797802")
        self.assertEqual(tomtom.convert_tile(empty, 598, 345), [])
        with self.assertRaises(Exception):
            tomtom.convert_tile(b"not a tile", 598, 345)

    def test_tile_quota(self):
        requested = tomtom.tiles()
        self.assertLessEqual(len(requested) * 288, 2500)
        self.assertEqual(len(requested), 6)
        with self.assertRaises(ValueError):
            tomtom.tiles((-10, 40, 30, 60))

    def test_request_parameters_and_no_secret_in_errors(self):
        for provider in (here, tomtom):
            with patch.dict("os.environ", {provider.__name__.upper() + "_API_KEY": "secret"}), \
                    patch.object(provider, "request", side_effect=RuntimeError("quota")) as request:
                with self.assertRaises(RuntimeError):
                    provider.fetch(common.KYIV_BBOX)
                self.assertEqual(request.call_count, 1)
                if provider is here:
                    self.assertEqual(request.call_args.args[1]["locationReferencing"], "shape")
        for error in (urllib.error.HTTPError("url?key=secret", 429, "secret", {}, None),
                      urllib.error.URLError("url?key=secret")):
            with patch("urllib.request.urlopen", side_effect=error):
                with self.assertRaises(RuntimeError) as raised:
                    common.request("https://example.test", {"key": "secret"}, "tile 1")
                self.assertNotIn("secret", str(raised.exception))
                self.assertIn("tile 1", str(raised.exception))

    def test_missing_key_nonzero_warning(self):
        import os
        environment = {k: v for k, v in os.environ.items() if k != "HERE_API_KEY"}
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run([sys.executable, str(Path(here.__file__)), "--output", directory + "/f.json"],
                                    env=environment, capture_output=True, text=True)
        self.assertEqual(result.returncode, 1)
        self.assertIn("WARNING", result.stderr)
        self.assertIn("HERE_API_KEY", result.stderr)

    def test_publish_and_freshness(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            stage, output = root / "stage", root / "out"
            version = stage / "260101"
            version.mkdir(parents=True)
            values = version / "Ukraine_Kyiv.traffic"
            values.write_bytes(b"values")
            values.with_suffix(".traffic.keys").write_bytes(b"keys")
            now = datetime.now(timezone.utc)
            feed = common.collection([], "here", now.isoformat())
            run.publish(stage, output, feed, 900)
            published = output / values.relative_to(stage)
            self.assertEqual(serve.read_fresh(published, now), b"values")
            with self.assertRaises(ValueError):
                serve.read_fresh(published, now + timedelta(seconds=901))
            values.write_bytes(b"updated")
            run.publish(stage, output, feed, 900)
            self.assertEqual(serve.read_fresh(published, now), b"updated")
            published.write_bytes(b"partial")
            with self.assertRaises(ValueError):
                serve.read_fresh(published, now)
            stale = copy.deepcopy(feed)
            stale["source_updated"] = (now - timedelta(hours=1)).isoformat()
            with self.assertRaises(ValueError):
                run.publish(stage, output, stale, 900)
            values.with_suffix(".traffic.keys").write_bytes(b"changed")
            with self.assertRaises(ValueError):
                run.publish(stage, output, feed, 900)
            self.assertFalse(list(output.rglob(".traffic-*")))


class OriginAndPipelineTests(unittest.TestCase):
    def test_origin_status_and_url_decoding(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            stage, output = root / "stage", root / "out"
            (stage / "260101").mkdir(parents=True)
            values = stage / "260101" / "Ukraine Kyiv.traffic"
            values.write_bytes(b"test-values")
            values.with_suffix(".traffic.keys").write_bytes(b"test-keys")
            feed = common.collection([], "here", common.utc_now())
            run.publish(stage, output, feed, 900)
            handler = serve.Handler.__new__(serve.Handler)
            handler.directory = str(output)
            handler.path = "/260101/Ukraine%20Kyiv.traffic"
            statuses, headers = [], {}
            handler.send_response = statuses.append
            handler.send_header = headers.__setitem__
            handler.end_headers = lambda: None
            handler.wfile = io.BytesIO()
            handler.do_GET()
            self.assertEqual(statuses, [200])
            self.assertEqual(handler.wfile.getvalue(), b"test-values")
            self.assertEqual(headers["Cache-Control"], "no-store")
            self.assertNotIn("ETag", headers)
            sidecar = output / "260101" / "Ukraine Kyiv.traffic.json"
            metadata = json.loads(sidecar.read_bytes())
            metadata["source_updated"] = (datetime.now(timezone.utc) - timedelta(hours=1)).isoformat()
            sidecar.write_text(json.dumps(metadata))
            handler.wfile = io.BytesIO()
            with self.assertLogs("traffic_feed", level="WARNING"):
                handler.do_GET()
            self.assertEqual(statuses, [200, 404])
            self.assertEqual(handler.wfile.getvalue(), b"0")

    def test_poll_generation_failure_preserves_publication(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "out"
            feed = common.collection([common.feature([[30.52, 50.45], [30.53, 50.45]], 0.25)],
                                     "here", common.utc_now())
            args = ["run.py", "--provider", "here", "--mwm", str(root / "Ukraine.mwm"),
                    "--traffic-gen", str(root / "traffic_gen"), "--out", str(output)]

            def generate(command, check):
                self.assertTrue(check)
                self.assertEqual(json.loads(Path(command[2]).read_text())["features"], feed["features"])
                generated = Path(command[3]) / "260101" / "Ukraine.traffic"
                generated.parent.mkdir()
                generated.write_bytes(b"values")
                generated.with_suffix(".traffic.keys").write_bytes(b"keys")

            with patch("sys.argv", args), patch("here.fetch", return_value=feed), \
                    patch("run.subprocess.run", side_effect=generate):
                self.assertEqual(run.main(), 0)
            published = output / "260101" / "Ukraine.traffic"
            before = published.with_suffix(".traffic.json").read_bytes()
            with patch("sys.argv", args), patch("here.fetch", return_value=feed), \
                    patch("run.subprocess.run", side_effect=subprocess.CalledProcessError(1, ["traffic_gen"])), \
                    self.assertLogs("traffic_feed", level="WARNING"):
                self.assertEqual(run.main(), 1)
            self.assertEqual(published.read_bytes(), b"values")
            self.assertEqual(published.with_suffix(".traffic.json").read_bytes(), before)

    def test_mvt_truncation_and_negative_deltas(self):
        for data in (b"\x80", b"\x80" * 11):
            with self.assertRaises(ValueError):
                mvt.varint(data, 0)
        for data in (b"\x00", b"\x1a\x05ab", b"\x0b"):
            with self.assertRaises(ValueError):
                list(mvt.fields(data))
        for commands in ([10, 0, 0], [9, 0, 0], [9, 0], [15]):
            with self.assertRaises(ValueError):
                mvt.geometry(commands)
        self.assertEqual(mvt.geometry([9, 20, 20, 10, 3, 5]), [[[10, 10], [8, 7]]])


if __name__ == "__main__":
    unittest.main()
