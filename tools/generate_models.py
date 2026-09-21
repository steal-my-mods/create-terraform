#!/usr/bin/env python3
"""
Builds the Terraform Extruder's blockstate and models.

Written rather than hand-authored for one reason: **interior faces**. A machine assembled from a
dozen boxes has a great many faces buried inside other boxes, and two coplanar faces drawn at the
same depth z-fight -- the shimmering checkerboard that shows up on a block only once it is in the
world. Finding them by eye across thirteen boxes and six faces each is not realistic. Here every
face is tested against every other box and dropped if it is completely covered, so the fault cannot
be authored in the first place.

The machine is a **rotary core drill run backwards**. A core rig turns a chuck, circulates mud down
the string and brings a cylinder of rock up; this one pushes one out. That is what the code has
always thought it was -- StrataSlice, `sample`, `sampleY`, a "core sample queue" -- and it is the
only shape that explains all three of the machine's inputs at once: mud in the tank, rotation into
the chuck, rock out of the barrel.

It keeps Create's Deployer conventions, because it still behaves like one:

  * Rotation enters from the SIDE, not the back -- DeployerBlock extends DirectionalAxisKineticBlock,
    so the shaft axis is perpendicular to the facing. That is what frees the back of the machine for
    the ram to slide out of, and it is why there are twelve blockstate variants and two models.
  * The ram travels through the block and past both ends of it. Create's deployer pole is modelled
    from z=-9 to 12 for the same reason.
  * Authored facing SOUTH with the shaft along X, which is the orientation Create authors its
    deployer in, so the variant table below is its table.

    python3 tools/generate_models.py
"""

import json
import math
import os
import sys

NS = 'createterraform'
OUT = 'src/main/resources/assets/' + NS

TEX = {k: f'{NS}:block/terraform_extruder_{k}' for k in
       ('casing', 'rail', 'collar', 'drive', 'die', 'ram', 'shaft',
        'barrel', 'chuck', 'shaft_end', 'substrate')}

#: How far the mud reaches along the facing axis. The gauge partial and the renderer agree with this,
#: and it is held a fraction clear of the tank walls at both ends: mud that lands exactly on a wall
#: shares a plane with it and shimmers. That fault is across two models, so only a check of the
#: assembled machine finds it -- see tools/preview_machine.py.
GAUGE = (3.3, 11.7)

AXES = {'east': (0, 16), 'west': (0, 0), 'up': (1, 16), 'down': (1, 0), 'south': (2, 16), 'north': (2, 0)}
OPPOSITE = {'east': 'west', 'west': 'east', 'up': 'down', 'down': 'up', 'south': 'north', 'north': 'south'}
SPAN = {'east': (1, 2), 'west': (1, 2), 'up': (0, 2), 'down': (0, 2), 'south': (0, 1), 'north': (0, 1)}


def sweep(lo, hi):
    """
    The radius a box of this width sweeps when it is spun about its own centre.

    A square turning about its centre is only as small as its *corners*, which stand a factor of
    root two further out than its flats. A rotating part therefore has to be measured on its corners
    against every static hole it passes through, and it is easy to forget: the flats can sit well
    inside a hole while the corners punch out through the casing and back in four times a turn.
    """
    return (hi - lo) / 2 * math.sqrt(2)


def clears(name, lo, hi, hole_lo, hole_hi, through):
    """Assert a spinning part fits the hole it turns in, and say so plainly when it does not."""
    radius, half = sweep(lo, hi), (hole_hi - hole_lo) / 2
    if radius > half:
        raise AssertionError(
            '{} spans {}..{} and sweeps {:.2f} from centre, but {} is {}..{}, half-width {:.2f}. '
            'Its corners would punch {:.2f}px through the casing and back, four times a turn.'
            .format(name, lo, hi, radius, through, hole_lo, hole_hi, half, radius - half))
    return True


