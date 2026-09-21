#!/usr/bin/env python3
"""
Draws the mod badge: the blue graph-paper disc the sibling addons all use, with this mod's subject
on it.

The disc, the ring, the grid and the drop shadow are lifted verbatim from those siblings -- same
RADIUS, same RING, same GRID_SPACING -- because a badge that is nearly the same reads as a
mistake on a mods list where the others sit next to it. Only SPRITE_SCALE differs between them,
because it is what fits each subject to the same circle.

The subject is **the world as a block**: an isometric cube of turf, soil and stone with an ore
lump in it. Nine subjects were drawn and rendered before this one, and it is the only one that is
not round -- which against four round siblings is the point. See `subject_sprite` for the two that
failed hardest and why.

It is drawn in pixels on a 20x20 grid and blown up by a whole number, so it is Minecraft-shaped
rather than a smooth vector illustration. Everything else is described once at a 256px reference
and scaled by a whole factor, so `--size 512` is the same badge larger rather than a different
one. Sizes must be multiples of 256.

    python3 tools/generate_logo.py                       # src/main/resources/<id>_icon.png, 256
    python3 tools/generate_logo.py branding/icon-512.png --size 512
"""

import math
import os
import struct
import sys
import zlib

REFERENCE = 256                # the size every measurement below was tuned at
SS = 3                         # supersampling factor per axis

# --- badge palette, shared with the sibling addons so the three match ----------
WHITE       = (255.0, 255.0, 255.0)
FIELD_LIGHT = (104.0, 172.0, 217.0)
FIELD       = ( 75.0, 139.0, 193.0)
FIELD_DEEP  = ( 56.0, 114.0, 168.0)
GRID        = (126.0, 190.0, 228.0)
SHADOW      = ( 30.0,  64.0, 100.0)

# --- subject palette -----------------------------------------------------------
# Ground, and deliberately *not* the palette tools/generate_textures.py paints the machine in.
# That one is a blue-grey built to read as painted steel, and rock drawn in it came out looking
# manufactured -- the badge was a machine with a green stripe on it. Earth gets neutral-warm greys
# and brown, close to what the game itself paints stone and dirt.
GRASS       = (104.0, 162.0,  72.0)
GRASS_LIGHT = (134.0, 190.0,  96.0)
GRASS_DARK  = ( 78.0, 126.0,  56.0)
GRASS_DEEP  = ( 56.0,  94.0,  44.0)
DIRT        = (134.0,  96.0,  67.0)
DIRT_LIGHT  = (162.0, 120.0,  86.0)
DIRT_DARK   = (106.0,  74.0,  50.0)
DIRT_DEEP   = ( 80.0,  55.0,  37.0)
# Warm greys, not neutral ones. A pure neutral grey reads as sheet metal at this size; a few
# points of red in the mix is the difference between that and stone.
ROCK        = (136.0, 128.0, 118.0)
ROCK_LIGHT  = (164.0, 156.0, 144.0)
ROCK_DARK   = (110.0, 102.0,  94.0)
ROCK_DEEP   = ( 86.0,  79.0,  72.0)
ORE         = (232.0, 190.0,  86.0)
ORE_LIGHT   = (250.0, 222.0, 140.0)
ORE_DARK    = (168.0, 130.0,  48.0)

# --- weights, which are fractions rather than lengths and so do not scale ------
GRID_ALPHA = 0.28
SHADOW_ALPHA = 0.26

USAGE = ('usage: generate_logo.py [output.png] [--size N]  '
         '(N a positive multiple of {})'.format(REFERENCE))

