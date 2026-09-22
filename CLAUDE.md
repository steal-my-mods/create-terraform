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
python3 tools/generate_ponder.py      # the Ponder structures, and their lang keys
python3 tools/generate_logo.py        # the mod badge
python3 tools/generate_page_art.py    # branding/: the banner and a sheet per recipe
python3 tools/generate_models.py      # blockstate, casing models, partials, item model
python3 tools/check_models.py         # block models: missing textures, holes in the block boundary
python3 tools/preview_machine.py -o /tmp/rig.json   # the whole machine, mud included
python3 tools/check_models.py /tmp                 # cross-partial z-fighting
python3 tools/render_block_model.py /tmp/rig.json -o out.png --angle hero
```

`render_block_model.py` is an orthographic renderer for a block model, and it is the difference
between designing the casing and guessing at it — three versions of this block were authored blind
and all three were wrong in ways one render would have caught. `--angle` takes `hero` and `profile`
(judge anything that stands out of the front from these; head-on, an orthographic projection
collapses a barrel into a square), `top` (straight down into the mud tank), `drive` and
`back-quarter`, plus the generic `iso`/`front`/`quarter`/`high`/`side`.

Always render **`preview_machine.py`'s output**, not a model from `assets/`. The casing alone is
missing everything that moves; the item model has the moving parts but no mud. `preview_machine.py`
adds the mud, and it applies the same up-to-facing swing the renderer applies at runtime — appending
the gauge verbatim puts it inside the housing instead of the tank, and the render is then quietly
wrong in exactly the place you were looking at.

`run-gametest/eula.txt` must say `eula=true` before the test server will start; it is gitignored, so
a fresh checkout needs it created once. CI does that itself.

## The frame

**A rotary core drill run backwards.** A core rig turns a chuck, circulates mud down the string and
brings a cylinder of rock up; this one pushes one out. Plinth, housing, open mud tank on the deck
above it; shaft in the back, chuck and barrel out the front. That is the shape the machine is built to,
and it was chosen because it is the only one that explains all three of the machine's inputs at
once — mud in the tank, rotation into the chuck, rock out of the barrel — and because it is what the
code has always thought it was: `StrataSlice`, `sample`, `sampleY`, a "core sample queue".

Six other shapes were mocked up and rendered before this one was picked (screw extruder, placing
pump, vibroseis baseplate, transmutation array, pattern emitter, analytical caster). Do not redesign
this block without doing the same — three earlier versions were authored blind and all three were
wrong.

**Three rules the shape must keep.**

**Rotation and fluid meet at a mechanism, never in a volume.** Create
never runs a shaft through a tank. The Mixer puts the shaft above and the basin below, the Press puts
the shaft above and the ram below, the Steam Engine puts fluid below and takes the shaft out the
side. A version of this block that ran the drive straight through the substrate was rejected for it, and
that is what the deck is: wet above, dry below.

**Brass means the barrel.** It is the one part that stands outside the cell and the one part a
player reads the front off, so nothing else on the machine is brass — the chuck wrapped around it is
steel for exactly this reason. A pass that quietly repointed the barrel at the slate palette made it
vanish against the casing.

**The drive is coaxial.** The shaft goes in the face behind the one the core comes out of, because
the barrel turns about the facing and a barrel that spins about the facing wants its drive on the
facing. It used to come in the side, Deployer-style, which was right while the business end was a
punching ram and needed a bevel pair to explain itself once it became a turning barrel. That change
took a blockstate property and six variants with it: six variants now, one casing model, no
`axis_along_first`.

Behaviourally it is still **a late-game Deployer whose inventory is the world.** On a contraption that is exactly what it is:
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
| `strata/PlacementRules` | The blacklist, the two placement rules, and the block-update flags. |
| `extruder/TerraformExtruderBlockEntity` | Standing still: core-sample queue, async double buffering, NBT. |
| `extruder/ExtruderMovementBehaviour` | Moving: Create's `MovementBehaviour`, and the Seismic Shift. |
| `extruder/ExtruderMountedStorage` | The machine's tank, mounted into a contraption's fluid pool, and synced so the mud window stays honest out there. |
| `client/TerraformExtruderRenderer` | Spindle, barrel and mud, all on the facing, standing still **and** on a contraption. Nothing is synced for the animation: standing still the angle is the machine's own speed, moving it is the contraption's. |
| `client/TerraformPartials` | Barrel, spindle and gauge — all up-authored, because that is what Create's orientation transform expects. |
| `client/ponder/ExtruderScenes` | The two Ponder storyboards. Layouts match `tools/generate_ponder.py`, which writes the structures and the lang. |

## The two placement rules

**Overwriting is safe exactly when you only visit a coordinate once**, and that is why the two modes
differ. `PlacementRules` has one rule each and they are not interchangeable:

- **`canPrintInto`** — stationary. Free space only: air, water, grass, snow. It may *not* write over
  rock, including its own output. A stationary machine returns to the same coordinate forever, so a
  machine that overwrote there would be racing whatever is consuming what it makes — and consuming
  what it makes is the entire point of a stationary setup. Three Mechanical Drills on the target
  block never finished one, because the block they were part-way through breaking kept turning into
  a different block and their progress went with it. The harvester now sets the pace; the machine
  waits at `OBSTRUCTED`, which is synced and shows under goggles. One block and stop with nothing
  attached is correct, not a stall.
- **`canDisplace`** — moving. Adds `EXTRUDER_REPLACEABLE` (base stone, deepslate, ore). It may
  overwrite rock because it has the guarantee the stationary machine cannot: `StrataMemory` records
  every coordinate it prints into and the Seismic Shift fires on a revisit, so it prints and leaves
  and can never race anything. It also *has* to — confined to empty space it would only work in
  caves, and painting displaced strata over solid ground is the machine's whole job.

`EXTRUDER_REPLACEABLE` is therefore a **contraption-only** tag; its JSON says so.

A stationary Extruder also refuses a coordinate that still holds an **item entity**, so it cannot
bury a harvester's drop before a Chute or belt has taken it.

There is deliberately **no fuel surcharge for displacing rock**. Cost scales with blocks produced,
not with what happened to be there — a surcharge would make the machine cheapest where it does least
(filling air) and dearest where it is most useful. The anti-exploit is the Seismic Shift, not the
fuel bill.

## Drawing the rig on a contraption

An assembled Extruder used to be a bare casing. It is not any more, and the three facts that made
that work are each the opposite of what they look like:

- **`renderInContraption` runs on every backend.** `ContraptionEntityRenderer` skips only the
  *structure* buffer when Flywheel is visualizing and calls `renderActors` either way, so the
  `VisualizationManager.supportsVisualization` check inside Create's Drill is the Drill deferring to
  its own `DrillActorVisual`, not Create asking. Copying that guard without writing a visual is how
  you get a headless machine on the backend almost everybody runs.
- **The block entity renderer *does* run on a contraption**, unless the actor's
  `disableBlockEntityRendering()` says otherwise. So an actor that draws its own moving parts has to
  turn it off or the parts are drawn twice — once live and once frozen, because the stationary
  renderer reads a kinetic speed and an actor belongs to no rotational network. Create's Drill, Saw,
  Deployer, Harvester and Roller all turn it off. `theExtruderIsRegisteredAsAContraptionActor`
  asserts we do.
- **`context.getFluidStorage()` is memoized, and a storage sync replaces the storage rather than
  mutating it.** So the obvious way to read the tank hands back the copy captured at assembly, for
  the life of the contraption. `ExtruderMountedStorage.afterSync` writes each arriving load into the
  client-side block entity instead — `contraption.getBlockEntityClientSide(pos)`, which still returns
  it even with the renderer disabled — and that is what the gauge reads. Create's own Fluid Tank
  does exactly this.

The angle out there is `MovementContext.getAnimationSpeed()`, which is motion rather than rotation
and already answers two edge cases: nought when the actor is switched off from the controls, and a
flat 700 while the contraption is stalled, which is how every Create actor shows it is straining.
It is deliberately **not** gated on direction the way the Drill's is — a Drill only cuts forwards,
and an Extruder prints on every position it enters whichever way it is going.

## Ponder

Two scenes, both hung off the Extruder item: the stationary machine, and one on a Rope Pulley.
Between them they cover the only two things about this machine a Create player cannot guess — that
the blocks come out of the world's own generator, and that standing still and moving obey different
rules. Everything else is a Deployer's and explaining it would be teaching Create.

- **Nothing in a ponder scene rotates by itself.** A ponder level is client-side and every path that
  assigns a kinetic speed is server-gated, so the Creative Motors in these structures drive nothing.
  `CreateSceneBuilder`'s `setKineticSpeed` writes the `Speed` tag the renderer reads, which is why
  both storyboards open by wrapping the builder they are handed. The motors are in the structures
  anyway: a scene showing an Extruder turning with nothing attached would be teaching that it does
  not need rotation.
- **The scene is not a real contraption.** Ponder has no assembly; the pulley scene is
  `showIndependentSection` plus `moveSection` and `movePulley`, so `renderInContraption` never runs
  in it. The scene teaches the mechanic; it does not exercise the code.
- **A scene's text is the source of its lang key.** `tools/generate_ponder.py` reads the `.text(...)`
  calls straight out of `ExtruderScenes.java` and writes `createterraform.ponder.<scene>.text_N` in
  call order, because Ponder derives that key itself and shows the player the raw key when it is
  missing. Edit a line, re-run the generator. `thePonderScenesAreTranslated` catches a lang file that
  has drifted, and CI catches a generator nobody re-ran.
- **The structures are generated and diffed, like every other asset here.** Do not hand-edit an
  `.nbt` under `assets/createterraform/ponder/`. A palette entry naming a block or a property that
  does not exist fails nowhere until a player opens the scene, which is what
  `thePonderStructuresAreValid` is for.

## Things that will bite

- **A block entity whose renderer draws outside its own cell must override
  `createRenderBoundingBox()`.** The default box is the one block, and anything drawn past it is
  culled with it — so the barrel vanished the moment the machine's own cell left the view frustum,
  which is exactly what happens when you walk up to the business end. Create's Deployer inflates by
  three for the same reason; so do we.
- **Create's block breakers park and wake on their *lazy* tick.**
  `BlockBreakingKineticBlockEntity` sets `ticksUntilNextProgress = -1` when there is nothing in
  front of it and only calls `destroyNextTick()` from `lazyTick()`, which Create runs every ten
  ticks. A Mechanical Drill aimed at a freshly printed block therefore idles up to half a second
  unless something tells it — `DrillBlock` overrides `neighborChanged`, so the stationary machine
  places with `STATIONARY_FLAGS` (neighbour updates included) rather than the suppressed
  `PLACEMENT_FLAGS` a contraption uses.
- **Never print on top of an item entity.** A harvester breaking the printed block leaves a drop
  standing in the space for a moment, and refilling it that tick seals the item inside a solid
  block — which is why a Chute under the target almost never caught anything.

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

## Known gaps

- **No Flywheel visual, stationary or moving.** Without one Create never skips the block entity
  renderer, so the stationary rig draws on every backend — correct, just not instanced. The same
  absence is why `ExtruderMovementBehaviour.renderInContraption` carries no
  `supportsVisualization` guard: Create's Drill guards its own because a `DrillActorVisual` takes
  over, and an Extruder that guarded would go headless on every backend but the fallback. Adding a
  visual means writing the rig's geometry a second time and keeping the two in step, and is only
  worth it once there are enough Extruders on screen for instancing to pay — at which point the
  guard goes in with it.

## Conventions

- Tabs, Create's formatting idiom, and javadoc that says *why* rather than *what*.
- Registries are plain NeoForge `DeferredRegister`, not Registrate — Create ships Registrate
  jar-in-jar and it is `compileOnly` here (see the comment in `build.gradle`).
- Textures and the test structure are generated and diffed in CI. Do not hand-edit a PNG or the NBT.
- `tools/check_models.py` gates block models: texture references, `#refs`, and boundary coverage.
  Coverage is opt-in via `"__solid": true`, because a Create-style machine casing is deliberately
  not a solid block — the Extruder is a twelve-pixel casing with an open mouth and sets
  `noOcclusion()`. A solid model may still leave one face open if something opaque backs it, via
  `"__backed": ["<face>"]`.
