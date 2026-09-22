package com.createterraform.client.ponder;

import com.createterraform.extruder.TerraformExtruderBlockEntity;
import com.createterraform.registry.TerraformFluids;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * The two scenes, and between them the only two things about this machine a Create player cannot
 * guess: that what it places comes out of the world's own terrain generation rather than out of an
 * inventory, and that standing still and riding a contraption are governed by different rules.
 *
 * <p>Everything else the block does is a Deployer's, deliberately — the reach, the rate, the
 * behaviour at speed — and a scene explaining those would be teaching Create rather than this.
 *
 * <p>The layouts match {@code tools/generate_ponder.py}, which writes the structures they play in.
 *
 * <h2>Nothing in a ponder scene rotates by itself</h2>
 * A ponder level is client-side, and every path that assigns a kinetic speed is server-gated —
 * {@code KineticBlockEntity#tick} only calls {@code attachKinetics()} under {@code !isClientSide},
 * and {@code RotationPropagator} bails the same way. So the Creative Motors in these structures
 * never drive anything on their own, and {@code KineticBlockEntityRenderer#getAngleForBe} reads the
 * speed straight off the block entity. Create's answer is to fake it: {@code setKineticSpeed} writes
 * the {@code Speed} tag the renderer reads, and it lives on {@link CreateSceneBuilder} rather than on
 * Ponder's own builder, which is why both storyboards open by wrapping the builder they are handed.
 *
 * <p>The motors are in the structures all the same. They are what makes the rig honest: a scene that
 * showed an Extruder turning with nothing attached to it would be teaching that the machine does not
 * need rotation, which is the first thing these scenes say it does.
 *
 * <p>Nothing needs zeroing first, unlike Create's scenes: their structures are captured out of
 * running worlds and arrive carrying real speeds, whereas {@code generate_ponder.py} writes no
 * {@code Speed} tag at all, so everything starts at rest and returns there when a scene replays.
 */
public class ExtruderScenes {

	// --- the stationary rig, laid out along +X at z=3 ------------------------------------------

	private static final BlockPos EXTRUDER = new BlockPos(2, 1, 3);
	/** Two east of the Extruder, which is {@link TerraformExtruderBlockEntity#REACH}. */
	private static final BlockPos TARGET = new BlockPos(4, 1, 3);
	private static final BlockPos DRILL = new BlockPos(5, 1, 3);

	// --- the contraption rig, hanging in column x=1 --------------------------------------------

	private static final BlockPos PULLEY = new BlockPos(1, 5, 2);
	private static final BlockPos CARRIAGE = new BlockPos(1, 4, 2);
	/** The column the carriage paints, two east of it, at each height it passes. */
	private static final int PRINTED_X = 3;
	private static final int PRINTED_Z = 2;

	/**
	 * Fast enough to read as running, slow enough that the barrel's lead is visible rather than a
	 * blur. A Creative Motor generates 16 untouched, which is what the structure places; this is
	 * scrolled up, and a scene is allowed to show a machine somebody has tuned.
	 */
	private static final float RPM = 64;

	/**
	 * What the machine prints, in the order it prints it.
	 *
	 * <p>Stone, then ore, then deepslate is not a random assortment — it is a column of world read
	 * top to bottom, which is the one idea both scenes exist to get across. A scene that printed the
	 * same block every cycle would be showing a block dispenser.
	 */
	private static final BlockState[] STRATA = {
		Blocks.STONE.defaultBlockState(),
		Blocks.IRON_ORE.defaultBlockState(),
		Blocks.DEEPSLATE.defaultBlockState(),
		Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState(),
	};

	/** What the same column comes out as after a Seismic Shift: somewhere else entirely. */
	private static final BlockState[] REROLLED = {
		Blocks.DEEPSLATE.defaultBlockState(),
		Blocks.TUFF.defaultBlockState(),
		Blocks.DEEPSLATE.defaultBlockState(),
		Blocks.DEEPSLATE_COPPER_ORE.defaultBlockState(),
	};

	public static void printing(SceneBuilder builder, SceneBuildingUtil util) {
		CreateSceneBuilder scene = new CreateSceneBuilder(builder);
		scene.title("terraform_extruder", "Printing terrain with a Terraform Extruder");
		scene.configureBasePlate(0, 0, 7);
		scene.showBasePlate();
		scene.idle(5);

		Selection drive = util.select()
			.fromTo(0, 1, 3, 2, 1, 3);
		Selection harvester = util.select()
			.fromTo(5, 1, 3, 6, 1, 3);

		scene.world()
			.showSection(drive, Direction.DOWN);
		scene.idle(10);
		scene.world()
			.setKineticSpeed(drive, RPM);
		scene.overlay()
			.showText(70)
			.text("The Terraform Extruder runs on rotation and a fluid called Mineral Substrate")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(util.vector()
				.blockSurface(EXTRUDER, Direction.UP));
		scene.idle(80);

		fillTank(scene, EXTRUDER);
		scene.idle(15);

		scene.overlay()
			.showOutline(PonderPalette.BLUE, TARGET, util.select()
				.position(TARGET), 60);
		scene.overlay()
			.showText(70)
			.text("It places a block two spaces in front of itself, at the same rate a Deployer works")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(util.vector()
				.centerOf(TARGET));
		scene.idle(80);

		scene.world()
			.setBlock(TARGET, STRATA[0], false);
		scene.idle(20);
		scene.overlay()
			.showText(100)
			.text("What it places is whatever your world's own terrain generation would have put there. Ore turns up at its usual rarity and depth, caves come out cave-shaped, and ore from other mods needs no setup")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(util.vector()
				.blockSurface(TARGET, Direction.UP));
		scene.idle(110);

		scene.overlay()
			.showText(80)
			.text("Standing still it will only place into empty space — never over rock, not even its own. So it fills the spot once and then waits")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(util.vector()
				.blockSurface(EXTRUDER, Direction.NORTH));
		scene.idle(90);

		scene.world()
			.showSection(harvester, Direction.DOWN);
		scene.idle(10);
		scene.world()
			.setKineticSpeed(harvester, RPM);
		scene.overlay()
			.showText(80)
			.text("Point a harvester at it and the harvester sets the pace")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(util.vector()
				.blockSurface(DRILL, Direction.UP));
		scene.idle(50);

		// Three more cycles, so the column of world reads as a column rather than as one lucky block,
		// and the tank comes down while they run, because the fuel is where this machine's price is.
		for (int cycle = 1; cycle < STRATA.length; cycle++) {
			harvest(scene);
			scene.world()
				.setBlock(TARGET, STRATA[cycle], false);
			setTank(scene, EXTRUDER, 1F - cycle * 0.2F);
			scene.idle(25);
		}

		scene.overlay()
			.showText(90)
			.text("Depth is not displaced, only the horizontal, so the machine works best underground. Up here it mostly samples open sky, and says so under goggles")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(util.vector()
				.blockSurface(EXTRUDER, Direction.WEST));
		scene.idle(100);
		scene.markAsFinished();
	}

	public static void onAContraption(SceneBuilder builder, SceneBuildingUtil util) {
		CreateSceneBuilder scene = new CreateSceneBuilder(builder);
		scene.title("extruder_contraption", "Terraform Extruders on a contraption");
		scene.configureBasePlate(0, 0, 5);
		scene.showBasePlate();
		scene.idle(5);

		Selection bank = util.select()
			.fromTo(3, 1, 1, 4, 4, 3);
		scene.world()
			.showSection(bank, Direction.DOWN);
		scene.idle(10);
		scene.world()
			.showSection(util.select()
				.position(PULLEY), Direction.DOWN);
		scene.idle(10);

		// The Extruder is shown on its own so it can be moved on its own. Everything else in the
		// scene stays where the structure put it.
		ElementLink<WorldSectionElement> carriage = scene.world()
			.showIndependentSection(util.select()
				.position(CARRIAGE), Direction.DOWN);
		scene.idle(10);

		fillTank(scene, CARRIAGE);
		scene.overlay()
			.showText(90)
			.text("Glue an Extruder to a Rope Pulley, a Gantry Carriage or a Minecart Contraption and it prints as it travels")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(util.vector()
				.blockSurface(CARRIAGE, Direction.WEST));
		scene.idle(100);

		scene.overlay()
			.showText(80)
			.text("On the move it prints over whatever is already there, rather than only into empty space — which is the whole job, since displaced strata have to go somewhere")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(util.vector()
				.blockSurface(new BlockPos(PRINTED_X, 4, PRINTED_Z), Direction.WEST));
		scene.idle(90);

		descend(scene, carriage, STRATA);

		scene.overlay()
			.showText(90)
			.text("It runs off the contraption's own tanks, and several Extruders on one contraption share a seed, so a wide print comes out as one piece of rock rather than in patches")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(util.vector()
				.blockSurface(CARRIAGE, Direction.WEST));
		scene.idle(100);

		scene.overlay()
			.showText(90)
			.text("Driving back over ground it has already printed rerolls the offset, so the same vein cannot be farmed twice")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(util.vector()
				.topOf(PULLEY));
		scene.idle(60);

		seismicShift(scene, util);
		ascend(scene, carriage, REROLLED);
		scene.markAsFinished();
	}

	// --- the moving parts ----------------------------------------------------------------------

	/**
	 * Descends one block at a time, printing the block it is level with before it moves off it.
	 *
	 * <p>One placement per position entered is Create's contract rather than this scene's licence:
	 * {@code tickActors} calls {@code visitNewPosition} once for each grid position an actor's active
	 * area crosses into, so a pulley descending a shaft really does print every block of it exactly
	 * once.
	 */
	private static void descend(CreateSceneBuilder scene, ElementLink<WorldSectionElement> carriage,
		BlockState[] strata) {
		for (int step = 0; step < strata.length; step++) {
			int y = CARRIAGE.getY() - step;
			scene.world()
				.setBlock(new BlockPos(PRINTED_X, y, PRINTED_Z), strata[step], false);
			// Down but never dry: the rig has the return trip to make, and a machine printing out of
			// an empty tank is the one thing the mud window must never show.
			setTank(scene, CARRIAGE, 1F - (step + 1) / (float) (strata.length + 2));
			scene.idle(15);
			if (step == strata.length - 1)
				break;
			scene.world()
				.movePulley(PULLEY, 1, 20);
			scene.world()
				.moveSection(carriage, new Vec3(0, -1, 0), 20);
			scene.idle(20);
		}
	}

	/** The same run in reverse, laying the rerolled column as it goes back up. */
	private static void ascend(CreateSceneBuilder scene, ElementLink<WorldSectionElement> carriage,
		BlockState[] strata) {
		for (int step = strata.length - 1; step >= 0; step--) {
			int y = CARRIAGE.getY() - step;
			scene.world()
				.setBlock(new BlockPos(PRINTED_X, y, PRINTED_Z), strata[step], false);
			scene.idle(15);
			if (step == 0)
				break;
			scene.world()
				.movePulley(PULLEY, -1, 20);
			scene.world()
				.moveSection(carriage, new Vec3(0, 1, 0), 20);
			scene.idle(20);
		}
	}

	/**
	 * The vent.
	 *
	 * <p>Sound and particles both, because the shift is the one thing the machine does that a player
	 * has to be able to notice without watching the blocks: it is an anti-exploit, and an anti-exploit
	 * that fires silently reads as the machine having broken.
	 */
	private static void seismicShift(CreateSceneBuilder scene, SceneBuildingUtil util) {
		Vec3 at = util.vector()
			.centerOf(CARRIAGE);
		scene.effects()
			.emitParticles(at, scene.effects()
				.simpleParticleEmitter(ParticleTypes.CAMPFIRE_COSY_SMOKE, new Vec3(0, 0.15, 0)), 12, 20);
		scene.overlay()
			.showOutline(PonderPalette.RED, CARRIAGE, util.select()
				.position(CARRIAGE), 40);
		scene.idle(30);
	}

	/** Cracks the printed block and takes it, which is what the Drill in the structure is for. */
	private static void harvest(CreateSceneBuilder scene) {
		for (int i = 0; i < 4; i++) {
			scene.world()
				.incrementBlockBreakingProgress(TARGET);
			scene.idle(5);
		}
		scene.world()
			.destroyBlock(TARGET);
		scene.idle(10);
	}

	// --- the mud -------------------------------------------------------------------------------

	private static void fillTank(CreateSceneBuilder scene, BlockPos pos) {
		setTank(scene, pos, 1F);
	}

	/**
	 * Sets what is in the tank, so the open mud window says something.
	 *
	 * <p>Through {@code setTankContentsForDisplay} rather than {@code setTankContents}: the latter
	 * calls {@code notifyUpdate}, which marks a chunk dirty and sends a block update, and a ponder
	 * level has neither to offer.
	 */
	private static void setTank(CreateSceneBuilder scene, BlockPos pos, float fill) {
		scene.world()
			.modifyBlockEntity(pos, TerraformExtruderBlockEntity.class,
				extruder -> extruder.setTankContentsForDisplay(
					new FluidStack(TerraformFluids.MINERAL_SUBSTRATE.get(),
						Math.round(extruder.getTankCapacity() * fill))));
	}
}
