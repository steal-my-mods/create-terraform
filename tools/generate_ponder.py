#!/usr/bin/env python3
"""
Builds the structures the Ponder scenes play inside, and the lang keys their text is read from.

Create authors these in-game with a schematic tool and checks the .nbt in. There is no such tool
here, so each layout is described in code and written straight out. That has one real advantage
worth keeping: the rig a player is shown and the rig the GameTests assert against are the same
arrangement, written down twice in the same repo rather than drawn once and hoped about.

Conventions copied from Create's own ponder files (assets/create/ponder/*.nbt): y=0 is a
checkerboard base plate of white concrete and snow, the build sits at y>=1, and the whole thing is
one block larger than the base plate the scene declares.

    python3 tools/generate_ponder.py

Every block placed here has to be a real blockstate, and nothing checks that until a player opens
the scene -- which is what `thePonderStructuresAreValid` in the GameTests is for. Run those after
changing a palette.
"""

import json
import os
import re
import struct
import sys
import zlib

DATA_VERSION = 3955  # 1.21.1

TAG_INT = 3
TAG_STRING = 8
TAG_LIST = 9
TAG_COMPOUND = 10
TAG_INT_ARRAY = 11


# --- a very small NBT writer ------------------------------------------------------------------


def _str(value):
    raw = value.encode('utf8')
    return struct.pack('>H', len(raw)) + raw


def _compound(pairs):
    out = b''
    for name, (tag, payload) in pairs:
        out += bytes([tag]) + _str(name) + payload
    return out + b'\x00'


def _list(tag, items):
    return bytes([tag]) + struct.pack('>i', len(items)) + b''.join(items)


def _int_list(values):
    return _list(TAG_INT, [struct.pack('>i', v) for v in values])


def _int_array(values):
    return struct.pack('>i', len(values)) + b''.join(struct.pack('>i', v) for v in values)


def gzip_bytes(payload):
    """
    A gzip container assembled by hand, because `gzip.compress` is not reproducible across Python
    versions -- the same reason and the same code as in generate_structures.py, where the full
    explanation lives. CI diffs these files, so a container that varies by interpreter is a red
    build on a machine that changed nothing.
    """
    body = zlib.compressobj(9, zlib.DEFLATED, -zlib.MAX_WBITS)
    deflated = body.compress(payload) + body.flush()
    header = b'\x1f\x8b\x08\x00' + b'\x00\x00\x00\x00' + b'\x02\xff'
    trailer = struct.pack('<II', zlib.crc32(payload) & 0xFFFFFFFF, len(payload) & 0xFFFFFFFF)
    return header + deflated + trailer


def write_structure(path, size, palette, blocks):
    """
    palette: list of (name, {property: value} or None).
    blocks: list of (state_index, (x, y, z)) or (state_index, (x, y, z), block_entity_pairs).
    """
    palette_entries = []
    for name, properties in palette:
        pairs = [('Name', (TAG_STRING, _str(name)))]
        if properties:
            pairs.append(('Properties', (TAG_COMPOUND, _compound(
                [(k, (TAG_STRING, _str(v))) for k, v in sorted(properties.items())]))))
        palette_entries.append(_compound(pairs))

    block_entries = []
    for entry in blocks:
        state, pos = entry[0], entry[1]
        pairs = [
            ('state', (TAG_INT, struct.pack('>i', state))),
            ('pos', (TAG_LIST, _int_list(list(pos)))),
        ]
        if len(entry) > 2 and entry[2]:
            pairs.append(('nbt', (TAG_COMPOUND, _compound(entry[2]))))
        block_entries.append(_compound(pairs))

    root = _compound([
        ('DataVersion', (TAG_INT, struct.pack('>i', DATA_VERSION))),
        ('size', (TAG_LIST, _int_list(list(size)))),
        ('palette', (TAG_LIST, _list(TAG_COMPOUND, palette_entries))),
        ('blocks', (TAG_LIST, _list(TAG_COMPOUND, block_entries))),
        ('entities', (TAG_LIST, _list(0, []))),
    ])
    payload = bytes([TAG_COMPOUND]) + _str('') + root

    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'wb') as handle:
        handle.write(gzip_bytes(payload))


