package com.createterraform.strata;

import net.minecraft.util.RandomSource;

/**
 * A Strata Signature is one {@code long}, and everything a print looks like follows from it.
 *
 * <p>It is not a seed for a generator of our own. The mod never builds a world; it asks the world
 * the player is standing in what rock belongs somewhere, and the signature says <em>where</em> to
 * ask. {@link #offsetX} and {@link #offsetZ} turn the signature, mixed with the level's own seed,
 * into a displacement on the horizontal plane; the machine's real coordinates plus that
 * displacement is the coordinate actually sampled.
 *
 * <p><b>Y is never displaced</b>, and that is the design rather than an omission. Depth is the one
 * thing about a coordinate a player can read off the F3 screen and reason about — diamonds are deep,
 * coal is shallow, deepslate starts around zero — so an Extruder at y=-50 prints what y=-50 is like
 * <em>somewhere</em>, not what some other altitude is like here. Displacing Y as well would make the
 * machine a slot machine; displacing only X and Z makes it a core sample from an undisclosed
 * location, which is both more honest and much more useful.
 *
 * <p>Mixing the level seed in matters for a different reason: two worlds running the same signature
 * would otherwise print the same rock, and a signature is meant to be worth rerolling.
 *
 * <p>The mixer is SplitMix64's finalizer. It is here rather than borrowed from {@code RandomSupport}
 * because what is needed is a pure function of the inputs that will not change under us — the same
 * signature must resolve to the same offset next week, on another machine, after a Minecraft update.
 */
public final class StrataSignature {

	/**
	 * Not a signature. {@link #roll} never returns it, so a persisted zero means "this contraption
	 * has not rolled one yet" without needing a second flag to say so.
	 */
	public static final long UNSET = 0L;

	private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;

	private StrataSignature() {
		throw new AssertionError("No instances");
	}

	/** A fresh signature. Never {@link #UNSET}. */
	public static long roll(RandomSource random) {
		long rolled;
		do {
			rolled = random.nextLong();
		} while (rolled == UNSET);
		return rolled;
	}

	/** Blocks east the sample is taken from: a whole number of chunks, within {@code ±range}. */
	public static int offsetX(long levelSeed, long signature, int range) {
		return chunkAlignedOffset(mix(levelSeed ^ mix(signature)), range);
	}

	/** Blocks south the sample is taken from: a whole number of chunks, within {@code ±range}. */
	public static int offsetZ(long levelSeed, long signature, int range) {
		return chunkAlignedOffset(mix(levelSeed + GOLDEN_GAMMA ^ mix(signature + GOLDEN_GAMMA)), range);
	}

	/**
	 * A whole number of chunks, and that is load-bearing rather than tidy.
	 *
	 * <p>What a machine reads is a virtual chunk, generated in full because features cannot be
	 * sampled a block at a time. A displacement of, say, 37 blocks would slide a machine's own column
	 * seven blocks sideways inside that chunk — which sounds harmless and is not: a footprint that
	 * straddles two virtual chunks needs both of them generated, and generating a chunk is the
	 * expensive thing this whole design is arranged around. Aligned to 16, a machine's column and its
	 * signature name exactly one chunk, and a Rope Pulley descending through it names that same chunk
	 * for the whole descent.
	 */
	private static int chunkAlignedOffset(long hash, int range) {
		int chunks = Math.max(1, range >> 4);
		return (int) (Math.floorMod(hash, 2L * chunks + 1L) - chunks) * 16;
	}

	/** SplitMix64's finalizer: a bijection with good avalanche, and stable for ever. */
	private static long mix(long value) {
		long z = value + GOLDEN_GAMMA;
		z = (z ^ z >>> 30) * 0xBF58476D1CE4E5B9L;
		z = (z ^ z >>> 27) * 0x94D049BB133111EBL;
		return z ^ z >>> 31;
	}
}
