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


def ramp(dark, light, steps):
    """
    Evenly spaced tones between two colours.

    Create's block textures carry seventeen or eighteen colours apiece and spend them on gradients;
    an earlier pass here spent four or five on hard steps and scattered per-pixel noise over the top,
    which is why the machine read as busy rather than shaded. A ramp is the fix: pick a level, not a
    colour, and let the tones in between do the softening.
    """
    return [tuple(round(d + (l - d) * i / (steps - 1)) for d, l in zip(dark[:3], light[:3])) + (255,)
            for i in range(steps)]


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

#: Twelve steps of slate, ten of brass, nine of mud. Deep ramps on purpose: Create's block
#: textures carry seventeen or eighteen colours apiece and spend them on gradients, and a
#: shallow ramp dithers between tones far enough apart that the dither itself is what you see.
#:
#: The slate ramp runs darker than the palette's own SLATE_DEEP so that **edges** have somewhere to
#: go. Smoothing the surfaces is only half of it: Create's casings are smooth *and* hard-edged, and
#: a pass that softened both turned this machine into a pale lump with no structure in it. Surfaces
#: live in the middle of the ramp; outlines, recesses and sockets reach for its ends.
SLATE_TONES = ramp(shade(SLATE_DEEP, -24), SLATE_LIGHT, 12)
BRASS_TONES = ramp(BRASS_DARK, shade(BRASS_LIGHT, 18), 10)
OCHRE_TONES = ramp(OCHRE_DEEP, OCHRE_LIGHT, 9)


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


def tone(tones, level):
    """A tone from a ramp by position, 0.0 darkest to 1.0 lightest, clamped."""
    return tones[max(0, min(len(tones) - 1, int(round(level * (len(tones) - 1)))))]


#: A 4x4 ordered dither. Blending two adjacent ramp steps through this softens a transition without
#: the salt-and-pepper that per-pixel hash noise leaves behind.
BAYER = [[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]]


def blend(tones, level, x, y):
    """A tone from a ramp at a fractional level, dithered between the two steps either side."""
    span = (len(tones) - 1) * max(0.0, min(1.0, level))
    low = int(span)
    if low >= len(tones) - 1:
        return tones[-1]
    return tones[low + (1 if (span - low) * 16 > BAYER[y % 4][x % 4] else 0)]


def gradient(pixels, width, height, tones, top, bottom):
    """
    A vertical ramp. Use it for something that really is a smooth curve, and almost nothing here is.

    Minecraft's own style guide names three ways of shading a surface that make it read as flat, and
    an earlier pass here managed all three:

      * **banding** -- pixels lined up brightest to darkest in straight rows, which reveals the pixel
        grid and flattens the shape. That is what this function does if you cover a face with it;
      * **pillow shading** -- shades applied concentrically from the outline inwards;
      * **pancake shading** -- highlight on one side, shadow on the other, disregarding the shape.
        That is what the barrel had, and it is why it looked wrong turning: half its faces were lit
        and half were not, and the lit half rotated with it.

    What Create actually does is neither. Its andesite casing is a hard outline, a bright bevel and
    an interior of high-frequency variation streaked along one axis; its shaft is symmetric across
    its width with the variation running along its length. Structured variation is information --
    grain, wear, machining. Isotropic per-pixel noise is not, and the style guide says so.
    """
    for y in range(height):
        level = top + (bottom - top) * y / max(1, height - 1)
        for x in range(width):
            put(pixels, width, x, y, tone(tones, level))


def streak(pixels, width, height, tones, base, spread, salt, along='y'):
    """
    Machined metal: a stable value per line, with a pixel of jitter on top.

    This is the interior Create gives a casing -- not a gradient. Each streak runs the length of the
    surface so the variation reads as grain rather than dirt, and the spread is kept small so the
    face still reads as one material.
    """
    for y in range(height):
        for x in range(width):
            line = x if along == 'y' else y
            level = base + spread * ((hash3(line, 0, salt) / 255.0) - 0.5)
            level += spread * 0.45 * ((hash3(x, y, salt + 7) / 255.0) - 0.5)
            put(pixels, width, x, y, tone(tones, level))


