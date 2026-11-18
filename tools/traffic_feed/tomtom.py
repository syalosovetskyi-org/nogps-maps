"""TomTom relative Vector Flow Tiles, with a bounded number of requests per poll."""

import math

import mvt

from common import KYIV_BBOX, api_key, cli, collection, feature, request, utc_now

ZOOM = 10
MAX_TILES = 8  # 8 * 288 polls/day = 2304 requests/day; no automatic retries.


def tile_xy(lon, lat, zoom):
    scale = 2 ** zoom
    return ((lon + 180) / 360 * scale,
            (1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * scale)


def tiles(bbox=KYIV_BBOX, zoom=ZOOM):
    west, south, east, north = bbox
    left, top = tile_xy(west, north, zoom)
    right, bottom = tile_xy(east, south, zoom)
    count = (math.ceil(right) - math.floor(left)) * (math.ceil(bottom) - math.floor(top))
    if count > MAX_TILES:
        raise ValueError(f"TomTom bbox needs {count} tiles/poll, limit is {MAX_TILES}")
    return [(x, y) for x in range(math.floor(left), math.ceil(right))
            for y in range(math.floor(top), math.ceil(bottom))]


def convert_tile(payload, x, y, zoom=ZOOM):
    # Preserve MVT's top-left origin; default decoder options flip the y axis.
    decoded = mvt.decode(payload)
    if set(decoded) == {"empty"}:
        return []
    layer = decoded["Traffic flow"]
    extent = layer["extent"]
    features = []
    for item in layer["features"]:
        props = item["properties"]
        geometry = item["geometry"]
        if geometry["type"] not in ("LineString", "MultiLineString"):
            raise ValueError("TomTom traffic geometry must be a line")
        coverage = props["traffic_road_coverage"]
        if coverage not in ("full", "one_side"):
            raise ValueError("unknown TomTom traffic_road_coverage")
        # For one_side, the affected carriageway is on the right of the oriented
        # shape for right-hand traffic (on the left for left-hand traffic).
        # 'full' is a one-way road, not a request to color both travel directions.
        lines = [geometry["coordinates"]] if geometry["type"] == "LineString" else geometry["coordinates"]
        for line in lines:
            coordinates = []
            for px, py in line:
                tx, ty = (x + px / extent) / 2 ** zoom, (y + py / extent) / 2 ** zoom
                coordinates.append([tx * 360 - 180,
                                    math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * ty))))])
            features.append(feature(coordinates, props.get("traffic_level"), props.get("road_closure", False)))
    return features


def fetch(bbox):
    requested_tiles = tiles(bbox)
    key = api_key("tomtom")
    # Tiles have no source timestamp: this is acquisition time, explicitly recorded.
    acquired = utc_now()
    features = []
    for x, y in requested_tiles:
        context = f"TomTom relative tile z={ZOOM} x={x} y={y}"
        payload = request(f"https://api.tomtom.com/traffic/map/4/tile/flow/relative/{ZOOM}/{x}/{y}.pbf",
                          {"key": key, "margin": "0"}, context)
        features.extend(convert_tile(payload, x, y))
    result = collection(features, "tomtom", acquired)
    result["timestamp_kind"] = "acquisition"
    result["tiles"] = len(requested_tiles)
    return result


if __name__ == "__main__":
    raise SystemExit(cli("tomtom", fetch))