def base_plate(blocks, size, white, snow):
    """The checkerboard every Create ponder scene stands on."""
    for x in range(size):
        for z in range(size):
            blocks.append((white if (x + z) % 2 == 0 else snow, (x, 0, z)))


# --- the scenes --------------------------------------------------------------------------------

# Both plates are declared here and again in ExtruderScenes.configureBasePlate. They have to agree:
# the plate the scene draws is not the structure, it is a frame drawn over it.
STATIONARY_PLATE = 7
CONTRAPTION_PLATE = 5


def stationary_scene():
    """
    A driven Extruder printing into open air, with a driven Mechanical Drill taking what it makes.

    Laid out along +X at z=3, so the camera looks along the machine's own axis and sees the barrel
    reach. Every kinetic block has a real source: the Extruder's motor is behind it on a shaft, and
    the Drill has one of its own butted against its back face, because a Drill takes rotation on any
    face except the one it cuts with. Nothing in here is driven by a speed written on by hand.

    The two-block gap between the Extruder and what it prints is not spare room -- it is the
    machine's reach. REACH is 2, the same as a Deployer's, and the barrel is what visibly crosses it.
    """
    palette = [
        ('minecraft:white_concrete', None),
        ('minecraft:snow_block', None),
        ('create:creative_motor', {'facing': 'east'}),
        ('create:shaft', {'axis': 'x', 'waterlogged': 'false'}),
        ('createterraform:terraform_extruder', {'facing': 'east'}),
        ('create:mechanical_drill', {'facing': 'west', 'waterlogged': 'false'}),
        ('create:creative_motor', {'facing': 'west'}),
    ]
    blocks = []
    base_plate(blocks, STATIONARY_PLATE, 0, 1)

    blocks.append((2, (0, 1, 3)))
    blocks.append((3, (1, 1, 3)))
    blocks.append((4, (2, 1, 3)))
    # (3,1,3) and (4,1,3) are deliberately air: the gap the barrel reaches across, and the space
    # printed into.
    blocks.append((5, (5, 1, 3)))
    blocks.append((6, (6, 1, 3)))

    return (STATIONARY_PLATE + 1, 4, STATIONARY_PLATE + 1), palette, blocks


def contraption_scene():
    """
    A Rope Pulley with one Extruder hanging off it, facing a bank of dirt it paints as it descends.

    The rig is in column x=1 and what it prints is in column x=3, two east of it, which is the same
    reach the stationary scene shows. The bank is solid on purpose: a moving Extruder displaces rock
    rather than filling air, and a scene that printed into empty space would be demonstrating the
    stationary rule while claiming the moving one.

    Two blocks deep rather than one so the bank still reads as ground once its face has been
    repainted, and three wide so the repainted stripe has something to be a stripe against.
    """
    palette = [
        ('minecraft:white_concrete', None),
        ('minecraft:snow_block', None),
        ('create:rope_pulley', {'axis': 'x'}),
        ('createterraform:terraform_extruder', {'facing': 'east'}),
        ('minecraft:dirt', None),
    ]
    blocks = []
    base_plate(blocks, CONTRAPTION_PLATE, 0, 1)

    blocks.append((2, (1, 5, 2)))
    blocks.append((3, (1, 4, 2)))

    for x in (3, 4):
        for y in range(1, 5):
            for z in (1, 2, 3):
                blocks.append((4, (x, y, z)))

    return (CONTRAPTION_PLATE + 1, 7, CONTRAPTION_PLATE + 1), palette, blocks


SCENES = {
    'terraform_extruder': stationary_scene,
    'extruder_contraption': contraption_scene,
}

# The Java the scene text is read back out of, so the lang file cannot drift from the scenes.
SCENE_SOURCE = 'src/main/java/com/createterraform/client/ponder/ExtruderScenes.java'
SCENE_ORDER = ['terraform_extruder', 'extruder_contraption']

