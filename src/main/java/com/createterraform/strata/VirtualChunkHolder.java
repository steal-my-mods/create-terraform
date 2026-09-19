package com.createterraform.strata;

import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jetbrains.annotations.Nullable;

/**
 * One cell of the 3x3 grid a {@link VirtualChunkCache} hands to a {@code WorldGenRegion}.
 *
 * <p>Create's generation pipeline reaches its neighbours through {@code StaticCache2D<GenerationChunkHolder>},
 * and a real holder is a {@code ChunkHolder} owned by the server's {@code ChunkMap} — a ticket level,
 * a future per status, a generation task, a place in the save queue. None of that exists here: these
 * chunks are scratch, they are generated to completion in one synchronous pass, and they are never
 * saved, lit, ticked or sent to anybody.
 *
 * <p>So the holder is a box with a chunk in it. Every {@code getChunkIfPresent...} answers with that
 * chunk whatever status is asked for, which is sound precisely because the cache never publishes a
 * grid until every chunk in it has been taken as far as it is going to go.
 *
 * <p>Holders outside the 3x3 hold nothing. The region asks for them because every generation step
 * declares a structure-starts requirement eight chunks out, and the grid is built to that radius so
 * that the lookup finds a holder rather than throwing — but structures are deliberately never
 * generated here, so the answer is always null and nothing ever follows it up.
 */
final class VirtualChunkHolder extends GenerationChunkHolder {

	@Nullable
	private final ProtoChunk chunk;

	VirtualChunkHolder(ChunkPos pos, @Nullable ProtoChunk chunk) {
		super(pos);
		this.chunk = chunk;
	}

	@Nullable
	@Override
	public ChunkAccess getChunkIfPresentUnchecked(ChunkStatus status) {
		return chunk;
	}

	@Nullable
	@Override
	public ChunkAccess getChunkIfPresent(ChunkStatus status) {
		return chunk;
	}

	@Nullable
	@Override
	public ChunkAccess getLatestChunk() {
		return chunk;
	}

	@Nullable
	@Override
	public ChunkStatus getPersistedStatus() {
		return chunk == null ? null : chunk.getPersistedStatus();
	}

	@Nullable
	@Override
	public ChunkStatus getLatestStatus() {
		return getPersistedStatus();
	}

	/**
	 * Zero is "as loaded as a chunk gets". Nothing in the generation path reads it — it exists
	 * because the base class is abstract — and a border level would only invite some future version
	 * of the pipeline to decide this chunk is too far away to write into.
	 */
	@Override
	public int getTicketLevel() {
		return 0;
	}

	@Override
	public int getQueueLevel() {
		return 0;
	}
}
