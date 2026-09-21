#!/usr/bin/env python3
"""
Checks this mod's block models for the faults that load a perfectly clean client and only go
wrong on screen.

Five of them, all silent:

  * A texture reference that resolves to nothing. The game draws the missing-texture check and
    logs at debug level, if at all.
  * A `#name` that no `textures` block defines. Same outcome.
  * Two faces drawn on the same plane, pointing the same way, overlapping each other. This is
    z-fighting: the shimmering checkerboard that appears only once the block is in the world and
    only from some angles, because which of the two wins is decided by floating-point noise. The
    generator drops faces that are completely *buried* in another box, which is a different fault;
    two boxes that merely butt up against each other leave both faces drawn and both visible.
  * A texture declared in a model's `textures` block that no face ever draws. Harmless to the game
    and poisonous to housekeeping: it keeps a PNG that nothing uses looking used, so the file stays
    in the repo and in CI's diff for as long as anyone greps for its name rather than for a face
    that references it.
  * A hole in the boundary of a model that claims to be a solid block. Such a model has to cover
    all six faces of the 16x16x16 cell between its boxes, or a face cullfaced against a neighbour
    leaves a gap you can see through once that neighbour is placed -- and only then, which is why
    it survives every screenshot taken while building the thing.

That last check is opt-in, because most machines are not solid blocks: the Terraform Extruder is
a twelve-pixel casing with its mouth open, exactly as Create's Deployer is, and its block sets
noOcclusion accordingly. A model that IS meant to be solid declares "__solid": true and gets
checked; one face of it may still be left open if something opaque backs it up, declared with
"__backed": ["north"].

Every model under models/block is checked, subdirectories included -- the Extruder's casing lives
in models/block/terraform_extruder/, and for a while this only globbed the top level and so checked
almost nothing.

    python3 tools/check_models.py

Exits non-zero on a fault, so it can gate a build.
"""

import json
import pathlib
import sys

ROOT = pathlib.Path('src/main/resources/assets')
NAMESPACE = 'createterraform'

# from/to index, boundary value, and the two axes the face spans
BOUNDARIES = {
    'west':  (0, 0, (1, 2)),
    'east':  (0, 16, (1, 2)),
    'down':  (1, 0, (0, 2)),
    'up':    (1, 16, (0, 2)),
    'north': (2, 0, (0, 1)),
    'south': (2, 16, (0, 1)),
}


def texture_faults(path, model):
    faults = []
    declared = model.get('textures') or {}
    for value in declared.values():
        if not isinstance(value, str) or value.startswith('#'):
            continue
        namespace, _, rest = value.partition(':')
        if not rest:
            namespace, rest = 'minecraft', value
        if namespace != NAMESPACE:
            continue
        if not (ROOT / namespace / 'textures' / (rest + '.png')).exists():
            faults.append(f'{path.name}: texture {value} does not exist')

    for element in model.get('elements', []):
        for name, face in element.get('faces', {}).items():
            reference = face.get('texture', '')
            if reference.startswith('#') and reference[1:] not in declared:
                faults.append(f'{path.name}: {name} face uses {reference}, which is not declared')
    return faults


#: How close two planes have to be before they are the same plane. Model coordinates are authored
#: in sixteenths and the renderer works in floats; anything under a tenth of a pixel is not a gap a
#: player could see through, it is two faces fighting.
SAME_PLANE = 0.05

#: Which axis each face lies on, which end of the box it sits at, and the two axes it spans.
PLANES = {
    'west':  (0, 'from', (1, 2)),
    'east':  (0, 'to', (1, 2)),
    'down':  (1, 'from', (0, 2)),
    'up':    (1, 'to', (0, 2)),
    'north': (2, 'from', (0, 1)),
    'south': (2, 'to', (0, 1)),
}


