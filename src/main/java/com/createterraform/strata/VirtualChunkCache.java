package com.createterraform.strata;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.createterraform.CreateTerraform;
import com.createterraform.TerraformConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.jetbrains.annotations.Nullable;

/**
 * The virtual chunks a <em>moving</em> contraption reads from, kept alive as long as they are useful
 * and dropped when they are not.
 *
 * <p>A contraption is the case the cache was built for. Its Strata Signature is fixed between Seismic
 * Shifts, so as the machine travels it walks through the displaced world in a straight line: the
 * chunk it is reading now is very often the chunk it was reading a moment ago, and the chunk it
 * moves into is one its neighbours have already been scaffolded for. A Rope Pulley descending a
 * shaft never leaves the column it started in at all, so the whole descent is served by the grid
 * built for its first block.
 *
 * <p>That is why the two halves are cached separately. Scaffolding — {@link VirtualStrata#scaffold}
 * through CARVERS — is what costs, and a chunk scaffolded as somebody's neighbour is the same chunk
 * the next grid needs in the middle, so moving one chunk sideways costs three new scaffolds rather
 * than nine. Decoration is then run once per chunk, ever, and remembered.
 *
 * <p><b>Stationary Extruders do not use this.</b> They reroll their signature every batch, which
 * means every grid would be a miss, and they read a whole Y-slice rather than one block. They build
 * their own grid on a worker thread instead — see
 * {@link com.createterraform.extruder.TerraformExtruderBlockEntity}.
 *
 * <p>Server thread only. The maps are plain and nothing here is safe to call from anywhere else;
 * {@link VirtualStrata} is the part that is.
 */
public final class VirtualChunkCache {

	private static final Map<ResourceKey<Level>, VirtualChunkCache> CACHES = new HashMap<>();

	/**
	 * Workers a single level may have cutting chunks at once. Two, so a pair of contraptions
	 * travelling in different directions both get covered, and no more: a prefetch that has not
	 * landed by the time the machine arrives costs a stall it would have had anyway, while an
	 * unbounded queue of them costs the server every thread it has.
	 */
	private static final int MAX_PREFETCHES_IN_FLIGHT = 2;

	private final ServerLevel level;
	/** Scaffolded chunks by position, least-recently-used evicted first. */
	private final Map<Long, ProtoChunk> scaffolds;

	/** Grids being generated on a worker, by the chunk they are centred on. Server thread only. */
	private final Map<Long, CompletableFuture<Map<Long, ProtoChunk>>> inFlight = new HashMap<>();

	private long gridsBuilt;
	private long gridsPrefetched;

	private VirtualChunkCache(ServerLevel level) {
		this.level = level;
		this.scaffolds = lru(TerraformConfig.virtualChunkCacheSize());
	}

	/**
	 * The cache for a level, built on first use.
	 *
	 * <p>The identity check is not paranoia: an integrated server torn down and started again on a
	 * new world reuses {@code minecraft:overworld}, and a cache still holding the old level would
	 * answer with the old world's rock.
	 */
	public static VirtualChunkCache of(ServerLevel level) {
		VirtualChunkCache cache = CACHES.get(level.dimension());
		if (cache == null || cache.level != level) {
			cache = new VirtualChunkCache(level);
			CACHES.put(level.dimension(), cache);
		}
		return cache;
	}

	/** An unloaded level must stop paying for its cached chunks. */
	public static void onLevelUnload(LevelEvent.Unload event) {
		if (event.getLevel() instanceof ServerLevel unloaded)
			CACHES.remove(unloaded.dimension());
	}

	/**
	 * The block that belongs at {@code target} under {@code signature}, ready to place.
	 *
	 * <p>Always returns something: air where the strata say nothing, air where they say something
	 * this mod will not print, and air if the chunk could not be generated at all.
	 */
	public BlockState sample(long signature, BlockPos target) {
		collectPrefetched();
		if (level.isOutsideBuildHeight(target.getY()))
			return Blocks.AIR.defaultBlockState();

		BlockPos probe = VirtualStrata.displace(level, signature, target);
		ProtoChunk chunk = decorated(probe.getX() >> 4, probe.getZ() >> 4);
		if (chunk == null)
			return Blocks.AIR.defaultBlockState();
		return PlacementRules.sanitise(chunk.getBlockState(probe));
	}

