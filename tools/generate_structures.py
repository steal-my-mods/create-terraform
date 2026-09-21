#!/usr/bin/env python3
"""
Writes the GameTest structure templates.

There is only one and it is empty: every test builds its own rig with `helper.setBlock`, so the
template's whole job is to declare a volume of air for the framework to clear and to give the
tests somewhere to stand. Committing an empty template rather than building it by hand in a
client is what keeps the tests runnable from a clean checkout.

    python3 tools/generate_structures.py [output_root]

Default output root is src/main/resources/data/createterraform/structure.
"""

import struct
import zlib
import os
import struct
import sys

DATA_VERSION = 3955  # 1.21.1

TAG_END = 0
TAG_INT = 3
TAG_STRING = 8
TAG_LIST = 9
TAG_COMPOUND = 10


def name(text):
    encoded = text.encode('utf-8')
    return struct.pack('>H', len(encoded)) + encoded


def tag_int(key, value):
    return bytes([TAG_INT]) + name(key) + struct.pack('>i', value)


def tag_string_payload(text):
    encoded = text.encode('utf-8')
    return struct.pack('>H', len(encoded)) + encoded


def tag_int_list(key, values):
    payload = bytes([TAG_INT]) + struct.pack('>i', len(values))
    for value in values:
        payload += struct.pack('>i', value)
    return bytes([TAG_LIST]) + name(key) + payload


def tag_empty_list(key):
    return bytes([TAG_LIST]) + name(key) + bytes([TAG_END]) + struct.pack('>i', 0)


def tag_palette(key, blocks):
    payload = bytes([TAG_COMPOUND]) + struct.pack('>i', len(blocks))
    for block in blocks:
        payload += bytes([TAG_STRING]) + name('Name') + tag_string_payload(block)
        payload += bytes([TAG_END])
    return bytes([TAG_LIST]) + name(key) + payload


def structure(width, height, depth):
    body = bytes([TAG_COMPOUND]) + name('')
    body += tag_int('DataVersion', DATA_VERSION)
    body += tag_int_list('size', [width, height, depth])
    body += tag_palette('palette', ['minecraft:air'])
    body += tag_empty_list('blocks')
    body += tag_empty_list('entities')
    body += bytes([TAG_END])
    return body


# 13 tall so a Rope Pulley placed near the top of the rig has somewhere to descend to: the
# extension range Create allows is the pulley's own height above the bottom of the world, and the
# GameTest server runs its rigs at y=-59 with the world floor at -64.
STRUCTURES = {
    'test_rig': (13, 13, 13),
}


def gzip_bytes(payload):
    """
    A gzip container assembled by hand, because `gzip.compress` is not reproducible across Python
    versions.

    Setting `mtime=0` is necessary but not sufficient. Two more header bytes carry values the
    standard library picks for itself -- XFL (byte 8) and OS (byte 9) -- and what it picks has
    changed between releases, because `gzip.compress` was reimplemented on top of `zlib.compress`
    rather than `GzipFile`. So the same payload, written by the same script, came out byte-different
    on a runner with a different Python than the machine that committed it, and CI failed a
    generated-asset check that was working exactly as intended.

    Writing the ten header bytes and the eight trailer bytes here leaves only the raw deflate stream
    to zlib, which is the same thing `write_png` in generate_textures.py has always relied on.
    """
    body = zlib.compressobj(9, zlib.DEFLATED, -zlib.MAX_WBITS)
    deflated = body.compress(payload) + body.flush()
    header = b'\x1f\x8b\x08\x00' + b'\x00\x00\x00\x00' + b'\x02\xff'
    trailer = struct.pack('<II', zlib.crc32(payload) & 0xFFFFFFFF, len(payload) & 0xFFFFFFFF)
    return header + deflated + trailer


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else 'src/main/resources/data/createterraform/structure'
    for key, size in STRUCTURES.items():
        path = os.path.join(root, key + '.nbt')
        os.makedirs(os.path.dirname(path), exist_ok=True)
        # Byte-identical between runs *and between machines*, so a re-run is a no-op in git.
        with open(path, 'wb') as handle:
            handle.write(gzip_bytes(structure(*size)))
        print('wrote', path, '(%dx%dx%d)' % size)


if __name__ == '__main__':
    main()
