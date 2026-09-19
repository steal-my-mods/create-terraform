package com.createterraform.client;

import com.createterraform.extruder.ExtruderIdleReason;
import com.createterraform.extruder.TerraformExtruderBlock;
import com.createterraform.extruder.TerraformExtruderBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;

import net.createmod.catnip.math.AngleHelper;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Drives the rig.
 *
 * <p>Three moving parts on two axes, and the split is the machine's whole explanation of itself. The
 * bevel gears turn about the <em>rotation</em> axis, which on this machine goes in the sides. The
 * chuck and the barrel turn about the <em>facing</em>, a quarter away from it — that quarter is what
 * the bevel is for, and it is why a shaft can come in the side of a machine whose core comes out the
 * front. The barrel alone also advances, so it slides through a chuck that stays put, which is what
 * makes this read as a drill rather than a piston.
 *
 * <p>The casing model holds none of them — it is the box they move in, with a bore through each side
 * wall for the gears, a wide bore through the front for the chuck, and a slot in the lid for the mud.
 * At full extension the cutting head is inside the block being printed into and the string bridges
 * the gap, which is the arrangement every Create machine of this kind uses — a Deployer's pole is
 * modelled from z=-9 to 12 and its hand from 12 to 25, both deliberately past the block boundary.
 *
 * <h2>Where the timing comes from</h2>
 * Nothing is synced for any of it, and nothing needs to be. Both the spin and the travel are
 * functions of one angle, and that angle comes from the machine's own speed, which is already on the
 * client because every kinetic block needs it. So the rig turns in time with the shaft at whatever
 * RPM it is running, and costs not one packet.
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
		//
		// One axis serves all of it. The drive is coaxial with the barrel it turns, so the rotation axis
		// IS the facing, and both partials are authored pointing up -- whichever way the machine points
		// becomes their local up, and the spin is about that local up.
		//
		// Which means the sign has to be put right by hand. getAngleForBe measures the angle about the
		// POSITIVE direction of the axis, and that is what Create's own kineticRotationTransform turns
		// about: rotateCentered(angle, Direction.get(POSITIVE, axis)). Our partials are swung onto the
		// FACING, and for north, west and down that is the negative direction — so the same angle
		// applied there turns the machine backwards against the very shaft driving it. Negating it is
		// exactly equivalent to rotating about the positive direction instead.
		float spin = AngleHelper.deg(
			KineticBlockEntityRenderer.getAngleForBe(be, be.getBlockPos(), facing.getAxis()));
		if (facing.getAxisDirection() == AxisDirection.NEGATIVE)
			spin = -spin;

		// The shaft stub in the back bore. It turns and stays put. Four pixels in a six-pixel bore,
		// which is Create's own proportion and not an accident: a four-wide square sweeps its corners
		// 2.83 from centre and the bore's half-width is 3, so it turns without touching the casing.
		oriented(TerraformPartials.EXTRUDER_SHAFT, state, facing).rotateYDegrees(spin)
			.uncenter()
			.light(light)
			.renderInto(ms, vb);

		// The barrel: the same spin, and the lead on top of it, so it slides through the chuck.
		// Uncentre before translating -- the last transform named is the first applied, so this moves
		// along the barrel's own axis rather than the world's, and spins about that axis rather than
		// orbiting it.
		oriented(TerraformPartials.EXTRUDER_BARREL, state, facing).rotateYDegrees(spin)
			.uncenter()
			.translate(0F, lead(spin), 0F)
			.light(light)
			.renderInto(ms, vb);

		renderGauge(be, facing, state, ms, vb, light);
	}

	/**
	 * The mud in the tank, seen straight down into it, because the tank has no lid.
	 *
	 * <p>Squashed rather than slid, which is why this one goes through the {@link PoseStack} instead
	 * of the buffer's own transforms: mud translated back to show an empty tank has to have somewhere
	 * to hide, and a machine this size has nowhere. Scaling about the back of the tank drains it away
	 * from the barrel and leaves the tank floor showing, which is what an empty gauge should look
	 * like.
	 *
	 * <p>There is one casing model now, so this no longer has to be symmetric about the facing to
	 * serve two of them — the tank is simply open at the top and the mud is visible straight down
	 * into it.
	 */
	private static void renderGauge(TerraformExtruderBlockEntity be, Direction facing, BlockState state,
		PoseStack ms, VertexConsumer vb, int light) {
		float fill = be.getFillLevel();
		if (fill <= 0.001F)
			return;

		ms.pushPose();
		ms.translate(0.5, 0.5, 0.5);
		ms.mulPose(com.mojang.math.Axis.YP.rotationDegrees(AngleHelper.horizontalAngle(facing)));
		ms.mulPose(com.mojang.math.Axis.XP.rotationDegrees(AngleHelper.verticalAngle(facing) + 90));
		ms.translate(-0.5, -0.5, -0.5);

		// Squash towards the back of the tank, so it drains away from the cutting head.
		ms.translate(0, GAUGE_START, 0);
		ms.scale(1F, fill, 1F);
		ms.translate(0, -GAUGE_START, 0);

		CachedBuffers.partial(TerraformPartials.EXTRUDER_GAUGE, state)
			.light(light)
			.renderInto(ms, vb);
		ms.popPose();
	}

	/** Centred and swung to face {@code facing}, still centred so the caller can spin it. */
	private static SuperByteBuffer oriented(PartialModel model, BlockState state, Direction facing) {
		return CachedBuffers.partial(model, state)
			.center()
			.rotateYDegrees(AngleHelper.horizontalAngle(facing))
			.rotateXDegrees(AngleHelper.verticalAngle(facing) + 90);
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
