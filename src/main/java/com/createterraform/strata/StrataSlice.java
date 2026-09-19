package com.createterraform.strata;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ProtoChunk;

/**
 * One horizontal slice of a virtual world, cut at a single Y and emptied of everything an Extruder
 * would not print — the pool a stationary machine works its way through.
 *
 * <h2>Why a slice and not a block</h2>
 * A stationary Extruder prints into one coordinate for ever, so with a fixed signature it would
 * print the same block until the world ended: it has to reroll. But a reroll under
 * {@link VirtualStrata} means a whole new chunk grid, and a grid costs about what generating nine
 * chunks costs. Rerolling per placement, several times a second, is not a performance problem so
 * much as a stopped server.
 *
 * <p>So the cost is paid once and spent slowly. One signature, one grid, and then <em>every block at
 * the machine's own altitude across the whole 48x48 footprint</em> — 2,304 columns — is read out,
 * filtered, shuffled and handed to the machine as a queue. The machine pops one per cycle. A batch
 * is thousands of placements, which is minutes to hours of running, and the next one is generated on
 * a worker thread long before the current one runs out.
 *
 * <p>What comes out is exactly the ore distribution that Y and those biomes really have, because the
 * whole slice was decorated rather than sampled: read 2,304 columns of real generated chunk and the
 * proportion of diamond in the pool <em>is</em> the proportion of diamond at that depth, for vanilla
 * ores and modded ones alike, with no rarity table anywhere in this mod.
 *
 * <h2>The footprint is decorated, not just scaffolded</h2>
 * All nine chunks of the 3x3 have their FEATURES step run, not only the middle one, or eight ninths
 * of the pool would be ore-free rock and every ore in the game would come out nine times rarer than
 * it should. Decorating a chunk needs its own 3x3 present, so the scaffold is 5x5 and the decorated
 * area is the 3x3 inside it. Twenty-five chunks of terrain for a pool of a couple of thousand blocks
 * is a good trade precisely because it is paid once.
 *
 * @param signature the Strata Signature this slice was cut from
 * @param y         the altitude it was cut at; a machine whose target moved must cut a new one
 * @param states    solid, printable block states in the order they should be spent
 */
public record StrataSlice(long signature, int y, List<BlockState> states) {

	/** Chunks either side of the middle that get decorated. 1 means a 3x3, so 48x48 columns. */
	public static final int DECORATED_RADIUS = 1;

	/** Chunks either side that get scaffolded: one more, because decorating needs its own 3x3. */
	private static final int SCAFFOLD_RADIUS = DECORATED_RADIUS + 1;

	/**
	 * Generates the grid, cuts the slice and filters it. <b>Safe on a worker thread</b>, and meant
	 * for one — see {@link VirtualStrata}. Nothing here touches the level except to read from it, and
	 * every chunk written to was created inside this call and is dropped when it returns.
	 */
	public static StrataSlice generate(ServerLevel level, long signature, BlockPos target) {
		BlockPos probe = VirtualStrata.displace(level, signature, target);
		int y = probe.getY();
		if (level.isOutsideBuildHeight(y))
			return new StrataSlice(signature, target.getY(), List.of());

		int centreX = probe.getX() >> 4;
		int centreZ = probe.getZ() >> 4;
		Map<Long, ProtoChunk> scaffolds = new HashMap<>();
		StaticCache2D<GenerationChunkHolder> grid =
			VirtualStrata.scaffold(level, centreX, centreZ, SCAFFOLD_RADIUS, scaffolds);

		List<BlockState> states = new ArrayList<>();
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int dx = -DECORATED_RADIUS; dx <= DECORATED_RADIUS; dx++) {
			for (int dz = -DECORATED_RADIUS; dz <= DECORATED_RADIUS; dz++) {
				ProtoChunk chunk = scaffolds.get(ChunkPos.asLong(centreX + dx, centreZ + dz));
				if (chunk == null)
					continue;
				VirtualStrata.decorate(level, grid, chunk);
				collect(chunk, y, cursor, states);
			}
		}

		VirtualStrata.shuffle(states, signature);
		return new StrataSlice(signature, target.getY(), states);
	}

	/**
	 * Reads one chunk's worth of the slice.
	 *
	 * <p>{@link PlacementRules#sanitise} is the filter, and it is the same one the contraption path
	 * uses: air stays out, fluids come back as air and stay out, block entities and bedrock likewise.
	 * Filtering here rather than at placement time is what makes the queue a queue of <em>work</em> —
	 * a machine popping from it always has something to print, and a cycle is never spent on a
	 * coordinate that turned out to be sky.
	 */
	private static void collect(ProtoChunk chunk, int y, BlockPos.MutableBlockPos cursor, List<BlockState> states) {
		int originX = chunk.getPos()
			.getMinBlockX();
		int originZ = chunk.getPos()
			.getMinBlockZ();
		for (int x = 0; x < 16; x++) {
			for (int z = 0; z < 16; z++) {
				cursor.set(originX + x, y, originZ + z);
				BlockState state = PlacementRules.sanitise(chunk.getBlockState(cursor));
				if (!state.isAir())
					states.add(state);
			}
		}
	}

	public boolean isEmpty() {
		return states.isEmpty();
	}
}
