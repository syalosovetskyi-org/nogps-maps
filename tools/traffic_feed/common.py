"""Shared GeoJSON contract and HTTP handling. Never log credential-bearing URLs."""

import argparse
import json
import logging
import math
import os
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

KYIV_BBOX = (30.24, 50.21, 30.83, 50.59)  # west, south, east, north
LOG = logging.getLogger("traffic_feed")


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def timestamp(value):
    result = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if result.tzinfo is None:
        raise ValueError("source timestamp must include timezone")
    return result.astimezone(timezone.utc)


def request(endpoint, params, context):
    url = endpoint + "?" + urllib.parse.urlencode(params)
    try:
        with urllib.request.urlopen(url, timeout=30) as response:
            return response.read()
    except urllib.error.HTTPError as error:
        # HTTPError.__str__ and response bodies can contain API keys.
        raise RuntimeError(f"{context}: HTTP {error.code}; quota/auth/server failure") from None
    except (urllib.error.URLError, TimeoutError, OSError):
        raise RuntimeError(f"{context}: network request failed") from None


def api_key(provider):
    name = provider.upper() + "_API_KEY"
    key = os.environ.get(name)
    if not key:
        raise ValueError(f"{name} is required")
    return key


def feature(coordinates, ratio=None, closed=False, direction="forward"):
    if len(coordinates) < 2:
        raise ValueError("traffic line must have at least two points")
    for point in coordinates:
        if (len(point) != 2 or not all(math.isfinite(v) for v in point)
                or not -180 <= point[0] <= 180 or not -85 <= point[1] <= 85):
            raise ValueError("invalid traffic coordinate")
    if type(closed) is not bool:
        raise ValueError("closed must be boolean")
    properties = {"closed": closed, "direction": direction}
    if ratio is not None:
        if not math.isfinite(ratio) or ratio < 0:
            raise ValueError("invalid traffic speed ratio")
        properties["speed_ratio"] = min(1.0, ratio)
    elif not closed:
        raise ValueError("missing speed ratio")
    return {"type": "Feature", "geometry": {"type": "LineString", "coordinates": coordinates},
            "properties": properties}


def collection(features, provider, source_updated):
    timestamp(source_updated)
    return {"type": "FeatureCollection", "features": features, "provider": provider,
            "source_updated": source_updated, "fetched_at": utc_now()}


def bbox_arg(value):
    try:
        west, south, east, north = map(float, value.split(","))
        if not (-180 <= west < east <= 180 and -85 <= south < north <= 85):
            raise ValueError()
        return west, south, east, north
    except ValueError:
        raise argparse.ArgumentTypeError("bbox must be west,south,east,north") from None


def cli(provider, fetch):
    parser = argparse.ArgumentParser(description=f"{provider} traffic to GeoJSON")
    parser.add_argument("--bbox", type=bbox_arg, default=KYIV_BBOX)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO)
    try:
        data = fetch(args.bbox)
        args.output.write_text(json.dumps(data, allow_nan=False), encoding="utf-8")
        return 0
    except Exception as error:
        LOG.warning("%s feed failed: %s", provider, error)
        return 1
