package com.createterraform.strata;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.createterraform.TerraformConfig;

import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

/**
 * Runs Minecraft's own chunk generation against throwaway chunks, so an Extruder can read real,
 * finished worldgen out of a place the world has never actually generated.
 *
 * <h2>Why a chunk, and not a block</h2>
 * Terrain is noise and can be sampled anywhere; <em>features</em> cannot. An ore vein, a geode, a
 * dirt patch is rolled once per chunk during the FEATURES step and then written across the 3x3
 * neighbourhood around it, and a blob that starts in one chunk finishes in the next. Sample a
 * density function at a coordinate and you get stone or air, cheaply and correctly, and no ore at
 * all. Decorate a chunk with no neighbours around it and every vein that crosses a border is sheared
 * off at the seam.
 *
 * <p>So this does what the server does. It builds a grid of {@link ProtoChunk}s, takes all of them
 * as far as CARVERS, and then runs FEATURES on the inner ones — with their neighbours present, so
 * veins have somewhere to spill and the borders come out whole. What comes back is not an
 * approximation of worldgen; it <em>is</em> worldgen: the same {@code ChunkGenerator}, the same
 * {@code RandomState}, the same world seed, the same globally sorted feature list, decorated by
 * {@code ChunkGenerator.applyBiomeDecoration} itself. Modded ores that register a placed feature are
 * included for free, at their own rarity and in their own depth band, because nothing here knows or
 * cares what a feature is.
 *
 * <h2>What is deliberately not run</h2>
 * The pipeline stops at FEATURES. INITIALIZE_LIGHT, LIGHT, SPAWN and FULL are never reached, so
 * there is no light propagation, no mob spawning, no promotion to a {@code LevelChunk} and no entry
 * in any chunk map. Structures are skipped too — STRUCTURE_STARTS and STRUCTURE_REFERENCES never
 * run, so {@code applyBiomeDecoration} finds no starts and places none. Block and fluid ticks
 * scheduled during generation land in the ProtoChunk's own tick containers and are thrown away with
 * it: nothing here is ever ticked, and no fluid ever flows.
 *
 * <p>These chunks exist only in the caller's hands. They are never given to the level, never written
 * to disk, and never seen by a player except as the individual blocks an Extruder copies out.
 *
 * <h2>Threading</h2>
 * Everything here is safe to run on a worker thread, and the stationary Extruder does exactly that.
 * The level is only read from — generator, {@code RandomState} (whose caches are concurrent maps),
 * registries, seed, height — and every chunk that gets written to was created inside the call. What
 * is <em>not</em> safe is sharing a scaffold map between threads; each caller brings its own.
 */
public final class VirtualStrata {

	/**
	 * How far out the holder grid reaches beyond the chunks being generated, asked of vanilla rather
	 * than assumed.
	 *
	 * <p>A {@code WorldGenRegion} resolves a neighbour by chessboard distance against its step's
	 * dependency rings, and {@code StaticCache2D} throws on a lookup outside its bounds — so the grid
	 * has to cover the radius the region might <em>ask</em> about, not the radius it will read. That
	 * number is a property of {@code ChunkPyramid}, so it is read off the steps this class runs
	 * instead of being written down here. (Today it is 8, from the structure-starts requirement every
	 * step declares. It is not this mod's business to know that.)
	 *
	 * <p>Borrowed, with thanks, from Orbital Regen's {@code haloChunkRadiusForStep} — see
	 * {@code docs/virtual-chunks.md}.
	 */
	private static final int HOLDER_MARGIN = holderMargin();

	private static int holderMargin() {
		int margin = 0;
		for (ChunkStatus status : new ChunkStatus[] { ChunkStatus.BIOMES, ChunkStatus.NOISE, ChunkStatus.SURFACE,
			ChunkStatus.CARVERS, ChunkStatus.FEATURES }) {
			ChunkStep step = ChunkPyramid.GENERATION_PYRAMID.getStepTo(status);
			margin = Math.max(margin, step.directDependencies()
				.getRadius());
			margin = Math.max(margin, step.blockStateWriteRadius());
		}
		return margin;
	}

