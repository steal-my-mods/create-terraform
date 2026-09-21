#!/usr/bin/env python3
"""
A small bitmap font, so a generated image can carry a word.

The page art wants headings, and nothing else in this repo draws text. The three
ways of getting it were a system font through a library (Pillow and a .ttf), a
font file committed here, or a handful of glyphs written out. The first two both
fail the same way: these generators are re-run in CI and the result is diffed
against what is committed, so a glyph that renders a hair differently on a
different machine -- a different freetype, a different hinting default, a font
that is simply absent on Linux -- turns a green gate red for no reason anybody
can act on. A bitmap is bytes. It renders identically everywhere or not at all.

Minecraft's own font is not an option: it is Mojang's, and this mod ships no art
it did not draw (see NOTICE.md, and the badge in generate_logo.py, which exists
in the shape it does for the same reason).

The cell is five wide by nine tall. Capitals occupy the top seven rows, lower
case sits on the same baseline with its x-height in rows 2-6, and the last two
rows are the descender, which is why the cell is nine and not seven. Glyphs are
written as pictures, one row of five per group, because the only useful review
of a letterform is looking at it.

Spacing is proportional, not fixed: `trim` drops the blank columns either side
of each glyph at import, so `l` takes the width it needs and `W` takes the width
it needs. A fixed grid reads as a spreadsheet at heading size.

Nothing here knows about colour. `render` returns a mask, and the caller decides
what to paint through it -- which is what lets one call draw a heading and its
drop shadow from the same shape.
"""

CELL_WIDTH = 5
CELL_HEIGHT = 9

# The blank columns either side of a glyph, and the gap between two of them, in
# unscaled pixels. One pixel of tracking at heading scale is a comfortable gap
# because it scales with everything else.
TRACKING = 1
SPACE_WIDTH = 3

# Glyphs, as nine rows of five. Written with '#' and '.' rather than packed into
# hex so that a wrong pixel is visible in the diff that introduces it.
PICTURES = {
    'A': '.###. #...# #...# ##### #...# #...# #...# ..... .....',
    'B': '####. #...# #...# ####. #...# #...# ####. ..... .....',
    'C': '.###. #...# #.... #.... #.... #...# .###. ..... .....',
    'D': '####. #...# #...# #...# #...# #...# ####. ..... .....',
    'E': '##### #.... #.... ####. #.... #.... ##### ..... .....',
    'F': '##### #.... #.... ####. #.... #.... #.... ..... .....',
    'G': '.###. #...# #.... #.### #...# #...# .###. ..... .....',
    'H': '#...# #...# #...# ##### #...# #...# #...# ..... .....',
    'I': '##### ..#.. ..#.. ..#.. ..#.. ..#.. ##### ..... .....',
    'J': '..### ...#. ...#. ...#. ...#. #..#. .##.. ..... .....',
    'K': '#...# #..#. #.#.. ##... #.#.. #..#. #...# ..... .....',
    'L': '#.... #.... #.... #.... #.... #.... ##### ..... .....',
    'M': '#...# ##.## #.#.# #...# #...# #...# #...# ..... .....',
    'N': '#...# ##..# #.#.# #..## #...# #...# #...# ..... .....',
    'O': '.###. #...# #...# #...# #...# #...# .###. ..... .....',
    'P': '####. #...# #...# ####. #.... #.... #.... ..... .....',
    'Q': '.###. #...# #...# #...# #.#.# #..#. .##.# ..... .....',
    'R': '####. #...# #...# ####. #.#.. #..#. #...# ..... .....',
    'S': '.#### #.... #.... .###. ....# ....# ####. ..... .....',
    'T': '##### ..#.. ..#.. ..#.. ..#.. ..#.. ..#.. ..... .....',
    'U': '#...# #...# #...# #...# #...# #...# .###. ..... .....',
    'V': '#...# #...# #...# #...# #...# .#.#. ..#.. ..... .....',
    'W': '#...# #...# #...# #.#.# #.#.# ##.## #...# ..... .....',
    'X': '#...# #...# .#.#. ..#.. .#.#. #...# #...# ..... .....',
    'Y': '#...# #...# .#.#. ..#.. ..#.. ..#.. ..#.. ..... .....',
    'Z': '##### ....# ...#. ..#.. .#... #.... ##### ..... .....',

    'a': '..... ..... .###. ....# .#### #...# .#### ..... .....',
    'b': '#.... #.... ####. #...# #...# #...# ####. ..... .....',
    'c': '..... ..... .###. #.... #.... #.... .###. ..... .....',
    'd': '....# ....# .#### #...# #...# #...# .#### ..... .....',
    'e': '..... ..... .###. #...# ##### #.... .###. ..... .....',
    'f': '..##. .#... ####. .#... .#... .#... .#... ..... .....',
    'g': '..... ..... .#### #...# #...# #...# .#### ....# .###.',
    'h': '#.... #.... ####. #...# #...# #...# #...# ..... .....',
    'i': '..#.. ..... .##.. ..#.. ..#.. ..#.. .###. ..... .....',
    'j': '...#. ..... ..##. ...#. ...#. ...#. ...#. #..#. .##..',
    'k': '#.... #.... #..#. #.#.. ##... #.#.. #..#. ..... .....',
    'l': '.##.. ..#.. ..#.. ..#.. ..#.. ..#.. .###. ..... .....',
    'm': '..... ..... ##.#. #.#.# #.#.# #.#.# #.#.# ..... .....',
    'n': '..... ..... ####. #...# #...# #...# #...# ..... .....',
    'o': '..... ..... .###. #...# #...# #...# .###. ..... .....',
    'p': '..... ..... ####. #...# #...# #...# ####. #.... #....',
    'q': '..... ..... .#### #...# #...# #...# .#### ....# ....#',
    'r': '..... ..... #.##. ##..# #.... #.... #.... ..... .....',
    's': '..... ..... .#### #.... .###. ....# ####. ..... .....',
    't': '.#... .#... ####. .#... .#... .#..# ..##. ..... .....',
    'u': '..... ..... #...# #...# #...# #..## .##.# ..... .....',
    'v': '..... ..... #...# #...# #...# .#.#. ..#.. ..... .....',
    'w': '..... ..... #...# #...# #.#.# #.#.# .#.#. ..... .....',
    'x': '..... ..... #...# .#.#. ..#.. .#.#. #...# ..... .....',
    'y': '..... ..... #...# #...# #...# #...# .#### ....# .###.',
    'z': '..... ..... ##### ...#. ..#.. .#... ##### ..... .....',

    '0': '.###. #...# #..## #.#.# ##..# #...# .###. ..... .....',
    '1': '..#.. .##.. ..#.. ..#.. ..#.. ..#.. .###. ..... .....',
    '2': '.###. #...# ....# ...#. ..#.. .#... ##### ..... .....',
    '3': '##### ...#. ..#.. ...#. ....# #...# .###. ..... .....',
    '4': '...#. ..##. .#.#. #..#. ##### ...#. ...#. ..... .....',
    '5': '##### #.... ####. ....# ....# #...# .###. ..... .....',
    '6': '..##. .#... #.... ####. #...# #...# .###. ..... .....',
    '7': '##### ....# ...#. ..#.. .#... .#... .#... ..... .....',
    '8': '.###. #...# #...# .###. #...# #...# .###. ..... .....',
    '9': '.###. #...# #...# .#### ....# ...#. .##.. ..... .....',

    '.': '..... ..... ..... ..... ..... ..... ..##. ..##. .....',
    ',': '..... ..... ..... ..... ..... ..##. ..##. ..#.. .#...',
    ':': '..... ..##. ..##. ..... ..##. ..##. ..... ..... .....',
    ';': '..... ..##. ..##. ..... ..##. ..##. ..#.. .#... .....',
    '!': '..#.. ..#.. ..#.. ..#.. ..#.. ..... ..#.. ..... .....',
    '?': '.###. #...# ....# ...#. ..#.. ..... ..#.. ..... .....',
    "'": '..#.. ..#.. ..... ..... ..... ..... ..... ..... .....',
    '"': '.#.#. .#.#. ..... ..... ..... ..... ..... ..... .....',
    '-': '..... ..... ..... .###. ..... ..... ..... ..... .....',
    '+': '..... ..#.. ..#.. ##### ..#.. ..#.. ..... ..... .....',
    '=': '..... ..... ##### ..... ##### ..... ..... ..... .....',
    '/': '....# ....# ...#. ..#.. .#... #.... #.... ..... .....',
    '(': '...#. ..#.. .#... .#... .#... ..#.. ...#. ..... .....',
    ')': '.#... ..#.. ...#. ...#. ...#. ..#.. .#... ..... .....',
    '&': '.##.. #..#. #.#.. .#... #.#.# #..#. .##.# ..... .....',
    '%': '##..# ##..# ...#. ..#.. .#... #..## #..## ..... .....',
    # The haul, which is what half these captions are about.
    '→': '..... ..#.. ...#. ##### ...#. ..#.. ..... ..... .....',
}


