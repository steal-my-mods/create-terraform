# Third-party notices

Create: Terraform is MIT licensed; see [LICENSE](LICENSE). This file records the third-party code it
is built from, and the notices that code's licence requires be carried along with it.

## Create

Create is split-licensed: its **code is MIT**, and everything under its `assets/` is **All Rights
Reserved**. Only the MIT half is used here, and it is used heavily — this mod is written against
Create's own extension points rather than around them:

- `TerraformExtruderBlockEntity` extends Create's `KineticBlockEntity` and reads rotation the way
  every Create machine does: speed from the kinetic network, stress declared through
  `BlockStressValues`, fluid held in a `SmartFluidTankBehaviour`.
- `ExtruderMovementBehaviour` implements Create's `MovementBehaviour` and is driven by
  `AbstractContraptionEntity.tickActors`. The one-visit-per-global-position contract that the
  Seismic Shift is built on is Create's, taken from `visitNewPosition`.
- `TerraformExtruderBlock` extends `DirectionalKineticBlock` and hands its block entity over through
  `IBE`, so a wrench, a goggle and Create's own rotation propagation all work without special cases.

That is enough of Create's MIT code that its notice travels with this mod:

> MIT License
>
> Copyright (c) The Create Team / The Creators of Create
>
> Permission is hereby granted, free of charge, to any person obtaining a copy
> of this software and associated documentation files (the "Software"), to deal
> in the Software without restriction, including without limitation the rights
> to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
> copies of the Software, and to permit persons to whom the Software is
> furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all
> copies or substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
> IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
> FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
> AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
> LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
> OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
> SOFTWARE.

Two things this mod deliberately does **not** do, both of which would need more than the notice
above:

- **It does not redistribute Create.** Create is resolved without transitives and nothing from it is
  bundled into the jar — at runtime the loader uses the player's own copy. This is also why the mod
  declares Create as a required dependency rather than shipping it.
- **It does not use any Create asset.** Every texture and icon here is drawn by
  `tools/generate_textures.py` and `tools/generate_logo.py` from geometry described in those files.
  No model in `assets/createterraform/` parents or textures off a `create:` resource.

## Orbital Regen (Regenarate-Chunks)

[cluudryfarts/Regenarate-Chunks](https://github.com/cluudryfarts/Regenarate-Chunks) is MIT licensed
and solves an adjacent problem — regenerating a *real* chunk in place — with the same vanilla
machinery this mod drives. Two ideas were taken from reading it, and no code:

- **Ask vanilla how big the halo is.** Its `haloChunkRadiusForStep` derives the neighbourhood a
  generation step needs from `ChunkStep` itself rather than writing the number down.
  `VirtualStrata.holderMargin` does the same thing, differently.
- **The carver halo can be empty.** `NoiseBasedChunkGenerator.applyCarvers` demands a 17x17
  neighbourhood of chunks that exist, and Orbital Regen satisfies it with placeholder protos. That
  is what made the carvers step affordable here at all; what it reads from a neighbour is only
  `carverBiome`, which uses the chunk as a memo slot and never touches a block, so this mod's
  placeholders are bare rather than noise-filled.

Everything else here diverges, because the problems do: Orbital Regen writes into the live world and
therefore reuses the server's own `ChunkHolder`s, obtrudes results into their futures, and needs a
mixin on `GenerationChunkHolder.completeFuture` to stop vanilla throwing when it later finishes the
same step. This mod's chunks are at displaced coordinates that must never reach the chunk map, so it
subclasses `GenerationChunkHolder` into a box with a chunk in it and mixes into nothing.

## Minecraft and NeoForge

Not redistributed either, and not linked into the jar. Mappings are Parchment, used at build time
only.

The strata pipeline drives vanilla's own worldgen rather than reimplementing any of it: it calls
`ChunkGenerator.createBiomes`, `fillFromNoise`, `buildSurface`, `applyCarvers` and
`applyBiomeDecoration` on `ProtoChunk`s of its own, through a `WorldGenRegion` over a
`StaticCache2D` it builds itself. Nothing is copied, and nothing is approximated. See
[docs/virtual-chunks.md](docs/virtual-chunks.md).
