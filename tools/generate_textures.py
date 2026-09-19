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

SLATE = (120, 127, 140, 255)
SLATE_DARK = (92, 98, 110, 255)
SLATE_DEEP = (58, 62, 72, 255)
SLATE_LIGHT = (156, 163, 176, 255)

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
# The block is a casing with an open mouth and a ram that punches a die into the block in front of
# it -- the arrangement every Create machine of this kind uses. A Deployer's pole is modelled from
# z=-9 to 12 and its hand from 12 to 25: the moving parts deliberately run past the block boundary
# and into the neighbour, which is what makes the machine look like it reaches the space it works
# on rather than merely touching it.
#
# So these textures clothe four things: the casing sides, the rails that cap it, the drive housing
# where rotation goes in, and the ram and die that do the reaching.
#
# The convention -- a hard 1px cast outline, a lit bevel top and left, a recessed panel, bolts at
# the corners -- is how machine casings have been drawn in this game since forever. None of it is
# copied from Create; the palette is this mod's own slate and brass.


def plate(salt, panel=True):
    """The casing language: outline, bevel, optional recessed panel, corner bolts."""
    pixels = canvas(16, 16, SLATE)
    grain(pixels, 16, 16, salt, 4)

    if panel:
        rect(pixels, 16, 3, 3, 13, 13, shade(SLATE, -6))
        frame(pixels, 16, 3, 3, 13, 13, SLATE_DARK)

    for i in range(16):
        put(pixels, 16, i, 1, SLATE_LIGHT)
        put(pixels, 16, 1, i, SLATE_LIGHT)
        put(pixels, 16, i, 14, SLATE_DARK)
        put(pixels, 16, 14, i, SLATE_DARK)
    frame(pixels, 16, 0, 0, 16, 16, SLATE_DEEP)

    bolts(pixels, 16, [(2, 2), (13, 2), (2, 13), (13, 13)], SLATE_DEEP, SLATE_LIGHT)
    return pixels


def disc(pixels, width, cx, cy, radius, colour):
    """A filled pixel-art circle, which at this size is really a rounded octagon."""
    for y in range(16):
        for x in range(16):
            dx = x - cx + 0.5
            dy = y - cy + 0.5
            if dx * dx + dy * dy <= radius * radius:
                put(pixels, width, x, y, colour)


def extruder_casing():
    """
    The body's sides.

    Carrying more contrast than a casing strictly needs, because at this size a flat dark panel
    reads as a painted box however much geometry is behind it: the panel is sunk with a shadowed
    top-left and a lit bottom-right, there are ribs down its length, and a brass seam across the
    waist ties the machine to the fluid it burns.
    """
    pixels = canvas(16, 16, SLATE)
    grain(pixels, 16, 16, 3, 5)

    # A sunk panel: dark where the light does not reach, lit on the far lip.
    rect(pixels, 16, 3, 3, 13, 13, SLATE_DARK)
    for i in range(3, 13):
        put(pixels, 16, i, 3, SLATE_DEEP)
        put(pixels, 16, 3, i, SLATE_DEEP)
        put(pixels, 16, i, 12, SLATE_LIGHT)
        put(pixels, 16, 12, i, SLATE_LIGHT)

    # Ribs across the sunk face, which is what the eye reads as depth at this size.
    for x in range(5, 12, 3):
        for y in range(4, 12):
            put(pixels, 16, x, y, shade(SLATE_DEEP, 6))
            put(pixels, 16, x + 1, y, shade(SLATE, 4))

    for i in range(16):
        put(pixels, 16, i, 1, SLATE_LIGHT)
        put(pixels, 16, 1, i, SLATE_LIGHT)
        put(pixels, 16, i, 14, SLATE_DARK)
        put(pixels, 16, 14, i, SLATE_DARK)
    frame(pixels, 16, 0, 0, 16, 16, SLATE_DEEP)

    for x in range(2, 14):
        put(pixels, 16, x, 7, shade(BRASS_DARK, noise(x, 7, 11) * 6))
        put(pixels, 16, x, 8, shade(BRASS, noise(x, 8, 11) * 6))

    bolts(pixels, 16, [(2, 2), (13, 2), (2, 13), (13, 13)], SLATE_DEEP, SLATE_LIGHT)
    return pixels


def extruder_rail():
    """
    The two-pixel rails that cap the casing top and bottom.

    Sampled as thin horizontal slices, so the design has to survive being cut anywhere: uniform
    along x, with bolts on a four-pixel pitch and a lit top edge.
    """
    pixels = canvas(16, 16, SLATE_DARK)
    grain(pixels, 16, 16, 21, 4)
    for x in range(16):
        put(pixels, 16, x, 0, SLATE_LIGHT)
        put(pixels, 16, x, 15, SLATE_DEEP)
        if x % 4 == 2:
            for y in range(16):
                put(pixels, 16, x, y, SLATE_DEEP)
                put(pixels, 16, x, y if y % 4 else y, SLATE_DEEP)
    for x in range(2, 16, 4):
        for y in range(2, 16, 5):
            put(pixels, 16, x, y, SLATE_DEEP)
            put(pixels, 16, x, y - 1, SLATE_LIGHT)
    return pixels