def frame(a0, a1, b0, b1, depth_axis, at, thickness, hole, texture, note=None, chamfer=0):
    """
    Four boxes forming a rectangular wall with a rectangular hole through it.

    The machine is a box with holes in it -- a slot in the lid to see the substrate through, a bore
    in each side for the shaft -- and a hole is the one thing a block model cannot express directly.
    Every wall that has something to look through goes through here.

    ``a`` and ``b`` are the two axes the wall lies in, in ascending order; ``hole`` is
    ``(a_lo, b_lo, a_hi, b_hi)``.

    ``chamfer`` fills the hole's four corners back in, turning a square hole into an **octagonal**
    one. Create's shafts are octagonal -- `axis_top` darkens exactly the four corner pixels of its
    four-by-four end -- so a shaft socket has to be too, and painting the octagon into the texture
    does not work: the corners of the ring are precisely the part a square hole cuts away.
    """
    a, b = [i for i in range(3) if i != depth_axis]
    out = []
    for name, (alo, ahi, blo, bhi) in {
        'top':    (a0, a1, hole[3], b1),
        'bottom': (a0, a1, b0, hole[1]),
        'left':   (a0, hole[0], hole[1], hole[3]),
        'right':  (hole[2], a1, hole[1], hole[3]),
    }.items():
        if alo >= ahi or blo >= bhi:
            continue
        f, t = [0, 0, 0], [0, 0, 0]
        f[a], t[a] = alo, ahi
        f[b], t[b] = blo, bhi
        f[depth_axis], t[depth_axis] = at, at + thickness
        out.append({'from': f, 'to': t, '__tex': texture, '__note': note if name == 'top' else None})

    for corner_a, corner_b in ((hole[0], hole[1]), (hole[2] - chamfer, hole[1]),
                               (hole[0], hole[3] - chamfer), (hole[2] - chamfer, hole[3] - chamfer)):
        if not chamfer:
            break
        f, t = [0, 0, 0], [0, 0, 0]
        f[a], t[a] = corner_a, corner_a + chamfer
        f[b], t[b] = corner_b, corner_b + chamfer
        f[depth_axis], t[depth_axis] = at, at + thickness
        out.append({'from': f, 'to': t, '__tex': texture, '__note': None})
    return out


def machine():
    """
    The rig, authored facing south — which is now also the axis the shaft runs on.

    Three stacked pieces, and the stack is the whole silhouette:

      * a **plinth** the full width of the cell, which everything else is inset from;
      * a **housing** between it and the deck, with the shaft socket bored through the back plate and
        the chuck bore through the front wall. This half is dry;
      * an **open mud tank** sitting on the deck. This half is wet.

    The deck between them is the point. Rotation and fluid meet at a mechanism and never in a volume
    — Create's Mixer puts the shaft above and the basin below, the Press the same, the Steam Engine
    keeps fluid below and takes the shaft out the side — and an earlier version of this machine ran
    its drive straight through the substrate, which is incoherent.

    The tank has no lid. An earlier version cut a slot in one and the four frame boxes each sampled a
    different crop of the same banded texture, which read as clutter from every angle. A basin you
    can see into is both cleaner and more Create: it is what the Basin and the Item Drain do.
    """
    boxes = []
    boxes.append({'from': [0, 0, 0], 'to': [16, 2, 16], '__tex': 'rail',
                  '__note': 'The plinth, full width, so everything above it reads as inset.'})

    boxes += frame(1, 15, 2, 16, 2, 0, 2, (5, 5, 11, 11), 'drive',
                   'The back plate, bored for the shaft. Six pixels for a four-pixel stub, so there '
                   'is a pixel of reveal all round and the stub reads as standing in a socket rather '
                   'than patched over a hole -- and the bore is chamfered into an octagon a pixel '
                   'outside the octagonal end of the stub, which is what makes it read as an outline '
                   'around the shaft rather than a square box behind it.',
                   chamfer=1)

    boxes.append({'from': [2, 2, 2], 'to': [14, 3, 10], '__tex': 'casing',
                  '__note': 'The housing: floor and two side walls, dry, with the string running '
                            'through it from the back plate to the chuck.'})
    boxes.append({'from': [2, 3, 2], 'to': [4, 12, 10], '__tex': 'casing'})
    boxes.append({'from': [12, 3, 2], 'to': [14, 12, 10], '__tex': 'casing'})

    boxes += frame(2, 14, 2, 13, 2, 10, 2, (4, 4, 12, 12), 'collar',
                   'The front wall, bored for the chuck. Every clearance around the chuck and the '
                   'barrel is a fraction of a pixel rather than a whole one: both pass through this '
                   'hole, and two faces landing on the same plane z-fight. check_models.py enforces '
                   'it.')

    boxes += frame(3, 13, 3, 13, 2, 12, 3, (4.4, 4.4, 11.6, 11.6), 'chuck',
                   'The chuck, standing proud of the front wall. It does NOT turn, and that is a '
                   'decision rather than an oversight: a ring this size turning about its own centre '
                   'sweeps its corners nearly seven pixels out, through a wall whose hole is four, '
                   'so it punched out through the casing and back four times a turn. Nothing else '
                   'on this block is big enough to spin, and the barrel running through it already '
                   'carries the rotation. Its top stops exactly at the deck, because anything above '
                   'that shares a plane with the mud tank.')

    boxes.append({'from': [2, 12, 2], 'to': [14, 13, 10], '__tex': 'rail',
                  '__note': 'The deck. Wet above it, dry below.'})
    boxes += frame(1, 15, 2, 14, 1, 13, 3, (4, 3, 12, 12), 'rail',
                   'The mud tank, open at the top.')
    return boxes