# --- geometry, in reference pixels ---------------------------------------------
# One factor moves all of it, and it has to leave SPRITE_SCALE a whole number -- keeping the
# subject's pixels square is the entire reason it is scaled by an integer -- so the output size
# must be a multiple of REFERENCE. 256 is the in-jar logo; 512 is what CurseForge wants for a
# project icon, since it downscales gracefully and never upscales.
GEOMETRY = {
    'RADIUS': 124.0,           # outer edge of the badge
    'RING': 9.0,               # white ring thickness
    'GRID_SPACING': 46.0,      # the sibling badges' grid; the old 26 read as a fine mesh
    'GRID_HALF_WIDTH': 2.5,
    'SPRITE_SCALE': 8,         # whole number, so subject pixels stay square
    'STROKE': 6.0,             # white outline thickness
    'SHADOW_DX': 6.0,
    'SHADOW_DY': 8.0,
    'GLOW_DX': -44.0,          # where the light sits, relative to the centre
    'GLOW_DY': -52.0,
}


def configure(size):
    """Scales the geometry above to the requested output size. Call before rendering."""
    if size <= 0 or size % REFERENCE:
        raise SystemExit('size must be a positive multiple of {}, got {}'.format(REFERENCE, size))
    factor = size // REFERENCE
    globals().update({name: value * factor for name, value in GEOMETRY.items()})
    globals().update(OUT=size, N=size * SS, CX=size / 2.0, CY=size / 2.0)


def lerp(a, b, t):
    return (a[0] + (b[0] - a[0]) * t,
            a[1] + (b[1] - a[1]) * t,
            a[2] + (b[2] - a[2]) * t)


# --- the subject ---------------------------------------------------------------

SPRITE_W, SPRITE_H = 20, 20

#: Where the top face sits and how big the block is, in sprite pixels. Narrower and deeper than the
#: first attempt, which came out a paving slab: a block in an inventory slot is about as tall in the
#: wall as it is across the top face.
TOP_Y, HALF_W, HALF_H, DEPTH = 4.4, 7.6, 3.8, 9.4

TONES = {
    'grass': (GRASS_DARK, GRASS_LIGHT, GRASS, GRASS_DARK, GRASS_DEEP),
    'dirt': (DIRT_DARK, DIRT_LIGHT, DIRT, DIRT_DARK, DIRT_DEEP),
    'rock': (ROCK_DARK, ROCK_LIGHT, ROCK, ROCK_DARK, ROCK_DEEP),
}


def across(x, tones, left, right):
    """
    Picks a tone for one column, so a face painted column by column comes out shaded.

    Five bands rather than a gradient: the edge turning away, the lit face, the middle, the shaded
    face, and the edge in shadow. A gradient at nine screen pixels per sprite pixel is just a
    gradient with square corners, which is the thing this badge is trying not to be.
    """
    edge_light, light, mid, dark, edge_dark = tones
    span = right - left
    if span <= 0:
        return mid
    at = (x - left) / span
    if at < 0.14:
        return edge_light
    if at < 0.36:
        return light
    if at < 0.68:
        return mid
    if at < 0.86:
        return dark
    return edge_dark


def speckle(colour, x, y, kind):
    """
    A deterministic fleck of light and shade, so stone reads as stone.

    Smooth shading is what makes a surface look machined, which is right for a barrel and wrong for
    ground. Rock in this game is mottled; two tones of grit scattered through it is the cheapest way
    to say so, and without it the badge's lower half was a sheet of metal however warm the grey --
    and a warm grey was itself a fix, because a neutral one beside anything brass reads as steel.
    """
    if kind == 'grass':
        return colour
    noise = (x * 73856093 ^ y * 19349663) & 0xFF
    if noise < 52:
        return lerp(colour, (0.0, 0.0, 0.0), 0.16)
    if noise > 214:
        return lerp(colour, (255.0, 255.0, 255.0), 0.13)
    return colour


