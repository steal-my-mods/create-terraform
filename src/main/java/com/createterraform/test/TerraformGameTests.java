package com.createterraform.test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.createterraform.CreateTerraform;
import com.createterraform.TerraformConfig;
import com.createterraform.extruder.ExtruderIdleReason;
import com.createterraform.extruder.TerraformExtruderBlockEntity;
import com.createterraform.registry.TerraformAttachments;
import com.createterraform.registry.TerraformBlocks;
import com.createterraform.registry.TerraformItems;
import com.createterraform.registry.TerraformFluids;
import com.createterraform.strata.StrataSignature;
import com.createterraform.strata.StrataMemory;
import com.createterraform.strata.StrataSlice;
import com.createterraform.strata.VirtualChunkCache;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.api.contraption.storage.fluid.MountedFluidStorageType;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.pulley.PulleyBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * <h2>What the GameTest world is, and why the assertions look the way they do</h2>
 * The test server runs a <em>superflat</em> world, and it runs every rig at y=-59. That has two
 * consequences worth stating once here rather than re-explaining at every test.
 *
 * <p>First, the generator is {@code FlatLevelSource}, so a virtual chunk is the superflat's own layer
 * stack: bedrock at -64, dirt at -63 and -62, grass at -61, air above. At the altitude a rig actually
 * occupies there is nothing but sky, so a real core sample cut there comes back empty — which is not
 * a degenerate case to work around but the exact thing
 * {@link ExtruderIdleReason#BARREN} exists to report, and it is asserted as such. Where a test wants
 * a machine that is printing, it hands the machine a core sample of its own rather than waiting on
 * the world to supply one.
 *
 * <p>Second, the layer stack makes two filter assertions exact rather than probabilistic: y=-64 is
 * bedrock in every column of this world and y=-62 is dirt in every column, so
 * {@code aCoreSampleAtTheWorldFloorIsEmpty} and {@code aCoreSampleHoldsOnlyPrintableBlocks} are both
 * checking the rule and not its luck.
 */
@GameTestHolder(CreateTerraform.ID)
@PrefixGameTestTemplate(false)
public class TerraformGameTests {

	/** Motor, then Extruder, then the block it prints into — west to east, near the rig's floor. */
	private static final BlockPos DRIVER = new BlockPos(4, 1, 6);
	private static final BlockPos EXTRUDER = new BlockPos(5, 1, 6);
	private static final BlockPos TARGET = new BlockPos(6, 1, 6);

	/**
	 * The bearing rig: a Mechanical Bearing pointing up with a three-block arm on it and the Extruder
	 * on the end, so that turning it sweeps the machine horizontally.
	 */
	private static final BlockPos BEARING = new BlockPos(6, 4, 6);
	private static final BlockPos BEARING_DRIVER = new BlockPos(6, 3, 6);
	private static final BlockPos ARM = new BlockPos(6, 5, 6);
	private static final int ARM_LENGTH = 3;
	private static final int BEARING_RPM = 64;

	/** The pulley rig: high enough that Create will let it descend a useful distance. */
	private static final BlockPos PULLEY = new BlockPos(6, 11, 6);
	private static final BlockPos PULLEY_DRIVER = new BlockPos(5, 11, 6);
	private static final BlockPos CARRIAGE = new BlockPos(6, 10, 6);

	/** Long enough for the rotation propagator to find the machine and for one cycle to fire. */
	private static final int SETTLE_TICKS = 10;

	/** Comfortably past the low-water mark, so a hand-fed machine does not also go surveying. */
	private static final int FED_SAMPLE_SIZE = 512;

	/**
	 * Slow enough that a pulley is still travelling when the assertions run.
	 *
	 * <p>Create moves a pulley at {@code rpm / 512} blocks per tick, and the rig only gives it about
	 * fifteen blocks of rope before it reaches the bottom of the world and takes itself apart. At the
	 * motor's top speed that is a thirty-tick round trip, which is over before anything has been
	 * looked at; at 32 RPM it is a block every sixteen ticks, which leaves the contraption assembled
	 * for the whole of both pulley tests.
	 */
	private static final int PULLEY_RPM = 32;


	/** In this world: bedrock. Every column, so nothing here depends on where the rig landed. */
	private static final int WORLD_FLOOR_Y = -64;
	/** In this world: dirt. */
	private static final int SOLID_LAYER_Y = -62;
	/** Deep in the Nether's ore band, and well below its lava sea's ceiling. */
	private static final int NETHER_SAMPLE_Y = 32;

	// --- stationary ------------------------------------------------------------------------

	/**
	 * The end-to-end path: rotation, a cycle firing, a block popped off the core sample, substrate
	 * spent and the world written.
	 *
	 * <p>The sample is handed over rather than surveyed for, because at the rig's altitude this world
	 * has nothing in it — see the class notes. What is under test here is the machine, and the
	 * pipeline that fills a real sample is under test in {@code aCoreSampleHoldsOnlyPrintableBlocks}.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 200)
	public static void aPoweredExtruderPrintsFromItsCoreSample(GameTestHelper helper) {
		rig(helper, Direction.EAST);
		fill(helper, Integer.MAX_VALUE);
		feed(helper, Blocks.DEEPSLATE.defaultBlockState());

		helper.runAfterDelay(SETTLE_TICKS + 20, () -> {
			TerraformExtruderBlockEntity extruder = extruder(helper);
			helper.assertTrue(extruder.getPrinted() > 0,
				"the Extruder printed nothing; it is " + extruder.getIdleReason());
			helper.assertBlockPresent(Blocks.DEEPSLATE, TARGET);
			helper.assertTrue(extruder.getSampleSize() < FED_SAMPLE_SIZE,
				"the core sample was never drawn down");
			helper.succeed();
		});
	}

	/** Substrate is the fuel, and a machine with rotation but no fuel must say so and do nothing. */
	@GameTest(template = "test_rig", timeoutTicks = 200)
	public static void anExtruderWithoutSubstratePrintsNothing(GameTestHelper helper) {
		rig(helper, Direction.EAST);
		feed(helper, Blocks.DEEPSLATE.defaultBlockState());

		helper.runAfterDelay(SETTLE_TICKS + 20, () -> {
			TerraformExtruderBlockEntity extruder = extruder(helper);
			helper.assertTrue(extruder.getPrinted() == 0,
				"a dry Extruder printed " + extruder.getPrinted() + " blocks");
			helper.assertTrue(extruder.getIdleReason() == ExtruderIdleReason.NO_SUBSTRATE,
				"expected NO_SUBSTRATE, was " + extruder.getIdleReason());
			helper.assertBlockPresent(Blocks.AIR, TARGET);
			helper.succeed();
		});
	}

	/** Fuel without rotation is equally useless, and for a different reason the overlay must name. */
	@GameTest(template = "test_rig", timeoutTicks = 200)
	public static void anExtruderWithoutRotationPrintsNothing(GameTestHelper helper) {
		helper.setBlock(EXTRUDER, extruderFacing(Direction.EAST));
		fill(helper, Integer.MAX_VALUE);
		feed(helper, Blocks.DEEPSLATE.defaultBlockState());

		helper.runAfterDelay(SETTLE_TICKS + 20, () -> {
			TerraformExtruderBlockEntity extruder = extruder(helper);
			helper.assertTrue(extruder.getPrinted() == 0,
				"an unpowered Extruder printed " + extruder.getPrinted() + " blocks");
			helper.assertTrue(extruder.getIdleReason() == ExtruderIdleReason.NO_ROTATION,
				"expected NO_ROTATION, was " + extruder.getIdleReason());
			helper.assertTrue(extruder.getSurveys() == 0,
				"an unpowered Extruder spent a worker thread surveying");
			helper.succeed();
		});
	}

	/**
	 * A chest is somebody's belongings. The replaceable tag lets the machine overwrite the rock it
	 * prints and nothing else, and a block entity is refused whatever the tags say.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 200)
	public static void anExtruderWillNotOverwriteAChest(GameTestHelper helper) {
		rig(helper, Direction.EAST);
		fill(helper, Integer.MAX_VALUE);
		feed(helper, Blocks.DEEPSLATE.defaultBlockState());
		helper.setBlock(TARGET, Blocks.CHEST);

		helper.runAfterDelay(SETTLE_TICKS + 20, () -> {
			TerraformExtruderBlockEntity extruder = extruder(helper);
			helper.assertTrue(extruder.getIdleReason() == ExtruderIdleReason.OBSTRUCTED,
				"expected OBSTRUCTED, was " + extruder.getIdleReason());
			helper.assertBlockPresent(Blocks.CHEST, TARGET);
			helper.assertTrue(extruder.getPrinted() == 0,
				"the Extruder wrote over a chest " + extruder.getPrinted() + " times");
			helper.succeed();
		});
	}

	/** Substrate is spent per block written, and not per cycle attempted. */
	@GameTest(template = "test_rig", timeoutTicks = 300)
	public static void substrateIsSpentPerBlockPrinted(GameTestHelper helper) {
		rig(helper, Direction.EAST);
		int filled = fill(helper, Integer.MAX_VALUE);
		feed(helper, Blocks.DEEPSLATE.defaultBlockState());

		helper.runAfterDelay(SETTLE_TICKS + 40, () -> {
			TerraformExtruderBlockEntity extruder = extruder(helper);
			long spent = filled - extruder.getTankContents()
				.getAmount();
			long expected = extruder.getPrinted() * (long) TerraformConfig.substratePerBlock();
			helper.assertTrue(extruder.getPrinted() > 0, "nothing was printed, so nothing is proven");
			helper.assertTrue(spent == expected,
				"spent " + spent + "mB for " + extruder.getPrinted() + " blocks, expected " + expected + "mB");
			helper.succeed();
		});
	}

	/** Throughput is the speed on the gauge: twice the RPM, half the wait, down to the floor. */
	@GameTest(template = "test_rig", timeoutTicks = 200)
	public static void rotationSetsThePrintingInterval(GameTestHelper helper) {
		rig(helper, Direction.EAST);
		motor(helper, DRIVER).generatedSpeed.setValue(16);

		helper.runAfterDelay(SETTLE_TICKS, () -> {
			int slow = extruder(helper).cycleTicks();
			motor(helper, DRIVER).generatedSpeed.setValue(32);

			helper.runAfterDelay(SETTLE_TICKS, () -> {
				int fast = extruder(helper).cycleTicks();
				helper.assertTrue(slow > fast,
					"16 RPM waited " + slow + " ticks and 32 RPM waited " + fast + "; faster must be shorter");
				helper.assertTrue(fast >= TerraformConfig.minimumCycleTicks(),
					"the interval floor was breached: " + fast);
				helper.succeed();
			});
		});
	}

	/**
	 * A machine at an altitude with no rock in it must say so and stop asking.
	 *
	 * <p>This is the whole reason {@link ExtruderIdleReason#BARREN} and its cooldown exist: an
	 * Extruder bolted to a hillside samples sky, and without the cooldown it would dispatch a
	 * twenty-five chunk survey every few ticks for ever, finding out the same thing each time.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 600)
	public static void anExtruderAboveTheStrataReportsBarren(GameTestHelper helper) {
		rig(helper, Direction.EAST);
		fill(helper, Integer.MAX_VALUE);

		helper.runAfterDelay(200, () -> {
			TerraformExtruderBlockEntity extruder = extruder(helper);
			helper.assertTrue(extruder.getIdleReason() == ExtruderIdleReason.BARREN,
				"expected BARREN above the strata, was " + extruder.getIdleReason());
			helper.assertTrue(extruder.getPrinted() == 0,
				"an Extruder with an empty core sample printed " + extruder.getPrinted() + " blocks");
			helper.assertTrue(extruder.getSampleSize() == 0, "an empty survey left blocks in the sample");
			helper.succeed();
		});
	}

	// --- the virtual chunk pipeline -----------------------------------------------------------

	/**
	 * A real core sample, cut through the whole 3x3 spoof: 25 chunks scaffolded, 9 decorated, a
	 * 48x48 slice read out and filtered.
	 *
	 * <p>The assertion is the filter's contract — no air, no fluid, no block entity — and it is
	 * exhaustive rather than sampled, because a pool of a couple of thousand blocks with one lava
	 * source in it is a machine that will eventually place one lava source.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 600)
	public static void aCoreSampleHoldsOnlyPrintableBlocks(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos origin = helper.absolutePos(BlockPos.ZERO);
		long signature = StrataSignature.roll(RandomSource.create(17L));

		StrataSlice slice = StrataSlice.generate(level, signature,
			new BlockPos(origin.getX(), SOLID_LAYER_Y, origin.getZ()));

		helper.assertTrue(!slice.isEmpty(), "a slice cut through solid ground came back empty");
		for (BlockState state : slice.states()) {
			helper.assertTrue(!state.isAir(), "the sample holds air, which is not a block to print");
			helper.assertTrue(state.getFluidState()
				.isEmpty(), "the sample holds " + state + ", which carries a fluid");
			helper.assertTrue(!state.hasBlockEntity(),
				"the sample holds " + state + ", which has a block entity");
		}
		helper.succeed();
	}

	/**
	 * Bedrock is exact here rather than lucky: the superflat this server runs puts bedrock across the
	 * whole of y=-64, so a slice cut there is 2,304 columns of bedrock and every one of them has to be
	 * filtered out.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 600)
	public static void aCoreSampleAtTheWorldFloorIsEmpty(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos origin = helper.absolutePos(BlockPos.ZERO);
		long signature = StrataSignature.roll(RandomSource.create(23L));

		StrataSlice slice = StrataSlice.generate(level, signature,
			new BlockPos(origin.getX(), WORLD_FLOOR_Y, origin.getZ()));

		helper.assertTrue(slice.isEmpty(),
			"a slice cut through bedrock came back with " + slice.states()
				.size() + " blocks in it");
		helper.succeed();
	}

	/**
	 * The architecture, end to end, against a generator that actually generates something.
	 *
	 * <p>Every other test here runs in the superflat the GameTest server builds, where
	 * {@code FlatLevelSource} makes surface rules and carvers no-ops and there is nothing for
	 * features to do. That is fine for testing the machine and the filter and useless for testing the
	 * thing this mod is: a 3x3 {@code WorldGenRegion} taken to FEATURES so that ore veins come out
	 * whole. The Nether is in the same server and is generated by real noise with a real feature list,
	 * so this cuts a slice there.
	 *
	 * <p>The assertion is that ore turns up. Not a particular ore and not a particular amount — the
	 * mod has no rarity table to check against, and that is the whole point of decorating a real chunk
	 * instead of inventing one. But netherrack with <em>nothing</em> in it would mean the FEATURES step
	 * silently did nothing, which is exactly the failure the single-block sampler this replaced had,
	 * and it is invisible from anywhere else.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 2400)
	public static void aCoreSampleFromANoiseWorldHoldsRealOre(GameTestHelper helper) {
		ServerLevel nether = helper.getLevel()
			.getServer()
			.getLevel(Level.NETHER);
		helper.assertTrue(nether != null, "the test server has no Nether to sample");

		RandomSource random = RandomSource.create(2024L);
		Set<Block> found = new HashSet<>();
		int sampled = 0;
		for (int trial = 0; trial < 2; trial++) {
			StrataSlice slice = StrataSlice.generate(nether, StrataSignature.roll(random),
				new BlockPos(0, NETHER_SAMPLE_Y, 0));
			sampled += slice.states()
				.size();
			for (BlockState state : slice.states()) {
				helper.assertTrue(state.getFluidState()
					.isEmpty(), "a Nether slice held " + state + ", which carries a fluid");
				helper.assertTrue(!state.hasBlockEntity(),
					"a Nether slice held " + state + ", which has a block entity");
				found.add(state.getBlock());
			}
		}

		helper.assertTrue(sampled > 1000,
			"two Nether slices came back with only " + sampled + " printable blocks between them");
		helper.assertTrue(found.contains(Blocks.NETHERRACK), "a Nether slice with no netherrack in it");
		helper.assertTrue(found.stream()
			.anyMatch(block -> block.defaultBlockState()
				.is(Tags.Blocks.ORES)),
			"two Nether slices at y=" + NETHER_SAMPLE_Y + " and not one ore between them; "
				+ "the FEATURES step is not running. Blocks seen: " + found);
		helper.succeed();
	}

	/**
	 * The performance claim, asserted rather than asserted-in-a-comment.
	 *
	 * <p>Every one of these chunks is generated in memory and thrown away. If any of it ever started
	 * going through the level's chunk source, a machine printing a few blocks a second would be
	 * generating and <em>saving</em> chunks a million blocks away, and the world folder would grow
	 * without bound. The loaded-chunk count is the cheapest thing that notices.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 600)
	public static void generatingVirtualChunksLoadsNoRealOnes(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		VirtualChunkCache cache = VirtualChunkCache.of(level);
		BlockPos origin = helper.absolutePos(BlockPos.ZERO);
		RandomSource random = RandomSource.create(31337L);

		int before = level.getChunkSource()
			.getLoadedChunksCount();
		for (int trial = 0; trial < 4; trial++) {
			long signature = StrataSignature.roll(random);
			for (int y = SOLID_LAYER_Y; y < SOLID_LAYER_Y + 4; y++)
				cache.sample(signature, new BlockPos(origin.getX(), y, origin.getZ()));
		}
		int after = level.getChunkSource()
			.getLoadedChunksCount();

		helper.assertTrue(after == before,
			"generating virtual chunks loaded " + (after - before) + " real ones; it must load none");
		helper.succeed();
	}

	/**
	 * Why a Rope Pulley is cheap: a machine travelling straight down never leaves the virtual chunk it
	 * started in, so the whole descent is served by the grid built for its first block.
	 *
	 * <p>This is the assertion that keeps the signature chunk-aligned. Displace by an arbitrary number
	 * of blocks instead and a machine's column straddles two virtual chunks, which doubles the cost of
	 * every print this mod makes.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 600)
	public static void aVerticalDescentBuildsOneGrid(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		VirtualChunkCache cache = VirtualChunkCache.of(level);
		BlockPos origin = helper.absolutePos(BlockPos.ZERO);
		long signature = StrataSignature.roll(RandomSource.create(99L));

		cache.sample(signature, new BlockPos(origin.getX(), SOLID_LAYER_Y, origin.getZ()));
		long after = cache.getGridsBuilt();

		for (int y = level.getMinBuildHeight(); y < level.getMinBuildHeight() + 32; y++)
			cache.sample(signature, new BlockPos(origin.getX(), y, origin.getZ()));

		helper.assertTrue(cache.getGridsBuilt() == after,
			"descending a column built " + (cache.getGridsBuilt() - after) + " extra grids; it must build none");
		helper.succeed();
	}

	/**
	 * Vector prefetching: the one stall left in the contraption path, generated away before anybody
	 * waits on it.
	 *
	 * <p>A contraption's signature is fixed between Seismic Shifts, so where it will need to read
	 * next is exactly where its motion vector points. This projects along that vector, waits for the
	 * worker to land, and then asserts that reading the projected chunk builds <em>no</em> grid on
	 * the server thread.
	 *
	 * <p>The cold read at the top is not scene-setting: without it, "the counter did not move" would
	 * pass just as well against a counter that never moves.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 600)
	public static void prefetchingTheNextChunkAvoidsTheStall(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		VirtualChunkCache cache = VirtualChunkCache.of(level);
		BlockPos origin = helper.absolutePos(BlockPos.ZERO);
		long signature = StrataSignature.roll(RandomSource.create(555L));
		BlockPos here = new BlockPos(origin.getX(), SOLID_LAYER_Y, origin.getZ());
		BlockPos ahead = here.offset(TerraformConfig.prefetchLookahead(), 0, 0);

		long cold = cache.getGridsBuilt();
		cache.sample(signature, here);
		helper.assertTrue(cache.getGridsBuilt() > cold,
			"reading an unvisited chunk built no grid, so this test cannot tell a stall from a hit");

		cache.prefetch(signature, here, new Vec3(1.0, 0.0, 0.0));
		helper.assertTrue(cache.getPrefetchesInFlight() > 0,
			"travelling east prefetched nothing; the projection is not leaving the current chunk");

		helper.startSequence()
			.thenWaitUntil(() -> {
				// Finished prefetches are merged in by the calls a running machine already makes,
				// and this is one of them -- asking again is how the result gets collected.
				cache.prefetch(signature, here, new Vec3(1.0, 0.0, 0.0));
				// Waiting on "nothing is in flight" rather than on the landed count, because every
				// test in this class shares one cache: a count that has gone up may have gone up for
				// somebody else, and this test would then read a chunk that is still being built.
				helper.assertTrue(cache.getPrefetchesInFlight() == 0, "the prefetch has not landed yet");
			})
			.thenExecute(() -> {
				long stalls = cache.getGridsBuilt();
				cache.sample(signature, ahead);
				helper.assertTrue(cache.getGridsBuilt() == stalls,
					"reading a prefetched chunk still built a grid on the server thread");
			})
			.thenSucceed();
	}

	/**
	 * The assembly stall: the one miss the motion vector cannot predict, because at assembly there is
	 * no motion and nothing has been read.
	 *
	 * <p>Uses a signature of its own, so the chunks it touches are nobody else's and the counters
	 * mean what they say even with other tests running in the same level.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 600)
	public static void warmingTheFirstChunkAvoidsTheAssemblyStall(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		VirtualChunkCache cache = VirtualChunkCache.of(level);
		BlockPos origin = helper.absolutePos(BlockPos.ZERO);
		long signature = StrataSignature.roll(RandomSource.create(4242L));
		BlockPos here = new BlockPos(origin.getX(), SOLID_LAYER_Y, origin.getZ());

		cache.warm(signature, here);
		helper.assertTrue(cache.getPrefetchesInFlight() > 0, "warming started nothing");

		helper.startSequence()
			.thenWaitUntil(() -> {
				// Finished prefetches are merged in by the calls a running machine already makes.
				cache.warm(signature, here);
				helper.assertTrue(cache.getPrefetchesInFlight() == 0, "the warm-up has not landed yet");
			})
			.thenExecute(() -> {
				long stalls = cache.getGridsBuilt();
				cache.sample(signature, here);
				helper.assertTrue(cache.getGridsBuilt() == stalls,
					"the first read after warming still built a grid on the server thread");
			})
			.thenSucceed();
	}

	/** Straight down is the Rope Pulley case, and it must not spend a worker on anything. */
	@GameTest(template = "test_rig", timeoutTicks = 200)
	public static void verticalTravelPrefetchesNothing(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		VirtualChunkCache cache = VirtualChunkCache.of(level);
		BlockPos origin = helper.absolutePos(BlockPos.ZERO);
		long signature = StrataSignature.roll(RandomSource.create(556L));
		BlockPos here = new BlockPos(origin.getX(), SOLID_LAYER_Y, origin.getZ());

		int before = cache.getPrefetchesInFlight();
		cache.prefetch(signature, here, new Vec3(0.0, -0.5, 0.0));
		helper.assertTrue(cache.getPrefetchesInFlight() == before,
			"a contraption travelling straight down started a prefetch it will never read");
		cache.prefetch(signature, here, Vec3.ZERO);
		helper.assertTrue(cache.getPrefetchesInFlight() == before,
			"a stalled contraption started a prefetch");
		helper.succeed();
	}

	/** A signature is a place, not a dice roll: asked twice, it has to answer the same. */
	@GameTest(template = "test_rig", timeoutTicks = 600)
	public static void oneSignatureAlwaysSamplesTheSameBlock(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		VirtualChunkCache cache = VirtualChunkCache.of(level);
		long signature = StrataSignature.roll(RandomSource.create(7L));
		BlockPos origin = helper.absolutePos(BlockPos.ZERO);

		for (int y = level.getMinBuildHeight(); y < level.getMinBuildHeight() + 8; y++) {
			BlockPos pos = new BlockPos(origin.getX(), y, origin.getZ());
			BlockState first = cache.sample(signature, pos);
			BlockState again = cache.sample(signature, pos);
			helper.assertTrue(first == again,
				"the same signature gave " + first + " then " + again + " at " + pos);
		}
		helper.succeed();
	}

	/**
	 * And different signatures have to be different places, or a reroll would be no reroll at all —
	 * which is the whole of the Seismic Shift and the whole of the stationary machine.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 600)
	public static void differentSignaturesReachDifferentChunks(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos origin = helper.absolutePos(BlockPos.ZERO);
		RandomSource random = RandomSource.create(13L);

		Set<Long> reached = new HashSet<>();
		for (int trial = 0; trial < 16; trial++) {
			long signature = StrataSignature.roll(random);
			BlockPos probe = com.createterraform.strata.VirtualStrata.displace(level, signature,
				new BlockPos(origin.getX(), SOLID_LAYER_Y, origin.getZ()));
			reached.add(net.minecraft.world.level.ChunkPos.asLong(probe.getX() >> 4, probe.getZ() >> 4));
		}

		helper.assertTrue(reached.size() > 1,
			"16 signatures all reached the same virtual chunk; the displacement is not moving");
		helper.succeed();
	}

	/** Chunk-aligned displacement, so one machine and one signature name exactly one virtual chunk. */
	@GameTest(template = "test_rig", timeoutTicks = 100)
	public static void theDisplacementIsAWholeNumberOfChunks(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos origin = helper.absolutePos(new BlockPos(3, 1, 11));
		RandomSource random = RandomSource.create(77L);

		for (int trial = 0; trial < 64; trial++) {
			BlockPos probe = com.createterraform.strata.VirtualStrata.displace(level,
				StrataSignature.roll(random), origin);
			helper.assertTrue((probe.getX() & 15) == (origin.getX() & 15),
				"the displacement moved the column within its chunk on X");
			helper.assertTrue((probe.getZ() & 15) == (origin.getZ() & 15),
				"the displacement moved the column within its chunk on Z");
			helper.assertTrue(probe.getY() == origin.getY(), "the displacement moved Y, which must stay honest");
		}
		helper.succeed();
	}

	// --- the Seismic Shift, without a contraption ---------------------------------------------

	/**
	 * The exploit and its answer, on the data structure itself.
	 *
	 * <p>A contraption driven back over its own print has to come out somewhere new, and that is two
	 * things at once: the history is emptied and the signature is replaced. Testing only the first
	 * would pass a machine that forgot where it had been and then reprinted the same rock.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 100)
	public static void revisitingACoordinateIsRefusedUntilTheSignatureMoves(GameTestHelper helper) {
		RandomSource random = RandomSource.create(5L);
		StrataMemory memory = new StrataMemory();
		BlockPos pos = new BlockPos(1, 2, 3);

		helper.assertTrue(memory.claim(pos), "a fresh coordinate must be claimable");
		helper.assertTrue(!memory.claim(pos), "the same coordinate must not be claimable twice");

		long before = memory.signature(random);
		memory.shift(random);
		helper.assertTrue(memory.printedCount() == 0, "a shift must empty the history");
		helper.assertTrue(memory.peekSignature() != before, "a shift must move the signature");
		helper.assertTrue(memory.claim(pos), "after a shift the coordinate is new again");
		helper.succeed();
	}

	/** The memory bound. A tunnel bore that never crosses its own path must still stop growing. */
	@GameTest(template = "test_rig", timeoutTicks = 200)
	public static void theHistoryStopsGrowingAtTheConfiguredLimit(GameTestHelper helper) {
		StrataMemory memory = new StrataMemory();
		int limit = TerraformConfig.seismicShiftLimit();

		for (int i = 0; i < limit; i++)
			memory.claim(new BlockPos(i & 1023, i >> 10, 0));
		helper.assertTrue(!memory.isOverFull(),
			"the history claimed to be over its " + limit + " limit at exactly the limit");

		memory.claim(new BlockPos(0, 0, 1));
		helper.assertTrue(memory.isOverFull(), "one past the limit must be over it");
		helper.succeed();
	}

	// --- wiring ------------------------------------------------------------------------------

	/**
	 * Both of these are silent when they go wrong: an Extruder with no movement behaviour rides a
	 * contraption doing nothing at all, and one with no mounted storage rides it with a tank full of
	 * substrate it cannot reach. Neither logs anything.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 100)
	public static void theExtruderIsRegisteredAsAContraptionActor(GameTestHelper helper) {
		BlockState state = TerraformBlocks.TERRAFORM_EXTRUDER.get()
			.defaultBlockState();
		helper.assertTrue(MovementBehaviour.REGISTRY.get(state) != null,
			"the Extruder has no MovementBehaviour; on a contraption it would be scenery");
		helper.assertTrue(MountedFluidStorageType.REGISTRY.get(state) != null,
			"the Extruder's tank is not mounted; on a contraption it could not reach its own fuel");
		helper.succeed();
	}

	/**
	 * The recipes load and produce what they say.
	 *
	 * <p>A recipe JSON that fails to parse is logged once at startup, on a line nobody reads, and
	 * then the machine simply does not exist for anyone playing in survival. Both of these are
	 * hand-written against Create's schemas, which is exactly the kind of thing that rots quietly
	 * across a Create version.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 100)
	public static void theRecipesLoad(GameTestHelper helper) {
		RecipeManager recipes = helper.getLevel()
			.getRecipeManager();

		RecipeHolder<?> extruder = recipes.byKey(CreateTerraform.asResource("terraform_extruder"))
			.orElseThrow(() -> new IllegalStateException(
				"the Terraform Extruder has no crafting recipe; it cannot be made in survival"));
		ItemStack result = extruder.value()
			.getResultItem(helper.getLevel()
				.registryAccess());
		helper.assertTrue(result.is(TerraformItems.TERRAFORM_EXTRUDER.get()),
			"the Extruder recipe makes " + result);
		helper.assertTrue(result.getCount() == 16, "the Extruder recipe yields " + result.getCount() + ", not 16");

		helper.assertTrue(recipes.byKey(CreateTerraform.asResource("mineral_substrate"))
			.isPresent(), "Mineral Substrate has no recipe; the Extruder would have no fuel");
		helper.succeed();
	}

	// --- on a Rope Pulley ---------------------------------------------------------------------

	/**
	 * The real thing: an Extruder glued to nothing but a Rope Pulley, descending under its own
	 * substrate, printing as it goes.
	 *
	 * <p>What it proves that the unit tests cannot: that Create actually calls the behaviour, that the
	 * shared memory really does end up on the contraption entity, and that the machine finds fuel —
	 * which it can only do because its own tank is mounted into the contraption's fluid pool.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 600)
	public static void anExtruderOnARopePulleyPrintsAsItDescends(GameTestHelper helper) {
		pulleyRig(helper);

		helper.runAfterDelay(60, () -> {
			StrataMemory memory = contraptionMemory(helper);
			helper.assertTrue(memory.printedCount() > 1,
				"a descending Extruder visited " + memory.printedCount() + " coordinates; expected several");
			helper.assertTrue(memory.peekSignature() != StrataSignature.UNSET,
				"the contraption never rolled a Strata Signature");
			helper.succeed();
		});
	}

	/**
	 * And the exploit it exists to stop: reverse the pulley and the machine is being driven back over
	 * its own work, so it vents, forgets, and prints somewhere else instead.
	 *
	 * <p>The assertion is on the signature rather than only on the history, because a machine that
	 * cleared its history and kept its signature would reprint the same ore and pass a history-only
	 * check.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 800)
	public static void reversingThePulleyTriggersASeismicShift(GameTestHelper helper) {
		pulleyRig(helper);

		helper.runAfterDelay(60, () -> {
			StrataMemory memory = contraptionMemory(helper);
			long signatureGoingDown = memory.peekSignature();
			int printedGoingDown = memory.printedCount();
			helper.assertTrue(printedGoingDown > 1,
				"nothing was printed on the way down, so there is no history to collide with");

			motor(helper, PULLEY_DRIVER).generatedSpeed.setValue(-PULLEY_RPM);

			helper.runAfterDelay(40, () -> {
				StrataMemory after = contraptionMemory(helper);
				helper.assertTrue(after.peekSignature() != signatureGoingDown,
					"the pulley was driven back over its own print and the signature never moved");
				helper.assertTrue(after.printedCount() < printedGoingDown,
					"the history was never cleared: " + printedGoingDown + " coordinates before, "
						+ after.printedCount() + " after");
				helper.succeed();
			});
		});
	}

	/**
	 * An Extruder swept sideways on a Mechanical Bearing — the one kind of travel nothing else here
	 * covers.
	 *
	 * <p>Every other contraption test is a Rope Pulley, which is vertical, and vertical is exactly
	 * the case where {@code VirtualChunkCache.prefetch} returns without doing anything. So the call
	 * in {@code visitNewPosition} had only ever run on the branch that does nothing, and whether
	 * Create's {@code context.motion} is what this mod assumes when a contraption actually travels
	 * horizontally was, until this test, an assumption.
	 *
	 * <p>Printing at more than one coordinate is what proves it translated: the Extruder faces down,
	 * so a contraption that assembled but never moved would claim one coordinate and stop.
	 *
	 * <p><b>What this does not prove:</b> that a particular chunk was prefetched. A swept arm curves,
	 * so it aims at a different chunk every few blocks, and the in-flight cap means some of those are
	 * dropped — an assertion about any one of them would be a coin toss. That prefetching targets and
	 * caches the right chunk is {@code prefetchingTheNextChunkAvoidsTheStall}, which drives the cache
	 * directly with a signature of its own. This test covers the half that one cannot: that real
	 * horizontal motion off a real contraption drives the whole path without misbehaving.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 600)
	public static void anExtruderSweptSidewaysPrintsAsItGoes(GameTestHelper helper) {
		bearingRig(helper);

		helper.runAfterDelay(80, () -> {
			StrataMemory memory = contraptionMemoryNear(helper, BEARING);
			helper.assertTrue(memory.peekSignature() != StrataSignature.UNSET,
				"the contraption never rolled a Strata Signature, so startMoving did not run");
			helper.assertTrue(memory.printedCount() > 1,
				"a swept Extruder printed at " + memory.printedCount()
					+ " coordinates; it cannot have moved");
			helper.succeed();
		});
	}

	// --- rigs --------------------------------------------------------------------------------

	private static BlockState extruderFacing(Direction facing) {
		return TerraformBlocks.TERRAFORM_EXTRUDER.get()
			.defaultBlockState()
			.setValue(BlockStateProperties.FACING, facing);
	}

	/** Creative motor, then the Extruder pointing away from it. */
	private static void rig(GameTestHelper helper, Direction facing) {
		helper.setBlock(DRIVER, AllBlocks.CREATIVE_MOTOR.getDefaultState()
			.setValue(BlockStateProperties.FACING, facing));
		helper.setBlock(EXTRUDER, extruderFacing(facing));
	}

	/**
	 * A pulley at the top of the rig with an Extruder hanging off it, pointing down.
	 *
	 * <p>The shaft is deliberately clear below: Create refuses to assemble a pulley contraption into a
	 * block it would immediately collide with, so a pre-filled shaft would produce a machine that
	 * never moves and a test that never ran.
	 */
	private static void pulleyRig(GameTestHelper helper) {
		helper.setBlock(PULLEY, AllBlocks.ROPE_PULLEY.getDefaultState()
			.setValue(BlockStateProperties.HORIZONTAL_AXIS, Direction.Axis.X));
		helper.setBlock(CARRIAGE, extruderFacing(Direction.DOWN));
		fill(helper, CARRIAGE, Integer.MAX_VALUE);
		helper.setBlock(PULLEY_DRIVER, AllBlocks.CREATIVE_MOTOR.getDefaultState()
			.setValue(BlockStateProperties.FACING, Direction.EAST));
		motor(helper, PULLEY_DRIVER).generatedSpeed.setValue(PULLEY_RPM);
	}

	/** Hands the Extruder a core sample of one block repeated, so a test can watch it print. */
	private static void feed(GameTestHelper helper, BlockState state) {
		List<BlockState> states = new ArrayList<>(FED_SAMPLE_SIZE);
		for (int i = 0; i < FED_SAMPLE_SIZE; i++)
			states.add(state);
		extruder(helper).acceptSample(new StrataSlice(1L, helper.absolutePos(TARGET)
			.getY(), states));
	}

	/**
	 * A Mechanical Bearing pointing up, a three-block arm across it, and the Extruder on the end
	 * facing down, so a turn sweeps it over new ground.
	 *
	 * <p>A bearing rather than a Mechanical Piston, which was the obvious choice and does not work
	 * here: a piston's assembly checks whether the contraption would collide at its first step, and
	 * the extension poles it has to push against are themselves in the way. It declines silently —
	 * no contraption, no exception, no log line — which is a wonderful way to spend an afternoon.
	 * A bearing has no poles and no collision check, and a swept arm is horizontal travel just the
	 * same.
	 */
	private static void bearingRig(GameTestHelper helper) {
		helper.setBlock(BEARING, AllBlocks.MECHANICAL_BEARING.getDefaultState()
			.setValue(BlockStateProperties.FACING, Direction.UP));
		// Slime, not stone. A contraption only collects blocks that are *attached* -- glued, chassis'd
		// or sticky -- and a line of plain blocks sitting next to each other is none of those, so a
		// stone arm assembles as a one-block contraption with the Extruder left behind in the world.
		for (int i = 0; i < ARM_LENGTH; i++)
			helper.setBlock(ARM.east(i), Blocks.SLIME_BLOCK);
		BlockPos tip = ARM.east(ARM_LENGTH);
		helper.setBlock(tip, extruderFacing(Direction.DOWN));
		fill(helper, tip, Integer.MAX_VALUE);

		helper.setBlock(BEARING_DRIVER, AllBlocks.CREATIVE_MOTOR.getDefaultState()
			.setValue(BlockStateProperties.FACING, Direction.UP));
		motor(helper, BEARING_DRIVER).generatedSpeed.setValue(BEARING_RPM);
	}

	/**
	 * The contraption in the rig, found by looking for it rather than by asking the block that made
	 * it: a bearing keeps its {@code movedContraption} protected, and searching the test's own bounds
	 * works for any contraption type without reaching into Create's internals.
	 */
	private static StrataMemory contraptionMemoryNear(GameTestHelper helper, BlockPos around) {
		// The test's own bounds, and nothing wider: GameTest rigs are spaced eight blocks apart, so
		// any generous radius around a rig reaches into the neighbouring test's contraptions.
		List<AbstractContraptionEntity> found = helper.getLevel()
			.getEntitiesOfClass(AbstractContraptionEntity.class, helper.getBounds());
		for (AbstractContraptionEntity entity : found)
			if (entity.hasData(TerraformAttachments.STRATA_MEMORY))
				return entity.getData(TerraformAttachments.STRATA_MEMORY);

		StringBuilder what = new StringBuilder();
		for (AbstractContraptionEntity entity : found)
			what.append(" [")
				.append(entity.getContraption()
					.getBlocks()
					.size())
				.append(" blocks]");
		throw new IllegalStateException("no contraption near " + around + " carries a strata memory; "
			+ found.size() + " contraption(s) in bounds:" + what);
	}

	private static TerraformExtruderBlockEntity extruder(GameTestHelper helper) {
		return extruder(helper, EXTRUDER);
	}

	private static TerraformExtruderBlockEntity extruder(GameTestHelper helper, BlockPos pos) {
		if (helper.getBlockEntity(pos) instanceof TerraformExtruderBlockEntity be)
			return be;
		throw new IllegalStateException("no Terraform Extruder at " + pos);
	}

	private static CreativeMotorBlockEntity motor(GameTestHelper helper, BlockPos pos) {
		if (helper.getBlockEntity(pos) instanceof CreativeMotorBlockEntity be)
			return be;
		throw new IllegalStateException("no Creative Motor at " + pos);
	}

	private static int fill(GameTestHelper helper, int amount) {
		return fill(helper, EXTRUDER, amount);
	}

	/** @return millibuckets the tank actually took. */
	private static int fill(GameTestHelper helper, BlockPos pos, int amount) {
		return extruder(helper, pos).getTankCapability()
			.fill(new FluidStack(TerraformFluids.MINERAL_SUBSTRATE.get(), amount), FluidAction.EXECUTE);
	}

	private static StrataMemory contraptionMemory(GameTestHelper helper) {
		if (!(helper.getBlockEntity(PULLEY) instanceof PulleyBlockEntity pulley))
			throw new IllegalStateException("no Rope Pulley at " + PULLEY);
		AbstractContraptionEntity entity = pulley.getAttachedContraption();
		if (entity == null)
			throw new IllegalStateException("the pulley has no contraption attached; speed "
				+ pulley.getSpeed() + ", offset " + pulley.getInterpolatedOffset(1.0F));
		if (!entity.hasData(TerraformAttachments.STRATA_MEMORY))
			throw new IllegalStateException("the contraption carries no strata memory; "
				+ "the Extruder's movement behaviour never ran");
		return entity.getData(TerraformAttachments.STRATA_MEMORY);
	}
}