def covered(face, box, others):
    """Is this face completely buried in another box?"""
    axis, _ = AXES[face]
    at = box['to'][axis] if face in ('east', 'up', 'south') else box['from'][axis]
    u, v = SPAN[face]
    for other in others:
        if other is box:
            continue
        edge = other['from'][axis] if face in ('east', 'up', 'south') else other['to'][axis]
        if abs(edge - at) > 1e-6:
            continue
        if other['from'][u] <= box['from'][u] and other['to'][u] >= box['to'][u] \
           and other['from'][v] <= box['from'][v] and other['to'][v] >= box['to'][v]:
            return True
    return False


def to_model(boxes, textures, comment):
    elements = []
    for box in boxes:
        faces = {}
        for face in AXES:
            if covered(face, box, boxes):
                continue
            u, v = SPAN[face]
            along = box.get('__rod')
            if along is not None:
                # A rod is the one shape whose faces cannot take their UVs from their own extents.
                # Derived that way, the two faces whose width runs along the LENGTH of the bar sample
                # the texture sideways -- for a twenty-pixel barrel that walks clean off the end of
                # the profile and shows the flat background, so two sides of the tube come out with
                # no colour on them at all. Create sets all four sides of create:block/shaft.json to
                # the same [6,0,10,16] for exactly this reason. Cross-section across, length down.
                cross = [a for a in range(3) if a != along][0]
                lo, hi = box['from'][cross], box['to'][cross]
                uv = [lo, lo, hi, hi] if AXES[face][0] == along else [lo, 0, hi, 16]
            else:
                uv = [box['from'][u], 16 - box['to'][v], box['to'][u], 16 - box['from'][v]] \
                    if face in ('north', 'south', 'east', 'west') else \
                    [box['from'][u], box['from'][v], box['to'][u], box['to'][v]]
            # Clamped, because a box that reaches outside the cell -- and the barrel reaches a long
            # way outside it -- derives UVs outside the sprite. In an atlas that samples whatever
            # texture happens to be next door, which is a fault that cannot be seen in a render and
            # cannot be predicted from the model, since it depends on atlas packing.
            end = box.get('__tex_end')
            texture = end if (end and AXES[face][0] == box.get('__rod')) else box['__tex']
            entry = {'texture': '#' + texture,
                     'uv': [round(min(16.0, max(0.0, c)), 2) for c in uv]}
            _, at = AXES[face]
            edge = box['to' if face in ('east', 'up', 'south') else 'from'][AXES[face][0]]
            if abs(edge - at) < 1e-6:
                entry['cullface'] = face
            faces[face] = entry
        element = {'from': box['from'], 'to': box['to'], 'faces': faces}
        if box.get('__note'):
            element = {'__comment': box['__note'], **element}
        elements.append(element)
    # Trimmed to what the boxes actually use. Declaring a texture no face draws is harmless to the
    # game and poisonous to housekeeping: it keeps a PNG that nothing uses looking used, so the file
    # stays in the repo and in CI's diff. Callers hand over the whole table and this decides.
    used = {box['__tex'] for box in boxes} | {b['__tex_end'] for b in boxes if b.get('__tex_end')}
    textures = {k: v for k, v in textures.items() if k in used}
    particle = textures.get('casing') or next(iter(textures.values()))
    return {'__comment': comment, 'parent': 'minecraft:block/block',
            'textures': dict({'particle': particle}, **textures), 'elements': elements}


