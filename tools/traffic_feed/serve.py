"""Freshness-aware origin for a reverse proxy. Stale data returns TrafficInfo's NO_DATA (404 + 0)."""

import argparse
import functools
import hashlib
import json
import logging
from datetime import datetime, timezone
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from common import LOG, timestamp


def read_fresh(path, now=None):
    metadata = json.loads(path.with_suffix(".traffic.json").read_bytes())
    age = ((now or datetime.now(timezone.utc)) - timestamp(metadata["source_updated"])).total_seconds()
    if not -60 <= age <= metadata["max_age_seconds"]:
        raise ValueError(f"stale traffic data: age={age:.0f}s")
    values = path.read_bytes()
    if hashlib.sha256(values).hexdigest() != metadata["values_sha256"]:
        raise ValueError("publication in progress: values/metadata hash mismatch")
    return values


class Handler(SimpleHTTPRequestHandler):
    def do_GET(self):
        path = Path(self.translate_path(self.path))
        if path.suffix != ".traffic":
            return super().do_GET()
        try:
            data = read_fresh(path)
            code = 200
        except (OSError, ValueError, KeyError, TypeError) as error:
            LOG.warning("Traffic unavailable %s: %s", path.name, error)
            data, code = b"0", 404
        self.send_response(code)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def do_HEAD(self):
        # Do not let proxy HEAD revalidation treat stale values as available.
        self.send_error(405)

    def list_directory(self, path):
        self.send_error(403)
        return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8080)
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO)
    handler = functools.partial(Handler, directory=str(args.out.resolve()))
    ThreadingHTTPServer((args.bind, args.port), handler).serve_forever()


if __name__ == "__main__":
    main()
