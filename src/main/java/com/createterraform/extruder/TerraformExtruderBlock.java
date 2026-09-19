package com.createterraform.extruder;

import com.createterraform.registry.TerraformBlockEntities;
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.foundation.block.IBE;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;

/**
 * FACING is the face the core comes out of, and the shaft goes in the face directly behind it. The
 * drive is <em>coaxial</em> with the barrel it turns, which is the arrangement Create's Mechanical
 * Drill uses and the reason this extends the plain {@code DirectionalKineticBlock}.
 *
 * <p>It did not always. An earlier version put the shaft in the sides, the way a Deployer does,
 * because a Deployer's back has to stay clear for its pole to slide out of. That stopped being true
 * the day the business end became a <em>turning</em> barrel rather than a punching ram: a barrel
 * that spins about the facing wants its drive on the facing, and a shaft entering the side then
 * needs a bevel pair to explain itself. Taking the bevel out took a blockstate property and six
 * variants with it.
 *
 * <p>The casing is a drill rig — a plinth, a housing with the shaft socket in the back and the chuck
 * in the front, and an open mud tank sitting on top of it. Everything that turns is a partial model
 * drawn by {@code TerraformExtruderRenderer}. The machine works two blocks out, as a Deployer does,
 * and the barrel is what visibly crosses the gap.
 */
public class TerraformExtruderBlock extends DirectionalKineticBlock implements IBE<TerraformExtruderBlockEntity> {

	public TerraformExtruderBlock(Properties properties) {
		super(properties);
	}

	public static Direction getFacing(BlockState state) {
		return state.getValue(FACING);
	}

	/**
	 * The shaft goes in the back, and nowhere else.
	 *
	 * <p>Not merely the back <em>axis</em> — the back face. A shaft butted against the front would be
	 * sharing a face with the chuck and the barrel coming out of it, which is both a lie about what
	 * is driving what and a mess to look at.
	 */
	@Override
	public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
		return face == state.getValue(FACING)
			.getOpposite();
	}

	/** The barrel turns about the facing, so that is the axis rotation has to arrive on. */
	@Override
	public Direction.Axis getRotationAxis(BlockState state) {
		return state.getValue(FACING)
			.getAxis();
	}

	/**
	 * Pushable, and glued onto contraptions like any other actor. A machine that could not be moved
	 * would be missing half of what it is for.
	 */
	@Override
	public PushReaction getPistonPushReaction(BlockState state) {
		return PushReaction.NORMAL;
	}

	@Override
	public Class<TerraformExtruderBlockEntity> getBlockEntityClass() {
		return TerraformExtruderBlockEntity.class;
	}

	@Override
	public BlockEntityType<? extends TerraformExtruderBlockEntity> getBlockEntityType() {
		return TerraformBlockEntities.TERRAFORM_EXTRUDER.get();
	}
}
