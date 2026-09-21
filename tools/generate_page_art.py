#!/usr/bin/env python3
"""
Draws the images the CurseForge project page is built out of.

A project page is the only part of this mod most people ever see, and the alternative to
generating its art is a screenshot, which is a thing nobody can regenerate: change the Extruder's
texture and the picture showing it is wrong, silently, for as long as the page lives.

So the art is drawn from the same assets the game loads. Models and sprites are read off disk,
names out of the lang file, and the recipes out of data/createterraform/recipe. Re-run this after
changing any of them and the page catches up.

    python3 tools/generate_page_art.py                          # everything, into branding/
    python3 tools/generate_page_art.py --only banner            # one sheet, while iterating
    python3 tools/generate_page_art.py --out /tmp/art           # somewhere else

What comes out:

    banner.png          1280x640, the hero: the name and the two things it adds.
    recipe-<thing>.png  one per recipe this mod ships, read out of
                        data/createterraform/recipe and drawn from it, so a recipe that
                        is rebalanced redraws rather than going stale. A recipe with a
                        pattern is drawn as a grid and everything else as a row, which
                        sorts Create's mechanical crafting from its mixing without this
                        needing to know either type by name.

The recipe sheets are mostly other people's art -- the presses and casings are Create's, the
gravel and the Nether Star are Mojang's -- and none of it is copied into this repo. Their models
are resolved through their own parent chains and their textures read out of the jars Gradle has
already cached, at the moment the picture is drawn, so what is kept here is a finished image and
not somebody else's texture. That needs a build to have run once; the banner does not.

There are no cards. The sibling this was ported from gives each of its three additions a portrait
with a line of copy under it. This mod adds one machine and one fluid, and three-quarters of a card
sheet would be padding.

"""

import argparse
import glob
import json
import math
import os
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import pixel_font  # noqa: E402
import render_block_model  # noqa: E402
# The badge's palette and the renderer's PNG codec, so the page and the icon are the same blue and
# nothing here reimplements either.
from generate_logo import (FIELD, FIELD_DEEP, FIELD_LIGHT, GRID, GRID_ALPHA,  # noqa: E402
                           SHADOW, WHITE, lerp)
from render_block_model import decode_png, read_png, write_png  # noqa: E402

ASSETS = 'src/main/resources/assets/createterraform'
LANG = ASSETS + '/lang/en_us.json'
MODELS = ASSETS + '/models/block'
ITEM_MODELS = ASSETS + '/models/item'
TEXTURES = ASSETS + '/textures'




def property_of(key, path='gradle.properties'):
    """One value out of gradle.properties, which is where the mod's name already lives."""
    with open(path) as handle:
        for line in handle:
            name, separator, value = line.partition('=')
            if separator and name.strip() == key:
                return value.strip()
    raise KeyError('{} has no {}'.format(path, key))


MOD_NAME = property_of('mod_name')
RECIPES = 'src/main/resources/data/createterraform/recipe'

# What the page is about. One entry per thing the mod adds; the banner column falls out of it.
#
# `name` is a translation key rather than a string, so a block renamed in the lang file is renamed
# on the project page by re-running this.
SUBJECTS = [
    {
        'key': 'terraform-extruder',
        'name': 'block.createterraform.terraform_extruder',
        # The item model rather than the casing. The casing is the box the moving parts live in, and
        # on its own it is a crate with holes in it: the barrel and the spindle are partials the
        # block entity renderer draws, and only the item model inlines them.
        'model': 'src/main/resources/assets/createterraform/models/item/terraform_extruder.json',
    },
    {
        'key': 'mineral-substrate',
        'name': 'fluid.createterraform.mineral_substrate',
        # Drawn as a block of itself. A fluid has no sprite of its own to borrow -- the bucket is
        # composited by NeoForge at runtime from the vanilla sprite and this texture, which is why
        # there is no bucket PNG in this repo to point at.
        'cube': TEXTURES + '/fluid/mineral_substrate_still.png',
    },
]


# A tag has no one icon -- the game cycles through everything in it -- so a recipe
# picture has to pick a stand-in. These are the ones a player would read as "any of
# these", and naming them here keeps the choice in one place rather than in a layout.
TAG_STAND_INS = {
    'minecraft:planks': 'minecraft:oak_planks',
}


# The tagline lives in docs/curseforge-page.md, not here. It was on the banner and made
# it feel packed; a sentence wants reading, which is the page's job, not a hero image's.
#
# Everything a sheet prints is US English -- "program", not "programme" -- because the
# page and the in-game text are read by the same people, and the lang file is already
# US. The comments in this repo are not, and are left alone: a code comment is read by
# whoever is editing the file, and mass-rewriting them would bury real changes.
#
# Read rather than typed, for the same reason the names are: a version on a project
# page that disagrees with the jar is worse than no version on the page at all.
FOOTER = 'Minecraft {}  /  NeoForge  /  requires Create {}'.format(
    property_of('minecraft_version'), property_of('create_version').split('.')[0])

# --- the sheets ------------------------------------------------------------------
BANNER = (1280, 640)
CARD = (960, 540)

# The graph paper's pitch, in output pixels. Fixed rather than proportional to the
# canvas: the sheets are a set, and a grid that changed size between them would make
# two images of one family look like two crops of different drawings.
GRID_SPACING = 64.0
GRID_HALF_WIDTH = 2.5