- **Textures are ramps, not palettes.** `ramp()` builds twelve steps of slate, ten of brass and nine
  of mud; a texture picks a *level* off one. Create's block textures carry seventeen or eighteen
  colours apiece.
- **Textures are material; geometry is structure.** This casing is twenty-five boxes and each one
  crops a 16x16 texture — the plinth gets rows 14-16, a bore strip gets columns 1-5, a chamfer gets a
  single pixel. So a texture drawn the way Create draws a casing, with an outline and a lit bevel and
  four corner bolts, lands its outline in the middle of some faces and nowhere on others and puts
  bolts wherever a crop happens to cover them: frames inside frames, which is what "too much going
  on" turned out to be. Create's andesite casing carries a frame because it is a texture on a
  **cube** and there is no geometry to carry one. Here there is, and Minecraft's directional face
  shading draws every edge of it for free. `material()` is therefore plain grain, uniform under any
  crop, and anything drawn on top of it has to be information the geometry cannot express — the
  octagonal rim of a socket, the jaws of a chuck, the glow at the tip of a die — positioned against
  something the model guarantees rather than against the edge of a square that will be cut up.
- **A rod's four side faces all take the same UV.** It is the one shape whose faces cannot take UVs
  from their own extents: derived that way, the two faces whose width runs along the *length* of the
  bar sample the texture sideways, and for a twenty-pixel barrel that walks clean off the end of the
  profile into flat background — two sides of the tube come out with no colour on them at all.
  `create:block/shaft.json` sets all four sides to `[6,0,10,16]` for exactly this reason: cross
  section across, length down. `__rod` in `generate_models.py` does it, and `__tex_end` gives the
  ends their own texture, which they need because the strip has no room left for one — the same
  reason Create splits `axis` from `axis_top`.
