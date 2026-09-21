<!--
The CurseForge project page copy. Kept here so the store description is versioned alongside the mod
it describes, and so updating it is an edit rather than a rewrite from memory.

The SUMMARY below goes in the project's one-line Summary field. Everything under the rule goes in
the Description field, as markdown.

CurseForge hosts images itself, so upload a screenshot through the project's gallery and replace the
IMAGE marker below with the URL it gives you. Leaving the marker in place ships a comment where a
screenshot should be, which is invisible in the rendered page: the section simply has no picture in
it.

The icon is branding/icon-512.png, uploaded separately as the project's avatar.
-->

SUMMARY: Adds the Terraform Extruder, a Create machine that places stone, deepslate and ore veins using your world's own terrain generation.

---

# Create: Terraform

Adds one machine, the Terraform Extruder. It runs on rotation and a fluid called Mineral Substrate,
and places stone, deepslate, gravel and ore.

The blocks it places are not random. It generates a piece of your world with an offset seed and
copies what is there, so ore shows up at its normal rarity and depth, caves and ravines come out
cave-shaped, and ore from other mods works without any setup.

<!-- IMAGE: a screenshot -- replace with the gallery URL -->

## Stationary

- Needs rotation and Mineral Substrate.
- Places a block two spaces in front of it, at the same rate as a Deployer.
- Only places into empty space, so point a drill at it and let that set the pace.
- Works best underground. Higher up it mostly samples air and will tell you so.

## On a contraption

- Works on Rope Pulleys, Gantry Carriages and Minecart Contraptions.
- Places as it moves, over whatever is already there.
- Extruders on the same contraption share one seed offset, so a wide print lines up instead of
  coming out in patches.
- Runs off the contraption's fluid tanks.
- Driving back over ground you have already printed rerolls the offset, so you cannot farm the same
  ore twice.

## Recipes

**Terraform Extruder.** Mechanical Crafter, 5x5, makes 16. Four Mechanical Presses, eight Brass
Casings, four Electron Tubes, four Precision Mechanisms, four Mechanical Pumps and a Nether Star.

**Mineral Substrate.** Superheated mixing. 250mB lava, gravel and a clay ball makes 100mB, which is
one block placed.

Requires Create 6.0 or later, Minecraft 1.21.1, NeoForge.
