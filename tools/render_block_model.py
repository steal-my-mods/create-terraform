#!/usr/bin/env python3
"""
Draws a block model, so a shape can be looked at without launching a client.

Borrowed from Create: Workers, where it was written for exactly the reason it is
needed here: the Terraform Extruder was designed blind for three rounds. A model was
written, the textures were regenerated, everything compiled, every test passed, and
the only way to find out that the front and the back looked identical was to hand a
build over and wait. A block model is data. It can be looked at.

It is a small orthographic renderer and nothing more: no ambient occlusion, no
bloom, no shadows. What it reproduces is the part that matters for judging a shape --
the isometric view an inventory icon uses, Minecraft's own per-face shading (an up
face is full brightness, a down face half, north/south 0.8, east/west 0.6), and the
auto-derived UVs, which are the thing most likely to be wrong and least likely to be
noticed. What it renders is what the game will render, minus the lighting.

    python3 tools/render_block_model.py <model.json> [-o out.png] [--size 320]
                     [--angle front|quarter|iso|high|side] [--lit N] [--dim N]

A model may carry a `_preview_marks` block describing indicator positions -- the
Worker Station's lamps, say -- and `--lit`/`--dim` then light that many of them, which
is how a staffed block and an empty one get compared side by side. A recess only reads
from the angles that can see into it, so judging one wants more than the inventory
view: `front` is roughly a player standing at the block, `quarter` is walking past it,
`high` is looking down from above.
"""

import json
import math
import os
import struct
import sys
import zlib


# --- PNG reading, lifted from Create: Workers' own renderer so this tool stands alone ---

def read_png(path):
    """
    Minimal reader for the 8-bit RGBA, non-interlaced PNGs this project writes.
    Keeps the tool self-contained rather than shelling out to an image library.
    """
    with open(path, 'rb') as handle:
        return decode_png(handle.read(), path)


# Channels per PNG colour type, which is what sets the stride and the filter's step.
CHANNELS = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}