LANG = 'src/main/resources/assets/createterraform/lang/en_us.json'
NAMESPACE = 'createterraform'


def scene_text(source):
    """
    Every scene's header and its lines of text, in the order Ponder will number them.

    Split on the `scene.title(...)` calls rather than on method boundaries: a storyboard is
    identified by the id it titles itself with, and that is the same string Ponder keys its lang on.
    """
    titles = list(re.finditer(r'scene\.title\("([^"]+)",\s*"([^"]+)"\)', source))
    if not titles:
        raise SystemExit('%s has no scene.title(...) call' % SCENE_SOURCE)

    found = {}
    for index, title in enumerate(titles):
        end = titles[index + 1].start() if index + 1 < len(titles) else len(source)
        body = source[title.end():end]
        texts = re.findall(r'\n\t+\.text\("((?:[^"\\]|\\.)*)"\)', body)
        # A .text( the pattern above failed to match would silently lose a line at runtime and
        # nowhere else, so count them a second way and insist the two agree.
        expected = len(re.findall(r'\.text\(', body))
        if len(texts) != expected:
            raise SystemExit('%s: matched %d of %d .text( calls in %r -- the pattern needs updating'
                             % (SCENE_SOURCE, len(texts), expected, title.group(1)))
        found[title.group(1)] = (title.group(2), texts)

    if sorted(found) != sorted(SCENE_ORDER):
        raise SystemExit('%s titles %s, expected %s'
                         % (SCENE_SOURCE, sorted(found), sorted(SCENE_ORDER)))
    return found


def sync_scene_lang():
    """
    Writes the ponder lang keys from the strings in the scene source.

    Ponder resolves every line of scene text through I18n against a key it derives itself --
    `<namespace>.ponder.<sceneId>.header` and `.text_N`, numbered from one in call order. The English
    passed to `.text(...)` is only the datagen default; if the key is missing the player is shown the
    key. Create generates these in datagen. There is no datagen here, so they are generated from the
    one place they already exist: the scene.

    The lang file is hand-grouped with blank lines, and json.dump would flatten it, so the blank
    lines are read off the file first and put back afterwards. A generator that tidies a file nobody
    asked it to tidy buries the change that was actually made.
    """
    with open(LANG) as handle:
        raw = handle.read()
    lang = json.loads(raw)

    spaced = set()
    previous_blank = False
    for line in raw.splitlines():
        key = re.match(r'\s*"([^"]+)":', line)
        if key and previous_blank:
            spaced.add(key.group(1))
        previous_blank = not line.strip()

    scenes = scene_text(open(SCENE_SOURCE).read())
    for key in [k for k in lang if k.startswith(NAMESPACE + '.ponder.')]:
        del lang[key]

    written = 0
    for scene_id in SCENE_ORDER:
        header, texts = scenes[scene_id]
        prefix = '%s.ponder.%s.' % (NAMESPACE, scene_id)
        spaced.add(prefix + 'header')
        lang[prefix + 'header'] = header
        for index, text in enumerate(texts, start=1):
            lang[prefix + 'text_%d' % index] = text
        written += len(texts) + 1
        print('synced %d lang entries for %s' % (len(texts) + 1, scene_id))

    lines = ['{']
    keys = list(lang)
    for index, key in enumerate(keys):
        comma = '' if index == len(keys) - 1 else ','
        if key in spaced and index:
            lines.append('')
        lines.append('  %s: %s%s' % (json.dumps(key), json.dumps(lang[key], ensure_ascii=False), comma))
    lines.append('}')
    with open(LANG, 'w') as handle:
        handle.write('\n'.join(lines) + '\n')
    return written


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else 'src/main/resources/assets/createterraform/ponder'
    for name, build in SCENES.items():
        size, palette, blocks = build()
        path = os.path.join(root, name + '.nbt')
        write_structure(path, size, palette, blocks)
        print('wrote %s (%d blocks)' % (path, len(blocks)))
    sync_scene_lang()


if __name__ == '__main__':
    main()