	/** Chunks currently scaffolded. Diagnostic; the eviction test reads it. */
	public int cachedChunks() {
		return scaffolds.size();
	}

	/**
	 * Grids built <em>on the server thread</em> since this cache existed — that is, times a machine
	 * had to stop and wait. The number prefetching exists to hold down, and what the tests assert on.
	 */
	public long getGridsBuilt() {
		return gridsBuilt;
	}

	/** Grids built on a worker and collected without anybody waiting. Diagnostic. */
	public long getGridsPrefetched() {
		return gridsPrefetched;
	}

	/** Prefetches currently running. Diagnostic; the tests poll it. */
	public int getPrefetchesInFlight() {
		return inFlight.size();
	}

	// --- prefetching ---------------------------------------------------------------------

	/**
	 * Starts generating the chunk a contraption is about to travel into, on a worker thread.
	 *
	 * <p>A moving machine's misses are predictable in a way a stationary machine's are not: its
	 * signature is fixed between Seismic Shifts, so it walks the displaced world in a straight line
	 * and the chunk it will need next is the one its motion vector points at. Projecting
	 * {@link #PREFETCH_LOOKAHEAD} blocks along that vector and generating <em>that</em> chunk ahead of
	 * time turns the one unavoidable stall in the contraption path — a horizontal crossing into
	 * unvisited ground — into work that finishes before anybody asks for it.
	 *
	 * <p>Vertical travel projects onto the same chunk it is already in and prefetches nothing, which
	 * is correct: a Rope Pulley never leaves its column. A stalled contraption has no motion and
	 * prefetches nothing either.
	 *
	 * <h4>Why the worker gets its own chunks</h4>
	 * It would be cheaper to let a prefetch reuse the scaffolds already in this cache — six of the
	 * nine, usually. It would also be a data race: decorating a chunk writes features into its
	 * neighbours, and {@code PalettedContainer} is explicitly not safe for a write on one thread
	 * against a read on another. So a prefetch scaffolds into a map of its own and the results are
	 * merged in on the server thread by {@link #collectPrefetched}. Paying for three redundant chunks
	 * on a worker is the cheap side of that trade.
	 */
	public void prefetch(long signature, BlockPos target, Vec3 motion) {
		collectPrefetched();

		int lookahead = TerraformConfig.prefetchLookahead();
		if (lookahead <= 0)
			return;
		double speed = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
		if (speed < 1.0E-4)
			return;

		BlockPos ahead = target.offset(Mth.floor(motion.x / speed * lookahead), 0,
			Mth.floor(motion.z / speed * lookahead));
		BlockPos probe = VirtualStrata.displace(level, signature, ahead);

		start(probe.getX() >> 4, probe.getZ() >> 4);
	}

	/**
	 * Starts generating the chunk a machine is standing in, rather than one it is heading for.
	 *
	 * <p>For the moment a contraption assembles, which is the one miss {@link #prefetch} cannot
	 * predict: there is no motion vector yet and nothing has been read, so the first block printed
	 * would otherwise be the one that pays for the first grid. Called from
	 * {@code ExtruderMovementBehaviour.startMoving}, a tick or more before the first placement, which
	 * on most generators is enough for the worker to have finished.
	 *
	 * <p>Best-effort, like every prefetch: if it has not landed by the time the machine asks, the
	 * synchronous path runs as it always did and this cost a worker thread a grid nobody used.
	 */
	public void warm(long signature, BlockPos target) {
		collectPrefetched();
		if (TerraformConfig.prefetchLookahead() <= 0)
			return;
		BlockPos probe = VirtualStrata.displace(level, signature, target);
		start(probe.getX() >> 4, probe.getZ() >> 4);
	}

