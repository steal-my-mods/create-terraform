package com.createterraform;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Server config. Every number here changes what appears in the world, so it has to agree between
 * client and server — hence SERVER rather than COMMON.
 *
 * <p>Two of these are not balance knobs and should be left alone unless something is actually
 * misbehaving: {@link #seismicShiftLimit} bounds how much memory one contraption may hold, and
 * {@link #oreCacheChunks} bounds how much a level's sampler may hold. Raising either trades server
 * memory for a longer print before a reroll, which is not usually the trade a pack wants.
 */
public class TerraformConfig {

	public static final ModConfigSpec SPEC;
	public static final TerraformConfig INSTANCE;

	/** Millibuckets of Mineral Substrate spent per block printed. */
	public final ModConfigSpec.IntValue substratePerBlock;
	/** Millibuckets a single Extruder holds on board. */
	public final ModConfigSpec.IntValue tankCapacity;
	/** Stress Units an Extruder draws per RPM. */
	public final ModConfigSpec.DoubleValue stressImpact;
	/** Multiplier on the Deployer-derived interval between placements. */
	public final ModConfigSpec.DoubleValue cycleScale;

	/** Blocks left in a core sample before a stationary Extruder cuts the next one. */
	public final ModConfigSpec.IntValue sliceLowWaterMark;
	/** Ticks a stationary Extruder waits before surveying again after an empty core sample. */
	public final ModConfigSpec.IntValue barrenRetryTicks;

	/** Coordinates a contraption remembers before the Seismic Shift fires on size alone. */
	public final ModConfigSpec.IntValue seismicShiftLimit;
	/** Virtual chunks a level keeps generated for the contraptions reading them. */
	public final ModConfigSpec.IntValue virtualChunkCacheSize;
	/** Blocks ahead of a moving contraption to generate early. 0 turns prefetching off. */
	public final ModConfigSpec.IntValue prefetchLookahead;
	/** Blocks either side of the origin the Strata Signature may displace a sample. */
	public final ModConfigSpec.IntValue signatureRange;

	private TerraformConfig(ModConfigSpec.Builder builder) {
		builder.comment("Terraform Extruder").push("extruder");
		substratePerBlock = builder
			.comment("Millibuckets of Mineral Substrate spent printing one block. A Fluid Tank holds",
				"8000mB, so the default is 80 blocks per tank.")
			.defineInRange("substratePerBlock", 100, 1, 100000);
		tankCapacity = builder
			.comment("Millibuckets an Extruder holds on board. On a contraption this is only a buffer:",
				"the machine draws from the contraption's own tanks and keeps this topped up.")
			.defineInRange("tankCapacity", 1000, 1, 1000000);
		stressImpact = builder
			.comment("Stress Units drawn per RPM, matching Create's own Drill and Deployer.",
				"Worth knowing what this does and does not cover: it is charged to a *stationary*",
				"Extruder and to nothing else. An Extruder riding a contraption is not a kinetic block",
				"entity at all -- it is a StructureBlockInfo with a MovementBehaviour -- and Create's",
				"actuators charge their own fixed impact regardless of what they are carrying, so a row",
				"of Extruders on a pulley draws no stress for being Extruders.",
				"Which is why this is not the lever that makes the machine expensive. The recipe and",
				"the substrate are.")
			.defineInRange("stressImpact", 4.0, 0.0, 1024.0);
		cycleScale = builder
			.comment("Multiplier on how long an Extruder waits between placements.",
				"At 1.0 the machine places at exactly a Mechanical Deployer's rate, which is where it",
				"belongs: on a contraption it already behaves like one, and a stationary Extruder that",
				"outran a Deployer by a factor of two was the odd machine out on a build made of",
				"Create's parts. Raise it to slow the machine down, lower it to speed it up.",
				"The interval itself is not configurable, because it is Create's: see cycleTicks() in",
				"TerraformExtruderBlockEntity. There is no separate floor -- Create's own clamp on",
				"timer speed gives one, at five ticks.")
			.defineInRange("cycleScale", 1.0, 0.05, 20.0);
		sliceLowWaterMark = builder
			.comment("Blocks left in a stationary Extruder's core sample before it starts cutting the",
				"next one on a worker thread. This is the double buffering: while the number is above",
				"zero the machine never waits, so it wants to be comfortably more than the machine can",
				"print in the time a survey takes.")
			.defineInRange("sliceLowWaterMark", 256, 0, 100000);
		barrenRetryTicks = builder
			.comment("Longest a stationary Extruder waits before surveying again after a core sample",
				"came back with nothing printable in it. It is a ceiling, not a fixed wait: the first",
				"retry is half a second and each further miss doubles it up to this. Y is never",
				"displaced, so whether a signature finds rock depends on the height the machine sits",
				"at -- underground nearly every one lands, at the surface most sample sky -- and a flat",
				"ten-second wait meant a machine placed up top could stand silent through several of",
				"them before its first block.")
			.defineInRange("barrenRetryTicks", 200, 20, 24000);
		builder.pop();

		builder.comment("Strata").push("strata");
		signatureRange = builder
			.comment("How far, in blocks, a Strata Signature may displace the sample from the machine's",
				"own coordinates. The sample keeps its Y -- depth stays honest, so an Extruder at",
				"y=-50 prints deepslate-depth rock -- and only X and Z move. Larger means more",
				"variety between signatures and more distinct terrain shapes.")
			.defineInRange("signatureRange", 1000000, 1024, 20000000);
		seismicShiftLimit = builder
			.comment("Coordinates one contraption remembers before a Seismic Shift fires on size alone.",
				"This is a memory bound, not a balance number: the history is what stops a pulley",
				"being reversed over its own print, and it has to stop growing somewhere. 65536",
				"coordinates is half a megabyte per contraption.")
			.defineInRange("seismicShiftLimit", 65536, 256, 1048576);
		virtualChunkCacheSize = builder
			.comment("Virtual chunks a level keeps generated for the contraptions reading them. Each is",
				"a real generated chunk held in memory -- on the order of a hundred kilobytes -- so",
				"this is a memory budget, not a speed setting.",
				"It has to be several times 25, and that number is not arbitrary: reading one chunk",
				"generates a 3x3 of terrain inside a 5x5 of biomes, so a single lookup puts 25 chunks",
				"in here. A bound near that evicts the chunk being read to make room for its own",
				"neighbours, and the cache degrades into a slow way of generating everything twice --",
				"a machine standing still would rebuild its grid every block. Room for the grid being",
				"read, the one being prefetched, and slack on top is the floor.")
			.defineInRange("virtualChunkCacheSize", 128, 64, 4096);
		prefetchLookahead = builder
			.comment("How far ahead of a moving contraption, in blocks, to start generating the chunk it",
				"is travelling into. A contraption's signature is fixed, so where it will need to read",
				"next is exactly where it is pointed; generating that on a worker thread in advance is",
				"what keeps a gantry from stalling the server every sixteen blocks. A Rope Pulley",
				"travelling straight down projects onto the chunk it is already in and prefetches",
				"nothing, which is correct.",
				"Wants to be more than one chunk, so the projection always lands outside the current",
				"one. Set to 0 to turn prefetching off and take the stalls.")
			.defineInRange("prefetchLookahead", 24, 0, 256);
		builder.pop();
	}

	static {
		Pair<TerraformConfig, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(TerraformConfig::new);
		INSTANCE = pair.getLeft();
		SPEC = pair.getRight();
	}

	/**
	 * Reading a config value before its file is loaded throws. Most callers here run deep inside a
	 * block entity tick, where it always is loaded — but the stress rating is also read by tooltip
	 * and recipe-viewer code that can run earlier, and a crash there would be a poor trade for a
	 * number that has a perfectly good default sitting right next to it.
	 */
	private static <T> T read(ModConfigSpec.ConfigValue<T> value) {
		return SPEC.isLoaded() ? value.get() : value.getDefault();
	}

	public static int substratePerBlock() {
		return read(INSTANCE.substratePerBlock);
	}

	public static int tankCapacity() {
		return read(INSTANCE.tankCapacity);
	}

	public static float stressImpact() {
		return read(INSTANCE.stressImpact).floatValue();
	}

	public static double cycleScale() {
		return read(INSTANCE.cycleScale);
	}

	public static int signatureRange() {
		return read(INSTANCE.signatureRange);
	}

	public static int seismicShiftLimit() {
		return read(INSTANCE.seismicShiftLimit);
	}

	public static int sliceLowWaterMark() {
		return read(INSTANCE.sliceLowWaterMark);
	}

	public static int barrenRetryTicks() {
		return read(INSTANCE.barrenRetryTicks);
	}

	public static int virtualChunkCacheSize() {
		return read(INSTANCE.virtualChunkCacheSize);
	}

	public static int prefetchLookahead() {
		return read(INSTANCE.prefetchLookahead);
	}
}
