#!/usr/bin/env python3
"""
Generates the mod badge: the Create-family circle of blue graph paper with this mod's subject
drawn large in front of it.

The badge *convention* -- a white-ringed azure disc of graph paper, the subject given a white
stroke and a soft shadow -- is what every Create addon uses to say "this plugs into Create", and a
convention is not artwork. Nothing here is copied from Create: the palette, the proportions and
the grid are the ones the sibling addons in this family already use, so they sit together on a
mods list, and the subject is drawn from scratch.

The subject is a Terraform Extruder seen three-quarters on, printing a block out of its face. A
machine with something visibly coming out of it is what this mod is, and it reads without a
caption.

It is drawn in pixels on a 24x24 grid and blown up by a whole number, so it is Minecraft-shaped
rather than a smooth vector illustration. Sizes must be multiples of 256.

    python3 tools/generate_logo.py [output.png] [--size 256]
"""

import math
import os
import struct
import sys
import zlib

REFERENCE = 256                # the size every measurement below was tuned at
GRID_CELLS = 24                # the subject is drawn on a 24x24 pixel grid

# --- badge palette, shared with the sibling addons so they match ----------------
WHITE = (255, 255, 255, 255)
FIELD_LIGHT = (104, 172, 217, 255)
FIELD = (75, 139, 193, 255)
FIELD_DEEP = (56, 114, 168, 255)
GRID = (126, 190, 228, 255)
SHADOW = (30, 64, 100, 255)
CLEAR = (0, 0, 0, 0)

# --- subject palette, lifted from tools/generate_textures.py --------------------
# The same slate, brass and ochre the block and the fluid are painted in, so the badge is the
# mod's own colours rather than a second set that only looks similar.
SLATE = (62, 66, 74, 255)
SLATE_DARK = (44, 47, 54, 255)
SLATE_DEEP = (30, 32, 38, 255)
SLATE_LIGHT = (86, 92, 102, 255)
BRASS = (176, 141, 63, 255)
BRASS_DARK = (128, 100, 40, 255)
BRASS_LIGHT = (214, 180, 96, 255)
OCHRE = (134, 96, 58, 255)
OCHRE_LIGHT = (170, 128, 80, 255)
EMBER = (206, 132, 56, 255)
STONE = (128, 128, 128, 255)
STONE_DARK = (98, 98, 98, 255)
STONE_LIGHT = (154, 154, 154, 255)


def write_png(path, size, pixels):
    raw = bytearray()
    for y in range(size):
        raw.append(0)
        for x in range(size):
            raw.extend(pixels[y * size + x])

    def chunk(tag, data):
        body = tag + data
        return struct.pack('>I', len(data)) + body + struct.pack('>I', zlib.crc32(body))

    png = b'\x89PNG\r\n\x1a\n'
    png += chunk(b'IHDR', struct.pack('>IIBBBBB', size, size, 8, 6, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(bytes(raw), 9))
    png += chunk(b'IEND', b'')

    directory = os.path.dirname(path)
    if directory:
        os.makedirs(directory, exist_ok=True)
    with open(path, 'wb') as handle:
        handle.write(png)


# --- the subject, as a 24x24 sprite ---------------------------------------------
#
# Rows are read top to bottom. A space is transparent; every other character is a colour in
# SPRITE_KEYS. Drawing it as text rather than as coordinates is what makes it possible to see
# what the badge looks like without running anything.

SPRITE_KEYS = {
    '#': SLATE_DEEP,
    'x': SLATE_DARK,
    'o': SLATE,
    '+': SLATE_LIGHT,
    'b': BRASS_DARK,
    'B': BRASS,
    'y': BRASS_LIGHT,
    'r': OCHRE,
    'R': OCHRE_LIGHT,
    'e': EMBER,
    's': STONE,
    'd': STONE_DARK,
    'l': STONE_LIGHT,
}

SPRITE = [
    '                        ',
    '                        ',
    '     ##########         ',
    '    #++++++++++#        ',
    '   #+oooooooooo+#       ',
    '   #+o########o+#       ',
    '   #+o#xxxxxx#o+#       ',
    '   #+o#xbbbbx#o+#  llll ',
    '   #+o#xbrrbx#o+# lssssd',
    '   #+o#xbreRx#o+# lssssd',
    '   #+o#xbreRx#o+# lssssd',
    '   #+o#xbrrbx#o+# lssssd',
    '   #+o#xbbbbx#o+#  dddd ',
    '   #+o#xxxxxx#o+#       ',
    '   #+o########o+#       ',
    '   #+oBBBBBBBBo+#       ',
    '   #+oyBBBBBByo+#       ',
    '   #+oooooooooo+#       ',
    '   #+oo#oooo#ooo+#      ',
    '   #++++++++++++#       ',
    '    ##########x#        ',
    '      ########          ',
    '                        ',
    '                        ',
]


def sprite_pixel(gx, gy):
    if not (0 <= gy < len(SPRITE)):
        return None
    row = SPRITE[gy]
    if not (0 <= gx < len(row)):
        return None
    return SPRITE_KEYS.get(row[gx])


def build(size):
    if size % REFERENCE != 0:
        raise SystemExit('size must be a multiple of %d' % REFERENCE)
    scale = size // REFERENCE
    pixels = [CLEAR] * (size * size)

    centre = (size - 1) / 2.0
    disc = size * 0.47
    ring_outer = size * 0.495
    grid_step = max(1, 16 * scale)

    for y in range(size):
        for x in range(size):
            dx = x - centre
            dy = y - centre
            distance = math.sqrt(dx * dx + dy * dy)
            if distance > ring_outer:
                continue
            if distance > disc:
                pixels[y * size + x] = WHITE
                continue
            # Graph paper: a lighter field towards the top left, ruled every 16 reference pixels.
            shade = FIELD_LIGHT if dx + dy < -size * 0.18 else FIELD
            if distance > disc * 0.86:
                shade = FIELD_DEEP
            if x % grid_step == 0 or y % grid_step == 0:
                shade = GRID
            pixels[y * size + x] = shade

    # The subject, blown up by a whole factor and centred, with a hard shadow one cell down-right
    # so it lifts off the paper the way the sibling badges do.
    cell = (size * 0.86) / GRID_CELLS
    origin = centre - cell * GRID_CELLS / 2.0

    def stamp(offset, override=None):
        for y in range(size):
            for x in range(size):
                gx = int((x - origin - offset) // cell)
                gy = int((y - origin - offset) // cell)
                colour = sprite_pixel(gx, gy)
                if colour is None:
                    continue
                dx = x - centre
                dy = y - centre
                if math.sqrt(dx * dx + dy * dy) > ring_outer:
                    continue
                pixels[y * size + x] = override or colour

    stamp(cell * 0.7, SHADOW)
    stamp(0.0)
    return pixels


def main():
    args = [a for a in sys.argv[1:]]
    size = REFERENCE
    if '--size' in args:
        index = args.index('--size')
        size = int(args[index + 1])
        del args[index:index + 2]
    path = args[0] if args else 'src/main/resources/createterraform_icon.png'
    write_png(path, size, build(size))
    print('wrote', path, '(%dx%d)' % (size, size))


if __name__ == '__main__':
    main()