KEYLINE = 3          # the white stroke round a subject, as on the badge
SHADOW_OFFSET = (7, 9)
SHADOW_SPREAD = 5
SHADOW_STRENGTH = 0.34
TEXT_SHADOW = (0, 3)


# --- the field the art sits on ---------------------------------------------------

def field(width, height):
    """The blue graph paper, as opaque RGBA rows.

    The badge's background, restated for a rectangle: a light source up and to the
    left, the field deepening towards the edges, and faint wide grid lines over it.
    The lines are anti-aliased by coverage rather than by supersampling the whole
    canvas -- they are the only hard edge in the drawing, and a banner is eight
    hundred thousand pixels, each of which would otherwise be sampled nine times.
    """
    glow_x, glow_y = width * 0.26, height * 0.16
    reach = math.hypot(width, height) * 0.70
    centre_x, centre_y = width / 2.0, height / 2.0
    half_diagonal = math.hypot(width, height) / 2.0

    rows = []
    for y in range(height):
        row = []
        for x in range(width):
            glow = min(1.0, math.hypot(x - glow_x, y - glow_y) / reach)
            colour = lerp(FIELD_LIGHT, FIELD, glow)

            edge = (math.hypot(x - centre_x, y - centre_y) / half_diagonal - 0.48) / 0.52
            if edge > 0.0:
                colour = lerp(colour, FIELD_DEEP, min(1.0, edge) ** 1.4 * 0.9)

            for coordinate in (x, y):
                offset = abs(((coordinate + GRID_SPACING / 2.0) % GRID_SPACING)
                             - GRID_SPACING / 2.0)
                cover = min(1.0, max(0.0, GRID_HALF_WIDTH + 0.5 - offset))
                if cover > 0.0:
                    colour = lerp(colour, GRID, GRID_ALPHA * cover)

            row.append((int(colour[0]), int(colour[1]), int(colour[2]), 255))
        rows.append(row)
    return rows


# --- subjects --------------------------------------------------------------------