def zfight_faults(path, model):
    """
    Faces sharing a plane, a direction and some area.

    Direction is part of it. Two faces back to back on the same plane -- the underside of a lid and
    the top of the body it sits on -- point opposite ways, and Minecraft culls backfaces, so each is
    visible only from the side the other is not. They never fight. It is two faces pointing the
    *same* way that do, and those are what this reports.
    """
    drawn = []
    for index, element in enumerate(model.get('elements', [])):
        for name, face in element.get('faces', {}).items():
            if name not in PLANES:
                continue
            axis, end, span = PLANES[name]
            drawn.append((name, axis, element[end][axis],
                          [(element['from'][a], element['to'][a]) for a in span], index))

    faults = []
    for i, (name, axis, at, rect, element) in enumerate(drawn):
        for other_name, other_axis, other_at, other_rect, other_element in drawn[i + 1:]:
            if name != other_name or abs(at - other_at) > SAME_PLANE:
                continue
            overlap = [min(a[1], b[1]) - max(a[0], b[0]) for a, b in zip(rect, other_rect)]
            if overlap[0] > SAME_PLANE and overlap[1] > SAME_PLANE:
                faults.append(
                    f'{path.name}: elements {element} and {other_element} both draw a {name} face '
                    f'at {at:g} overlapping {overlap[0]:g}x{overlap[1]:g} -- z-fighting')
    return faults


def unused_texture_faults(path, model):
    """Names in `textures` that no face asks for."""
    declared = {k for k in (model.get('textures') or {}) if k != 'particle'}
    used = {face['texture'].lstrip('#')
            for element in model.get('elements', [])
            for face in element.get('faces', {}).values()
            if isinstance(face.get('texture'), str)}
    # A name may stand in for another (`"0": "#side"`), so anything a used name resolves through
    # counts as used too.
    for value in (model.get('textures') or {}).values():
        if isinstance(value, str) and value.startswith('#'):
            used.add(value.lstrip('#'))
    return [f'{path.name}: texture "{name}" is declared but no face draws it'
            for name in sorted(declared - used)]


def coverage_faults(path, model):
    """Every boundary of the block must be paved by the boxes that touch it."""
    elements = model.get('elements', [])
    if not elements or not model.get('__solid'):
        return []
    backed = set(model.get('__backed', []))
    faults = []

    for face, (axis, at, (u_axis, v_axis)) in BOUNDARIES.items():
        if face in backed:
            continue
        covered = set()
        for element in elements:
            low, high = element['from'], element['to']
            if low[axis] != at and high[axis] != at:
                continue
            if face not in element.get('faces', {}):
                continue
            for u in range(int(low[u_axis]), int(high[u_axis])):
                for v in range(int(low[v_axis]), int(high[v_axis])):
                    covered.add((u, v))
        missing = 256 - len(covered)
        if missing:
            faults.append(f'{path.name}: the {face} boundary has {missing} of 256 pixels uncovered; '
                          f'a neighbouring block will show a hole there')
    return faults


def main():
    faults = []
    checked = 0
    roots = [pathlib.Path(a) for a in sys.argv[1:]] or [ROOT / NAMESPACE / 'models' / 'block']
    for root in roots:
        for path in sorted(root.rglob('*.json')):
            try:
                model = json.loads(path.read_text())
            except ValueError:
                continue
            # A directory named on the command line may hold JSON that is not a model at all --
            # point this at /tmp and it will find plenty -- so anything not shaped like one is
            # skipped rather than crashed on. Checked once here rather than in each check.
            if not isinstance(model, dict) or not isinstance(model.get('textures', {}), dict) \
                    or not isinstance(model.get('elements', []), list):
                continue
            checked += 1
            faults += texture_faults(path, model)
            faults += zfight_faults(path, model)
            faults += unused_texture_faults(path, model)
            faults += coverage_faults(path, model)

    if not checked:
        print('no block models found to check', file=sys.stderr)
        return 1
    for fault in faults:
        print(fault, file=sys.stderr)
    print(f'checked {checked} block model(s): {"ok" if not faults else str(len(faults)) + " fault(s)"}')
    return 1 if faults else 0


if __name__ == '__main__':
    sys.exit(main())
