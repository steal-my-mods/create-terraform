#!/usr/bin/env python3
"""
Draws every texture this mod ships.

None of Create's art is used or derived from: Create's code is MIT but everything under its
assets/ is All Rights Reserved, so the only safe amount of it to copy is none. What is borrowed
is the *convention* -- 16x16, a flat base with two shade steps, hard 1px highlights, bolts at
the corners -- which is how Minecraft block art has looked since 2011 and is not anyone's to own.

Everything is deterministic: the "noise" is a hash of the coordinate, so re-running this produces
byte-identical files and a diff in the repo means someone changed the drawing.

    python3 tools/generate_textures.py [output_root]

Default output root is src/main/resources/assets/createterraform/textures.
"""

import os
import struct
import sys
import zlib

# --- PNG ------------------------------------------------------------------------------------


def write_png(path, width, height, pixels):
    """pixels: flat list of (r, g, b, a) tuples, row-major from the top left."""
    raw = bytearray()
    for y in range(height):
        raw.append(0)  # filter type 0 (None) -- these are tiny, compression is not the point
        for x in range(width):
            raw.extend(pixels[y * width + x])

    def chunk(tag, data):
        body = tag + data
        return struct.pack('>I', len(data)) + body + struct.pack('>I', zlib.crc32(body))

    png = b'\x89PNG\r\n\x1a\n'
    png += chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(bytes(raw), 9))
    png += chunk(b'IEND', b'')

    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'wb') as handle:
        handle.write(png)


# --- helpers --------------------------------------------------------------------------------


def noise(x, y, salt):
    """A stable -1/0/1 per pixel, so a texture looks worked rather than printed."""
    h = (x * 374761393 + y * 668265263 + salt * 2246822519) & 0xFFFFFFFF
    h = (h ^ (h >> 13)) * 1274126177 & 0xFFFFFFFF
    return ((h >> 7) % 3) - 1


def hash3(x, y, salt):
    """A stable 0..255 per pixel, for anything that needs more than three levels."""
    h = (x * 2654435761 + y * 40503 + salt * 2246822519) & 0xFFFFFFFF
    h = (h ^ (h >> 15)) * 2246822519 & 0xFFFFFFFF
    return (h >> 13) & 0xFF


def shade(colour, amount):
    return tuple(max(0, min(255, c + amount)) for c in colour[:3]) + (colour[3],)


def canvas(width, height, colour):
    return [colour] * (width * height)


def put(pixels, width, x, y, colour):
    if 0 <= x < width and 0 <= y < len(pixels) // width:
        pixels[y * width + x] = colour


def rect(pixels, width, x0, y0, x1, y1, colour):
    for y in range(y0, y1):
        for x in range(x0, x1):
            put(pixels, width, x, y, colour)


def frame(pixels, width, x0, y0, x1, y1, colour):
    for x in range(x0, x1):
        put(pixels, width, x, y0, colour)
        put(pixels, width, x, y1 - 1, colour)
    for y in range(y0, y1):
        put(pixels, width, x0, y, colour)
        put(pixels, width, x1 - 1, y, colour)


def grain(pixels, width, height, salt, strength=6):
    for y in range(height):
        for x in range(width):
            pixels[y * width + x] = shade(pixels[y * width + x], noise(x, y, salt) * strength)


def bolts(pixels, width, positions, colour, highlight):
    for (x, y) in positions:
        put(pixels, width, x, y, colour)
        put(pixels, width, x, y - 1, highlight)


# --- palette --------------------------------------------------------------------------------
#
# The machine is deepslate-dark with brass fittings, because what it is for is deep rock and
# because a black machine reads at a glance against Create's andesite-and-copper greys.

SLATE = (62, 66, 74, 255)
SLATE_DARK = (44, 47, 54, 255)
SLATE_DEEP = (30, 32, 38, 255)
SLATE_LIGHT = (86, 92, 102, 255)

BRASS = (176, 141, 63, 255)
BRASS_DARK = (128, 100, 40, 255)
BRASS_LIGHT = (214, 180, 96, 255)

# Mineral Substrate: wet rock, closer to raw clay-and-iron mud than to lava.
OCHRE = (134, 96, 58, 255)
OCHRE_DARK = (98, 68, 40, 255)
OCHRE_DEEP = (70, 48, 28, 255)
OCHRE_LIGHT = (170, 128, 80, 255)
EMBER = (206, 132, 56, 255)


# --- the Extruder ---------------------------------------------------------------------------
#
# Four faces. The side is the plain casing and sets the language for the rest: a dark plate,
# a lighter bevel on the top and left, a brass band across the waist, four bolts.


def casing(salt):
    pixels = canvas(16, 16, SLATE)
    grain(pixels, 16, 16, salt, 5)

    # Bevel: lit from the top left, as everything in Minecraft is.
    for i in range(16):
        put(pixels, 16, i, 0, SLATE_LIGHT)
        put(pixels, 16, 0, i, SLATE_LIGHT)
        put(pixels, 16, i, 15, SLATE_DEEP)
        put(pixels, 16, 15, i, SLATE_DEEP)
    return pixels


def waistband(pixels):
    rect(pixels, 16, 1, 7, 15, 9, BRASS_DARK)
    for x in range(1, 15):
        put(pixels, 16, x, 7, shade(BRASS, noise(x, 7, 11) * 8))
    return pixels