def sprite(path, scale):
    """An item sprite blown up by a whole number, so its pixels stay square."""
    width, height, pixels = read_png(path)
    return [[pixels[y // scale][x // scale] for x in range(width * scale)]
            for y in range(height * scale)]


def sprite_extent(path):
    """How much of a sprite is actually drawn, in texels."""
    width, height, pixels = read_png(path)
    columns = [x for x in range(width) if any(pixels[y][x][3] for y in range(height))]
    rows = [y for y in range(height) if any(pixel[3] for pixel in pixels[y])]
    if not columns or not rows:
        raise ValueError('{}: sprite is entirely transparent'.format(path))
    return columns[-1] - columns[0] + 1, rows[-1] - rows[0] + 1


def model_extent(path, camera):
    """A model's projected width and height, as fractions of the renderer's `size`.

    Projection is linear in `size`, so measuring once at 1.0 gives the ratio for any
    of them -- which is what lets a subject be asked to fit a box rather than be given
    a pixel size that happened to look right for a cube.
    """
    with open(path) as handle:
        model = json.load(handle)
    view = render_block_model.View(1.0, camera)
    points = []
    for box in model['elements']:
        (x1, y1, z1), (x2, y2, z2) = box['from'], box['to']
        for x in (x1, x2):
            for y in (y1, y2):
                for z in (z1, z2):
                    points.append(view.project((x, y, z)))
    xs = [point[0] for point in points]
    ys = [point[1] for point in points]
    return max(xs) - min(xs), max(ys) - min(ys)


def subject_art(subject, mass, box):
    """One entry's picture, as RGBA rows, drawn at `mass` and clamped to `box`.

    `mass` is the geometric mean of the art's width and height, which is the measure
    two shapes of different proportions have to share if they are to look like they
    belong in one row. Matching their heights instead is the obvious thing and it is
    wrong: a hat sprite is sixteen texels wide and ten tall, a block in this
    projection is taller than it is wide, and at equal height the hat comes out
    looking like twice the object. Matching widths gets the same wrong the other way.

    `box` is a hard limit, so a column can refuse to be overrun whatever is put in it.
    Between the two, adding a thing to SUBJECTS needs no number tuned by hand for it.
    """
    limit_width, limit_height = box
    if 'cube' in subject:
        reference = 'createterraform:block/_subject'
        model = cube_model(reference)
        camera = render_block_model.ANGLES['iso']
        span_width, span_height = extent_of(model, camera)
        size = min(mass / math.sqrt(span_width * span_height),
                   limit_width / span_width, limit_height / span_height)
        return render_block_model.render_model(
            model, int(round(size)), camera=camera,
            sheets={reference: read_png(subject['cube'])[2]})

    if 'sprite' in subject:
        columns, rows = sprite_extent(subject['sprite'])
        scale = min(mass / math.sqrt(columns * rows),
                    limit_width / float(columns), limit_height / float(rows))
        # Whole numbers only: a sprite scaled by 11.9 has pixels that are no longer
        # square, which is the one thing generate_logo.py exists to avoid as well.
        return sprite(subject['sprite'], max(1, int(scale)))

    camera = render_block_model.ANGLES['iso']
    span_width, span_height = model_extent(subject['model'], camera)
    size = min(mass / math.sqrt(span_width * span_height),
               limit_width / span_width, limit_height / span_height)
    sheets = subject.get('sheets')
    return render_block_model.render(
        subject['model'], int(round(size)), None, camera,
        subject.get('lit', 0), subject.get('dim', 0), subject.get('marks'),
        sheets() if callable(sheets) else sheets)


def trim(tile):
    """Crop a tile to what is actually drawn, so `size` means the art and not its margins."""
    rows = [y for y, row in enumerate(tile) if any(pixel[3] for pixel in row)]
    columns = [x for x in range(len(tile[0])) if any(row[x][3] for row in tile)]
    if not rows or not columns:
        raise ValueError('subject is entirely transparent')
    return [row[columns[0]:columns[-1] + 1] for row in tile[rows[0]:rows[-1] + 1]]


# --- compositing -----------------------------------------------------------------

def pad(tile, margin):
    """The tile with `margin` transparent pixels on every side, room for a halo."""
    width = len(tile[0]) + margin * 2
    blank = [(0, 0, 0, 0)] * width
    body = [[(0, 0, 0, 0)] * margin + list(row) + [(0, 0, 0, 0)] * margin for row in tile]
    return [list(blank) for _ in range(margin)] + body + [list(blank) for _ in range(margin)]


def distance_from(mask, reach):
    """Chamfer distance out of a boolean mask, two sweeps -- enough for so short a reach."""
    height, width = len(mask), len(mask[0])
    far = float(reach + 2)
    distance = [[0.0 if mask[y][x] else far for x in range(width)] for y in range(height)]
    straight, diagonal = 1.0, 1.41421356

    for y in range(height):
        for x in range(width):
            if distance[y][x] == 0.0:
                continue
            best = distance[y][x]
            if x:
                best = min(best, distance[y][x - 1] + straight)
            if y:
                best = min(best, distance[y - 1][x] + straight)
                if x:
                    best = min(best, distance[y - 1][x - 1] + diagonal)
                if x < width - 1:
                    best = min(best, distance[y - 1][x + 1] + diagonal)
            distance[y][x] = best

    for y in range(height - 1, -1, -1):
        for x in range(width - 1, -1, -1):
            if distance[y][x] == 0.0:
                continue
            best = distance[y][x]
            if x < width - 1:
                best = min(best, distance[y][x + 1] + straight)
            if y < height - 1:
                best = min(best, distance[y + 1][x] + straight)
                if x < width - 1:
                    best = min(best, distance[y + 1][x + 1] + diagonal)
                if x:
                    best = min(best, distance[y + 1][x - 1] + diagonal)
            distance[y][x] = best

    return distance


def with_keyline(tile, thickness=KEYLINE):
    """The subject with a white stroke round it, the way the badge draws its hat.

    Pixel art on a patterned field loses its edge -- the hat's own outline is dark
    and so is the deep end of the graph paper. The stroke is what makes a subject sit
    in front of the page rather than in it, and it is the badge's, so the two match.
    """
    padded = pad(tile, thickness + 1)
    mask = [[pixel[3] > 8 for pixel in row] for row in padded]
    distance = distance_from(mask, thickness + 1)

    out = []
    for y, row in enumerate(padded):
        line = []
        for x, pixel in enumerate(row):
            if pixel[3] == 255:
                line.append(pixel)
                continue
            cover = min(1.0, max(0.0, thickness + 0.5 - distance[y][x]))
            halo = (int(WHITE[0]), int(WHITE[1]), int(WHITE[2]), int(round(255 * cover)))
            line.append(over(pixel, halo) if pixel[3] else halo)
        out.append(line)
    return out


def over(top, bottom):
    """`top` composited onto `bottom`, both premultiplied by nothing in particular."""
    ta, ba = top[3] / 255.0, bottom[3] / 255.0
    out = ta + ba * (1.0 - ta)
    if out <= 0.0:
        return (0, 0, 0, 0)
    return tuple(int(round((top[i] * ta + bottom[i] * ba * (1.0 - ta)) / out))
                 for i in range(3)) + (int(round(out * 255)),)


def blur(plane, radius):
    """A separable box blur, run twice -- close enough to a gaussian for a shadow."""
    height, width = len(plane), len(plane[0])
    for _ in range(2):
        for y in range(height):
            row, running = plane[y], []
            total = 0.0
            for x in range(width + radius):
                if x < width:
                    total += row[x]
                if x - 2 * radius - 1 >= 0:
                    total -= row[x - 2 * radius - 1]
                if x >= radius:
                    running.append(total / (2 * radius + 1))
            plane[y] = running + [0.0] * (width - len(running))
        for x in range(width):
            column = [plane[y][x] for y in range(height)]
            total, running = 0.0, []
            for y in range(height + radius):
                if y < height:
                    total += column[y]
                if y - 2 * radius - 1 >= 0:
                    total -= column[y - 2 * radius - 1]
                if y >= radius:
                    running.append(total / (2 * radius + 1))
            for y in range(height):
                plane[y][x] = running[y] if y < len(running) else 0.0
    return plane


def paste(canvas, tile, left, top):
    for y, row in enumerate(tile):
        target = top + y
        if not 0 <= target < len(canvas):
            continue
        line = canvas[target]
        for x, pixel in enumerate(row):
            if pixel[3] == 0:
                continue
            column = left + x
            if 0 <= column < len(line):
                line[column] = over(pixel, line[column]) if pixel[3] < 255 else pixel


def cast_shadow(canvas, tile, left, top):
    """The soft shadow a subject throws on the field, before the subject is drawn."""
    margin = SHADOW_SPREAD * 3
    padded = pad(tile, margin)
    plane = [[pixel[3] / 255.0 for pixel in row] for row in padded]
    plane = blur(plane, SHADOW_SPREAD)

    for y, row in enumerate(plane):
        target = top - margin + SHADOW_OFFSET[1] + y
        if not 0 <= target < len(canvas):
            continue
        line = canvas[target]
        for x, value in enumerate(row):
            if value <= 0.004:
                continue
            column = left - margin + SHADOW_OFFSET[0] + x
            if 0 <= column < len(line):
                base = line[column]
                mixed = lerp(base[:3], SHADOW, min(1.0, value) * SHADOW_STRENGTH)
                line[column] = (int(mixed[0]), int(mixed[1]), int(mixed[2]), base[3])


def stamp(canvas, mask, left, top, colour):
    for y, row in enumerate(mask):
        target = top + y
        if not 0 <= target < len(canvas):
            continue
        line = canvas[target]
        for x, on in enumerate(row):
            if on and 0 <= left + x < len(line):
                line[left + x] = colour
    return None


def text(canvas, string, left, top, scale, colour=WHITE, shadow=SHADOW):
    """One line, with a hard drop shadow so it holds up over the grid lines."""
    mask = pixel_font.render(string, scale)
    tint = (int(colour[0]), int(colour[1]), int(colour[2]), 255)
    if shadow:
        dark = (int(shadow[0]), int(shadow[1]), int(shadow[2]), 255)
        stamp(canvas, mask, left + TEXT_SHADOW[0] * scale // 2,
              top + TEXT_SHADOW[1] * scale // 3, dark)
    stamp(canvas, mask, left, top, tint)
    return pixel_font.measure(string, scale)


def wrap(string, scale, limit):
    """Greedy wrap against the font's own measurements."""
    lines, line = [], ''
    for word in string.split():
        candidate = word if not line else line + ' ' + word
        if pixel_font.measure(candidate, scale)[0] > limit and line:
            lines.append(line)
            line = word
        else:
            line = candidate
    if line:
        lines.append(line)
    return lines


def centred(canvas, string, centre, top, scale, colour=WHITE, shadow=SHADOW):
    width = pixel_font.measure(string, scale)[0]
    return text(canvas, string, int(centre - width / 2), top, scale, colour, shadow)


def fit_scale(string, limit, candidates):
    """The largest of `candidates` at which `string` fits in `limit`.

    A heading is set at whatever size the longest name allows, not at a size chosen
    once and hoped for: the whole point of reading names out of the lang file is that
    a rename must not need this script edited, and the rename that breaks a layout is
    the one that makes a name longer.
    """
    for scale in sorted(candidates, reverse=True):
        if pixel_font.measure(string, scale)[0] <= limit:
            return scale
    return min(candidates)


# --- the sheets ------------------------------------------------------------------

def names():
    with open(LANG) as handle:
        return json.load(handle)


# --- other people's sprites ------------------------------------------------------
#
# A recipe picture is mostly somebody else's art: andesite alloy is Create's, dye and
# planks are Mojang's. None of it is copied into this repo. The sprites are read out
# of the jars Gradle has already resolved, at the moment the picture is drawn, and
# only the finished PNG is kept -- which puts these images in exactly the position a
# screenshot of a crafting table is in, rather than in the position of a repo that
# redistributes two other projects' textures.

def find_jar(*candidates):
    """The first of several glob patterns that matches something Gradle has cached."""
    for pattern in candidates:
        found = sorted(glob.glob(os.path.expanduser(pattern)))
        if found:
            return found[0]
    return None


def minecraft_jar():
    version = property_of('minecraft_version')
    return find_jar(
        '~/.gradle/caches/neoformruntime/artifacts/minecraft_{}_client.jar'.format(version),
        '~/.gradle/caches/neoformruntime/artifacts/*client*.jar')


def create_jar():
    version = property_of('create_version')
    minecraft = property_of('minecraft_version')
    return find_jar(
        '~/.gradle/caches/modules-2/files-2.1/com.simibubi.create/create-{}/{}/*/create-{}-{}.jar'
        .format(minecraft, version, minecraft, version),
        '~/.gradle/caches/modules-2/files-2.1/com.simibubi.create/create-*/*/*/create-*.jar')


class Sprites:
    """Reads a texture by its namespaced id, from this repo or from a cached jar."""

    def __init__(self, minecraft=None, create=None):
        self.jars = {}
        self.cache = {}
        for namespace, path in (('minecraft', minecraft or minecraft_jar()),
                                ('create', create or create_jar())):
            if path:
                self.jars[namespace] = zipfile.ZipFile(path)
        self.paths = {name: handle.filename for name, handle in self.jars.items()}

    def _read(self, namespace, kind, name):
        entry = 'assets/{}/textures/{}/{}.png'.format(namespace, kind, name)
        if namespace == 'createterraform':
            local = os.path.join(TEXTURES, kind, name + '.png')
            return read_png(local)[2] if os.path.exists(local) else None
        jar = self.jars.get(namespace)
        if jar is None:
            raise SystemExit(
                "no jar found for '{}'. Run ./gradlew build once so Gradle caches it, "
                "or pass --{}-jar.".format(namespace, namespace))
        try:
            return decode_png(jar.read(entry), entry)[2]
        except KeyError:
            return None

    def _json(self, namespace, path):
        jar = self.jars.get(namespace)
        if jar is None:
            return None
        try:
            return json.loads(jar.read('assets/{}/models/{}.json'.format(namespace, path)))
        except KeyError:
            return None

    #: Where a parent chain stops. These are the game's own built-ins: they have no geometry of
    #: their own, they only say how to treat what the child declared.
    ROOTS = ('item/generated', 'item/handheld', 'builtin/generated', 'builtin/entity')

    def _model_art(self, namespace, name, size):
        """
        One item drawn from its model, following `parent` the way the game does.

        Returns None when there is no model to read, so the caller can fall back to a bare texture.
        """
        data = self._json(namespace, 'item/' + name)
        if data is None:
            return None

        chain, seen, source = [], set(), namespace
        while data is not None:
            chain.append(data)
            parent = data.get('parent')
            if not parent or parent in seen:
                break
            seen.add(parent)
            source, _, path = parent.partition(':')
            if not path:
                source, path = 'minecraft', source
            if path in self.ROOTS:
                break
            data = self._json(source, path)

        textures = {}
        for step in reversed(chain):
            textures.update(step.get('textures') or {})
        elements = next((step['elements'] for step in chain if 'elements' in step), None)
        if elements is None:
            return None

        sheets = {}
        for value in textures.values():
            if not isinstance(value, str) or value.startswith('#'):
                continue
            where, _, path = value.partition(':')
            if not path:
                where, path = 'minecraft', where
            kind, _, leaf = path.partition('/')
            pixels = self._read(where, kind, leaf)
            if pixels is None:
                return None
            sheets[value] = pixels

        model = {'textures': textures, 'elements': elements}
        camera = render_block_model.ANGLES['iso']
        span_width, span_height = extent_of(model, camera)
        return render_block_model.render_model(
            model, int(round(min(size / span_width, size / span_height))),
            camera=camera, sheets=sheets)

    def icon(self, identifier, size):
        """One ingredient, drawn the way the inventory draws it: flat if it is an item,
        as a cube if it is a block. Which of the two it is, is a question only the
        assets can answer -- so it is asked of them, by looking for the item sprite
        first and falling back to the block texture."""
        if identifier in self.cache and self.cache[identifier][0] == size:
            return self.cache[identifier][1]

        namespace, _, name = identifier.partition(':')
        if not name:
            namespace, name = 'minecraft', namespace

        if identifier in FLUID_TEXTURES:
            source, kind, texture = FLUID_TEXTURES[identifier]
            pixels = self._read(source, kind, texture)
            if pixels is None:
                raise SystemExit('no texture for fluid {}'.format(identifier))
            # Still textures are animation strips, frames stacked down the file. The first frame is
            # the one the world shows at rest, and the rest would come out as a very tall block.
            pixels = pixels[:len(pixels[0])]
            reference = '{}:block/{}'.format(source, texture)
            model = cube_model(reference)
            camera = render_block_model.ANGLES['iso']
            span_width, span_height = extent_of(model, camera)
            art = render_block_model.render_model(
                model, int(round(min(size / span_width, size / span_height))),
                camera=camera, sheets={reference: pixels})
            self.cache[identifier] = (size, art)
            return art

        # One of this mod's own blocks is drawn from its model, the way the inventory
        # draws it -- there is no flat sprite to find, because the block wears four
        # different textures. An unprogrammed Station is what comes out of a crafting
        # table, so its lamps are all off, which is what `lit`/`dim` left at zero says.
        # The item model, not the block one. This mod's casing is a box with holes in it and the
        # barrel and spindle are partials the block entity renderer draws, so a render of the block
        # model alone is a crate. The item model inlines them, which is what a recipe result should
        # show anyway: the thing you get, not the thing that is left when it stops.
        local_model = os.path.join(ITEM_MODELS, name + '.json')
        if not os.path.exists(local_model):
            local_model = os.path.join(MODELS, name + '.json')
        if namespace == 'createterraform' and os.path.exists(local_model):
            camera = render_block_model.ANGLES['iso']
            span_width, span_height = model_extent(local_model, camera)
            art = render_block_model.render(
                local_model, int(round(min(size / span_width, size / span_height))), None, camera)
            self.cache[identifier] = (size, art)
            return art

        # Most of Create's blocks have no single texture to fall back on: a Mechanical Press wears
        # four, and the only thing that knows which goes where is its model. So the model is what is
        # read, parent chain and all, exactly as the game resolves it.
        resolved = self._model_art(namespace, name, size)
        if resolved is not None:
            self.cache[identifier] = (size, resolved)
            return resolved

        flat = self._read(namespace, 'item', name)
        if flat is not None:
            columns, rows = opaque_span(flat)
            scale = max(1, int(min(size / float(columns), size / float(rows))))
            art = [[row[x // scale] for x in range(len(row) * scale)]
                   for row in flat for _ in range(scale)]
        else:
            block = self._read(namespace, 'block', name)
            if block is None:
                raise SystemExit('no texture for {} in {} or {}'
                                 .format(identifier, 'item/', 'block/'))
            reference = '{}:block/{}'.format(namespace, name)
            model = cube_model(reference)
            camera = render_block_model.ANGLES['iso']
            span_width, span_height = extent_of(model, camera)
            art = render_block_model.render_model(
                model, int(round(min(size / span_width, size / span_height))),
                camera=camera, sheets={reference: block})

        self.cache[identifier] = (size, art)
        return art


def cube_model(reference):
    """A plain full cube wearing one texture, for a block drawn as an inventory icon."""
    return {
        'textures': {'all': reference},
        'elements': [{
            'from': [0, 0, 0], 'to': [16, 16, 16],
            'faces': {face: {'texture': '#all'} for face in
                      ('down', 'up', 'north', 'south', 'west', 'east')},
        }],
    }


def opaque_span(pixels):
    """The drawn width and height of decoded pixels, in texels."""
    height, width = len(pixels), len(pixels[0])
    columns = [x for x in range(width) if any(pixels[y][x][3] for y in range(height))]
    rows = [y for y in range(height) if any(pixel[3] for pixel in pixels[y])]
    if not columns or not rows:
        raise ValueError('sprite is entirely transparent')
    return columns[-1] - columns[0] + 1, rows[-1] - rows[0] + 1


def extent_of(model, camera):
    """model_extent, for a model already in hand."""
    view = render_block_model.View(1.0, camera)
    points = []
    for box in model['elements']:
        (x1, y1, z1), (x2, y2, z2) = box['from'], box['to']
        for x in (x1, x2):
            for y in (y1, y2):
                for z in (z1, z2):
                    points.append(view.project((x, y, z)))
    xs = [point[0] for point in points]
    ys = [point[1] for point in points]
    return max(xs) - min(xs), max(ys) - min(ys)


def draw_subject(canvas, subject, centre_x, mass, box, bottom=None, middle=None):
    """A subject, keylined and shadowed, either sat on a baseline or centred on one.

    Both are wanted: a row of subjects reads as a row only if they share a floor, and
    a single subject beside a block of text wants its middle on the text's middle.
    """
    art = with_keyline(trim(subject_art(subject, mass, box)))
    left = int(centre_x - len(art[0]) / 2)
    top = int(bottom - len(art)) if bottom is not None else int(middle - len(art) / 2)
    cast_shadow(canvas, art, left, top)
    paste(canvas, art, left, top)
    return left, top, len(art[0]), len(art)


def banner(lang):
    """The hero: the name, the three things, and what it runs on.

    The tagline used to sit between the name and the row and it made the whole thing
    feel packed -- three bands of text competing over one strip. It belongs in the
    first paragraph of the page anyway, where it can be read rather than glanced at,
    so the banner keeps the name and spends what it saves on air and bigger art.
    """
    width, height = BANNER
    canvas = field(width, height)

    label_scale, footer_scale = 4, 2
    centre = width / 2.0

    title_scale = fit_scale(MOD_NAME, int(width * 0.84), (14, 13, 12, 11, 10, 9, 8))
    centred(canvas, MOD_NAME, centre, 72, title_scale)

    # The three things in a row, on one baseline, each with its name under it.
    baseline = height - 132
    column = width / float(len(SUBJECTS))
    box = (column * 0.82, 250)
    for index, subject in enumerate(SUBJECTS):
        centre_x = column * (index + 0.5)
        draw_subject(canvas, subject, centre_x, 225, box, bottom=baseline)
        centred(canvas, lang[subject['name']], centre_x, baseline + 32, label_scale)

    centred(canvas, FOOTER, centre, height - 44, footer_scale, GRID, None)
    return canvas


def rule(canvas, left, top, length, thickness=3, strength=0.5):
    """A hairline under a heading, which is what keeps two sizes of text apart."""
    for y in range(max(0, top), min(len(canvas), top + thickness)):
        for x in range(max(0, left), min(len(canvas[y]), left + length)):
            base = canvas[y][x]
            mixed = lerp(base[:3], WHITE, strength)
            canvas[y][x] = (int(mixed[0]), int(mixed[1]), int(mixed[2]), 255)



RECIPE_WIDTH = 960
SLOT = 104               # a crafting slot, including its border
SLOT_GAP = 10
RESULT_SLOT = 140
ARROW_LENGTH = 86


def slot(canvas, left, top, size):
    """One crafting slot: a square sunk into the page.

    Drawn here rather than lifted off Minecraft's own GUI sheet. Two reasons, and the
    second is the one that decides it: the vanilla widget is grey and would sit on
    blue graph paper looking like a screenshot pasted onto a poster, and it is Mojang's
    art, which this repo does not carry. A slot is a rectangle with two edges lit; it
    is not worth borrowing.
    """
    fill = lerp(FIELD_DEEP, (0.0, 0.0, 0.0), 0.28)
    near = lerp(fill, (0.0, 0.0, 0.0), 0.35)          # the wall the light misses
    far = lerp(fill, WHITE, 0.30)                     # and the one it catches
    for y in range(top, top + size):
        if not 0 <= y < len(canvas):
            continue
        for x in range(left, left + size):
            if not 0 <= x < len(canvas[y]):
                continue
            inset = min(x - left, y - top, left + size - 1 - x, top + size - 1 - y)
            if inset >= 3:
                colour = fill
            elif (x - left) < 3 or (y - top) < 3:
                # Light from the top left, so the top and left inner walls are the
                # ones in shadow and the bottom and right ones catch it. Inverted,
                # the same three pixels read as a tile sitting on the page instead of
                # a hole cut into it.
                colour = near
            else:
                colour = far
            canvas[y][x] = (int(colour[0]), int(colour[1]), int(colour[2]), 255)


def arrow(canvas, left, top, length, colour=WHITE):
    """The crafting arrow, pointing right."""
    shaft = max(6, length // 7)
    head = length // 2
    middle = top
    for x in range(length):
        if x < length - head:
            half = shaft // 2
        else:
            half = int(head * (length - x) / float(head))
        for y in range(middle - half, middle + half + 1):
            if 0 <= y < len(canvas) and 0 <= left + x < len(canvas[y]):
                base = canvas[y][left + x]
                mixed = lerp(base[:3], colour, 0.92)
                canvas[y][left + x] = (int(mixed[0]), int(mixed[1]), int(mixed[2]), 255)


def crafting_recipes():
    """
    Every recipe this mod ships, read off disk so a rebalance redraws rather than going stale.

    Sorted by *shape* rather than by type name. A grid is anything with a pattern and a key, which
    covers vanilla's shaped crafting and Create's mechanical crafting without this needing to know
    that the second one exists; everything else with ingredients and results is drawn as a row.
    Matching on the type string instead would have meant this mod's only two recipes both falling
    through, which is what it did the first time it was run here.
    """
    found = []
    for path in sorted(glob.glob(os.path.join(RECIPES, '*.json'))):
        with open(path) as handle:
            recipe = json.load(handle)
        name = os.path.splitext(os.path.basename(path))[0]
        if 'pattern' in recipe and 'key' in recipe:
            found.append((name, recipe, recipe_sheet))
        elif 'ingredients' in recipe and 'results' in recipe:
            found.append((name, recipe, processing_sheet))
    return found


#: Where a fluid's picture comes from. A fluid has no item sprite: what the inventory shows is a
#: bucket, composited at runtime, and what a recipe means is the liquid itself. So each one names
#: the still texture to draw a block out of instead.
FLUID_TEXTURES = {
    'minecraft:lava': ('minecraft', 'block', 'lava_still'),
    'minecraft:water': ('minecraft', 'block', 'water_still'),
    'createterraform:mineral_substrate': ('createterraform', 'fluid', 'mineral_substrate_still'),
}


def ingredient_id(entry):
    """The item a recipe key stands for, tag or fluid or not."""
    if 'fluid' in entry:
        return entry['fluid']
    if 'id' in entry and 'item' not in entry:
        return entry['id']
    if 'item' in entry:
        return entry['item']
    tag = entry['tag']
    identifier = TAG_STAND_INS.get(tag if ':' in tag else 'minecraft:' + tag)
    if identifier is None:
        raise SystemExit('no stand-in icon for tag {}; add one to TAG_STAND_INS'.format(tag))
    return identifier


def result_name(identifier, lang):
    namespace, _, name = identifier.partition(':')
    for prefix in ('block', 'item'):
        key = '{}.{}.{}'.format(prefix, namespace, name)
        if key in lang:
            return lang[key]
    return name.replace('_', ' ').title()


def recipe_sheet(recipe, lang, sprites):
    pattern = recipe['pattern']
    keys = recipe['key']
    columns = max(len(row) for row in pattern)
    rows = len(pattern)

    grid_width = columns * SLOT + (columns - 1) * SLOT_GAP
    grid_height = rows * SLOT + (rows - 1) * SLOT_GAP
    total = grid_width + 54 + ARROW_LENGTH + 54 + RESULT_SLOT

    # The sheet is as tall as its contents rather than a fixed size: a recipe with
    # three rows and one with two should both come out looking laid out, and padding
    # a short one to a tall canvas is how a picture ends up with a hole under it.
    title = result_name(recipe['result']['id'], lang)
    width = RECIPE_WIDTH
    title_scale = fit_scale(title, int(width * 0.8), (7, 6, 5))
    top = 42 + pixel_font.CELL_HEIGHT * title_scale + 48
    height = top + max(grid_height, RESULT_SLOT) + 62

    canvas = field(width, height)
    centred(canvas, title, width / 2.0, 42, title_scale)
    left = int((width - total) / 2)

    for row_index, row in enumerate(pattern):
        for column_index in range(columns):
            x = left + column_index * (SLOT + SLOT_GAP)
            y = top + row_index * (SLOT + SLOT_GAP)
            slot(canvas, x, y, SLOT)
            symbol = row[column_index] if column_index < len(row) else ' '
            if symbol == ' ':
                continue
            icon = trim(sprites.icon(ingredient_id(keys[symbol]), SLOT - 30))
            paste(canvas, icon,
                  x + (SLOT - len(icon[0])) // 2, y + (SLOT - len(icon)) // 2)

    middle = top + grid_height // 2
    arrow(canvas, left + grid_width + 54, middle, ARROW_LENGTH)

    result_left = left + grid_width + 54 + ARROW_LENGTH + 54
    result_top = middle - RESULT_SLOT // 2
    slot(canvas, result_left, result_top, RESULT_SLOT)
    icon = trim(sprites.icon(recipe['result']['id'], RESULT_SLOT - 34))
    paste(canvas, icon,
          result_left + (RESULT_SLOT - len(icon[0])) // 2,
          result_top + (RESULT_SLOT - len(icon)) // 2)
    # A yield worth knowing: this one makes sixteen, and a picture of the grid that does not say so
    # reads as an expensive recipe for one machine.
    label = amount_label(recipe['result'])
    if label:
        centred(canvas, label, result_left + RESULT_SLOT / 2.0,
                result_top + RESULT_SLOT + 10, 2, GRID, None)

    return canvas


def amount_label(entry):
    """`250mB` under a slot, for the entries that are a quantity of liquid rather than a stack."""
    if 'fluid' in entry or entry.get('id', '') in FLUID_TEXTURES:
        return '{}mB'.format(entry['amount']) if 'amount' in entry else None
    count = entry.get('count')
    return 'x{}'.format(count) if count and count > 1 else None


def processing_sheet(recipe, lang, sprites):
    """
    A recipe with no grid: what goes in, an arrow, what comes out.

    Create's processing recipes are a list rather than a layout, and drawing them on a crafting grid
    would be a lie about where they happen. A row reads as "these, together, make that", which is
    what a Basin under a Mixer actually is, and the heat sits under the title because it is a
    condition on the whole recipe rather than on any one ingredient.
    """
    ingredients = recipe['ingredients']
    results = recipe['results']

    row_width = len(ingredients) * SLOT + (len(ingredients) - 1) * SLOT_GAP
    total = row_width + 54 + ARROW_LENGTH + 54 + RESULT_SLOT

    title = result_name(results[0].get('id', ''), lang)
    heat = recipe.get('heat_requirement')
    width = RECIPE_WIDTH
    title_scale = fit_scale(title, int(width * 0.8), (7, 6, 5))
    top = 42 + pixel_font.CELL_HEIGHT * title_scale + (34 if heat else 0) + 48
    height = top + max(SLOT, RESULT_SLOT) + 62

    canvas = field(width, height)
    centred(canvas, title, width / 2.0, 42, title_scale)
    if heat:
        centred(canvas, heat.replace('_', ' ').title(), width / 2.0,
                42 + pixel_font.CELL_HEIGHT * title_scale + 12, 3, GRID, None)

    left = int((width - total) / 2)
    middle = top + SLOT // 2
    for index, entry in enumerate(ingredients):
        x = left + index * (SLOT + SLOT_GAP)
        slot(canvas, x, top, SLOT)
        icon = trim(sprites.icon(ingredient_id(entry), SLOT - 30))
        paste(canvas, icon, x + (SLOT - len(icon[0])) // 2, top + (SLOT - len(icon)) // 2)
        label = amount_label(entry)
        if label:
            centred(canvas, label, x + SLOT / 2.0, top + SLOT + 10, 2, GRID, None)

    arrow(canvas, left + row_width + 54, middle, ARROW_LENGTH)

    result_left = left + row_width + 54 + ARROW_LENGTH + 54
    result_top = middle - RESULT_SLOT // 2
    slot(canvas, result_left, result_top, RESULT_SLOT)
    icon = trim(sprites.icon(results[0]['id'], RESULT_SLOT - 34))
    paste(canvas, icon, result_left + (RESULT_SLOT - len(icon[0])) // 2,
          result_top + (RESULT_SLOT - len(icon)) // 2)
    label = amount_label(results[0])
    if label:
        centred(canvas, label, result_left + RESULT_SLOT / 2.0,
                result_top + RESULT_SLOT + 10, 2, GRID, None)

    return canvas


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', default='branding', help='where the PNGs are written')
    parser.add_argument('--only', default=None,
                        help="one sheet: 'banner', a subject key, or a recipe name")
    parser.add_argument('--minecraft-jar', default=None,
                        help='overrides the Minecraft jar the recipe sprites come from')
    parser.add_argument('--create-jar', default=None,
                        help="overrides Create's jar")
    arguments = parser.parse_args()

    lang = names()
    # No cards. The sibling this was ported from adds three things and gives each one a portrait
    # with a line of copy under it; this mod adds one machine and one fluid, and three-quarters of
    # a card sheet would be padding. The banner shows both of them and the page says the rest.
    sheets = [('banner', lambda: banner(lang))]

    # Opened lazily: the jars are only needed for recipe sheets, and a checkout that
    # has never run a build should still be able to draw the banner.
    box = []

    def sprites():
        if not box:
            box.append(Sprites(arguments.minecraft_jar, arguments.create_jar))
        return box[0]

    for name, recipe, draw in crafting_recipes():
        sheets.append(('recipe-' + name.replace('_', '-'),
                       lambda recipe=recipe, draw=draw: draw(recipe, lang, sprites())))

    wanted = arguments.only
    chosen = [(name, build) for name, build in sheets
              if not wanted or wanted in (name, name.replace('card-', ''),
                                          name.replace('recipe-', ''))]
    if not chosen:
        # Silently writing nothing is the worst answer to a typo: the images look
        # unchanged because they are, and nothing says why.
        raise SystemExit('no sheet called {!r}; try one of: {}'.format(
            wanted, ', '.join(name for name, _ in sheets)))

    os.makedirs(arguments.out, exist_ok=True)
    for name, build in chosen:
        path = os.path.join(arguments.out, name + '.png')
        rows = build()
        write_png(path, rows)
        print('wrote {} ({}x{})'.format(path, len(rows[0]), len(rows)))


if __name__ == '__main__':
    main()