def decode_png(data, path='<bytes>'):
    """
    The same reader, over bytes rather than a file, and over more than one layout.

    Split out because the page art reads sprites straight out of Minecraft's and
    Create's jars, which are zips -- and extracting them to disk first would mean
    either littering the repo with somebody else's art or keeping a scratch
    directory in step with two dependencies. `path` is only ever used to say which
    file was wrong.

    This repo's own PNGs are all 8-bit RGBA, which is all this used to read.
    Minecraft's are not: most vanilla and Create sprites are 4- or 8-bit *palette*
    images with their transparency in a tRNS chunk, so reading them needs palette,
    greyscale and RGB as well. Everything is returned as RGBA either way, so nothing
    downstream has to know which it got.

    Interlaced images and 16-bit channels are still refused rather than guessed at;
    neither appears in a Minecraft resource pack.
    """
    if data[:8] != b'\x89PNG\r\n\x1a\n':
        raise ValueError('{}: not a PNG'.format(path))

    width = height = depth = colour = None
    palette, transparency = [], b''
    compressed = b''
    offset = 8
    while offset < len(data):
        length = struct.unpack('>I', data[offset:offset + 4])[0]
        kind = data[offset + 4:offset + 8]
        payload = data[offset + 8:offset + 8 + length]
        if kind == b'IHDR':
            width, height, depth, colour, compression, filtering, interlace = \
                struct.unpack('>IIBBBBB', payload)
            if interlace:
                raise ValueError('{}: interlaced PNGs are not read'.format(path))
            if colour not in CHANNELS:
                raise ValueError('{}: unknown colour type {}'.format(path, colour))
            if depth == 16 or (colour != 3 and depth != 8):
                raise ValueError('{}: expected 8 bits per channel, got {}'.format(path, depth))
            if (compression, filtering) != (0, 0):
                raise ValueError('{}: unexpected compression or filter method'.format(path))
        elif kind == b'PLTE':
            palette = [tuple(payload[i:i + 3]) for i in range(0, len(payload), 3)]
        elif kind == b'tRNS':
            transparency = payload
        elif kind == b'IDAT':
            compressed += payload
        elif kind == b'IEND':
            break
        offset += 12 + length

    channels = CHANNELS[colour]
    bits = channels * depth
    stride = (width * bits + 7) // 8
    # The filter's step is whole bytes, and never less than one -- which is what makes
    # a sub-byte palette image filter over bytes rather than over pixels.
    step = max(1, bits // 8)

    raw = zlib.decompress(compressed)
    rows = []
    previous = bytearray(stride)
    position = 0
    for _ in range(height):
        filter_type = raw[position]
        position += 1
        line = bytearray(raw[position:position + stride])
        position += stride
        for i in range(stride):
            left = line[i - step] if i >= step else 0
            up = previous[i]
            upper_left = previous[i - step] if i >= step else 0
            if filter_type == 0:
                pass
            elif filter_type == 1:
                line[i] = (line[i] + left) & 0xFF
            elif filter_type == 2:
                line[i] = (line[i] + up) & 0xFF
            elif filter_type == 3:
                line[i] = (line[i] + ((left + up) >> 1)) & 0xFF
            elif filter_type == 4:
                estimate = left + up - upper_left
                da, db, dc = abs(estimate - left), abs(estimate - up), abs(estimate - upper_left)
                nearest = left if (da <= db and da <= dc) else (up if db <= dc else upper_left)
                line[i] = (line[i] + nearest) & 0xFF
            else:
                raise ValueError('{}: unknown filter {}'.format(path, filter_type))
        rows.append(_to_rgba(line, width, depth, colour, palette, transparency, path))
        previous = line
    return width, height, rows



def _samples(line, width, depth):
    """One unfiltered row as a list of samples, unpacking sub-byte depths."""
    if depth == 8:
        return list(line[:width])
    per_byte = 8 // depth
    mask = (1 << depth) - 1
    out = []
    for x in range(width):
        byte = line[x // per_byte]
        shift = 8 - depth * (x % per_byte + 1)
        out.append((byte >> shift) & mask)
    return out


def _to_rgba(line, width, depth, colour, palette, transparency, path):
    """One unfiltered row, whatever its layout, as RGBA tuples."""
    if colour == 6:
        return [tuple(line[x * 4:x * 4 + 4]) for x in range(width)]
    if colour == 2:
        return [tuple(line[x * 3:x * 3 + 3]) + (255,) for x in range(width)]
    if colour == 4:
        return [(line[x * 2], line[x * 2], line[x * 2], line[x * 2 + 1]) for x in range(width)]
    if colour == 0:
        return [(value, value, value, 255) for value in _samples(line, width, depth)]

    if not palette:
        raise ValueError('{}: palette image with no PLTE'.format(path))
    out = []
    for index in _samples(line, width, depth):
        # tRNS is shorter than the palette whenever the entries past it are opaque,
        # which is the usual case: one transparent entry at the front and no more.
        alpha = transparency[index] if index < len(transparency) else 255
        out.append(palette[index] + (alpha,))
    return out


def read_png_pixels(path):
    """Just the pixel rows; the size is a len() away."""
    return read_png(path)[2]

ASSETS = 'src/main/resources/assets'

# The camera sits up, to the right and in front, which is the three faces an
# inventory icon shows: the top, the east side, and the north face a Station is
# authored facing. A unit vector with all three components equal is what makes the
# three axes foreshorten equally -- a true isometric rather than a perspective.
CAMERA = (1.0, 1.0, -1.0)

# A recess only reads from the angles that can see into it, so judging one needs more
# than the inventory view: "front" is roughly a player standing at the block, "three
# quarter" is walking past it, and "high" is looking down from a block above.
ANGLES = {
    'iso': (1.0, 1.0, -1.0),
    'front': (0.0, 0.25, -1.0),
    'quarter': (0.45, 0.45, -1.0),
    'high': (0.8, 1.6, -1.0),
    'side': (1.0, 0.45, -0.55),
    # The Extruder is authored facing south, the way Create authors its Deployer, so the views that
    # matter for judging its business end look at it from +Z rather than -Z.
    'nozzle': (0.0, 0.25, 1.0),
    'nozzle-quarter': (0.55, 0.5, 1.0),
    'nozzle-iso': (1.0, 1.0, 1.0),
    # Straight at a drive face, which is the only way to judge whether it reads as a shaft socket.
    'drive': (1.0, 0.2, 0.0),
    # And the back, which has to be plain enough that nobody mistakes it for the business end.
    'back-quarter': (-0.55, 0.5, -1.0),
    # Straight at the back plate, the only way to judge the shaft socket against Create's own.
    'back': (0.0, 0.2, -1.0),
    # Anything that STANDS OUT of the front has to be judged from the side. Looked at down its own
    # axis an orthographic projection collapses a barrel into a square, which is how three rounds of
    # this block got signed off with a silhouette nobody had actually seen.
    'profile': (1.0, 0.18, 0.06),
    'hero': (1.0, 0.55, 0.72),
    # Straight down into the open mud tank, which is the only way to judge the gauge.
    'top': (0.02, 1.0, 0.18),
}

# Minecraft's own directional shading, out of ModelBlockRenderer. Flat faces of one
# colour are indistinguishable without it, which is most of why an untextured shape
# is so hard to judge.
SHADE = {'up': 1.0, 'down': 0.5, 'north': 0.8, 'south': 0.8, 'west': 0.6, 'east': 0.6}

# Anti-aliasing. The image is drawn at this multiple and boxed down at the end;
# without it every edge in an isometric view is a staircase and the shape reads worse
# on screen than it does in game.
OVERSAMPLE = 2


def normalise(vector):
    length = math.sqrt(sum(component * component for component in vector))
    return tuple(component / length for component in vector)


def cross(a, b):
    return (a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0])


def dot(a, b):
    return sum(x * y for x, y in zip(a, b))


class View:
    """The screen basis: right, up and towards-the-camera, from one camera direction."""

    def __init__(self, size, camera=CAMERA, pad=0):
        self.towards = normalise(camera)
        self.right = normalise(cross((0.0, 1.0, 0.0), self.towards))
        self.up = normalise(cross(self.towards, self.right))
        # A block is 16 units across and its longest diagonal is 16*sqrt(3). The scale stays tied to
        # `size` and not to the padded canvas, so a caller that sized two models against each other
        # still gets them at the same scale.
        self.scale = size / (16.0 * math.sqrt(3.0) * 1.08)
        # ...and `pad` grows the canvas around that, for a model that reaches outside its own cell.
        # Plenty do: this mod's barrel runs to z=31 and Create's Mechanical Press stands above y=16.
        # Without it they are not scaled down, they are simply cut off at the cell boundary, which
        # is what cropped the front off the Extruder and the top off the Press.
        self.size = size + 2 * pad
        self.centre = self.size / 2.0

    def project(self, point):
        """A model-space point (0..16 per axis, or outside it) to (x, y, depth) in pixels."""
        offset = (point[0] - 8.0, point[1] - 8.0, point[2] - 8.0)
        return (self.centre + dot(offset, self.right) * self.scale,
                self.centre - dot(offset, self.up) * self.scale,
                dot(offset, self.towards))

    @staticmethod
    def fitting(size, camera, model):
        """A view whose canvas is big enough for every corner the model actually has."""
        probe = View(size, camera)
        overflow = 0.0
        for corner in corners_of(model):
            x, y, _ = probe.project(corner)
            overflow = max(overflow, -x, -y, x - size, y - size)
        if overflow <= 0:
            return probe
        # Rounded up to a whole oversampled pixel, so the canvas still divides exactly when it is
        # downsampled and the render does not lose its last row.
        pad = int(math.ceil((overflow + 1) / OVERSAMPLE)) * OVERSAMPLE
        return View(size, camera, pad=pad)


# The corner of a face at (s, t), and the texture rectangle it maps to, restated
# from BlockElement.uvsByFace. Getting these wrong is invisible in the file and
# obvious on the block: the north face's u runs *backwards* along x, and every
# vertical face's v runs downwards from the top of the sheet, which is why "the
# counter's band is rows 4 and 5" is a fact about the element's y and not a choice.
def turn(point, spin):
    """One model-space point through an element's rotation."""
    origin_x, origin_y, origin_z = spin.get('origin', (8.0, 8.0, 8.0))
    axis = spin.get('axis', 'y')
    angle = math.radians(spin.get('angle', 0.0))
    cosine, sine = math.cos(angle), math.sin(angle)
    stretch = (1.0 / math.cos(angle)) if (spin.get('rescale') and angle) else 1.0

    dx, dy, dz = point[0] - origin_x, point[1] - origin_y, point[2] - origin_z
    if axis == 'x':
        dy, dz = (dy * cosine - dz * sine) * stretch, (dy * sine + dz * cosine) * stretch
    elif axis == 'z':
        dx, dy = (dx * cosine - dy * sine) * stretch, (dx * sine + dy * cosine) * stretch
    else:
        dx, dz = (dx * cosine + dz * sine) * stretch, (-dx * sine + dz * cosine) * stretch
    return origin_x + dx, origin_y + dy, origin_z + dz


def corners_of(model):
    """Every corner of every element, rotation included. What a canvas has to hold."""
    for box in model.get('elements', []):
        (x1, y1, z1), (x2, y2, z2) = box['from'], box['to']
        spin = box.get('rotation')
        for x in (x1, x2):
            for y in (y1, y2):
                for z in (z1, z2):
                    yield turn((x, y, z), spin) if spin else (x, y, z)


def spun(point, spin):
    """
    Wraps a face's point function in Minecraft's per-element rotation.

    Up to forty-five degrees about one axis through a named origin, which models lean on far more
    than they look like they do: Create's Mechanical Pump has forty-nine rotated elements and its
    Mechanical Press fifteen. A renderer that ignores this does not fail, it quietly draws every one
    of them square, which is how a press comes out looking like a furnace.

    The face keeps the shade of the direction it was *declared* in rather than of the direction it
    now points, because that is what the game does too.
    """
    def turned(s, t):
        return turn(point(s, t), spin)

    return turned


def face_geometry(box, name):
    (x1, y1, z1), (x2, y2, z2) = box['from'], box['to']
    spin = box.get('rotation')
    if name == 'north':
        point, uv = lambda s, t: (x2 + s * (x1 - x2), y2 + t * (y1 - y2), z1), (16 - x2, 16 - y2, 16 - x1, 16 - y1)
        return (spun(point, spin) if spin else point), uv
    if name == 'south':
        point, uv = lambda s, t: (x1 + s * (x2 - x1), y2 + t * (y1 - y2), z2), (x1, 16 - y2, x2, 16 - y1)
        return (spun(point, spin) if spin else point), uv
    if name == 'west':
        point, uv = lambda s, t: (x1, y2 + t * (y1 - y2), z1 + s * (z2 - z1)), (z1, 16 - y2, z2, 16 - y1)
        return (spun(point, spin) if spin else point), uv
    if name == 'east':
        point, uv = lambda s, t: (x2, y2 + t * (y1 - y2), z2 + s * (z1 - z2)), (16 - z2, 16 - y2, 16 - z1, 16 - y1)
        return (spun(point, spin) if spin else point), uv
    if name == 'up':
        point, uv = lambda s, t: (x1 + s * (x2 - x1), y2, z1 + t * (z2 - z1)), (x1, z1, x2, z2)
        return (spun(point, spin) if spin else point), uv
    if name == 'down':
        point, uv = lambda s, t: (x1 + s * (x2 - x1), y1, z2 + t * (z1 - z2)), (x1, 16 - z2, x2, 16 - z1)
        return (spun(point, spin) if spin else point), uv
    raise ValueError(name)


class Canvas:
    def __init__(self, size):
        self.size = size
        self.pixels = [[(0, 0, 0, 0)] * size for _ in range(size)]
        self.depth = [[-1e9] * size for _ in range(size)]

    def plot(self, x, y, z, colour):
        column, row = int(x), int(y)
        if 0 <= column < self.size and 0 <= row < self.size and z > self.depth[row][column]:
            self.depth[row][column] = z
            self.pixels[row][column] = colour

    def downsample(self, factor):
        out = self.size // factor
        rows = []
        for row in range(out):
            line = []
            for column in range(out):
                red = green = blue = alpha = 0
                for dy in range(factor):
                    for dx in range(factor):
                        r, g, b, a = self.pixels[row * factor + dy][column * factor + dx]
                        red += r * a
                        green += g * a
                        blue += b * a
                        alpha += a
                if alpha:
                    line.append((red // alpha, green // alpha, blue // alpha,
                                 alpha // (factor * factor)))
                else:
                    line.append((0, 0, 0, 0))
            rows.append(line)
        return rows


def draw_face(canvas, view, corner, uv, sheet, shade):
    """Splat a textured quad, sampling densely enough to leave no gaps."""
    width, height = len(sheet[0]), len(sheet)
    u1, v1, u2, v2 = uv

    # How many samples the face needs: its longest projected edge, with a margin. Too
    # few leaves holes in the fill, and the cost of too many is only time.
    corners = [view.project(corner(s, t)) for s, t in ((0, 0), (1, 0), (0, 1), (1, 1))]
    span = max(abs(a[0] - b[0]) + abs(a[1] - b[1]) for a in corners for b in corners)
    steps = max(2, int(span * 1.5))

    for i in range(steps + 1):
        s = i / steps
        u = u1 + s * (u2 - u1)
        column = min(width - 1, max(0, int(u * width / 16.0)))
        for j in range(steps + 1):
            t = j / steps
            v = v1 + t * (v2 - v1)
            row = min(height - 1, max(0, int(v * height / 16.0)))
            red, green, blue, alpha = sheet[row][column]
            if alpha < 128:
                continue
            x, y, depth = view.project(corner(s, t))
            canvas.plot(x, y, depth,
                        (int(red * shade), int(green * shade), int(blue * shade), 255))


def resolve(reference, textures, extra):
    """A model texture reference to a file on disk."""
    while reference.startswith('#'):
        reference = textures[reference[1:]]
    namespace, _, path = reference.partition(':')
    if not path:
        namespace, path = 'minecraft', namespace
    name = path.split('/')[-1]
    if extra:
        candidate = os.path.join(extra, name + '.png')
        if os.path.exists(candidate):
            return candidate
    return os.path.join(ASSETS, namespace, 'textures', path + '.png')


def render(model_path, size, textures_dir, camera=CAMERA, lit=0, dim=0, marks=None, sheets=None):
    with open(model_path) as handle:
        model = json.load(handle)
    return render_model(model, size, textures_dir, camera, lit, dim, marks, sheets)


_BORROWED = None


def borrowed(reference):
    """A texture this mod references but does not ship, read out of the jar that owns it.

    Both blocks wear Create's andesite casing. The sprite stays in Create's jar -- it is
    referenced at runtime and never copied in here, which is the whole of why this is use
    rather than redistribution -- so there is no file on disk to read and the jar has to be
    opened instead. generate_page_art already knows how to find it; the import is late
    because that module imports this one.
    """
    global _BORROWED
    from generate_page_art import Sprites

    namespace, _, path = reference.partition(':')
    kind, _, name = path.partition('/')
    if not name:
        raise FileNotFoundError(reference)
    if _BORROWED is None:
        _BORROWED = Sprites()
    pixels = _BORROWED._read(namespace, kind, name)
    if pixels is None:
        raise FileNotFoundError(reference)
    return pixels


def render_model(model, size, textures_dir=None, camera=CAMERA, lit=0, dim=0,
                 marks=None, sheets=None):
    """The same, over a model already in hand rather than a file on disk.

    `sheets` maps a resolved texture reference to pixels already decoded, which is how
    generate_page_art draws a vanilla block whose texture lives inside the Minecraft
    jar: there is no file to point `textures_dir` at, and unpacking somebody else's
    art into this repo to get one is the thing worth avoiding.

    A key may also be a `(reference, face)` pair, which wins over the bare reference for
    that one face. Both of this mod's blocks wear one casing sheet on all six faces, so a
    readout drawn into it -- the Canteen's gauge -- would otherwise appear on the top and
    the bottom as well as on the flanks it belongs to.
    """
    internal = size * OVERSAMPLE
    view = View.fitting(internal, camera, model)
    canvas = Canvas(view.size)

    supplied = sheets or {}
    loaded = {}

    def sheet_for(reference, face_name):
        while reference.startswith('#'):
            reference = model.get('textures', {})[reference[1:]]
        if (reference, face_name) in supplied:
            return supplied[(reference, face_name)]
        if reference in supplied:
            return supplied[reference]
        path = resolve(reference, {}, textures_dir)
        if path not in loaded:
            loaded[path] = read_png_pixels(path) if os.path.exists(path) else borrowed(reference)
        return loaded[path]

    for box in model['elements']:
        for name, face in box['faces'].items():
            corner, uv = face_geometry(box, name)
            # An explicit uv wins, exactly as it does in the game. The item models lean on this:
            # a lamp plate is 2.6 units across and samples one third of a sheet three cells wide,
            # which no rectangle derived from the element's own coordinates could ever be.
            if 'uv' in face:
                uv = tuple(face['uv'])
            draw_face(canvas, view, corner, uv, sheet_for(face['texture'], name), SHADE[name])

    # A caller may supply the marks instead of the model carrying them, which is how
    # generate_page_art lights a Station's lamps without preview data going into a
    # shipped asset -- and lets it take the positions from the script that draws the
    # sheet rather than restating them a third time.
    marks = marks or model.get('_preview_marks')
    if marks:
        draw_marks(canvas, view, marks, lit, dim)

    return canvas.downsample(OVERSAMPLE)


def blob(colour, shape, bezel=None):
    """A small indicator sprite -- a lamp, a tag, a pip -- built from one colour.

    Generated rather than drawn on a sheet because the whole point of an indicator is
    that there are several of it in several states, and a sheet per state is a sheet
    nobody will keep in step.

    `bezel` is the fitting the lamp sits in, and giving it one here rather than
    painting sockets on the block's own texture is what frees the positions from the
    sixteen-pixel grid: a socket drawn on a sheet has to sit on a whole texel, so four
    of them across a panel cannot be evenly spaced, and the attempt put the middle pair
    a whole pixel closer than the outer two. It is also one source of truth rather than
    two that have to be kept in step -- the same split that once drew every hat inside
    the board.

    A real bezel does not change colour when the bulb behind it lights, so it is the
    same in every state, which is also what makes an unlit lamp read as a fitting.
    """
    red, green, blue = colour
    base = bezel if bezel else (red // 3, green // 3, blue // 3)

    # A ring of one flat colour is flat by definition, whatever is inside it. A socket
    # has depth because it is lit unevenly: with the light coming from the top left --
    # which is where Minecraft's does -- the *far* inner wall catches it and the near
    # wall is shadowed, so the ring is dark at the top left and lighter at the bottom
    # right. That is the same inversion that makes a sunk panel look sunk, and leaving
    # it off is what made these read as dots painted on rather than bulbs sat in.
    near = tuple(max(0, channel - 12) for channel in base) + (255,)
    far = tuple(min(255, channel + 46) for channel in base) + (255,)
    face = (red, green, blue, 255)

    rows = []
    for y in range(16):
        line = []
        for x in range(16):
            dx, dy = x - 7.5, y - 7.5
            distance = (dx * dx + dy * dy) ** 0.5
            inside = distance <= 7.0 if shape == 'round' else (1 <= x <= 14 and 1 <= y <= 14)
            edge = distance > 5.2 if shape == 'round' else (x <= 3 or x >= 12 or y <= 3 or y >= 12)
            if not inside:
                line.append((0, 0, 0, 0))
            elif edge:
                line.append(near if (dx + dy) < 0 else far)
            else:
                # And the bulb is convex, so its catch-light sits up and to the left.
                # A highlight in the middle of a disc reads as a button, not a lamp.
                lit = ((dx + 1.6) ** 2 + (dy + 1.6) ** 2) ** 0.5 < 2.0
                line.append((min(255, red + 70), min(255, green + 70),
                             min(255, blue + 70), 255) if lit else face)
        rows.append(line)
    return rows


def draw_marks(canvas, view, marks, lit, dim):
    """Light the first `lit` positions, mark the next `dim`, leave the rest empty.

    Three states per position is what the block actually knows: nothing programmed
    here, a job with nobody on it, and a job being done. A readout with two states
    cannot tell the second from the third, which is the one a player wants when a
    line has stopped.
    """
    shape = marks.get('shape', 'round')

    # A sheet of states side by side, which is what the block actually ships: previewing
    # the real art rather than a reconstruction of it is the difference between checking
    # the block and checking this file's opinion of the block.
    if 'sheet' in marks:
        strip = read_png_pixels(marks['sheet'])
        width = len(strip[0]) // marks['cells']
        cut = [[row[cell * width:(cell + 1) * width] for row in strip]
               for cell in range(marks['cells'])]
        sprites = {'off': cut[0], 'dim': cut[1], 'lit': cut[2]}
    else:
        colours = marks['colours']
        bezel = marks.get('bezel')
        sprites = {state: blob(colours[state], shape, bezel) for state in colours}
    for index, (x, y) in enumerate(marks['spots']):
        state = 'lit' if index < lit else ('dim' if index < lit + dim else 'off')
        flat(canvas, view, sprites[state], x, y, marks['plane'], marks['size'])


def flat(canvas, view, sheet, x, y, plane, size):
    """One flat item, centred at (x, y) and standing a whisker off the plane."""
    half = size / 2.0
    box = {'from': [x - half, y - half, plane - 0.3], 'to': [x + half, y + half, plane]}
    corner, uv = face_geometry(box, 'north')
    # An item is lit by the air in front of it, not shaded as a block face, so it is
    # drawn at full brightness -- which is also what makes it read as a thing hung on
    # the block rather than as part of it.
    draw_face(canvas, view, corner, (0, 0, 16, 16), sheet, 1.0)


def write_png(path, rows):
    height, width = len(rows), len(rows[0])
    raw = b''
    for row in rows:
        raw += b'\x00'
        for r, g, b, a in row:
            raw += struct.pack('BBBB', r, g, b, a)

    def chunk(kind, payload):
        return (struct.pack('>I', len(payload)) + kind + payload
                + struct.pack('>I', zlib.crc32(kind + payload) & 0xFFFFFFFF))

    with open(path, 'wb') as handle:
        handle.write(b'\x89PNG\r\n\x1a\n'
                     + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0))
                     + chunk(b'IDAT', zlib.compress(raw, 9))
                     + chunk(b'IEND', b''))


def main():
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('model')
    parser.add_argument('-o', '--out', default=None)
    parser.add_argument('--size', type=int, default=320)
    parser.add_argument('--textures', default=None, help='a directory searched first')
    parser.add_argument('--angle', default='iso', choices=sorted(ANGLES))
    parser.add_argument('--lit', type=int, default=0)
    parser.add_argument('--dim', type=int, default=0)
    arguments = parser.parse_args()

    out = arguments.out or os.path.splitext(os.path.basename(arguments.model))[0] + '.png'
    write_png(out, render(arguments.model, arguments.size, arguments.textures,
                          ANGLES[arguments.angle], arguments.lit, arguments.dim))
    print('wrote %s' % out)


if __name__ == '__main__':
    main()