def ore_blob(cells, spots):
    """
    Ore as the game draws it: a chunky lump with a dark edge, not a hairline vein.

    A two-pixel diagonal of a bright colour is a scratch. What makes an ore block read as ore is
    that it is a lump with a rim, and at this size that costs three pixels: a dark one, a body and
    a highlight.
    """
    for x, y in spots:
        for dx, dy in ((0, 0), (1, 0), (0, 1), (1, 1)):
            if 0 <= y + dy < SPRITE_H and 0 <= x + dx < SPRITE_W and cells[y + dy][x + dx] is not None:
                cells[y + dy][x + dx] = ORE_DARK
        if cells[y][x] is not None:
            cells[y][x] = ORE_LIGHT
        if 0 <= y + 1 < SPRITE_H and 0 <= x + 1 < SPRITE_W and cells[y + 1][x + 1] is not None:
            cells[y + 1][x + 1] = ORE


def subject_sprite():
    """
    The world as a block, drawn the way this game draws a block in an inventory slot.

    Eight other subjects were drawn and rendered before this one, and the two that failed hardest
    are the reason it is this:

      * **A drill was the wrong shape.** A drill removes material and this machine adds it, so the
        silhouette was arguing against the mod. A block argues nothing -- it is simply what a world
        is made of here.
      * **A cogwheel was the wrong outline.** Eight teeth round a disc is a sheriff's badge at
        thumbnail size, in brass or in turf; the colours were never the problem.

    It is also the only subject in this family that is not round, which against four round siblings
    is the point rather than an accident. Isometric, because that is the projection every block in
    this game is drawn in, and the one that makes a square read as a solid rather than as a tile.
    """
    cells = [[None] * SPRITE_W for _ in range(SPRITE_H)]
    cx = SPRITE_W / 2.0

    for y in range(SPRITE_H):
        for x in range(SPRITE_W):
            dx, dy = x + 0.5 - cx, y + 0.5 - TOP_Y
            if abs(dx) / HALF_W + abs(dy) / HALF_H <= 1.0:
                cells[y][x] = speckle(across(x, TONES['grass'], cx - HALF_W, cx + HALF_W),
                                      x, y, 'grass')
                continue
            # The two visible walls: below the top face's lower edges, within its width. The left
            # wall takes the lit tone and the right the shaded one, which is what tells them apart
            # when both are the same dirt.
            edge = TOP_Y + HALF_H * (1.0 - abs(dx) / HALF_W)
            if abs(dx) <= HALF_W and edge < y + 0.5 <= edge + DEPTH:
                kind = 'dirt' if (y + 0.5 - edge) < DEPTH * 0.45 else 'rock'
                tones = TONES[kind]
                cells[y][x] = speckle(tones[1] if dx < 0 else tones[3], x, y, kind)

    ore_blob(cells, ((5, 13), (12, 14)))

    rows = []
    for y in range(SPRITE_H):
        rows.append([(0.0, 0.0, 0.0, 0.0) if cells[y][x] is None
                     else (cells[y][x][0], cells[y][x][1], cells[y][x][2], 255.0)
                     for x in range(SPRITE_W)])
    return SPRITE_W, SPRITE_H, rows


def opaque_bounds(width, height, pixels):
    """Bounding box of the visible part, so the badge centres on the art not the canvas."""
    min_x, min_y, max_x, max_y = width, height, -1, -1
    for y in range(height):
        for x in range(width):
            if pixels[y][x][3] > 0:
                min_x = min(min_x, x)
                max_x = max(max_x, x)
                min_y = min(min_y, y)
                max_y = max(max_y, y)
    if max_x < 0:
        raise ValueError('subject is entirely transparent')
    return min_x, min_y, max_x + 1, max_y + 1


