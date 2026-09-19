package com.createterraform.extruder;

import com.createterraform.registry.TerraformBlockEntities;
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.foundation.block.IBE;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;

/**
 * FACING is the face the machine prints out of; the shaft is on the opposite face and the rotation
 * axis runs between them, which is the same arrangement as Create's Drill and Deployer and means a
 * player who has built one of those already knows how to place this.
 *
 * <p>A full cube, deliberately. Every part of the shape argument for a smaller machine — seeing the
 * shaft, seeing the mechanism — is an argument about a machine that mostly sits still, and this one
 * spends its working life buried in a contraption printing into rock.
 */
public class TerraformExtruderBlock extends DirectionalKineticBlock implements IBE<TerraformExtruderBlockEntity> {

	public TerraformExtruderBlock(Properties properties) {
		super(properties);
	}

	public static Direction getFacing(BlockState state) {
		return state.getValue(FACING);
	}

	@Override
	public Axis getRotationAxis(BlockState state) {
		return getFacing(state).getAxis();
	}

	@Override
	public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
		return face == getFacing(state).getOpposite();
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
