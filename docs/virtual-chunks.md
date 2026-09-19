# How an Extruder knows what rock belongs somewhere

The Terraform Extruder prints the block that this world *would* have generated at a displaced
coordinate. This is a record of how it finds that out, because the obvious approach is much cheaper,
looks right, and is wrong — and because two of the ways of doing it properly deadlock the server.

## The obvious approach, and why it was abandoned

Sample the noise. `ChunkGenerator.getBaseColumn(x, z, level, randomState)` builds one column out of
the density function without a `ChunkAccess` existing anywhere, in about a millisecond, and gives
back exactly the terrain the world has at that column: stone, air, the aquifer's water, the
1.18-era large ore veins. It is the right tool for "is this coordinate inside a cave".

It is the wrong tool for ore, and the reason is structural rather than a matter of accuracy.

**Ore is not in the noise.** A vein is a *feature*: `OreFeature` rolls a handful of origins per
chunk during the FEATURES generation step and grows a blob at each one, and the blob is written
across the 3x3 neighbourhood of the chunk that rolled it. There is no function from coordinate to
ore. There is only "run the chunk's features and see where they landed".

You can reimplement that — read the biome's `PlacedFeature` list, run its `PlacementModifier` chain
yourself, transcribe `OreFeature.doPlace` — and the first version of this mod did. It produces
plausible ore at plausible rates. But it is a parallel implementation of a moving target: it has to
know which placement modifiers are safe to run outside a real world, it silently drops any modded
feature that uses one it does not recognise, it cannot reproduce vanilla's feature-index seeding
without the chunk generator's private sorted list, and every one of those compromises is invisible
from the outside. A vein that is 20% too rare looks exactly like a vein that is correct.

And a single chunk decorated on its own shears every vein that crosses its border, which is the
thing a player actually notices.

## What it does instead

It generates the chunk. Not a chunk of this mod's invention — *the* chunk, through Minecraft's own
pipeline:

1. Create a `ProtoChunk` for the target chunk and its neighbours.
2. `ChunkGenerator.createBiomes`, then `fillFromNoise`, then `buildSurface`, then `applyCarvers` —
   the same calls `ChunkStatusTasks` makes, with the same `RandomState`, the same world seed and the
   same `Blender.empty()`.
3. `applyCarvers`, then `ChunkGenerator.applyBiomeDecoration` on the middle chunk, inside a
   `WorldGenRegion` over the grid, so its features have their neighbours to spill into.
4. Read block states out of the middle chunk. Throw the rest away.

Nothing is approximated, because nothing is reimplemented. Modded ores work for free and at their
own rarity, because at no point does this code know what an ore is.

### Where it stops

`ChunkStatus.FEATURES`, and not one step further. `INITIALIZE_LIGHT`, `LIGHT`, `SPAWN` and `FULL`
never run: no light propagation, no mobs, no promotion to a `LevelChunk`, no entry in any chunk map.
`STRUCTURE_STARTS` and `STRUCTURE_REFERENCES` never run either, so `applyBiomeDecoration` finds no
structure starts and places none — no villages, no mineshafts, no spawners. Block and fluid ticks
scheduled during generation land in the ProtoChunk's own tick containers and are discarded with it,
so nothing is ever ticked and no fluid ever flows.

These chunks exist only in memory, are never handed to the level, and are never written to disk.
`generatingVirtualChunksLoadsNoRealOnes` asserts the loaded-chunk count does not move.

## Three things that bite, in the order they bit

### 1. The structure manager must be bound to the region, not the level

`ChunkGenerator.createBiomes` builds a `NoiseChunk`, which builds a `Beardifier`, which asks the
`StructureManager` what structures are in the chunk. Hand it `level.structureManager()` and that
question becomes `ServerLevel.getChunk(...)` on a chunk a million blocks away: real generation,
scheduled onto the server thread, and joined from a worker thread while the server thread is itself
blocked waiting for that worker.

That is a deadlock, not a slowdown, and it is silent — the server simply stops.

Every stage gets `level.structureManager().forWorldGenRegion(region)` instead, which resolves
`getChunk` against the virtual grid. The Beardifier then asks a ProtoChunk with no structure
references and gets the truthful answer: there are none.