def check_fits(width, height, pixels, bounds):
    """
    Refuses a subject whose stroked silhouette would run off the edge of the disc.

    Growing the sprite by a row, or nudging SPRITE_SCALE up one, is the obvious way to make the
    badge bolder and the failure is not obvious at all: the corner nearest the rim gets shaved
    flat by the clip in render(), which at a glance reads as a design choice rather than as the
    drawing being too big. So measure the far corner of every opaque cell, add the stroke, and say
    so here instead. The margin is small on purpose -- the point of the check is to allow the
    subject right up to the ring.
    """
    min_x, min_y, max_x, max_y = bounds
    left = CX - (max_x - min_x) * SPRITE_SCALE / 2.0 - min_x * SPRITE_SCALE
    top = CY - (max_y - min_y) * SPRITE_SCALE / 2.0 - min_y * SPRITE_SCALE

    worst = 0.0
    for y in range(height):
        for x in range(width):
            if not pixels[y][x][3]:
                continue
            for cx in (left + x * SPRITE_SCALE, left + (x + 1) * SPRITE_SCALE):
                for cy in (top + y * SPRITE_SCALE, top + (y + 1) * SPRITE_SCALE):
                    worst = max(worst, math.hypot(cx - CX, cy - CY))

    limit = RADIUS - RING
    if worst + STROKE > limit:
        raise SystemExit(
            'subject overruns the disc: {:.1f}px of stroked art against a {:.1f}px field. '
            'Drop SPRITE_SCALE or trim the sprite.'.format(worst + STROKE, limit))
    return worst + STROKE, limit


def place_sprite():
    """Blows the subject up to badge scale, returning a supersampled colour buffer."""
    width, height, pixels = subject_sprite()
    bounds = opaque_bounds(width, height, pixels)
    check_fits(width, height, pixels, bounds)
    min_x, min_y, max_x, max_y = bounds

    drawn_width = (max_x - min_x) * SPRITE_SCALE
    drawn_height = (max_y - min_y) * SPRITE_SCALE
    left = CX - drawn_width / 2.0 - min_x * SPRITE_SCALE
    top = CY - drawn_height / 2.0 - min_y * SPRITE_SCALE

    step = SPRITE_SCALE * SS
    buffer = [None] * (N * N)
    for y in range(height):
        for x in range(width):
            r, g, b, a = pixels[y][x]
            if not a:
                continue
            packed = (r, g, b)
            x0 = int(round((left + x * SPRITE_SCALE) * SS))
            y0 = int(round((top + y * SPRITE_SCALE) * SS))
            for gy in range(max(0, y0), min(N, y0 + step)):
                row = gy * N
                for gx in range(max(0, x0), min(N, x0 + step)):
                    buffer[row + gx] = packed
    return buffer


def outline_distance(buffer, reach):
    """
    Chamfer distance from the subject, in supersampled pixels, so the white stroke can be taken as
    a band around it. Two sweeps, which is plenty for so short a reach.
    """
    far = float(reach + 2)
    distance = [0.0 if cell is not None else far for cell in buffer]
    straight, diagonal = 1.0, 1.41421356

    for y in range(N):
        row = y * N
        previous = row - N
        for x in range(N):
            index = row + x
            best = distance[index]
            if best == 0.0:
                continue
            if x > 0:
                best = min(best, distance[index - 1] + straight)
            if y > 0:
                best = min(best, distance[previous + x] + straight)
                if x > 0:
                    best = min(best, distance[previous + x - 1] + diagonal)
                if x < N - 1:
                    best = min(best, distance[previous + x + 1] + diagonal)
            distance[index] = best

    for y in range(N - 1, -1, -1):
        row = y * N
        following = row + N
        for x in range(N - 1, -1, -1):
            index = row + x
            best = distance[index]
            if best == 0.0:
                continue
            if x < N - 1:
                best = min(best, distance[index + 1] + straight)
            if y < N - 1:
                best = min(best, distance[following + x] + straight)
                if x < N - 1:
                    best = min(best, distance[following + x + 1] + diagonal)
                if x > 0:
                    best = min(best, distance[following + x - 1] + diagonal)
            distance[index] = best

    return distance