	/** Dispatches one grid onto a worker, unless it is already here or already being generated. */
	private void start(int chunkX, int chunkZ) {
		long key = ChunkPos.asLong(chunkX, chunkZ);
		if (inFlight.size() >= MAX_PREFETCHES_IN_FLIGHT || inFlight.containsKey(key))
			return;
		ProtoChunk existing = scaffolds.get(key);
		if (existing != null && existing.getPersistedStatus()
			.isOrAfter(ChunkStatus.FEATURES))
			return;

		ServerLevel generating = level;
		inFlight.put(key, CompletableFuture.supplyAsync(() -> {
			Map<Long, ProtoChunk> generated = new HashMap<>();
			StaticCache2D<GenerationChunkHolder> grid =
				VirtualStrata.scaffold(generating, chunkX, chunkZ, 1, generated);
			ProtoChunk centre = generated.get(key);
			if (centre != null)
				VirtualStrata.decorate(generating, grid, centre);
			return generated;
		}, VirtualStrata.worker())
			.exceptionally(failure -> {
				CreateTerraform.LOGGER.error("Could not prefetch the virtual chunk at {}, {}", chunkX, chunkZ,
					failure);
				return Map.of();
			}));
	}

	/**
	 * Merges finished prefetches into the cache, on the server thread.
	 *
	 * <p>A chunk already here is kept unless the incoming one has got further, so a prefetch can
	 * never demote a decorated chunk back to a scaffold — and the redundant copies a worker made of
	 * chunks this cache already had are simply dropped.
	 */
	private void collectPrefetched() {
		if (inFlight.isEmpty())
			return;
		Iterator<Map.Entry<Long, CompletableFuture<Map<Long, ProtoChunk>>>> entries = inFlight.entrySet()
			.iterator();
		while (entries.hasNext()) {
			Map.Entry<Long, CompletableFuture<Map<Long, ProtoChunk>>> entry = entries.next();
			if (!entry.getValue()
				.isDone())
				continue;
			entries.remove();
			for (Map.Entry<Long, ProtoChunk> generated : entry.getValue()
				.join()
				.entrySet()) {
				ProtoChunk here = scaffolds.get(generated.getKey());
				if (here == null || here.getPersistedStatus()
					.isBefore(generated.getValue()
						.getPersistedStatus()))
					scaffolds.put(generated.getKey(), generated.getValue());
			}
			gridsPrefetched++;
		}
	}

	@Nullable
	private ProtoChunk decorated(int chunkX, int chunkZ) {
		long key = ChunkPos.asLong(chunkX, chunkZ);
		ProtoChunk chunk = scaffolds.get(key);
		// How far a chunk has got is the ProtoChunk's own status, which is also what stops one being
		// decorated twice and growing two of every vein.
		if (chunk != null && chunk.getPersistedStatus()
			.isOrAfter(ChunkStatus.FEATURES))
			return chunk;

		// Wrapped, because a feature that throws inside vanilla's decoration loop comes out as a
		// ReportedException carrying a crash report -- and raised from inside a contraption tick,
		// that takes the server down. A modded feature that cannot cope with a world it does not
		// recognise should cost this machine one chunk of rock, not the save.
		try {
			StaticCache2D<GenerationChunkHolder> grid = VirtualStrata.scaffold(level, chunkX, chunkZ, 1, scaffolds);
			chunk = scaffolds.get(key);
			if (chunk == null)
				return null;
			if (chunk.getPersistedStatus()
				.isBefore(ChunkStatus.FEATURES))
				VirtualStrata.decorate(level, grid, chunk);
			gridsBuilt++;
			return chunk;
		} catch (Throwable failure) {
			CreateTerraform.LOGGER.error("Could not generate the virtual chunk at {}, {}; "
				+ "an Extruder reading it will print nothing", chunkX, chunkZ, failure);
			return null;
		}
	}

	/**
	 * Access-ordered and self-evicting: the chunk that goes is the one no machine has read for
	 * longest, which for a contraption is the ground it has left behind. This is the leak guard —
	 * without a bound, a machine printing its way across the world would hold every chunk it had ever
	 * touched.
	 */
	private static Map<Long, ProtoChunk> lru(int capacity) {
		return new LinkedHashMap<>(16, 0.75F, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<Long, ProtoChunk> eldest) {
				return size() > capacity;
			}
		};
	}
}