def extruder_side():
    pixels = waistband(casing(3))
    bolts(pixels, 16, [(2, 3), (13, 3), (2, 13), (13, 13)], SLATE_DEEP, SLATE_LIGHT)
    return pixels


def extruder_top():
    """The casing seen from a face with no fitting on it, plus two substrate lines."""
    pixels = casing(5)
    rect(pixels, 16, 4, 1, 6, 15, BRASS_DARK)
    rect(pixels, 16, 10, 1, 12, 15, BRASS_DARK)
    for y in range(1, 15):
        put(pixels, 16, 4, y, shade(BRASS, noise(4, y, 7) * 8))
        put(pixels, 16, 10, y, shade(BRASS, noise(10, y, 7) * 8))
    return pixels


def extruder_front():
    """The aperture: a recessed square with the die rings inside it and substrate glowing through."""
    pixels = casing(9)
    rect(pixels, 16, 3, 3, 13, 13, SLATE_DEEP)
    frame(pixels, 16, 3, 3, 13, 13, SLATE_DARK)
    frame(pixels, 16, 4, 4, 12, 12, BRASS_DARK)
    frame(pixels, 16, 5, 5, 11, 11, SLATE_DEEP)
    rect(pixels, 16, 6, 6, 10, 10, OCHRE_DARK)
    rect(pixels, 16, 7, 7, 9, 9, EMBER)
    put(pixels, 16, 7, 7, OCHRE_LIGHT)
    bolts(pixels, 16, [(1, 2), (14, 2), (1, 14), (14, 14)], SLATE_DEEP, SLATE_LIGHT)
    return pixels


def extruder_back():
    """The shaft socket. Create's shafts are 4px across, so the hole is drawn to match."""
    pixels = casing(13)
    rect(pixels, 16, 5, 5, 11, 11, SLATE_DARK)
    frame(pixels, 16, 5, 5, 11, 11, SLATE_LIGHT)
    rect(pixels, 16, 6, 6, 10, 10, SLATE_DEEP)
    rect(pixels, 16, 7, 7, 9, 9, BRASS_DARK)
    put(pixels, 16, 7, 7, BRASS_LIGHT)
    bolts(pixels, 16, [(2, 3), (13, 3), (2, 13), (13, 13)], SLATE_DEEP, SLATE_LIGHT)
    return pixels


# --- Mineral Substrate ----------------------------------------------------------------------
#
# Rock in suspension, not lava: mostly umber, with grit suspended in it and the odd hot fleck
# where it has not finished cooling. Still and flowing share a palette so a tank and a pipe do
# not look like two different fluids.


def substrate_still():
    pixels = canvas(16, 16, OCHRE)
    for y in range(16):
        for x in range(16):
            h = hash3(x, y, 21)
            if h < 40:
                colour = OCHRE_DEEP
            elif h < 96:
                colour = OCHRE_DARK
            elif h < 216:
                colour = shade(OCHRE, noise(x, y, 23) * 7)
            elif h < 246:
                colour = OCHRE_LIGHT
            else:
                colour = EMBER
            put(pixels, 16, x, y, colour)
    return pixels


def substrate_flow():
    """The same mud pulled downhill: the grit smears into short diagonal streaks."""
    pixels = canvas(16, 16, OCHRE)
    for y in range(16):
        for x in range(16):
            streak = (x + y * 2) % 16
            h = hash3(streak, y // 2, 31)
            if h < 56:
                colour = OCHRE_DEEP
            elif h < 120:
                colour = OCHRE_DARK
            elif h < 228:
                colour = shade(OCHRE, noise(streak, y, 33) * 7)
            elif h < 250:
                colour = OCHRE_LIGHT
            else:
                colour = EMBER
            put(pixels, 16, x, y, colour)
    return pixels



# --- no bucket here, deliberately -----------------------------------------------------------
#
# The Mineral Substrate bucket is not drawn. It is NeoForge's `neoforge:fluid_container` model over
# `neoforge:item/bucket`, which composites the *vanilla* item/bucket sprite with this fluid's own
# still texture masked into the pail.
#
# That is the right answer for a bucket specifically, and it took a wrong one to see why. A
# hand-drawn bucket has to reproduce a silhouette every player already knows by heart -- fluid in
# the mouth and not the body, no handle, a tapering pail -- and getting any of it slightly off
# reads immediately as wrong. Letting the game draw its own bucket cannot be slightly off, follows
# resource packs for free, and leaves nothing here to keep in step with a Minecraft update.
#
# Every other texture this mod ships is its own, because every other texture is its own object.


TEXTURES = {
    'block/terraform_extruder_side': extruder_side,
    'block/terraform_extruder_top': extruder_top,
    'block/terraform_extruder_front': extruder_front,
    'block/terraform_extruder_back': extruder_back,
    'fluid/mineral_substrate_still': substrate_still,
    'fluid/mineral_substrate_flow': substrate_flow,
}


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else 'src/main/resources/assets/createterraform/textures'
    for name, draw in TEXTURES.items():
        path = os.path.join(root, name + '.png')
        write_png(path, 16, 16, draw())
        print('wrote', path)


if __name__ == '__main__':
    main()