	private VirtualStrata() {
		throw new AssertionError("No instances");
	}

	/**
	 * Brings the neighbourhood of {@code (chunkX, chunkZ)} up to the state the FEATURES step needs,
	 * reusing anything {@code scaffolds} already holds, and returns the holder grid it sits in.
	 *
	 * <p>Two rings, because the stages do not all reach the same distance:
	 * <ul>
	 * <li>{@code radius} gets terrain and surface rules — that is the neighbourhood a chunk can be
	 * decorated in the middle of.
	 * <li>{@code radius + 1} gets biomes and nothing else. {@code SurfaceSystem} asks
	 * {@code BiomeManager} for the biome at every column it paints, and that lookup is deliberately
	 * fuzzy — it jitters the sample by up to a block, so painting the outer edge of the outer ring
	 * asks about the chunk beyond it. Biomes are much the cheaper half of generating a chunk, and one
	 * extra ring of them is what keeps that question answerable.
	 * </ul>
	 *
	 * <p>How far each chunk has got is {@link ProtoChunk#getPersistedStatus()} — the field vanilla
	 * already keeps for exactly this — so a chunk that was scaffolded as somebody's biome ring is
	 * picked up and taken further when it becomes somebody else's middle.
	 *
	 * <h4>Why the grid is built before the first generator call</h4>
	 * Every stage has to be handed a {@code StructureManager} bound to a {@code WorldGenRegion} over
	 * this grid, and not the level's own. The level's own resolves {@code getChunk} against the real
	 * chunk source: {@code NoiseBasedChunkGenerator} builds a {@code Beardifier} while filling biomes,
	 * the Beardifier asks the structure manager what structures are in the chunk, and with the level's
	 * manager that becomes {@code ServerLevel.getChunk} on a chunk a million blocks away — real
	 * generation, scheduled onto the server thread, joined from a worker while the server thread is
	 * itself blocked waiting for that worker. A deadlock, not a slowdown. Bound to the region instead,
	 * the same question is answered by a ProtoChunk that has no structure references, which is the
	 * truthful answer: this mod generates no structures.
	 *
	 * <h4>How the carvers step is afforded</h4>
	 * {@code NoiseBasedChunkGenerator.applyCarvers} walks a 17x17 neighbourhood of
	 * {@code level.getChunk}, and every one of those 289 chunks must exist or the region throws.
	 * Generating 289 chunks to carve one would not be worth it at any cache size.
	 *
	 * <p>It does not have to. All the carver wants from a neighbour is
	 * {@link net.minecraft.world.level.chunk.ChunkAccess#carverBiome}, which uses the chunk purely as
	 * a memo slot for a supplier that computes the answer from the biome source and the chunk's
	 * coordinates. It never reads a block. So the outer halo is filled with bare ProtoChunks that
	 * have had nothing done to them at all — enough to be found, and nothing more — and they are
	 * built per grid and thrown away with it rather than going anywhere near the cache.
	 *
	 * <p>They stop at {@code biomeRadius} and not before: a bare chunk would answer a biome lookup
	 * with whatever a fresh {@code PalettedContainer} defaults to, and the surface builder does look
	 * that far out. Silently wrong rock at a chunk seam is worse than a thrown exception, so the ring
	 * that might be asked about biomes gets real ones.
	 *
	 * <h4>Parallelism</h4>
	 * The two expensive stages are joined all at once rather than one chunk at a time: vanilla runs
	 * both on the background executor, so waiting for twenty-five parallel noise fills costs rather
	 * less than waiting for twenty-five in a row.
	 */
	public static StaticCache2D<GenerationChunkHolder> scaffold(ServerLevel level, int chunkX, int chunkZ, int radius,
		Map<Long, ProtoChunk> scaffolds) {
		ChunkGenerator generator = level.getChunkSource()
			.getGenerator();
		RandomState randomState = level.getChunkSource()
			.randomState();
		Registry<Biome> biomes = level.registryAccess()
			.registryOrThrow(Registries.BIOME);

		int biomeRadius = radius + 1;
		for (int dx = -biomeRadius; dx <= biomeRadius; dx++)
			for (int dz = -biomeRadius; dz <= biomeRadius; dz++)
				scaffolds.computeIfAbsent(ChunkPos.asLong(chunkX + dx, chunkZ + dz),
					key -> blank(level, biomes, ChunkPos.getX(key), ChunkPos.getZ(key)));

		// Bare chunks for the carver halo. Local to this call and dropped with it: there are a few
		// hundred of them and they carry nothing, so putting them in a caller's cache would evict
		// everything worth keeping to store nothing worth having.
		Map<Long, ProtoChunk> halo = new HashMap<>();
		StaticCache2D<GenerationChunkHolder> grid =
			StaticCache2D.create(chunkX, chunkZ, HOLDER_MARGIN + radius, (x, z) -> {
				long key = ChunkPos.asLong(x, z);
				ProtoChunk chunk = scaffolds.get(key);
				if (chunk == null)
					chunk = halo.computeIfAbsent(key, absent -> blank(level, biomes, x, z));
				return new VirtualChunkHolder(new ChunkPos(x, z), chunk);
			});

		ChunkStep biomesStep = ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.BIOMES);
		ChunkStep noiseStep = ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.NOISE);
		ChunkStep surfaceStep = ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.SURFACE);
		ChunkStep carversStep = ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.CARVERS);

		List<ProtoChunk> needBiomes = ring(scaffolds, chunkX, chunkZ, biomeRadius, ChunkStatus.BIOMES);
		join(needBiomes, chunk -> {
			WorldGenRegion region = new WorldGenRegion(level, grid, biomesStep, chunk);
			return generator.createBiomes(randomState, Blender.empty(), structures(level, region), chunk);
		});
		advance(needBiomes, ChunkStatus.BIOMES);

		List<ProtoChunk> needNoise = ring(scaffolds, chunkX, chunkZ, radius, ChunkStatus.NOISE);
		join(needNoise, chunk -> {
			WorldGenRegion region = new WorldGenRegion(level, grid, noiseStep, chunk);
			return generator.fillFromNoise(Blender.empty(), randomState, structures(level, region), chunk);
		});
		advance(needNoise, ChunkStatus.NOISE);

		// Surface rules and carvers write only into the chunk they are centred on, so each gets a
		// region of its own over the same grid.
		List<ProtoChunk> needSurface = ring(scaffolds, chunkX, chunkZ, radius, ChunkStatus.SURFACE);
		for (ProtoChunk chunk : needSurface) {
			WorldGenRegion region = new WorldGenRegion(level, grid, surfaceStep, chunk);
			generator.buildSurface(region, structures(level, region), randomState, chunk);
		}
		advance(needSurface, ChunkStatus.SURFACE);

		List<ProtoChunk> needCarvers = ring(scaffolds, chunkX, chunkZ, radius, ChunkStatus.CARVERS);
		for (ProtoChunk chunk : needCarvers) {
			WorldGenRegion region = new WorldGenRegion(level, grid, carversStep, chunk);
			generator.applyCarvers(region, level.getSeed(), randomState, level.getBiomeManager(),
				structures(level, region), chunk, GenerationStep.Carving.AIR);
		}
		advance(needCarvers, ChunkStatus.CARVERS);

		return grid;
	}

	/** A chunk with nothing done to it. */
	private static ProtoChunk blank(ServerLevel level, Registry<Biome> biomes, int chunkX, int chunkZ) {
		return new ProtoChunk(new ChunkPos(chunkX, chunkZ), UpgradeData.EMPTY, level, biomes, null);
	}

	/** The chunks within {@code radius} that have not yet reached {@code status}. */
	private static List<ProtoChunk> ring(Map<Long, ProtoChunk> scaffolds, int chunkX, int chunkZ, int radius,
		ChunkStatus status) {
		List<ProtoChunk> behind = new ArrayList<>();
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				ProtoChunk chunk = scaffolds.get(ChunkPos.asLong(chunkX + dx, chunkZ + dz));
				if (chunk != null && chunk.getPersistedStatus()
					.isBefore(status))
					behind.add(chunk);
			}
		}
		return behind;
	}

	private static void advance(List<ProtoChunk> chunks, ChunkStatus status) {
		for (ProtoChunk chunk : chunks)
			chunk.setPersistedStatus(status);
	}

	/**
	 * A structure manager that answers out of the virtual grid instead of out of the world. Never
	 * pass {@code level.structureManager()} to a generator stage — see {@link #scaffold}.
	 */
	private static StructureManager structures(ServerLevel level, WorldGenRegion region) {
		return level.structureManager()
			.forWorldGenRegion(region);
	}

	/**
	 * Runs the FEATURES step on one chunk. Its whole 3x3 must already be scaffolded into {@code grid}
	 * — that is the entire point of the exercise — and it must not have been decorated before, or it
	 * grows two of every vein.
	 */
	public static void decorate(ServerLevel level, StaticCache2D<GenerationChunkHolder> grid, ProtoChunk chunk) {
		Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.MOTION_BLOCKING,
			Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.WORLD_SURFACE));
		ChunkStep featuresStep = ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FEATURES);
		WorldGenRegion region = new WorldGenRegion(level, grid, featuresStep, chunk);
		level.getChunkSource()
			.getGenerator()
			.applyBiomeDecoration(region, chunk, structures(level, region));
		chunk.setPersistedStatus(ChunkStatus.FEATURES);
	}

	/**
	 * Where a signature reads from: the machine's own coordinates, displaced.
	 *
	 * <p>Chunk-aligned on purpose — see {@link StrataSignature#offsetX}. A displacement that was not a
	 * whole number of chunks would put a machine's footprint across two virtual chunks and double
	 * everything this class costs.
	 */
	public static BlockPos displace(ServerLevel level, long signature, BlockPos target) {
		int range = TerraformConfig.signatureRange();
		long seed = level.getSeed();
		return new BlockPos(clampToWorld(target.getX() + StrataSignature.offsetX(seed, signature, range)),
			target.getY(), clampToWorld(target.getZ() + StrataSignature.offsetZ(seed, signature, range)));
	}

	/** Well inside the ±30,000,000 the world allows, so a displacement can never walk off the edge. */
	private static int clampToWorld(int coordinate) {
		return net.minecraft.util.Mth.clamp(coordinate, -29_000_000, 29_000_000);
	}

	/** A deterministic shuffle, so a batch is organic but a seed still reproduces it exactly. */
	public static void shuffle(List<BlockState> states, long signature) {
		RandomSource random = RandomSource.create(signature);
		for (int i = states.size(); i > 1; i--) {
			int j = random.nextInt(i);
			BlockState swap = states.get(i - 1);
			states.set(i - 1, states.get(j));
			states.set(j, swap);
		}
	}

	private static void join(List<ProtoChunk> chunks, Function<ProtoChunk, CompletableFuture<?>> stage) {
		if (chunks.isEmpty())
			return;
		CompletableFuture<?>[] pending = new CompletableFuture<?>[chunks.size()];
		for (int i = 0; i < chunks.size(); i++)
			pending[i] = stage.apply(chunks.get(i));
		CompletableFuture.allOf(pending)
			.join();
	}

	/** The executor vanilla generates chunks on. Named here so every caller uses the same one. */
	public static java.util.concurrent.Executor worker() {
		return Util.backgroundExecutor();
	}
}
