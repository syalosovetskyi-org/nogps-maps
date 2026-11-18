"""HERE Traffic API v7 flow; shapes are supplied in travel direction."""

import json

from common import api_key, cli, collection, feature, request


def convert(payload):
    features = []
    for result in payload["results"]:
        flow = result["currentFlow"]
        closed = flow.get("traversability", "open") in ("closed", "reversibleNotRoutable") or flow["jamFactor"] == 10
        free = flow["freeFlow"]
        if free <= 0 and not closed:
            raise ValueError("HERE freeFlow must be positive on an open road")
        ratio = flow["speed"] / free if free > 0 else None
        # Keep links separate: connecting disjoint shapes would create invented roads.
        # Stage 1 uses the location aggregate rather than subSegments' linear offsets.
        for link in result["location"]["shape"]["links"]:
            points = [[point["lng"], point["lat"]] for point in link["points"]]
            features.append(feature(points, ratio, closed))
    return collection(features, "here", payload["sourceUpdated"])


def fetch(bbox):
    payload = request("https://data.traffic.hereapi.com/v7/flow",
                      {"apiKey": api_key("here"), "in": "bbox:" + ",".join(map(str, bbox)),
                       "locationReferencing": "shape"}, "HERE flow bbox=" + str(bbox))
    return convert(json.loads(payload))


if __name__ == "__main__":
    raise SystemExit(cli("here", fetch))