def extruder_drive():
    """
    The back plate: the face rotation goes in through.

    There is no shaft drawn here. The model cuts a real six-pixel bore through this plate, chamfered
    into an octagon, and a four-pixel stub stands in it -- so the socket and the shaft are both
    geometry and both octagonal, as Create's are. All this has to do is ring the hole.

    The ring is measured on the same octagonal metric the bore is cut on. Measured on a square one
    -- which an earlier version did -- the corners of the ring fall *inside* the hole and are thrown
    away, leaving four thin slivers on the flats and a socket that reads as a plain square.
    """
    pixels = plate(13, panel=False)
    for y in range(16):
        for x in range(16):
            dx, dy = abs(x - 7.5), abs(y - 7.5)
            reach = max(dx, dy, (dx + dy) * 0.66)
            if 2.6 <= reach < 3.6:
                put(pixels, 16, x, y, SLATE_DEEP)
            elif 3.6 <= reach < 4.7:
                put(pixels, 16, x, y, SLATE_LIGHT)
    bolts(pixels, 16, [(1, 1), (14, 1), (1, 14), (14, 14)], SLATE_DEEP, SLATE_LIGHT)
    return pixels


def extruder_collar():
    """
    The nozzle's mounting plate. Grey, not brass: the brass is reserved for the spout itself, so
    the one hot-coloured thing on the machine is the thing that does the work.
    """
    pixels = plate(31, panel=False)
    frame(pixels, 16, 2, 2, 14, 14, SLATE_DEEP)
    rect(pixels, 16, 3, 3, 13, 13, SLATE_DARK)
    frame(pixels, 16, 5, 5, 11, 11, SLATE_DEEP)
    rect(pixels, 16, 6, 6, 10, 10, SLATE)
    return pixels


def extruder_shaft():
    """
    The stub of shaft standing in the back socket.

    Measured off Create's own, not guessed at. `create:block/shaft.json` is **four** pixels square,
    from 6 to 10 -- not six -- and its end, `axis_top`, is not a square: the four corner pixels are
    the darkest in the texture, which chamfers it into an **octagon**. Getting either of those wrong
    gives a socket that does not line up with the shaft a player butts against it, which is the one
    thing about a kinetic block nobody will forgive.

    Laid out to serve the two places the model samples it from:

      * the centre four-by-four, which both stub ends read, is the octagonal end of a shaft;
      * the top and bottom strips, which the stubs' sides read, are the streaks along its length.

    Anything outside those regions is never drawn.
    """
    pixels = canvas(16, 16, SLATE_DARK)

    # The streaks along the length, sampled by the sides of both stubs. Create's `axis` is a column
    # of irregular vertical stripes; regular banding reads as a screw thread instead of a rod.
    for strip in (0, 14):
        for y in range(strip, strip + 2):
            for x in range(6, 10):
                shade_of = (SLATE_DEEP, SLATE, SLATE_LIGHT, SLATE_DARK)
                put(pixels, 16, x, y, shade_of[hash3(x, y, 73) % 4])

    # The end of the shaft: lighter towards the middle, with the corners taken off.
    rect(pixels, 16, 6, 6, 10, 10, SLATE)
    put(pixels, 16, 7, 7, SLATE_LIGHT)
    put(pixels, 16, 8, 8, SLATE_LIGHT)
    put(pixels, 16, 8, 7, SLATE_LIGHT)
    for x, y in ((6, 6), (9, 6), (6, 9), (9, 9)):
        put(pixels, 16, x, y, SLATE_DEEP)
    return pixels


def extruder_barrel():
    """
    The core barrel: the heavy tube the rock comes out of.

    **Brass**, not steel. The barrel is the one thing on this machine that stands outside the cell
    and the one thing a player looks at to tell the front from the back, and it was brass in the
    design this block was chosen from. A grey barrel against a grey casing disappears -- which is
    what happened when an earlier pass quietly repointed this at the slate palette.

    Banded along its length rather than around it, because the barrel turns about its own axis and
    bands running the length are the only thing that makes that visible -- the same reason a real
    drill collar is fluted.
    """
    pixels = canvas(16, 16, BRASS)
    grain(pixels, 16, 16, 61, 5)
    for x in range(16):
        band = x % 5
        if band == 0:
            rect(pixels, 16, x, 0, x + 1, 16, BRASS_DARK)
        elif band == 1:
            rect(pixels, 16, x, 0, x + 1, 16, BRASS_LIGHT)
        elif band == 3:
            rect(pixels, 16, x, 0, x + 1, 16, OCHRE)
    for y in range(0, 16, 6):
        rect(pixels, 16, 0, y, 16, y + 1, BRASS_DARK)
    return pixels


