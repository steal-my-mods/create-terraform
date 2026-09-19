package com.createterraform.extruder;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.createterraform.registry.TerraformFluids;
import com.createterraform.registry.TerraformMountedStorage;
import com.simibubi.create.api.contraption.storage.fluid.MountedFluidStorageType;
import com.simibubi.create.api.contraption.storage.fluid.WrapperMountedFluidStorage;

import net.minecraft.core.BlockPos;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import org.jetbrains.annotations.Nullable;

/**
 * An Extruder's own tank, while the machine is riding a contraption.
 *
 * <p>Without this the buffer would simply be luggage: a contraption's fluid pool is built from the
 * storages Create mounts at assembly, and anything else is inert NBT until the machine is taken
 * apart again. Mounting it means a lone Extruder glued to a Rope Pulley runs off the substrate it
 * was already carrying, and — because the pool is shared — a Fluid Tank anywhere on the contraption
 * tops up every Extruder on it. One tank feeds the whole machine either way; this is only about
 * whether the machine has to have one at all.
 *
 * <p>{@link #unmount} puts what is left back where it came from, so a print that runs dry does not
 * also lose the dregs.
 */
public class ExtruderMountedStorage extends WrapperMountedFluidStorage<FluidTank> {

	public static final MapCodec<ExtruderMountedStorage> CODEC = RecordCodecBuilder.mapCodec(
		instance -> instance
			.group(ExtraCodecs.NON_NEGATIVE_INT.fieldOf("capacity")
				.forGetter(ExtruderMountedStorage::getCapacity),
				FluidStack.OPTIONAL_CODEC.fieldOf("fluid")
					.forGetter(ExtruderMountedStorage::getFluid))
			.apply(instance, ExtruderMountedStorage::new));

	protected ExtruderMountedStorage(int capacity, FluidStack contents) {
		super(TerraformMountedStorage.EXTRUDER.get(), tank(capacity, contents));
	}

	private static FluidTank tank(int capacity, FluidStack contents) {
		FluidTank tank = new FluidTank(capacity, stack -> stack.getFluid()
			.isSame(TerraformFluids.MINERAL_SUBSTRATE.get()));
		tank.setFluid(contents.copy());
		return tank;
	}

	public FluidStack getFluid() {
		return wrapped.getFluid();
	}

	public int getCapacity() {
		return wrapped.getCapacity();
	}

	@Override
	public void unmount(Level level, BlockState state, BlockPos pos, @Nullable BlockEntity be) {
		if (be instanceof TerraformExtruderBlockEntity extruder)
			extruder.setTankContents(getFluid());
	}

	public static ExtruderMountedStorage fromExtruder(TerraformExtruderBlockEntity extruder) {
		return new ExtruderMountedStorage(extruder.getTankCapacity(), extruder.getTankContents()
			.copy());
	}

	/** The type, whose only job is to say which block entities this storage can be made from. */
	public static class Type extends MountedFluidStorageType<ExtruderMountedStorage> {

		public Type() {
			// Qualified: the unqualified name resolves to MountedFluidStorageType's own dispatch
			// codec, which compiles as far as the constructor and then means nothing here.
			super(ExtruderMountedStorage.CODEC);
		}

		@Nullable
		@Override
		public ExtruderMountedStorage mount(Level level, BlockState state, BlockPos pos, @Nullable BlockEntity be) {
			return be instanceof TerraformExtruderBlockEntity extruder ? fromExtruder(extruder) : null;
		}
	}
}