### 2. The carvers step wants 289 chunks, and none of them need anything in them

`NoiseBasedChunkGenerator.applyCarvers` walks a **17x17** neighbourhood of `level.getChunk`. Every
one of those 289 chunks must exist or the region throws, and generating 289 chunks in order to carve
one is not a trade worth making at any cache size. The first version of this pipeline gave up and
stopped at SURFACE, which cost ravines and the classic carver caves.

It did not have to. All the carver wants from a neighbour is `ChunkAccess.carverBiome(supplier)`,
and that method uses the chunk purely as a memo slot for a supplier which computes the answer from
the biome source and the chunk's own coordinates. It never reads a block. So the outer halo is
filled with **bare ProtoChunks that have had nothing done to them at all** — enough to be found, and
nothing more. They are built per grid, live in a local map, and are thrown away with it; putting a
few hundred empty chunks into the cache would evict everything worth keeping in order to store
nothing worth having.

Measured: carvers cost about **6%** on top of a grid. Ravines are worth 6%.

The halo is bare only *outside* the ring that might be asked about biomes — see (3). A bare chunk
answers a biome query with whatever a fresh `PalettedContainer` defaults to, and silently wrong rock
at a chunk seam is worse than a thrown exception.

This one was not worked out from first principles. [Orbital Regen](https://github.com/cluudryfarts/Regenarate-Chunks)
(MIT) solves the in-place regeneration problem and satisfies the same 17x17 demand with placeholder
protos; reading it is what turned "carvers are impossible here" into "carvers are 6%". It also
derives its halo radius from `ChunkStep` rather than hard-coding 8, which `VirtualStrata.holderMargin`
now does too. See `NOTICE.md`.

### 3. Surface rules read one chunk further than they write

`SurfaceSystem.buildSurface` asks `BiomeManager` for the biome at every column it paints, and that
lookup is deliberately fuzzy — it jitters the sample by up to a block. Painting the outer edge of
the outermost scaffolded chunk therefore asks about the chunk beyond it, and if that chunk has no
biomes the region throws `Requested chunk unavailable during world generation`.

So the scaffold is two rings. The inner one gets terrain and surface; the outer one gets **biomes
only**, which is much the cheaper half. How far each chunk has got is `ProtoChunk.getPersistedStatus()`
— the field vanilla already keeps for exactly this — so a chunk scaffolded as somebody's biome ring
is picked up and taken further when it becomes somebody else's middle.

All three of these were invisible in testing until a GameTest ran against a **noise** generator. The
GameTest server builds a superflat, where `FlatLevelSource` makes surface rules and carvers no-ops
and there is nothing for features to do, so nineteen tests passed against a pipeline that deadlocked
on contact with a real world. `aCoreSampleFromANoiseWorldHoldsRealOre` runs in the Nether, which is
in the same server and is generated by real noise, and it is the only test here that could have
caught any of it.

## What it costs, and the two ways that cost is paid

Measured in the Nether on a 2,304-column slice: **370–600 ms** per grid, on a worker thread. The
two machines spend that differently, because they need different things.

### A contraption: cache the chunks

A contraption's Strata Signature is fixed between Seismic Shifts, so as it travels it walks through
the displaced world in a straight line. `VirtualChunkCache` keeps the generated chunks in a bounded,
access-ordered map and reuses them, and scaffolds are shared between grids — moving one chunk
sideways costs three new scaffolds, not nine.

The displacement is **chunk-aligned** for this reason and no other: a displacement of, say, 37 blocks
would slide a machine's column seven blocks sideways inside its virtual chunk, so its footprint would
straddle two of them and everything above would double. Aligned to 16, a Rope Pulley descending a
shaft never leaves the chunk it started in, and the whole descent is served by the grid built for its
first block. `aVerticalDescentBuildsOneGrid` asserts exactly that.

That leaves one stall: a horizontal crossing into a chunk nobody has read yet. A gantry travelling
sideways would pay it once every sixteen blocks.

So it is prefetched. A contraption's signature is fixed between shifts, which makes its misses
*predictable* in a way a stationary machine's are not — where it will need to read next is exactly
where its motion vector points. `VirtualChunkCache.prefetch` projects `prefetchLookahead` blocks
along Create's own per-tick motion vector, and if that lands in a chunk nobody has generated, starts
generating it on a worker. Finished grids are merged in on the server thread by the calls a running
machine already makes.

Travelling straight down projects onto the chunk the machine is already in and prefetches nothing,
which is correct and is asserted by `verticalTravelPrefetchesNothing`. A stalled contraption has no
motion and prefetches nothing either.

Create fires `visitNewPosition` once per tick in which the actor's grid position changed, so a
contraption moving faster than one block per tick skips whatever it passed over. That is matched
rather than corrected: every actor Create ships behaves this way, and an Extruder that quietly did
something cleverer would be the odd one out on a machine assembled from Create's own parts.

There is exactly one miss the motion vector cannot predict: the **first** one. At assembly there is
no motion yet and nothing has been read, so the opening block of a print would pay for a whole grid.
`MovementBehaviour.startMoving` calls `VirtualChunkCache.warm` on the chunk the contraption is
standing in, a tick or more before the first placement, which on most generators is enough. It also
rolls the signature there, which is where a contraption's signature belongs anyway.

**A grid is 25 chunks, and the cache has to be several times that.** Reading one chunk generates a
3x3 of terrain inside a 5x5 of biomes, so a single lookup puts 25 entries in the LRU. A bound near
that evicts the chunk being read to make room for its own neighbours, and the cache quietly becomes
a slow way of generating everything twice — a machine standing still would rebuild its grid every
block. The default was 24 for a while, which was exactly that bug; it was invisible until
prefetching doubled the pressure, and `aVerticalDescentBuildsOneGrid` is what caught it.

**The worker gets its own chunks, and that is not an oversight.** It would be cheaper to let a
prefetch reuse the scaffolds already in the cache — six of the nine, usually. It would also be a
data race: decorating a chunk writes features into its neighbours, and `PalettedContainer` is
explicitly not safe for a write on one thread against a read on another. So a prefetch scaffolds into
a map of its own, and on merge a chunk already in the cache is kept unless the incoming one has got
further — a prefetch can never demote a decorated chunk back to a scaffold. Paying for three
redundant chunks on a worker is the cheap side of that trade.

`VirtualChunkCache.getGridsBuilt()` counts grids built **on the server thread** — the times a machine
actually had to wait — separately from `getGridsPrefetched()`. That split is what
`prefetchingTheNextChunkAvoidsTheStall` asserts on, and it does a cold read first so that "the
counter did not move" cannot pass against a counter that never moves.

### A stationary machine: cache the blocks

A machine that never moves has one coordinate to print into, so it must reroll its signature or print
the same block for ever — and a reroll is a whole new grid. Rerolling per placement is a stopped
server.

So it does not sample per placement at all. One signature buys one **core sample**: every printable
block at the machine's own altitude across the whole 48x48 footprint of the decorated 3x3, filtered,
shuffled, and held as a queue that the machine pops one block from per cycle. Two thousand blocks is
minutes to hours of running, and the heavy work is paid once for all of them.

Decorating all nine chunks of the footprint rather than only the middle one is what makes the ratios
right. Read 2,304 columns of real generated chunk and the proportion of diamond in the pool *is* the
proportion of diamond at that depth — for modded ores as much as vanilla ones, with no rarity table
anywhere in this mod. Decorating a chunk needs its own 3x3 present, so the scaffold is 5x5 and the
decorated area is the 3x3 inside it; twenty-five chunks of terrain for a pool of a couple of thousand
blocks is a good trade precisely because it is paid once.

The next sample is cut on a worker thread when the queue falls below a low-water mark, so a machine
that is running properly never waits. A machine that has just been placed waits once. A sample that
comes back empty means there is no rock at this altitude — an Extruder bolted to a hillside samples
sky — which is reported as `BARREN` and retried on a cooldown rather than immediately.

The queue is saved to NBT as a palette and a run of counts rather than a list of states: a sample is
a bag, not a sequence, and it is reshuffled on load anyway, so two thousand blocks compress to a few
dozen entries instead of a hundred kilobytes per machine.
