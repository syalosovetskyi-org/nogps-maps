"""Rebuild the synthetic PBF fixture from tomtom.json using the documented wire schema.

No response was downloaded from an API. Geometry is deliberately simple enough to
check by hand: MoveTo/LineTo, zigzag deltas, extent 4096, top-left origin.
"""

import json
import struct
from pathlib import Path


def integer(value):
    result = bytearray()
    while value >= 128:
        result.append((value & 127) | 128)
        value >>= 7
    result.append(value)
    return bytes(result)


def field(number, value):
    if isinstance(value, int):
        return integer(number << 3) + integer(value)
    if isinstance(value, str):
        value = value.encode()
    return integer((number << 3) | 2) + integer(len(value)) + value


def encode_value(value):
    if isinstance(value, str):
        return field(1, value)
    if isinstance(value, bool):
        return field(7, int(value))
    return integer((3 << 3) | 1) + struct.pack("<d", value)


def main():
    directory = Path(__file__).parent
    fixture = json.loads((directory / "tomtom.json").read_text())
    keys, values, features = [], [], []
    for feature in fixture["features"]:
        tags = []
        for key, value in feature["properties"].items():
            if key not in keys:
                keys.append(key)
            encoded = encode_value(value)
            if encoded not in values:
                values.append(encoded)
            tags.extend((keys.index(key), values.index(encoded)))
        geom = feature["geometry"]
        lines = [geom["coordinates"]] if geom["type"] == "LineString" else geom["coordinates"]
        commands, x, y = [], 0, 0
        for line in lines:
            for i, (px, py) in enumerate(line):
                commands.append(9 if i == 0 else 10)  # one MoveTo or LineTo
                for delta in (px - x, py - y):
                    commands.append((delta << 1) ^ (delta >> 31))
                x, y = px, py
        data = field(2, b"".join(map(integer, tags))) + field(3, 2) + field(4, b"".join(map(integer, commands)))
        features.append(field(2, data))
    layer = (field(1, fixture["name"]) + b"".join(features) + b"".join(field(3, k) for k in keys)
             + b"".join(field(4, v) for v in values) + field(5, 4096) + field(15, 2))
    (directory / "tomtom.pbf").write_bytes(field(3, layer))


if __name__ == "__main__":
    main()