VARIANTS = {
    'facing=south': {},
    'facing=north': {'y': 180},
    'facing=east':  {'y': 270},
    'facing=west':  {'y': 90},
    'facing=up':    {'x': 270},
    'facing=down':  {'x': 90},
}


def write_raw(full, data):
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, 'w') as handle:
        handle.write(json.dumps(data, indent=2) + '\n')
    print('wrote', full)


def write(path, data):
    full = os.path.join(OUT, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, 'w') as handle:
        handle.write(json.dumps(data, indent=2) + '\n')
    print('wrote', full)


def main():
    # Every spinning part, against every static hole it turns inside. Run before anything is written,
    # because a part whose corners clip the casing is not a thing you can see in a still render --
    # it only shows up in motion, as something appearing and disappearing inside the machine.
    clears('the barrel', 5.5, 10.5, 4, 12, 'the front wall bore')
    clears('the barrel', 5.5, 10.5, 4.4, 11.6, 'the chuck collar')
    clears('the string', 5.5, 10.5, 4, 12, 'the housing interior')
    clears('the shaft stub', 6, 10, 5, 11, 'the back plate bore')

    casing = machine()
    used = dict(TEX)
    write('models/block/terraform_extruder/casing.json', to_model(
        casing, used,
        'The casing. Generated by tools/generate_models.py -- do not hand-edit; '
        'faces buried inside other boxes are dropped there, which is what keeps this from z-fighting.'))
    write_raw('src/main/resources/assets/%s/blockstates/terraform_extruder.json' % NS, {
        '__comment': 'Six variants and one model, because the drive is coaxial with the barrel: '
                     'facing says which way the machine points and that is also the axis the shaft '
                     'runs on. It was twelve and two while rotation came in the side.',
        'variants': {k: dict({'model': f'{NS}:block/terraform_extruder/casing'}, **r)
                     for k, r in sorted(VARIANTS.items())},
    })

    # The core barrel: the string inside the housing, the barrel itself, two drill collars and the
    # cutting head. It both turns about the facing and advances along it, which is why it is a
    # partial and not part of the casing.
    barrel = [
        {'from': [5.5, 2, 5.5], 'to': [10.5, 9, 10.5], '__tex': 'ram', '__rod': 1,
         '__note': 'The string, running from the back plate to the chuck. Six pixels square rather '
                   'than four, so it fills the bore behind the stub: at four you look straight down '
                   'the socket past it and see the brass of the barrel, which reads as brass sitting '
                   'behind the shaft. It butts both its neighbours rather than overlapping either: '
                   'the barrel ahead of it, because same-width boxes that overlap put four coplanar '
                   'faces in one model; and the shaft stub behind it, because the stub does not '
                   'advance and this does, so any overlap has the stub sinking into it and rising '
                   'back out once a turn -- something clipping in and out inside the socket.'},
        {'from': [5.5, 9, 5.5], 'to': [10.5, 29, 10.5], '__tex': 'barrel', '__rod': 1,
         '__note': 'The barrel, running out through the chuck and most of the way across the empty '
                   'block in front -- a Deployer\'s pole is modelled past its own boundary for the '
                   'same reason. At rest the cutting head stops one pixel short of the block being '
                   'printed into, and the helical travel is what closes that pixel, so the machine '
                   'visibly bites what it is working on once a turn.'},
        {'from': [4.8, 16, 4.8], 'to': [11.2, 17, 11.2], '__tex': 'ram', '__rod': 1,
         '__note': 'Drill collars in steel against the brass, which is what tells a tube from a peg. Both sit beyond the chuck '
                   'even at rest and the barrel only ever travels further out, so neither can be '
                   'driven back through a hole it does not fit -- which is the one thing that would '
                   'give the whole arrangement away.'},
        {'from': [4.8, 24, 4.8], 'to': [11.2, 25, 11.2], '__tex': 'ram', '__rod': 1},
        {'from': [5.8, 29, 5.8], 'to': [10.2, 31, 10.2], '__tex': 'die', '__rod': 1,
         '__note': 'The cutting head.'},
    ]
    model = to_model(barrel, dict(TEX),
                     'The core barrel, turned and advanced by TerraformExtruderRenderer. Authored '
                     'pointing UP -- the renderer swings it onto the facing with Create\'s own '
                     'transform, which expects up -- and both spun about and translated along its '
                     'own axis.')
    for e in model['elements']:
        for f in e['faces'].values():
            f.pop('cullface', None)
    write('models/block/terraform_extruder/barrel.json', model)

    # The one thing that turns without advancing. It used to carry the chuck as well, until the
    # arithmetic above showed a ring that size cannot turn inside this block without coming out
    # through the side of it.
    spindle = [
        {'from': [6, 0, 6], 'to': [10, 2, 10], '__tex': 'shaft', '__rod': 1, '__tex_end': 'shaft_end',
         '__note': 'The shaft stub standing in the back bore, flush with the plate so a real shaft '
                   'butted against it meets it end to end. Four pixels square, from 6 to 10, because '
                   'that is what create:block/shaft.json is -- a six-pixel stub would not line up '
                   'with the shaft a player puts against it.'},
    ]
    model = to_model(spindle, dict(TEX),
                     'The shaft stub, spun about the facing by TerraformExtruderRenderer. Authored '
                     'pointing up, like the barrel. It turns and does not advance; the barrel does '
                     'both, and slides through the chuck the casing holds.')
    for e in model['elements']:
        for f in e['faces'].values():
            f.pop('cullface', None)
    write('models/block/terraform_extruder/shaft.json', model)

    # The mud in the tank, seen straight down into it because the tank has no lid. Authored in the
    # barrel's frame -- local Y is the facing -- and squashed along it by the fill level, so it
    # drains back away from the chuck.
    gauge = [{'from': [4.3, GAUGE[0], 13], 'to': [11.7, GAUGE[1], 15.5], '__tex': 'substrate',
              '__note': 'Shy of the tank walls on every side, so nothing shares a plane with them.'}]
    model = to_model(gauge, dict(TEX),
                     'The mud in the tank, drawn by TerraformExtruderRenderer and squashed to the '
                     'tank\'s fill level.')
    for e in model['elements']:
        for f in e['faces'].values():
            f.pop('cullface', None)
    write('models/block/terraform_extruder/gauge.json', model)

    # The item has to show the machine complete, because the block model leaves the moving parts to
    # the renderer. Create does the same in deployer/item.json. Built from the same sources here so
    # it cannot drift out of step by hand.
    # Both partials stand on the facing now, and both are authored pointing up, so both turn the
    # same way here. While rotation came in the side there were two mappings and getting them the
    # same way round buried the gears in the front wall, where the item showed a machine that could
    # not work; with a coaxial drive there is only one axis to get wrong.
    ONTO_FACING = (lambda lo, hi: ([lo[0], 8 + (lo[2] - 8), 8 + (lo[1] - 8)],
                                   [hi[0], 8 + (hi[2] - 8), 8 + (hi[1] - 8)]),
                   {'up': 'south', 'down': 'north', 'north': 'down', 'south': 'up',
                    'east': 'east', 'west': 'west'})

    extra = []
    for parts in (barrel, spindle):
        move, remap = ONTO_FACING
        for box in parts:
            rotated_lo, rotated_hi = move(box['from'], box['to'])
            one = to_model([box], dict(TEX), '')['elements'][0]
            extra.append({'from': rotated_lo, 'to': rotated_hi,
                          'faces': {remap[n]: {'texture': f['texture'], 'uv': f['uv']}
                                    for n, f in one['faces'].items()}})

    shell = to_model(casing, used, '')
    item = {'__comment': 'The item shows the machine complete -- casing, spindle and barrel at rest. '
                         'Generated by tools/generate_models.py from the same sources as the block '
                         'model and the partials, so it cannot drift out of step.',
            'parent': 'minecraft:block/block',
            # Turned to face the viewer. minecraft:block/block hands down a GUI rotation of
            # [30, 225, 0], which shows a block's NORTH and EAST faces -- and this casing is
            # authored facing south, so the inventory icon was the back of the machine: a plain
            # plate with a shaft socket, and no barrel. 315 brings the south face round instead.
            'display': {'gui': {'rotation': [30, 315, 0], 'translation': [0, 0, 0],
                                'scale': [0.625, 0.625, 0.625]}},
            'textures': dict(shell['textures'],
                             **{name: TEX[name] for p in (barrel, spindle) for b in p
                                for name in (b['__tex'], b.get('__tex_end')) if name}),
            'elements': [{k: v for k, v in e.items() if k != '__comment'} for e in shell['elements']] + extra}
    write('models/item/terraform_extruder.json', item)
    return 0


if __name__ == '__main__':
    sys.exit(main())
