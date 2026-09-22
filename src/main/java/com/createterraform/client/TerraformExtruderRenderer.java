package com.createterraform.client;

import com.createterraform.extruder.ExtruderIdleReason;
import com.createterraform.extruder.TerraformExtruderBlock;
import com.createterraform.extruder.TerraformExtruderBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.render.ContraptionMatrices;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld;

import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Drives the rig.
 *
 * <p>Three moving parts on one axis. The shaft stub and the barrel both turn about the
 * <em>facing</em>, because the drive is coaxial with the barrel it turns; the barrel alone also
 * advances, sliding through a chuck that stays where it is, which is what makes the machine read as
 * a drill rather than a piston. The third is the mud, squashed to whatever is in the tank.
 *
 * <p>The casing model holds none of them — it is the box they move in, with a bore through the back
 * plate for the shaft stub, a wider bore through the front the barrel runs out of, and an open tank
 * on the deck above.
 *
 * <h2>Two ways in, one rig</h2>
 * {@link #renderSafe} draws the machine standing still and {@link #renderInContraption} draws it
 * riding one, and the only thing that differs between them is where the angle comes from and which
 * matrices the result is submitted through. Everything about the geometry — the swing onto the
 * facing, the spin, the lead, the squash of the mud — is shared, because two copies of it would
 * drift and the drift would be invisible until somebody assembled a machine and looked at it.
 *
 * <h2>Where the timing comes from</h2>
 * Nothing is synced for any of it, and nothing needs to be. Standing still, both the spin and the
 * travel are functions of one angle, and that angle comes from the machine's own speed, which is
 * already on the client because every kinetic block needs it. On a contraption there is no kinetic
 * speed to read — an actor is not part of any rotational network — so the angle comes from how fast
 * the contraption is moving, which is what Create's own Drill does and for the same reason.
 *
 * <p>Deriving the travel from the angle rather than from a timer beside it is what keeps the two
 * from drifting apart. An earlier version pumped a ram on a cycle timer, which meant the stroke and
 * the spin agreed only by arithmetic, the stroke had to be suppressed whenever the machine was not
 * actually printing — a machine punching while nothing comes out looks broken — and {@link
 * ExtruderIdleReason} had to be synced to know that. A threaded barrel has no such problem: it
 * advances because it is turning, so it stops when the shaft stops and never claims anything about
 * what is coming out. {@code ExtruderIdleReason} is still synced, but now only for the goggle
 * overlay, which is a thing a player asks for rather than something the block asserts.
 */
public class TerraformExtruderRenderer extends SafeBlockEntityRenderer<TerraformExtruderBlockEntity> {

	/**
	 * How far the barrel walks along its own axis over one turn, in model units.
	 *
	 * <p>Deliberately tiny. This is a <em>lead</em>, not a stroke: the barrel is threaded, so turning
	 * it walks it forward and back by a pixel and a half and no more. An earlier version pumped it
	 * eighteen pixels on a cycle timer, which is what a Press or a Deployer does and what a drill
	 * emphatically does not.
	 *
	 * <p>At rest the cutting head stops one pixel short of the block being printed into. A pixel and
	 * a half of lead is therefore exactly enough to close that gap at the top of every turn, so the
	 * machine is seen to bite what it is working on without ever looking like it is punching it.
	 */
	private static final float LEAD = 1.5F / 16F;

	/**
	 * Where the mud in the tank starts along the machine's own axis, in model units. Must match
	 * {@code GAUGE} in tools/generate_models.py, which is what sizes the block of mud in the tank.
	 */
	private static final float GAUGE_START = 3.3F / 16F;

	/** Below this there is not enough mud left to be worth a draw call, or to see. */
	private static final float EMPTY = 0.001F;

	public TerraformExtruderRenderer(Context context) {
	}

	@Override
	protected void renderSafe(TerraformExtruderBlockEntity be, float partialTicks, PoseStack ms,
		MultiBufferSource buffer, int light, int overlay) {
		BlockState state = be.getBlockState();
		Direction facing = TerraformExtruderBlock.getFacing(state);
		VertexConsumer vb = buffer.getBuffer(RenderType.solid());

		// Everything here turns whenever the shaft does, printing or not. That is the machine's only
		// sign of life while it waits on mud, and a block that takes rotation should look like it takes
		// rotation.
		float spin = aboutFacing(AngleHelper.deg(
			KineticBlockEntityRenderer.getAngleForBe(be, be.getBlockPos(), facing.getAxis())), facing);

		shaft(CachedBuffers.partial(TerraformPartials.EXTRUDER_SHAFT, state), facing, spin).light(light)
			.renderInto(ms, vb);
		barrel(CachedBuffers.partial(TerraformPartials.EXTRUDER_BARREL, state), facing, spin).light(light)
			.renderInto(ms, vb);

		float fill = be.getFillLevel();
		if (fill <= EMPTY)
			return;
		ms.pushPose();
		poseGauge(ms, facing, fill);
		CachedBuffers.partial(TerraformPartials.EXTRUDER_GAUGE, state)
			.light(light)
			.renderInto(ms, vb);
		ms.popPose();
	}

	/**
	 * The same rig, on a contraption, drawn straight rather than instanced.
	 *
	 * <p>Create calls this for every actor on every backend — {@code ContraptionEntityRenderer}
	 * skips only the structure buffer when Flywheel is visualizing, and runs the actors either way.
	 * Its own Drill guards the body of this method with {@code supportsVisualization} because it has
	 * a {@code DrillActorVisual} to take over; we have no visual, so there is nothing to defer to and
	 * no guard. The cost is that the rig is not instanced, which is the same trade the stationary
	 * machine already makes.
	 *
	 * <p>Without this an assembled Extruder is its casing and nothing else. The block entity renderer
	 * above does not run out here — {@code ExtruderMovementBehaviour.disableBlockEntityRendering}
	 * turns it off, and has to: it reads a kinetic speed, and an actor belongs to no rotational
	 * network, so it would draw a head frozen at whatever the machine happened to be doing the
	 * instant it was assembled.
	 */
	public static void renderInContraption(MovementContext context, VirtualRenderWorld renderWorld,
		ContraptionMatrices matrices, MultiBufferSource buffer) {
		BlockState state = context.state;
		Direction facing = TerraformExtruderBlock.getFacing(state);
		VertexConsumer vb = buffer.getBuffer(RenderType.solid());
		int light = LevelRenderer.getLightColor(renderWorld, context.localPos);

		// Motion is the drive out here. getAnimationSpeed is Create's own reading of it, and it
		// already answers the two edge cases for us: nought when the actor has been switched off
		// from the controls, and a hard 700 while the contraption is stalled, which is how every
		// Create actor shows that it is straining against something.
		//
		// Not gated on direction, unlike the Drill's. A Drill only cuts going forwards, so Create
		// stops its head when one is dragged backwards; an Extruder prints on every position it
		// enters whichever way it is travelling, and a rig that stopped turning while it was still
		// laying rock would be lying about what it was doing.
		float spin = aboutFacing(AnimationTickHolder.getRenderTime() / 20F * context.getAnimationSpeed() % 360,
			facing);

		submit(shaft(CachedBuffers.partial(TerraformPartials.EXTRUDER_SHAFT, state)
			.transform(matrices.getModel()), facing, spin), context, matrices, vb, light);
		submit(barrel(CachedBuffers.partial(TerraformPartials.EXTRUDER_BARREL, state)
			.transform(matrices.getModel()), facing, spin), context, matrices, vb, light);

		float fill = fillLevelOf(context);
		if (fill <= EMPTY)
			return;
		PoseStack model = matrices.getModel();
		model.pushPose();
		poseGauge(model, facing, fill);
		submit(CachedBuffers.partial(TerraformPartials.EXTRUDER_GAUGE, state)
			.transform(model), context, matrices, vb, light);
		model.popPose();
	}

	/**
	 * How full the tank is, read off the block entity the contraption keeps on the client.
	 *
	 * <p>Not off {@code context.getFluidStorage()}, which looks like the obvious source and is a
	 * trap: that supplier is memoized, and a storage sync replaces the storage object rather than
	 * mutating it, so the context would go on handing back the tank as it stood at assembly for the
	 * life of the contraption. {@code ExtruderMountedStorage.afterSync} pushes each arriving load
	 * into this block entity instead, which is how Create's own Fluid Tank keeps its level honest.
	 */
	private static float fillLevelOf(MovementContext context) {
		BlockEntity be = context.contraption.getBlockEntityClientSide(context.localPos);
		return be instanceof TerraformExtruderBlockEntity extruder ? extruder.getFillLevel() : 0F;
	}

	/** Lit and handed to the contraption's matrices, which is the only part of this Create owns. */
	private static void submit(SuperByteBuffer rig, MovementContext context, ContraptionMatrices matrices,
		VertexConsumer vb, int light) {
		rig.light(light)
			.useLevelLight(context.world, matrices.getWorld())
			.renderInto(matrices.getViewProjection(), vb);
	}

	/**
	 * The shaft stub in the back bore. It turns and stays put.
	 *
	 * <p>Four pixels in a six-pixel bore, which is Create's own proportion and not an accident: a
	 * four-wide square sweeps its corners 2.83 from centre and the bore's half-width is 3, so it
	 * turns without touching the casing.
	 */
	private static SuperByteBuffer shaft(SuperByteBuffer raw, Direction facing, float spin) {
		return swung(raw, facing).rotateYDegrees(spin)
			.uncenter();
	}

	/**
	 * The barrel: the same spin, and the lead on top of it, so it slides through the chuck.
	 *
	 * <p>Uncentred before it is translated — the last transform named is the first applied — so this
	 * moves along the barrel's own axis rather than the world's, and spins about that axis rather
	 * than orbiting it.
	 */
	private static SuperByteBuffer barrel(SuperByteBuffer raw, Direction facing, float spin) {
		return swung(raw, facing).rotateYDegrees(spin)
			.uncenter()
			.translate(0F, lead(spin), 0F);
	}

	/**
	 * Centred and swung onto {@code facing}, still centred so the caller can spin it.
	 *
	 * <p>One axis serves the whole rig. The drive is coaxial with the barrel it turns, so the rotation
	 * axis <em>is</em> the facing, and both partials are authored pointing up — whichever way the
	 * machine points becomes their local up, and the spin is about that local up.
	 */
	private static SuperByteBuffer swung(SuperByteBuffer raw, Direction facing) {
		return raw.center()
			.rotateYDegrees(AngleHelper.horizontalAngle(facing))
			.rotateXDegrees(AngleHelper.verticalAngle(facing) + 90);
	}

	/**
	 * The same angle, measured the way the partials are swung.
	 *
	 * <p>{@code getAngleForBe} measures about the <em>positive</em> direction of the axis, and that is
	 * what Create's own {@code kineticRotationTransform} turns about:
	 * {@code rotateCentered(angle, Direction.get(POSITIVE, axis))}. Our partials are swung onto the
	 * facing, and for north, west and down that is the negative direction — so the same angle applied
	 * there turns the machine backwards against the very shaft driving it. Negating it is exactly
	 * equivalent to rotating about the positive direction instead, and half of all placements looked
	 * wrong until it did.
	 */
	private static float aboutFacing(float angle, Direction facing) {
		return facing.getAxisDirection() == AxisDirection.NEGATIVE ? -angle : angle;
	}

	/**
	 * Poses the mud in the tank, seen straight down into it, because the tank has no lid.
	 *
	 * <p>Squashed rather than slid, which is why this one goes through the {@link PoseStack} instead
	 * of the buffer's own transforms: mud translated back to show an empty tank has to have somewhere
	 * to hide, and a machine this size has nowhere. Scaling about the back of the tank drains it away
	 * from the barrel and leaves the tank floor showing, which is what an empty gauge should look
	 * like.
	 */
	private static void poseGauge(PoseStack ms, Direction facing, float fill) {
		ms.translate(0.5, 0.5, 0.5);
		ms.mulPose(com.mojang.math.Axis.YP.rotationDegrees(AngleHelper.horizontalAngle(facing)));
		ms.mulPose(com.mojang.math.Axis.XP.rotationDegrees(AngleHelper.verticalAngle(facing) + 90));
		ms.translate(-0.5, -0.5, -0.5);

		// Squash towards the back of the tank, so it drains away from the cutting head.
		ms.translate(0, GAUGE_START, 0);
		ms.scale(1F, fill, 1F);
		ms.translate(0, -GAUGE_START, 0);
	}

	/**
	 * How far along its axis the barrel sits, for a given angle of turn.
	 *
	 * <p>A function of the angle and nothing else, which is the whole idea: the travel comes from the
	 * rotation rather than from a timer beside it, so it cannot drift out of step with the spin the
	 * way a cycle-timed stroke can, it speeds up and slows down with the shaft for free, and it stops
	 * dead the moment the shaft does. A threaded barrel is exactly this and no more.
	 *
	 * <p>It is not gated on {@link ExtruderIdleReason} the way the old stroke was. A machine punching
	 * while nothing comes out looks broken; a drill idling and turning over does not, and a barrel
	 * that only crept forward while it happened to be printing would read as a stutter.
	 */
	private static float lead(float spin) {
		return (1F - Mth.cos(spin * Mth.DEG_TO_RAD)) * 0.5F * LEAD;
	}

	/** The rig is small; there is no point drawing it from across the map. */
	@Override
	public int getViewDistance() {
		return 96;
	}
}