def rod(tones, core=0.78, edge=0.16, salt=61, centre=8.0, radius=2.5):
    """
    A round bar seen from the side.

    **Symmetric** across its width, which is the whole point: this thing turns, and the texture turns
    with it. A highlight placed off to one side rotates with the barrel, so half the faces are lit at
    any moment and which half keeps changing -- the machine looks like it is flashing rather than
    spinning. Dark at both edges and light through the core reads the same from every angle, which is
    what Create's own shaft does.

    The detail is along the length, where rotation does not move it: a stable jitter per row, so the
    barrel has wear to track as it turns instead of being four flat faces.
    """
    pixels = canvas(16, 16, tones[0])
    for x in range(16):
        across = min(1.0, abs(x + 0.5 - centre) / radius)
        for y in range(16):
            level = core - (core - edge) * across ** 1.25
            # Wear along the length, where rotation does not carry it. Banded in pairs of rows so it
            # reads as scoring on a turned bar rather than as dirt sprinkled over one.
            level += 0.28 * ((hash3(y // 2, x % 2, salt) / 255.0) - 0.5)
            put(pixels, 16, x, y, tone(tones, level))
    return pixels


def material(salt, base=0.44, spread=0.38, along='y'):
    """
    A plain machined surface: grain and nothing else. **Uniform under any crop.**

    This replaced a `plate()` that drew an outline, a lit bevel and four corner bolts, which is how
    Create draws a casing -- and which was wrong here for a reason worth writing down.

    Create's andesite casing is a texture on a **cube**. It carries a frame because there is no
    geometry to carry one. This machine's casing is twenty-five boxes, and every one of them took a
    whole-face texture and cropped it: the plinth got rows 14-16, a bore strip got columns 1-5, a
    chamfer got a single pixel. So the outline landed in the middle of some faces and nowhere on
    others, the bolts turned up wherever a crop happened to cover them, and the block ended up with
    frames inside frames. All of the structure this block needs -- edges, recesses, sockets, bevels
    -- is already in the geometry, and Minecraft's directional face shading draws it for free.

    So: textures are material, geometry is structure. Anything drawn *here* has to be information
    the geometry cannot express -- the octagonal rim of a shaft socket, the jaws of a chuck, the glow
    at the tip of a die -- and it has to be positioned against something the model guarantees, not
    against the edge of a 16x16 square that will be cut up.
    """
    pixels = canvas(16, 16, tone(SLATE_TONES, base))
    streak(pixels, 16, 16, SLATE_TONES, base, spread, salt, along=along)
    return pixels


def disc(pixels, width, cx, cy, radius, colour):
    """A filled pixel-art circle, which at this size is really a rounded octagon."""
    for y in range(16):
        for x in range(16):
            dx = x - cx + 0.5
            dy = y - cy + 0.5
            if dx * dx + dy * dy <= radius * radius:
                put(pixels, width, x, y, colour)


def octagon(x, y, centre=7.5):
    """Distance from centre on the metric Create's shafts are cut on."""
    dx, dy = abs(x - centre), abs(y - centre)
    return max(dx, dy, (dx + dy) * 0.66)


def extruder_casing():
    """
    The body's sides.

    Plain. The housing is walls, a floor and a deck, and the model already draws every edge of
    them; a panel drawn here would only be a second panel inside the first.
    """
    return material(3, base=0.44, spread=0.34)


def extruder_rail():
    """
    The plinth, the deck and the tank walls.

    Darker than the housing, which is the only thing distinguishing it now that neither carries an
    outline -- and enough, because every edge between them is a change of face direction and
    Minecraft shades those apart on its own.

    Sampled as thin slices from anywhere in the texture, so the grain runs along the slice rather
    than across it: that is what keeps a two-pixel cut from turning into a stripe.
    """
    return material(21, base=0.30, spread=0.36, along='x')


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
    pixels = material(13, base=0.40, spread=0.30)
    for y in range(16):
        for x in range(16):
            reach = octagon(x, y)
            if 2.6 <= reach < 3.5:
                put(pixels, 16, x, y, tone(SLATE_TONES, 0.02))
            elif 3.5 <= reach < 4.4:
                put(pixels, 16, x, y, tone(SLATE_TONES, 0.76))
    return pixels


def extruder_collar():
    """The front wall around the chuck. Quiet: the chuck and the barrel are in front of it."""
    return material(31, base=0.48, spread=0.30)


def extruder_chuck():
    """
    The chuck: the ring at the front the barrel turns in.

    Steel, not brass. Brass on this machine means *the barrel* -- it is the one part that stands
    outside the cell and the one part a player reads the front off -- and a brass chuck wrapped
    around a brass barrel turns the whole business end into one gold smear.

    Only a narrow band of this is ever drawn: the ring runs 3 to 13 with its middle cut out at 4.4,
    so the jaws are placed on the four flats where that band actually falls. An earlier version put
    them at the top and bottom centre, where three quarters of each one was inside the hole.
    """
    pixels = material(19, base=0.52, spread=0.26)

    for x0, y0, x1, y1 in ((6, 3, 10, 5), (6, 11, 10, 13), (3, 6, 5, 10), (11, 6, 13, 10)):
        rect(pixels, 16, x0, y0, x1, y1, tone(SLATE_TONES, 0.04))
        for x in range(x0, x1):
            put(pixels, 16, x, y0, tone(SLATE_TONES, 0.52))
    return pixels


def extruder_shaft():
    """
    The sides of the shaft stub: a four-pixel column, cross-section across, length down.

    Split from the end the way Create splits `axis` from `axis_top`, and for the same reason: with
    all four sides mapped to one strip there is no room left in the strip for an end as well.
    """
    return rod(SLATE_TONES, core=0.80, edge=0.18, salt=73, radius=2.0)


def extruder_shaft_end():
    """
    The end of the shaft stub.

    Measured off Create's own: `create:block/shaft.json` is **four** pixels square, from 6 to 10, and
    `axis_top` darkens exactly its four corner pixels, which chamfers it into an **octagon**. Getting
    either wrong gives a socket that does not line up with the shaft a player butts against it.
    """
    pixels = canvas(16, 16, tone(SLATE_TONES, 0.30))
    for y in range(6, 10):
        for x in range(6, 10):
            put(pixels, 16, x, y, tone(SLATE_TONES, 0.80 - 0.16 * (x - 6) - 0.06 * (y - 6)))
    for x, y in ((6, 6), (9, 6), (6, 9), (9, 9)):
        put(pixels, 16, x, y, tone(SLATE_TONES, 0.10))
    return pixels


def extruder_barrel():
    """
    The core barrel: the heavy tube the rock comes out of.

    **Brass**, not steel. The barrel is the one thing on this machine that stands outside the cell
    and the one thing a player reads the front off, and it was brass in the design this block was
    chosen from. A grey barrel against a grey casing disappears.

    Round, so it is shaded across its width. An earlier version banded it every five pixels along
    its length, which at a five-pixel width meant the whole barrel was stripes.
    """
    return rod(BRASS_TONES, core=0.94, edge=0.46, salt=61)


def extruder_ram():
    """The drill string: the rod between the shaft socket and the chuck. Plain steel, round."""
    return rod(SLATE_TONES, core=0.70, edge=0.24, salt=43, radius=3.2)


def extruder_substrate():
    """What the tank shows: wet mineral mud, mottled rather than speckled."""
    pixels = canvas(16, 16, OCHRE_TONES[1])
    for y in range(16):
        for x in range(16):
            swirl = (hash3(x // 2, y // 2, 51) / 255.0) * 0.7 + (hash3(x, y, 53) / 255.0) * 0.3
            put(pixels, 16, x, y, blend(OCHRE_TONES, 0.12 + 0.80 * swirl, x, y))
    return pixels


def extruder_die():
    """
    The cutting head: a brass throat with substrate glowing at the centre.

    This is the face that enters the block being printed into, so it is the one piece of the machine
    a player looks straight at, and the only place on it worth spending contrast.
    """
    pixels = canvas(16, 16, tone(SLATE_TONES, 0.2))
    for y in range(16):
        for x in range(16):
            put(pixels, 16, x, y, tone(BRASS_TONES, 0.62 - 0.22 * octagon(x, y) / 8))
    for y in range(16):
        for x in range(16):
            reach = octagon(x, y)
            if reach < 1.6:
                put(pixels, 16, x, y, EMBER)
            elif reach < 2.6:
                put(pixels, 16, x, y, tone(OCHRE_TONES, 0.78))
            elif reach < 3.4:
                put(pixels, 16, x, y, tone(OCHRE_TONES, 0.36))
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
    'block/terraform_extruder_shaft_end': extruder_shaft_end,
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
