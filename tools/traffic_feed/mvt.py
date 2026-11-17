"""Minimal reader for the published TomTom MVT schema (flow LineStrings only).

Unknown protobuf fields are skipped for schema compatibility. Invalid/truncated
messages and unsupported geometries fail the whole poll rather than losing roads.
"""

import struct


def varint(data, offset):
    value = 0
    for shift in range(0, 70, 7):
        if offset == len(data):
            raise ValueError("truncated MVT varint")
        byte = data[offset]
        offset += 1
        value |= (byte & 127) << shift
        if byte < 128:
            if value >= 1 << 64:
                raise ValueError("MVT varint exceeds 64 bits")
            return value, offset
    raise ValueError("MVT varint exceeds 64 bits")


def fields(data):
    offset = 0
    while offset < len(data):
        tag, offset = varint(data, offset)
        number, wire = tag >> 3, tag & 7
        if number == 0:
            raise ValueError("invalid MVT field number")
        if wire == 0:
            value, offset = varint(data, offset)
        else:
            if wire == 2:
                size, offset = varint(data, offset)
            elif wire in (1, 5):
                size = 8 if wire == 1 else 4
            else:
                raise ValueError("unsupported MVT wire type")
            if offset + size > len(data):
                raise ValueError("truncated MVT field")
            value = data[offset:offset + size]
            offset += size
        yield number, wire, value


def packed(data):
    offset = 0
    result = []
    while offset < len(data):
        value, offset = varint(data, offset)
        result.append(value)
    return result


def zigzag(value):
    return (value >> 1) ^ -(value & 1)


def tile_value(data):
    for number, wire, value in fields(data):
        if number == 1 and wire == 2:
            return value.decode("utf-8")
        if (number, wire) in ((2, 5), (3, 1)):
            return struct.unpack("<f" if number == 2 else "<d", value)[0]
        if number in (4, 5, 6, 7) and wire == 0:
            if number == 7:
                return bool(value)
            if number == 6:
                return zigzag(value)
            return value
    raise ValueError("MVT value missing or unsupported")


def geometry(commands):
    lines = []
    offset, x, y = 0, 0, 0
    while offset < len(commands):
        command = commands[offset] & 7
        count = commands[offset] >> 3
        offset += 1
        if command not in (1, 2) or count == 0 or offset + 2 * count > len(commands):
            raise ValueError("invalid MVT LineString command")
        for _ in range(count):
            x += zigzag(commands[offset])
            y += zigzag(commands[offset + 1])
            offset += 2
            if command == 1:
                lines.append([])
            if not lines:
                raise ValueError("MVT LineString has no MoveTo")
            lines[-1].append([x, y])
    if not lines or any(len(line) < 2 for line in lines):
        raise ValueError("MVT LineString is empty or degenerate")
    return lines


def decode(payload):
    layers = {}
    for number, wire, layer_data in fields(payload):
        if (number, wire) != (3, 2):
            continue
        name, extent, keys, values, features = None, 4096, [], [], []
        for number, wire, value in fields(layer_data):
            if (number, wire) == (1, 2):
                name = value.decode("utf-8")
            elif (number, wire) == (2, 2):
                features.append(value)
            elif (number, wire) == (3, 2):
                keys.append(value.decode("utf-8"))
            elif (number, wire) == (4, 2):
                values.append(tile_value(value))
            elif (number, wire) == (5, 0):
                extent = value
        if not name or extent <= 0 or name in layers:
            raise ValueError("invalid MVT layer")
        decoded = []
        # Other named layers contain no flow and need not be interpreted.
        if name == "Traffic flow":
            for data in features:
                tags, commands, kind = [], [], None
                for number, wire, value in fields(data):
                    if (number, wire) == (2, 2):
                        tags.extend(packed(value))
                    elif (number, wire) == (3, 0):
                        kind = value
                    elif (number, wire) == (4, 2):
                        commands.extend(packed(value))
                if kind != 2 or len(tags) % 2:
                    raise ValueError("invalid MVT traffic feature")
                properties = {}
                for index in range(0, len(tags), 2):
                    if tags[index] >= len(keys) or tags[index + 1] >= len(values):
                        raise ValueError("MVT tag index outside dictionary")
                    properties[keys[tags[index]]] = values[tags[index + 1]]
                lines = geometry(commands)
                decoded.append({"properties": properties,
                                "geometry": {"type": "MultiLineString", "coordinates": lines}})
        layers[name] = {"extent": extent, "features": decoded}
    return layers
