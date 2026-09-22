package com.createterraform.extruder;

import com.createterraform.TerraformConfig;
import com.createterraform.client.TerraformExtruderRenderer;
import com.createterraform.registry.TerraformAttachments;
import com.createterraform.registry.TerraformFluids;
import com.createterraform.strata.PlacementRules;
import com.createterraform.strata.StrataMemory;
import com.createterraform.strata.VirtualChunkCache;
import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.render.ContraptionMatrices;
import com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

/**
 * A Terraform Extruder riding a contraption.
 *
 * <p>Create drives this: {@code AbstractContraptionEntity.tickActors} works out where each actor's
 * active area is in the world, notices when that has crossed into a new block, and calls
 * {@link #visitNewPosition}. One call per coordinate entered is exactly the contract a printer wants
 * — a Rope Pulley descending at any speed prints every block of the shaft once, and a pulley crawling
 * slowly does not print the same block forty times.
 *
 * <h2>One signature for the whole machine</h2>
 * The {@link StrataMemory} lives on the contraption entity, not on any Extruder, so every Extruder on
 * board samples the same displaced world. That is what makes a wide print come out as one continuous
 * piece of rock: a cave that starts under the first Extruder carries on under the fourth, and a vein
 * clipped by one is finished by its neighbour.
 *
 * <h2>The Seismic Shift</h2>
 * The memory also records every global coordinate the machine has printed into. Arriving at one it
 * has already printed means the machine is being driven back over its own work — a Rope Pulley
 * reversed up the shaft it just came down, which without this would be an unlimited reroll on the
 * same column of ore. So it vents: {@link StrataMemory#shift} forgets everything and rolls a new
 * signature, and the print carries on into rock that is genuinely somewhere else.
 *
 * <p>It is a reroll rather than a refusal on purpose. Refusing would make a reversed pulley print
 * nothing, which reads as a broken machine; rerolling means the player still gets rock on the way
 * back up, just not the same rock twice. The same shift fires on
 * {@link StrataMemory#isOverFull() size alone}, which is what keeps a tunnel bore that never crosses
 * its own path from growing the history without bound.
 *
 * <h2>Fuel</h2>
 * Substrate comes from the contraption's own tanks — glue a Fluid Tank to the machine — and not from
 * the Extruder's internal buffer, which is unreachable while assembled and comes back with its
 * contents when the contraption is taken apart. One tank, wherever it is on the contraption, feeds
 * every Extruder on it.
 */
public class ExtruderMovementBehaviour implements MovementBehaviour {

	/**
	 * Two blocks ahead, which is Create's Deployer exactly — {@code DeployerMovementBehaviour}
	 * returns {@code scale(2.0)} and the stationary one acts on {@code relative(facing, 2)}.
	 *
	 * <p>The Drill's 0.65 was the wrong model to copy. A Drill is pressed against the rock it
	 * breaks; a machine of this shape works at arm's length, leaving the block in front empty for
	 * the ram to travel through, which is the whole reason it looks like it reaches.
	 */
	private static final double ACTIVE_AREA_REACH = TerraformExtruderBlockEntity.REACH;

	/**
	 * Starts on the contraption's first chunk the moment it assembles.
	 *
	 * <p>{@link #visitNewPosition} predicts every miss after the first from the motion vector, but it
	 * cannot predict the first: at assembly there is no motion and nothing has been read, so without
	 * this the opening block of a print is the one that pays for a whole grid on the server thread.
	 * Create calls this a tick or more before the first placement, which is usually enough.
	 *
	 * <p>Rolling the signature here rather than at the first print is deliberate and free: a
	 * contraption's signature is a property of the assembly, and it has to exist before anything can
	 * be generated for it.
	 */
	@Override
	public void startMoving(MovementContext context) {
		if (!(context.world instanceof ServerLevel level))
			return;
		AbstractContraptionEntity entity = context.contraption.entity;
		if (entity == null)
			return;
		StrataMemory memory = entity.getData(TerraformAttachments.STRATA_MEMORY);
		BlockPos here = context.contraption.anchor.offset(context.localPos);
		VirtualChunkCache.of(level)
			.warm(memory.signature(level.random), here);
	}

	@Override
	public Vec3 getActiveAreaOffset(MovementContext context) {
		return Vec3.atLowerCornerOf(TerraformExtruderBlock.getFacing(context.state)
			.getNormal())
			.scale(ACTIVE_AREA_REACH);
	}

