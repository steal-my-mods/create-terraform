package com.createterraform.extruder;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.createterraform.registry.TerraformFluids;
import com.createterraform.registry.TerraformMountedStorage;
import com.simibubi.create.api.contraption.storage.SyncedMountedStorage;
import com.simibubi.create.api.contraption.storage.fluid.MountedFluidStorageType;
import com.simibubi.create.api.contraption.storage.fluid.WrapperMountedFluidStorage;
import com.simibubi.create.content.contraptions.Contraption;

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
 *
 * <h2>Why it syncs</h2>
 * Draining happens on the server, and the tank the client holds is whatever was captured at
 * assembly — so without {@link SyncedMountedStorage} an Extruder that had spent its entire load
 * would still be showing a full mud tank, for as long as the contraption stayed together. That was
 * tolerable only while nothing on a contraption drew the mud. {@code renderInContraption} does, so
 * it is not tolerable now.
 *
 * <p>Create's own Fluid Tank does this the same way and by the same route: a dirty flag raised
 * inside the handler, and an {@link #afterSync} that pushes the arriving contents into the
 * <em>client-side</em> block entity. Writing it there rather than reading it back off this object is
 * what makes it reliable — {@code MovementContext}'s storage supplier is memoized, and a sync
 * replaces the storage rather than mutating it, so a renderer holding a context would go on reading
 * the copy that arrived at assembly forever.
 */
public class ExtruderMountedStorage extends WrapperMountedFluidStorage<ExtruderMountedStorage.Tank>
	implements SyncedMountedStorage {

	public static final MapCodec<ExtruderMountedStorage> CODEC = RecordCodecBuilder.mapCodec(
		instance -> instance
			.group(ExtraCodecs.NON_NEGATIVE_INT.fieldOf("capacity")
				.forGetter(ExtruderMountedStorage::getCapacity),
				FluidStack.OPTIONAL_CODEC.fieldOf("fluid")
					.forGetter(ExtruderMountedStorage::getFluid))
			.apply(instance, ExtruderMountedStorage::new));

	private boolean dirty;

	protected ExtruderMountedStorage(int capacity, FluidStack contents) {
		super(TerraformMountedStorage.EXTRUDER.get(), tank(capacity, contents));
		// After super, because a constructor cannot hand `this` to anything before it. Create's own
		// FluidTankMountedStorage attaches its callback in exactly the same place.
		wrapped.onChange = () -> dirty = true;
	}

	private static Tank tank(int capacity, FluidStack contents) {
		Tank tank = new Tank(capacity);
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

	@Override
	public boolean isDirty() {
		return dirty;
	}

	@Override
	public void markClean() {
		dirty = false;
	}

	/**
	 * Hands the freshly arrived contents to the block entity the contraption renders from.
	 *
	 * <p>That block entity is not drawn — {@code ExtruderMovementBehaviour.disableBlockEntityRendering}
	 * turns its renderer off, because a stationary renderer on a moving machine reads a kinetic speed
	 * that means nothing out here. It is still the machine's client-side state, though, and it is
	 * what {@code TerraformExtruderRenderer.renderInContraption} asks how full the tank is.
	 */
	@Override
	public void afterSync(Contraption contraption, BlockPos pos) {
		if (contraption.getBlockEntityClientSide(pos) instanceof TerraformExtruderBlockEntity extruder)
			extruder.setTankContentsForDisplay(getFluid());
	}

	public static ExtruderMountedStorage fromExtruder(TerraformExtruderBlockEntity extruder) {
		return new ExtruderMountedStorage(extruder.getTankCapacity(), extruder.getTankContents()
			.copy());
	}

	/**
	 * The tank itself, which exists only to have somewhere to hang the dirty flag.
	 *
	 * <p>{@code FluidTank} raises {@link #onContentsChanged()} on every fill and drain and does
	 * nothing with it, which is the hook Create's own synced storages use and the only one there is
	 * — {@code IFluidHandler} has no notion of an observer.
	 */
	public static class Tank extends FluidTank {

		public Runnable onChange = () -> {
		};

		public Tank(int capacity) {
			super(capacity, stack -> stack.getFluid()
				.isSame(TerraformFluids.MINERAL_SUBSTRATE.get()));
		}

		@Override
		protected void onContentsChanged() {
			onChange.run();
		}
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
