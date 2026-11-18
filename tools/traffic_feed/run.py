"""One poll, normalization, generation and atomic publication. Schedule every 5 min."""

import argparse
import fcntl
import hashlib
import json
import logging
import os
import subprocess
import tempfile
from datetime import datetime, timezone
from pathlib import Path

from common import KYIV_BBOX, LOG, bbox_arg, timestamp, utc_now


def digest(data):
    return hashlib.sha256(data).hexdigest()


def atomic_write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=path.parent, prefix=".traffic-", delete=False) as output:
        temporary = Path(output.name)
        try:
            os.fchmod(output.fileno(), 0o644)
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
            os.replace(temporary, path)
        finally:
            temporary.unlink(missing_ok=True)


def publish(stage, output, feed, max_age):
    updated = timestamp(feed["source_updated"])
    age = (datetime.now(timezone.utc) - updated).total_seconds()
    if age > max_age or age < -60:
        raise ValueError(f"source data timestamp outside freshness window: age={age:.0f}s")
    files = sorted(stage.glob("*/*.traffic"))
    if not files:
        raise ValueError("traffic_gen produced no .traffic files")
    for values in files:
        relative = values.relative_to(stage)
        destination = output / relative
        keys = values.with_suffix(".traffic.keys").read_bytes()
        key_path = destination.with_suffix(".traffic.keys")
        if key_path.exists() and key_path.read_bytes() != keys:
            raise ValueError(f"immutable traffic keys changed for {relative}; use exact client MWM")
        content = values.read_bytes()
        metadata = {"provider": feed["provider"], "source_updated": feed["source_updated"],
                    "timestamp_kind": feed.get("timestamp_kind", "provider"),
                    "fetched_at": feed["fetched_at"], "published_at": utc_now(),
                    "max_age_seconds": max_age, "values_sha256": digest(content),
                    "keys_sha256": digest(keys)}
        atomic_write(key_path, keys)
        atomic_write(destination, content)
        # Written last; freshness-aware serving rejects a partially published pair by hash.
        atomic_write(destination.with_suffix(".traffic.json"), json.dumps(metadata).encode())
        LOG.info("Published %s source_updated=%s", relative, feed["source_updated"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--provider", choices=("here", "tomtom"), required=True)
    parser.add_argument("--mwm", type=Path, nargs="+", required=True, help="Exact Ukraine .mwm files used by clients")
    parser.add_argument("--traffic-gen", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--bbox", type=bbox_arg, default=KYIV_BBOX)
    parser.add_argument("--max-age", type=int, default=900)
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO)
    try:
        if args.max_age <= 0:
            raise ValueError("max-age must be positive")
        args.out.mkdir(parents=True, exist_ok=True)
        with (args.out / ".traffic-feed.lock").open("w") as lock:
            # A second scheduled process must not replace a newer snapshot with an older poll.
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            # Import only the selected provider.
            if args.provider == "here":
                from here import fetch
            else:
                from tomtom import fetch
            feed = fetch(args.bbox)
            if not feed["features"]:
                raise ValueError("provider returned no traffic features; previous publication retained")
            with tempfile.TemporaryDirectory(prefix="traffic-feed-") as temporary:
                stage = Path(temporary)
                geojson = stage / "flow.geojson"
                geojson.write_text(json.dumps(feed, allow_nan=False), encoding="utf-8")
                for mwm in args.mwm:
                    subprocess.run([str(args.traffic_gen.resolve()), str(mwm.resolve()), str(geojson), str(stage)],
                                   check=True)
                publish(stage, args.out, feed, args.max_age)
        return 0
    except Exception as error:
        LOG.warning("%s publication failed: %s", args.provider, error)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