	/**
	 * The stationary renderer must not run out here, because the only thing it knows how to read is a
	 * kinetic speed.
	 *
	 * <p>An actor belongs to no rotational network — a contraption is driven by whatever is carrying
	 * it, not by a shaft — so {@code getAngleForBe} would return whatever speed happened to be saved
	 * in the machine's NBT the instant it was assembled, and hold it there. A rig frozen mid-turn
	 * while the contraption moves is worse than one that does not move at all, because it looks
	 * broken rather than simply plain.
	 *
	 * <p>Create's Drill, Saw, Deployer, Harvester and Roller all do this, and all for the same
	 * reason: a block with a {@link MovementBehaviour} that draws its own moving parts draws them in
	 * {@link #renderInContraption}, where the contraption's own motion is in reach.
	 */
	@Override
	public boolean disableBlockEntityRendering() {
		return true;
	}

	/**
	 * Draws the spindle, the barrel and the mud.
	 *
	 * <p>Deliberately unguarded. Create's own Drill wraps this in
	 * {@code VisualizationManager.supportsVisualization} and falls through to a
	 * {@code DrillActorVisual} when Flywheel is running; there is no Extruder visual, so there is
	 * nothing to defer to, and guarding would leave an assembled machine headless on every backend
	 * but the fallback. {@code ContraptionEntityRenderer} calls this either way.
	 */
	@Override
	public void renderInContraption(MovementContext context, VirtualRenderWorld renderWorld,
		ContraptionMatrices matrices, MultiBufferSource buffer) {
		TerraformExtruderRenderer.renderInContraption(context, renderWorld, matrices, buffer);
	}

	/**
	 * Create fires this once for each new block position the machine's active area enters.
	 *
	 * <p>Once per <em>changed grid position</em>, to be exact: {@code tickActors} compares this tick's
	 * position with last tick's, so a contraption moving faster than a block per tick skips what it
	 * passed over. That is deliberately not corrected here. It is how every actor Create ships
	 * behaves — a Deployer and a Drill skip exactly the same way — and an Extruder that quietly did
	 * something cleverer would be the odd one out on a machine built from Create's parts. Run it
	 * slower if you want every block.
	 */
	@Override
	public void visitNewPosition(MovementContext context, BlockPos pos) {
		if (!(context.world instanceof ServerLevel level))
			return;
		AbstractContraptionEntity entity = context.contraption.entity;
		if (entity == null)
			return;

		BlockState existing = level.getBlockState(pos);
		if (!PlacementRules.canDisplace(existing))
			return;

		IFluidHandler tanks = context.contraption.getStorage()
			.getFluids();
		if (tanks == null)
			return;
		FluidStack cost = substrate();
		if (tanks.drain(cost, FluidAction.SIMULATE)
			.getAmount() < cost.getAmount())
			return;

		StrataMemory memory = entity.getData(TerraformAttachments.STRATA_MEMORY);

		// Claimed before sampling, so the shift has already happened by the time a signature is
		// asked for and the reprint comes out of the new world rather than the old one.
		if (!memory.claim(pos)) {
			seismicShift(level, pos, memory);
			memory.claim(pos);
		}

		long signature = memory.signature(level.random);
		VirtualChunkCache chunks = VirtualChunkCache.of(level);
		BlockState strata = chunks.sample(signature, pos);

		// Now that this block is dealt with, start on the chunk the machine is heading into. The
		// motion vector is Create's own, recomputed every tick in `shouldActorTrigger`, so it is
		// where the contraption is actually going rather than where it was pointed when it
		// assembled.
		chunks.prefetch(signature, pos, context.motion);

		if (strata == existing)
			return;

		tanks.drain(cost, FluidAction.EXECUTE);
		level.setBlock(pos, strata, PlacementRules.PLACEMENT_FLAGS);
		level.playSound(null, pos, strata.getSoundType()
			.getPlaceSound(), SoundSource.BLOCKS, 0.35F, 0.7F);

		if (memory.isOverFull())
			seismicShift(level, pos, memory);
	}

	/**
	 * Vent, forget, reroll.
	 *
	 * <p>The sound is two of Create's own layered — the steam vent it already uses for a boiler
	 * letting go, under a crushing-wheel grind — rather than an ogg of this mod's own. Nothing here
	 * needs a sound nobody has heard before, and a player who runs Create already reads both of these
	 * as "a machine has just done something heavy".
	 */
	private static void seismicShift(ServerLevel level, BlockPos pos, StrataMemory memory) {
		memory.shift(level.random);

		AllSoundEvents.STEAM.playOnServer(level, pos, 1.2F, 0.55F);
		AllSoundEvents.CRUSHING_1.playOnServer(level, pos, 0.9F, 0.5F);
		level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, pos.getX() + 0.5, pos.getY() + 0.5,
			pos.getZ() + 0.5, 12, 0.35, 0.35, 0.35, 0.02);
	}

	private static FluidStack substrate() {
		return new FluidStack(TerraformFluids.MINERAL_SUBSTRATE.get(), TerraformConfig.substratePerBlock());
	}
}
