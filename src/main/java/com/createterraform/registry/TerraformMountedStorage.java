package com.createterraform.registry;

import java.util.function.Supplier;

import com.createterraform.CreateTerraform;
import com.createterraform.extruder.ExtruderMountedStorage;
import com.simibubi.create.api.contraption.storage.fluid.MountedFluidStorageType;
import com.simibubi.create.api.registry.CreateRegistries;

import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

public class TerraformMountedStorage {

	public static final DeferredRegister<MountedFluidStorageType<?>> MOUNTED_FLUID_STORAGE_TYPES =
		DeferredRegister.create(CreateRegistries.MOUNTED_FLUID_STORAGE_TYPE, CreateTerraform.ID);

	public static final Supplier<ExtruderMountedStorage.Type> EXTRUDER =
		MOUNTED_FLUID_STORAGE_TYPES.register("terraform_extruder", ExtruderMountedStorage.Type::new);

	/**
	 * Tells Create that this block's tank is worth mounting. Same deferral as the movement
	 * behaviour: Create's association map is keyed on the block instance and is not built for the
	 * parallel loading threads.
	 */
	public static void registerBlockAssociation(FMLCommonSetupEvent event) {
		event.enqueueWork(() -> MountedFluidStorageType.REGISTRY
			.register(TerraformBlocks.TERRAFORM_EXTRUDER.get(), EXTRUDER.get()));
	}
}