- **A surface is grain, not a gradient.** Read `create:block/andesite_casing.png` pixel by pixel and
  it is a hard outline, a bright bevel inside it, and an interior of high-frequency variation
  streaked along one axis. There is no smooth ramp anywhere in it. `streak()` does this: a stable
  value per line with a pixel of jitter on top. **Structured** variation is information — grain,
  wear, machining. Isotropic per-pixel noise is not, and Minecraft's own style guide says so.
- **The three named ways to make a surface look flat**, all of which versions of this block managed:
    - **Banding** — pixels lined up brightest to darkest in straight rows. That is what `gradient()`
      does across a whole face, so it is reserved for something that really is a smooth curve.
    - **Pillow shading** — shades applied concentrically from the outline inwards. The chuck ring had
      it.
    - **Pancake shading** — highlight on one side, shadow on the other, disregarding the shape. The
      barrel had it, and on a part that *turns* it is worse than flat: half the faces are lit at any
      moment and which half keeps changing, so the machine looks like it is flashing rather than
      spinning.
- **A part that rotates is shaded symmetrically about its axis.** `rod()` is dark at both edges and
  light through the core, which reads the same from every angle — Create's own `axis.png` is exactly
  this. Its detail runs *along* the length, where rotation does not carry it.
- **Dither at a boundary, never over a surface.** The style guide's words are "the overuse of
  dithering where the transition starts, covering too much surface area". A pass that blanketed
  `blend()` over every face put a checkerboard on the whole machine.
