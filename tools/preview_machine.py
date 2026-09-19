#!/usr/bin/env python3
"""
Assembles the Extruder as a player sees it -- casing, spindle, barrel and a full tank of mud -- into
one model for tools/render_block_model.py.

The item model already inlines the moving parts, but it deliberately leaves out the mud, because how
much mud is in the tank is a runtime question. So judging the gauge means adding it here, and adding
it means applying the same transform the renderer applies at runtime: every partial is authored
pointing UP and swung onto the facing, so a partial's own coordinates are not world coordinates.

Appending the gauge verbatim -- which is what an earlier throwaway version of this did -- puts the
mud inside the housing instead of in the tank, and the resulting render is quietly wrong in exactly
the place you were looking. Hence a real script.

    python3 tools/preview_machine.py -o /tmp/rig.json
    python3 tools/render_block_model.py /tmp/rig.json -o /tmp/rig.png --angle top
"""

import argparse
import json
import os
import sys

ASSETS = 'src/main/resources/assets/createterraform/models'

#: The renderer swings every partial from pointing up onto the facing, and the casing is authored
#: facing south. This is that swing, for a model authored facing south: local up becomes world south.
ONTO_FACING = {'up': 'south', 'down': 'north', 'north': 'down', 'south': 'up',
               'east': 'east', 'west': 'west'}


def swing(element):
    """One element of an up-authored partial, turned onto a south facing."""
    low, high = element['from'], element['to']
    return {
        'from': [low[0], 8 + (low[2] - 8), 8 + (low[1] - 8)],
        'to': [high[0], 8 + (high[2] - 8), 8 + (high[1] - 8)],
        'faces': {ONTO_FACING[name]: {k: v for k, v in face.items() if k != 'cullface'}
                  for name, face in element['faces'].items()},
    }


def load(name):
    with open(os.path.join(ASSETS, name)) as handle:
        return json.load(handle)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('-o', '--out', default='/tmp/extruder-preview.json')
    arguments = parser.parse_args()

    model = load('item/terraform_extruder.json')
    gauge = load('block/terraform_extruder/gauge.json')
    model['textures'].update(gauge['textures'])
    model['elements'] = [{k: v for k, v in e.items() if not k.startswith('__')}
                         for e in model['elements']] + [swing(e) for e in gauge['elements']]
    model.pop('__comment', None)

    with open(arguments.out, 'w') as handle:
        handle.write(json.dumps(model, indent=2) + '\n')
    print('wrote', arguments.out)
    return 0


if __name__ == '__main__':
    sys.exit(main())
