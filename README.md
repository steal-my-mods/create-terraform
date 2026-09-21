# Create: Terraform

A late-game industrial terraforming addon for [Create](https://github.com/Creators-of-Create/Create),
Minecraft 1.21.1, NeoForge.

One machine, and the shortest way to describe it is **a late-game Deployer whose inventory is the
world**. The **Terraform Extruder** burns **Mineral Substrate** and prints the strata this world
would have generated somewhere else — real stone, real deepslate, real caves, and real ore veins at
the real rates that depth and biome have. It works standing still, and it works bolted to a moving
contraption.

## What it actually does

It does not invent rock. When an Extruder needs to know what belongs at a coordinate, it generates
that piece of the world — the actual chunk, through Minecraft's own generator, at this world's own
seed, displaced by a **Strata Signature** — and reads the answer out of it. Ore comes out at its own
rarity in its own depth band, and ore from other mods works without this mod knowing that other mod
exists, because at no point does any of this code know what an ore is.

The generated chunks live in memory, run terrain, surface rules, carvers and features, stop at
`ChunkStatus.FEATURES`, and are never lit, ticked, spawned into, handed to the level or written to
disk. See
[docs/virtual-chunks.md](docs/virtual-chunks.md) for how that is done and what it costs — including
the three things that make it harder than it sounds.

## The two machines

### Standing still

Give it rotation and substrate and it prints into the block it faces, on an interval that scales
with RPM. It works from a **core sample**: one signature buys every printable block at the machine's
own altitude across a 48×48 footprint of virtual world — a couple of thousand blocks, shuffled —
and each cycle spends one. The next sample is cut on a worker thread before the current one runs
out, so the machine never stutters and the server thread never generates a chunk.

The result is a randomised, biome-accurate ore generator whose ratios are not a table in this mod
but a measurement of the world it is standing in. Put it underground: bolted to a hillside it samples
sky, says so, and stops asking.

### On a contraption

Glue Extruders to a Rope Pulley, a Gantry Carriage or a Minecart Contraption and they print as they
move. Every Extruder on board shares **one** Strata Signature, so a wide print comes out as one
continuous piece of world — a cave that starts under the first machine carries on under the fourth,
and a vein clipped by one is finished by its neighbour.

Substrate comes from the contraption's own tanks, and an Extruder's internal buffer is mounted into
that pool, so a lone machine on a pulley runs off what it was already carrying.

Because a contraption's signature is fixed, where it will need to read next is exactly where it is
pointed — so the chunk ahead of it is generated on a worker thread before it gets there. A machine
travelling in a straight line never waits for one.

### The Seismic Shift

A contraption remembers every coordinate it has printed into. Drive it back over its own work —
reverse a Rope Pulley up the shaft it just came down — and it vents, forgets everything, and rolls a
new signature. You still get rock on the way back up; you do not get the same rock twice. The same
shift fires on size alone at 65,536 coordinates, so a tunnel bore that never crosses its own path
still stops growing its history.

## Getting one

**Terraform Extruder** — a 5×5 Mechanical Crafter recipe, yielding 16. Four Mechanical Presses,
eight Brass Casings, four Electron Tubes, four Precision Mechanisms, four Mechanical Pumps and a
Nether Star.

**Mineral Substrate** — mixed superheated: 250mB of lava, gravel and a clay ball in a Basin under a
Mechanical Mixer with a Blaze Burner on blaze cake, for 100mB out. At the default cost that is one
craft per block printed, which is where the machine's price actually lives — not in its stress, which
is a Deployer's 4 SU/RPM and is charged only when it is standing still.

## Configuration

Server config, `createterraform-server.toml`. Everything is documented in the file itself. The ones
worth knowing about:

| Setting | Default | What it is |
| --- | --- | --- |
| `substratePerBlock` | 100 mB | Fuel per block printed |
| `cycleScale` | 1.0 | Multiplier on the printing interval. At 1.0 the machine places at exactly a Mechanical Deployer's rate |
| `sliceLowWaterMark` | 256 | Blocks left before a stationary machine surveys again |
| `virtualChunkCacheSize` | 128 | Generated chunks a level keeps for contraptions |
| `prefetchLookahead` | 24 blocks | How far ahead of a moving contraption to generate early; 0 disables |
| `seismicShiftLimit` | 65536 | Coordinates a contraption remembers |
| `signatureRange` | 1,000,000 | How far a signature may displace a sample |

Two block tags decide what an Extruder may print and what it may print over —
`createterraform:extruder_blacklist` and `createterraform:extruder_replaceable`. Both are meant to be
edited by datapacks.

`extruder_replaceable` applies to **contraptions only**, and the asymmetry is deliberate. A moving
Extruder paints over rock, because it visits each coordinate once and then leaves; confined to empty
space it would only work in caves. A **stationary** Extruder prints into free space and nothing else
— it returns to the same coordinate forever, so a machine that overwrote there would be racing the
drill or crusher harvesting its output, and the block being mined would keep turning into a different
block. So a stationary Extruder prints one block and waits at *Obstructed* until something takes it
away: the harvester sets the pace.

## Building

```
./gradlew build            # jar in build/libs
./gradlew runGameTestServer # the test suite
./gradlew runClient        # a dev client
```

Art and the GameTest structure are generated rather than checked in by hand:

```
python3 tools/generate_textures.py
python3 tools/generate_structures.py
python3 tools/generate_logo.py
```

CI re-runs all three and fails if anything changes.

## Licence

MIT; see [LICENSE](LICENSE). Create is a required dependency and is not redistributed — see
[NOTICE.md](NOTICE.md) for what is used and under what terms. No Create asset is used; every texture
this mod ships is drawn by the scripts above. The Mineral Substrate bucket is the only thing not
drawn: it is NeoForge's `fluid_container` model over the vanilla bucket sprite, which is why it
matches every other bucket in the game and follows resource packs.