- **The render canvas grows to fit the model, rather than assuming a 16-unit cell.** `View` scales
  to the cell's diagonal, which is right, but the canvas used to be that size too, so anything
  reaching outside `0..16` was simply cut off at the boundary. Plenty of models do reach outside:
  this mod's barrel runs to z=31 and Create's Mechanical Press stands above y=16, which is what
  cropped the front off the Extruder and the top off the Press. `View.fitting` pads the canvas by
  the overflow, rounded to a whole oversampled pixel so the downsample still divides. The *scale*
  stays tied to the requested size, so a caller that sized two models against each other still gets
  them at the same scale.
- **The renderer applies per-element rotation, and has to.** Models lean on it far more than they
  look like they do: Create's Mechanical Pump has forty-nine rotated elements and its Mechanical
  Press fifteen. A renderer without it does not fail, it quietly draws every one of them square, so
  a press comes out looking like a furnace. A rotated face keeps the shade of the direction it was
  *declared* in rather than the one it now points, which is what the game does too.
- **The blockstate's vertical variants follow Create's Drill, not intuition.** The casing is
  authored facing south, so each variant is the rotation carrying +Z onto the facing. Create's
  Mechanical Drill is the same kind of block and fixes the convention: its model is authored
  pointing **up** (`facing=up` carries no rotation) and `facing=north` is `x: 90`, so `x: 90` carries
  +Y onto -Z, and the same rotation carries +Z onto +Y. South-authored, that makes `x: 90` the **up**
  variant and `x: 270` the **down** one. They were the wrong way round, so an Extruder placed
  pointing up faced down.
- **The barrel's authored position is its innermost one.** `lead()` returns nought to `LEAD` and
  never less, so the travel only ever pushes it further out from where the partial is drawn. The
  item model therefore inlines those boxes exactly as they stand: it is already showing the barrel
  as far in as the machine can pull it. Winding it back further to tidy up the icon was tried and
  reverted, because a shorter barrel is a pose the machine cannot reach, and an item that shows one
  is lying about the block.
