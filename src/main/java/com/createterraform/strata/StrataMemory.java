package com.createterraform.strata;

import com.createterraform.TerraformConfig;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.common.util.INBTSerializable;

/**
 * What a contraption remembers about its own printing: one Strata Signature, and every global
 * coordinate it has printed into.
 *
 * <p>Attached to the contraption <em>entity</em> rather than to any Extruder on it, because both
 * halves are properties of the machine as a whole. Four Extruders in a row on a Rope Pulley must
 * share a signature or the print comes out as four unrelated slabs of rock instead of one piece of
 * world; and the history has to be shared too, or the exploit the history exists to stop would work
 * perfectly well by alternating which Extruder passes over a coordinate.
 *
 * <h2>The Seismic Shift</h2>
 * A contraption that arrives at a coordinate it has already printed is being driven back over its own
 * work — reverse a Rope Pulley and the same Extruder passes the same blocks again, which without a
 * history would let one shaft be re-rolled for diamonds indefinitely. So the machine {@link #shift}s
 * instead: it forgets everything and rolls a new signature. The player still gets rock on the way
 * back up, which is the point — the machine is not punished, it is simply no longer printing the same
 * rock twice. See {@link com.createterraform.extruder.ExtruderMovementBehaviour}.
 *
 * <p>The same thing happens on {@link #isOverFull() size alone}. A tunnel bore that never crosses its
 * own path would otherwise grow this set without bound, and an unbounded set inside a saved entity is
 * a server that runs out of memory on a long enough machine.
 *
 * <h2>Longs, not BlockPos</h2>
 * {@link BlockPos#asLong} into a {@link LongOpenHashSet}: at the default limit of 65,536 coordinates
 * that is half a megabyte of primitives, against roughly four times that for boxed positions in a
 * {@code HashSet}, and it is saved as a single {@code long[]} rather than 65,536 compound tags. The
 * packed form covers ±33,554,431 on X and Z and the whole legal Y range, so nothing a machine can
 * reach is lost to it.
 */
public class StrataMemory implements INBTSerializable<CompoundTag> {

	private static final String SIGNATURE_KEY = "Signature";
	private static final String PRINTED_KEY = "Printed";

	private long signature = StrataSignature.UNSET;
	private final LongOpenHashSet printed = new LongOpenHashSet();

	/**
	 * This contraption's signature, rolled on first use.
	 *
	 * <p>Lazily rather than in the constructor because an attachment is constructed on whatever
	 * thread first asks for it, including the client's copy of the entity, and a signature rolled
	 * there would be a different one from the server's.
	 */
	public long signature(RandomSource random) {
		if (signature == StrataSignature.UNSET)
			signature = StrataSignature.roll(random);
		return signature;
	}

	/**
	 * Records a coordinate as printed.
	 *
	 * @return false if it had already been printed — the caller's cue to {@link #shift}.
	 */
	public boolean claim(BlockPos pos) {
		return printed.add(pos.asLong());
	}

	/** Whether the history has outgrown its bound and a shift is owed on size alone. */
	public boolean isOverFull() {
		return printed.size() > TerraformConfig.seismicShiftLimit();
	}

	/** Forget everything and roll a new signature. The Seismic Shift itself. */
	public void shift(RandomSource random) {
		printed.clear();
		printed.trim();
		signature = StrataSignature.roll(random);
	}

	/** Coordinates printed since the last shift. For the goggle overlay and the tests. */
	public int printedCount() {
		return printed.size();
	}

	/**
	 * The signature as it stands, without rolling one. {@link StrataSignature#UNSET} until the
	 * machine has printed something. Only the tests care — everything else wants
	 * {@link #signature(RandomSource)}, which is the same value but guaranteed to exist.
	 */
	public long peekSignature() {
		return signature;
	}

	@Override
	public CompoundTag serializeNBT(HolderLookup.Provider provider) {
		CompoundTag tag = new CompoundTag();
		tag.putLong(SIGNATURE_KEY, signature);
		tag.putLongArray(PRINTED_KEY, printed.toLongArray());
		return tag;
	}

	@Override
	public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
		signature = tag.getLong(SIGNATURE_KEY);
		printed.clear();
		for (long packed : tag.getLongArray(PRINTED_KEY))
			printed.add(packed);
		printed.trim();
	}
}