def background(x, y):
    """The graph-paper field at one point, before the subject is laid over it."""
    glow = math.hypot(x - (CX + GLOW_DX), y - (CY + GLOW_DY)) / (RADIUS * 1.55)
    colour = lerp(FIELD_LIGHT, FIELD, min(1.0, glow))
    distance = math.hypot(x - CX, y - CY)
    rim = min(1.0, max(0.0, (distance / RADIUS - 0.55) / 0.45)) ** 1.4
    colour = lerp(colour, FIELD_DEEP, rim)
    for coordinate in (x, y):
        offset = abs(((coordinate + GRID_SPACING / 2.0) % GRID_SPACING) - GRID_SPACING / 2.0)
        if offset < GRID_HALF_WIDTH:
            colour = lerp(colour, GRID, GRID_ALPHA)
    return colour


def render():
    buffer = place_sprite()
    reach = STROKE * SS
    distance = outline_distance(buffer, reach)

    shadow_dx = int(round(SHADOW_DX * SS))
    shadow_dy = int(round(SHADOW_DY * SS))
    inner = RADIUS - RING

    rows = []
    samples = SS * SS
    for py in range(OUT):
        row = []
        for px in range(OUT):
            r = g = b = a = 0.0
            for sy in range(SS):
                gy = py * SS + sy
                y = (gy + 0.5) / SS
                for sx in range(SS):
                    gx = px * SS + sx
                    x = (gx + 0.5) / SS

                    from_centre = math.hypot(x - CX, y - CY)
                    if from_centre > RADIUS:
                        continue
                    if from_centre > inner:
                        colour = WHITE
                    else:
                        index = gy * N + gx
                        cell = buffer[index]
                        if cell is not None:
                            colour = (float(cell[0]), float(cell[1]), float(cell[2]))
                        elif distance[index] <= reach:
                            colour = WHITE
                        else:
                            colour = background(x, y)
                            sx0, sy0 = gx - shadow_dx, gy - shadow_dy
                            if 0 <= sx0 < N and 0 <= sy0 < N:
                                cast = sy0 * N + sx0
                                if buffer[cast] is not None or distance[cast] <= reach:
                                    colour = lerp(colour, SHADOW, SHADOW_ALPHA)

                    r += colour[0]
                    g += colour[1]
                    b += colour[2]
                    a += 1.0

            if a <= 0.0:
                row.append((0, 0, 0, 0))
                continue
            row.append((
                int(round(min(255.0, r / a))),
                int(round(min(255.0, g / a))),
                int(round(min(255.0, b / a))),
                int(round(255.0 * a / samples)),
            ))
        rows.append(row)
    return rows


def write_png(path, rows):
    height, width = len(rows), len(rows[0])
    raw = b''.join(b'\x00' + b''.join(struct.pack('BBBB', *p) for p in row) for row in rows)

    def chunk(kind, data):
        return (struct.pack('>I', len(data)) + kind + data
                + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff))

    png = (b'\x89PNG\r\n\x1a\n'
           + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0))
           + chunk(b'IDAT', zlib.compress(raw, 9))
           + chunk(b'IEND', b''))
    directory = os.path.dirname(path)
    if directory:
        os.makedirs(directory, exist_ok=True)
    with open(path, 'wb') as handle:
        handle.write(png)
    return len(png)


def main():
    arguments = sys.argv[1:]
    size = REFERENCE
    if '--size' in arguments:
        at = arguments.index('--size')
        if at + 1 >= len(arguments):
            raise SystemExit('--size needs a value: %s' % USAGE)
        try:
            size = int(arguments[at + 1])
        except ValueError:
            raise SystemExit('--size wants a whole number, got %r: %s'
                             % (arguments[at + 1], USAGE))
        del arguments[at:at + 2]
    target = arguments[0] if arguments else 'src/main/resources/createterraform_icon.png'

    configure(size)
    written = write_png(target, render())
    print('wrote {} ({}x{}, {} bytes)'.format(target, OUT, OUT, written))


if __name__ == '__main__':
    main()