- **An item's icon is drawn from its model's own `display.gui` rotation**, not from a camera chosen
  here, so a picture of an item and the item in an inventory cannot disagree. `minecraft:block/block`
  hands down `[30, 225, 0]`, which shows a block's **north and east** faces. This casing is authored
  facing **south**, so the Extruder's inventory icon was the back of the machine: a plain plate with
  a shaft socket and no barrel in sight. The item model overrides it to `[30, 315, 0]`.
- **Models are generated, not hand-authored.** `tools/generate_models.py` drops every face that is
  buried inside another box, which is the only practical defence against z-fighting across a dozen
  boxes — the shimmering checkerboard only shows up once the block is in the world. Do not hand-edit
  a model under `models/block/terraform_extruder/`.
- **The casing is a box with holes in it**, not a stack of slabs. A hole is the one thing a block
  model cannot express, so every wall with something to look through is built by `frame()` in
  `generate_models.py` — four boxes around a gap. Two holes, each the only one of its shape: a bore
  through the back plate with the shaft stub and gear in it, and a wider bore through the front wall
  the chuck spins on and the barrel runs through.
- **The mud tank has no lid, deliberately.** An earlier version cut a slot in one, and the four frame
  boxes each sampled a different crop of the same banded texture, which read as clutter from every
  angle. An open basin is both cleaner and more Create — it is what the Basin and the Item Drain do.
- **Clearances around the chuck and barrel are fractions of a pixel, deliberately.** The chuck sits
  on the front wall and grips the barrel; landing on either plane exactly z-fights. Both drill
  collars sit beyond the chuck even at rest and the barrel only ever travels further out, so neither
  can be driven back through a hole it does not fit.
- **A spinning part is only as small as its corners.** A square turning about its centre sweeps a
  factor of root two further out than its flats, so its flats can sit well inside a hole while its
  corners punch out through the casing and back four times a turn — which is invisible in a still
  render and obvious the moment the shaft turns. `clears()` in `generate_models.py` asserts every
  rotating part against every static hole it turns in, and it runs before anything is written. This
  is why the **chuck is casing and does not turn**: a ring that size sweeps 6.9 from centre through
  a hole whose half-width is 4. It is also why the shaft stub is 4-in-6 — 2.83 against 3.0 — which
  is Create's own proportion and, on this evidence, not an accident.
- **Kinetic geometry is measured off Create, not guessed at.** `create:block/shaft.json` is **four**
  pixels square, from 6 to 10, and `axis_top` darkens exactly the four corner pixels of its end,
  which chamfers it into an **octagon**. So the stub is 4×4 and the bore it stands in is chamfered
  to an octagon a pixel outside it — `frame(..., chamfer=1)`. A six-pixel square stub does not line
  up with the shaft a player butts against it, and painting an octagon into the plate texture does
  not work: the corners of a ring drawn on an octagonal metric fall *inside* a square hole and are
  thrown away, leaving four slivers on the flats. Create's jar is in the Gradle cache; read it.
- **`check_models.py` finds z-fighting**, and it is the only thing that will: two faces on the same
  plane pointing the same way, overlapping. `generate_models.py` drops faces *buried* inside another
  box, which is a different fault — two boxes that merely butt up against each other leave both faces
  drawn. Direction is part of the test: back-to-back faces are culled by the renderer and never
  fight. The checker walks `models/block` recursively; it globbed only the top level for a while and
  so checked almost nothing.
- **Each model declares only the textures its boxes use.** `to_model` trims the table it is handed,
  so a texture cannot stay declared after the last face that drew it is gone — which is how the
  `cog`, `back` and `nozzle` PNGs stayed in the repo and in CI's diff after nothing referenced them.
  `check_models.py` fails on a declared-but-undrawn texture for the same reason.
- **Model UVs are clamped into 0..16.** The barrel reaches nine pixels outside the cell, and a face
  derived from coordinates outside it samples whatever is packed next door in the atlas — a fault
  that cannot be seen in a render and cannot be predicted from the model.
- `GAUGE` in `generate_models.py` and `GAUGE_START` in the renderer have to agree — that is what
  makes the mud drain back away from the chuck rather than sink or slide.