def _trim(picture):
    """A glyph's rows, cropped to the columns it actually uses."""
    rows = picture.split()
    if len(rows) != CELL_HEIGHT or any(len(row) != CELL_WIDTH for row in rows):
        raise ValueError('a glyph must be {} rows of {}'.format(CELL_HEIGHT, CELL_WIDTH))
    used = [x for x in range(CELL_WIDTH) if any(row[x] == '#' for row in rows)]
    if not used:
        return []
    first, last = used[0], used[-1] + 1
    return [[cell == '#' for cell in row[first:last]] for row in rows]


GLYPHS = {character: _trim(picture) for character, picture in PICTURES.items()}


def width_of(character):
    glyph = GLYPHS.get(character)
    if character == ' ' or glyph is None:
        return SPACE_WIDTH
    return len(glyph[0])


def measure(text, scale=1):
    """The size `render` will produce, so a caller can lay out before it draws."""
    if not text:
        return 0, 0
    total = sum(width_of(character) for character in text) + TRACKING * (len(text) - 1)
    return total * scale, CELL_HEIGHT * scale


def render(text, scale=1):
    """
    The text as a mask: rows of booleans, one per output pixel.

    A mask rather than an image because the caller wants to paint the same shape
    more than once -- a heading and the shadow under it are one call and two
    colours -- and because the page art composites onto a background it has
    already drawn.
    """
    width, height = measure(text, scale)
    mask = [[False] * width for _ in range(height)]
    pen = 0
    for character in text:
        glyph = GLYPHS.get(character)
        if glyph is None:
            if character != ' ':
                raise KeyError('no glyph for {!r}; add one to PICTURES'.format(character))
            pen += (SPACE_WIDTH + TRACKING) * scale
            continue
        for y, row in enumerate(glyph):
            for x, on in enumerate(row):
                if not on:
                    continue
                for dy in range(scale):
                    line = mask[y * scale + dy]
                    for dx in range(scale):
                        line[pen + x * scale + dx] = True
        pen += (len(glyph[0]) + TRACKING) * scale
    return mask
