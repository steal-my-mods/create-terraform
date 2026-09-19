# Create: Terraform — working notes

Minecraft 1.21.1, NeoForge 21.1.248, Create 6.0.11. Java 21. Mod id `createterraform`, package
`com.createterraform`.

## Commands

```
./gradlew build                 # compile + jar
./gradlew runGameTestServer     # the test suite -- this is the real check
./gradlew runClient             # dev client
./gradlew runServer             # dev dedicated server

python3 tools/generate_textures.py    # every texture the mod ships
python3 tools/generate_structures.py  # the GameTest structure template
python3 tools/generate_logo.py        # the mod badge
```

`run-gametest/eula.txt` must say `eula=true` before the test server will start; it is gitignored, so
a fresh checkout needs it created once. CI does that itself.

## The frame

**A late-game Deployer whose inventory is the world.** On a contraption that is exactly what it is:
same activation model, same one-placement-per-new-position, same behaviour at speed. When a question
comes up about how the machine should *feel* — reach, disabling, what happens when it is driven fast
— the answer is "whatever a Deployer does", and that is why the gap at speed is matched rather than
fixed.

The frame stops at the contraption, though, and pushing it further would be a mistake. A stationary
Extruder is not an applicator, it is a **generator**: its output comes from the world's own chunk
generator rather than from an inventory, which is the whole of `StrataSlice` and has no Deployer
analogue. Neither does the Seismic Shift — a Deployer needs no anti-exploit because its output is
bounded by its input, and the Extruder's is not.

## Architecture landmarks

Read [docs/virtual-chunks.md](docs/virtual-chunks.md) before touching anything under `strata/`. It is
the design document for the only genuinely difficult part of this mod, and it records three failure
modes that are silent, expensive and easy to reintroduce.

| Where | What |
| --- | --- |
| `strata/VirtualStrata` | The generation pipeline. Scaffolds ProtoChunks through CARVERS and decorates them at FEATURES, using Minecraft's own `ChunkGenerator`. Thread-safe; the stationary machine runs it on a worker. |
| `strata/VirtualChunkHolder` | A `GenerationChunkHolder` with a chunk in it, so a `WorldGenRegion` can be built over a grid that has no chunk map behind it. |
| `strata/VirtualChunkCache` | Per-level LRU of generated chunks, plus vector prefetching along a contraption's motion. The **contraption** path. Server thread only. |
| `strata/StrataSlice` | A 48×48 Y-slice, filtered and shuffled. The **stationary** path, cut on a worker. |
| `strata/StrataSignature` | Signature → chunk-aligned displacement. The alignment is load-bearing. |
| `strata/StrataMemory` | A contraption's signature + printed-coordinate history. The Seismic Shift. |
| `strata/PlacementRules` | The blacklist, the overwrite rule, and the block-update flags. |
| `extruder/TerraformExtruderBlockEntity` | Standing still: core-sample queue, async double buffering, NBT. |
| `extruder/ExtruderMovementBehaviour` | Moving: Create's `MovementBehaviour`, and the Seismic Shift. |
| `extruder/ExtruderMountedStorage` | The machine's tank, mounted into a contraption's fluid pool. |

## Things that will bite

- **Never pass `level.structureManager()` to a generator stage.** It resolves `getChunk` against the
  real chunk source and deadlocks the server. Always `forWorldGenRegion(region)`.
- **Never call `applyCarvers`.** It requires a 17×17 chunk neighbourhood.
- **The scaffold has two rings.** Surface rules read biomes one chunk further than they write.
- **The GameTest server is a superflat**, where `FlatLevelSource` makes most of the pipeline a no-op.
  A test that only runs there proves almost nothing about worldgen.
  `aCoreSampleFromANoiseWorldHoldsRealOre` runs in the Nether and is the only test that would catch
  any of the above. Keep it.
- **`StrataSignature` displacement is chunk-aligned.** Unaligning it doubles the cost of every print.
- The stationary machine's async result is collected by polling `CompletableFuture.isDone()` on the
  server thread. Nothing else may touch the queue from a worker.
- **A worker must never share a scaffold map with the cache.** Decorating writes features into
  neighbouring chunks and `PalettedContainer` is not safe for a concurrent write/read. Prefetches
  generate into a private map and are merged on the server thread; the obvious "optimisation" of
  reusing cached scaffolds is the bug.
- `getGridsBuilt()` counts *server-thread* builds only. Keep it that way — it is the number the
  prefetch test asserts on.
- **`virtualChunkCacheSize` must stay several times 25.** One read generates a 3×3 of terrain inside
  a 5×5 of biomes, so a single lookup puts 25 chunks in the LRU. A bound near that evicts the chunk
  being read to make room for its own neighbours, and a machine standing still rebuilds its grid
  every block. This was latent at the old default of 24 and only showed up once prefetching doubled
  the pressure; `aVerticalDescentBuildsOneGrid` is what catches it.
- **GameTests share one `VirtualChunkCache` per level and run interleaved on the server thread.**
  Never wait on `getGridsPrefetched()` — it may have moved for another test. Wait on
  `getPrefetchesInFlight() == 0`. Reads inside a single `thenExecute` lambda are safe, because
  nothing else runs between them.

## Two things that look like they need Create's Deployer, and do not

- **The "when does it fire" math is not in `DeployerMovementBehaviour`.** It is in
  `AbstractContraptionEntity.tickActors` / `shouldActorTrigger`, which every `MovementBehaviour`
  gets for free. `DeployerMovementBehaviour` is a `DeployerFakePlayer`, item extraction from
  contraption storage, filter handling, schematic printing and a block-breaking stall machine.
  Extending it would mean overriding all of that to no-ops.
- **The gap at speed is deliberate, not an oversight.** `shouldActorTrigger` fires once per tick in
  which the grid position changed, so a contraption moving faster than a block a tick skips the rest
  — for a Deployer and a Drill as much as for us. It was fixed once, by filling the run between the
  last visited coordinate and this one, and then reverted on purpose: matching what every actor
  Create ships does is worth more than being quietly better than them, and an Extruder that behaved
  differently would be the odd one out on a machine built from Create's parts. Do not re-add it
  without deciding that again.

## Conventions

- Tabs, Create's formatting idiom, and javadoc that says *why* rather than *what*.
- Registries are plain NeoForge `DeferredRegister`, not Registrate — Create ships Registrate
  jar-in-jar and it is `compileOnly` here (see the comment in `build.gradle`).
- Textures and the test structure are generated and diffed in CI. Do not hand-edit a PNG or the NBT.
- **The bucket is deliberately not ours.** `neoforge:fluid_container` over `neoforge:item/bucket`
  composites the vanilla sprite with the fluid's still texture. A hand-drawn one has to reproduce a
  silhouette every player knows by heart and reads as wrong if it is slightly off — which it was.
  Do not add a `mineral_substrate_bucket.png`.
- No Create asset is used or derived from. See `NOTICE.md`.

## Distribution

GitHub Releases only for now. `curseforge_project_id` in `gradle.properties` is deliberately blank
and `build.gradle` omits the whole CurseForge destination while it stays that way — an empty project
id would otherwise sail past `-PdryRun=true` and fail against the real API *after* the GitHub release
had been created. Modrinth is not a destination.