- **Two authoring conventions, on purpose.** the casing models are authored facing south with
  the shaft along X, which is the orientation Create authors its Deployer in, so the twelve-variant
  blockstate table is Create's table. `terraform_extruder/head.json` is authored pointing
  up because that is what Create's orientation transform expects. Each only has to agree with the
  thing that rotates it. `models/item/terraform_extruder.json` inlines both at rest, generated from
  the same sources so it cannot drift.
- **The shaft stub spins but does not advance; the barrel does both.** A string sliding through a
  chuck that stays put is what makes the machine read as a drill rather than a piston.
- **The spin is measured about the POSITIVE direction of the axis**, because that is what
  `getAngleForBe` returns and what Create's own `kineticRotationTransform` rotates about —
  `rotateCentered(angle, Direction.get(POSITIVE, axis))`. Our partials are swung onto the *facing*,
  which is the negative direction for north, west and down, so the angle has to be negated there or
  the machine turns backwards against the shaft driving it. Half of all placements looked wrong.
- **The goggle overlay is a Spout's, plus a status line.** `containedFluidTooltip` and nothing above
  it — that helper already draws its own header ("Fluid Container Info:"), so a title of ours would
  be a second header saying what the first one frames. A Deployer has a title because it has no tank
  to name it; Create's Drill, Press and Mixer add nothing at all beyond speed and stress. The status
  line shows only when something is wrong, because a working machine says so by turning. We return
  `true` where a Spout returns the helper's own boolean: its overlay is the tank and nothing else, so
  an absent tank means nothing to draw, whereas an Extruder that has just run dry is exactly when
  somebody goes looking at it.

  An earlier version also quoted the core sample's remaining size, the blocks-per-second and the last
  block printed. That is bookkeeping rather than anything a player acts on, and it buried the one
  line that matters. Both `lastPrinted` and `clientSampleSize` existed only to feed it, and
  `lastPrinted` was synced — a packet on almost every placement for a line nobody needed. Only
  `idleReason` crosses the wire now.
- **The interval between placements is a Mechanical Deployer's, to the tick.** Not approximated:
  `cycleTicks()` is Create's own arithmetic, read out of `DeployerBlockEntity`. Its `timer` counts
  down by `clamp(|rpm| * 2, 8, 512)` a tick, and one placement is three phases of it — 1000
  extending, `activate()`, 1000 retracting, 500 waiting. Each phase is ceilinged **separately**,
  because each is a separate countdown, and summing first comes out a tick short at some speeds. The
  clamp is what gives the curve its shape: below 4 RPM and above 256 speed stops buying anything,
  which is why there is no hand-placed floor. Our own interval-at-a-reference-RPM formula ran about
  twice a Deployer's rate across the whole band. `cycleScale` multiplies it; the interval itself is
  not configurable, because it is Create's. `theIntervalMatchesADeployer` asserts the hard numbers —
  16 RPM is 80 ticks, 64 is 20, 256 is 5 — because a monotonic "faster is shorter" check would pass
  for any formula at all.
- **A barren core sample backs off rather than waiting flat.** Y is never displaced, so whether a
  signature finds rock is decided by the height the machine sits at: underground nearly all of them
  land, at the surface most sample sky and come back empty. A flat `barrenRetryTicks` wait meant a
  machine placed up top could stand silent through several ten-second waits before its first block.
  The first retry is half a second and doubles from there; `barrenRetryTicks` is now the ceiling.
- **The bench rig is pinned to 64 RPM** (`RIG_RPM`). At the creative motor's default 16 RPM one
  placement is eighty ticks, and every delay in the gametest file was written when it was forty.
- **The barrel's travel is a lead, not a stroke.** A pixel and a half, derived from the angle of turn
  and nothing else: the barrel is threaded, so turning it walks it. That is what keeps it in step
  with the spin for free, makes it speed up and stop with the shaft, and means it never has to be
  suppressed when the machine is not printing — which an eighteen-pixel cycle-timed stroke did, and
  which is why `ExtruderIdleReason` used to gate the animation. It is still synced, but now only for
  the goggle overlay. At rest the cutting head stops one pixel short of the block being printed into,
  so the lead is exactly enough to bite it once a turn.
- **Both drill collars sit beyond the chuck even at rest**, and the barrel only ever travels further
  out, so neither can be driven back through a hole it does not fit. Clearances around the chuck and
  barrel are fractions of a pixel rather than whole ones, because two faces landing on the same plane
  z-fight.
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
