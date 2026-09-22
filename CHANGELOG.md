# Changelog

## 0.1.0

First release.

- **Terraform Extruder** — a kinetic machine that prints the strata this world *would* have
  generated somewhere else. It does not invent rock: it runs Minecraft's own chunk generator over
  throwaway chunks, up to `ChunkStatus.FEATURES` — terrain, surface rules, carvers and features —
  and reads the answer out. Caves and ravines are real, ore appears at its own rarity in its own
  depth band, and ore from other mods works without this mod knowing they exist.
- **Mineral Substrate** — the heavy fluid the Extruder burns, mixed superheated from lava, clay,
  powdered obsidian and blaze powder. Three separate farms, all infinite and all unattended.
- **Standing still.** Fed rotation and substrate, an Extruder works from a *core sample*: every
  printable block at its own altitude across a 48x48 footprint of virtual world, shuffled, one spent
  per cycle. The next sample is cut on a worker thread before the current one runs out, so the server
  thread never generates a chunk and the machine never stutters.
- **On a contraption.** Assembled onto a Rope Pulley, a Gantry Carriage or a Minecart Contraption,
  every Extruder on board shares one Strata Signature, so a print comes out as one continuous piece
  of world rather than a mosaic. An Extruder's own tank is mounted into the contraption's fluid pool,
  so a lone machine runs off what it was already carrying. The chunk ahead of a moving contraption is
  generated on a worker thread before it arrives, so a machine travelling in a straight line never
  waits for one, and the first chunk is started the moment the contraption assembles.
- **The Seismic Shift.** A contraption that revisits a coordinate it has already printed vents,
  forgets everything it has printed, and rerolls its signature — so driving a pulley up and down over
  the same shaft prints new rock instead of the same ore twice. The same shift fires on size alone at
  65,536 coordinates.
- No liquid is ever placed, and neither is bedrock, an end portal frame, reinforced deepslate or
  anything carrying a block entity. Placements skip neighbour and shape updates, so a machine
  printing next to standing water cannot cascade.
- **The rig turns on a contraption too.** An assembled Extruder used to be a bare casing with an
  empty mud window; the spindle, the barrel and the mud now all draw and animate out there, off the
  contraption's own motion rather than off a rotational network it is not part of. The machine's
  mounted tank is synced, so the mud window tells the truth about a print that has been running for
  a while.
- **Two Ponder scenes**, on the Extruder itself: one of the machine standing still with a harvester
  taking what it makes, and one of a Rope Pulley painting a bank of ground on the way down and
  rerolling it on the way back up.