def extruder_chuck():
    """
    The chuck: the ring at the front that grips the barrel and turns it.

    Steel, not brass. Brass on this machine means *the barrel* -- it is the one part that stands
    outside the cell and the one part a player reads the front off -- and a brass chuck wrapped
    around a brass barrel turns the whole business end into one gold smear. Only the middle of this
    texture is ever drawn; the model cuts the centre out for the barrel to pass through.

    Four jaw slots, because a chuck has jaws and because four notches sweeping past the front of the
    machine is what makes a ring read as turning rather than painted on.
    """
    pixels = plate(19, panel=False)
    frame(pixels, 16, 1, 1, 15, 15, SLATE_DEEP)
    rect(pixels, 16, 2, 2, 14, 14, SLATE_DARK)
    frame(pixels, 16, 2, 2, 14, 14, SLATE_LIGHT)
    for x0, y0, x1, y1 in ((7, 1, 9, 4), (7, 12, 9, 15), (1, 7, 4, 9), (12, 7, 15, 9)):
        rect(pixels, 16, x0, y0, x1, y1, SLATE_DEEP)
        frame(pixels, 16, x0, y0, x1, y1, SLATE)
    bolts(pixels, 16, [(3, 3), (12, 3), (3, 12), (12, 12)], SLATE_DEEP, SLATE_LIGHT)
    return pixels


def extruder_substrate():
    """What the gauge shows: the same umber the fluid is, a shade darker for being seen through glass."""
    pixels = canvas(16, 16, OCHRE_DARK)
    for y in range(16):
        for x in range(16):
            h = hash3(x, y, 51)
            if h < 60:
                colour = OCHRE_DEEP
            elif h < 200:
                colour = shade(OCHRE_DARK, noise(x, y, 53) * 6)
            elif h < 246:
                colour = OCHRE
            else:
                colour = EMBER
            put(pixels, 16, x, y, colour)
    return pixels


def extruder_die():
    """
    The business end of the ram: a brass throat stepping down to substrate glowing at the centre.

    This is the face that actually enters the block being printed into, so it is the one piece of
    the machine a player looks straight at.
    """
    pixels = canvas(16, 16, SLATE_DEEP)
    grain(pixels, 16, 16, 17, 3)
    frame(pixels, 16, 1, 1, 15, 15, SLATE_DARK)
    frame(pixels, 16, 2, 2, 14, 14, BRASS_DARK)
    rect(pixels, 16, 3, 3, 13, 13, SLATE_DARK)
    frame(pixels, 16, 3, 3, 13, 13, BRASS)
    rect(pixels, 16, 4, 4, 12, 12, SLATE_DEEP)
    rect(pixels, 16, 5, 5, 11, 11, OCHRE_DARK)
    rect(pixels, 16, 6, 6, 10, 10, OCHRE)
    rect(pixels, 16, 7, 7, 9, 9, EMBER)
    put(pixels, 16, 7, 7, OCHRE_LIGHT)
    put(pixels, 16, 8, 8, shade(EMBER, 20))
    return pixels


def extruder_ram():
    """
    The drill string: the rod between the shaft socket and the chuck.

    Plain steel. It used to carry brass bands, from back when it was the spine of a ram, and a pixel
    of it shows through the reveal around the shaft socket -- so those bands read as brass sitting
    behind the shaft, which is nothing the machine has.
    """
    pixels = canvas(16, 16, SLATE_DARK)
    grain(pixels, 16, 16, 43, 7)
    for x in range(16):
        if x % 4 == 0:
            rect(pixels, 16, x, 0, x + 1, 16, SLATE_DEEP)
        elif x % 4 == 1:
            rect(pixels, 16, x, 0, x + 1, 16, SLATE)
    return pixels


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
    'block/terraform_extruder_casing': extruder_casing,
    'block/terraform_extruder_rail': extruder_rail,
    'block/terraform_extruder_collar': extruder_collar,
    'block/terraform_extruder_substrate': extruder_substrate,
    'block/terraform_extruder_drive': extruder_drive,
    'block/terraform_extruder_shaft': extruder_shaft,
    'block/terraform_extruder_barrel': extruder_barrel,
    'block/terraform_extruder_chuck': extruder_chuck,
    'block/terraform_extruder_die': extruder_die,
    'block/terraform_extruder_ram': extruder_ram,
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
