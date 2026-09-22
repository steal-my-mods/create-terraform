#!/bin/sh
# Every generated asset in this repo, in one place.
#
# Both workflows check that regenerating is a no-op, and for a while they did it from two
# hand-maintained lists -- which drifted the day the Ponder structures were added, leaving the
# release gate blind to exactly the kind of stale file it exists to catch. One list now.
#
#     sh tools/regenerate.sh
#
# generate_page_art.py is deliberately not here. It reads Create's and Minecraft's jars out of the
# Gradle cache at the moment it draws, so it needs a build to have run first, and a branding image
# is not worth making a build depend on that. Re-run it by hand after changing a recipe or a model.
set -eu

python3 tools/generate_textures.py
python3 tools/generate_structures.py
python3 tools/generate_ponder.py
python3 tools/generate_models.py
python3 tools/check_models.py
python3 tools/generate_logo.py
python3 tools/generate_logo.py branding/icon-512.png --size 512
