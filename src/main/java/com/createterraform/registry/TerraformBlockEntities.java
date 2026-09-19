package com.createterraform.registry;

import java.util.function.Supplier;

import com.createterraform.CreateTerraform;
import com.createterraform.extruder.TerraformExtruderBlockEntity;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

public class TerraformBlockEntities {

	public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
		DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, CreateTerraform.ID);

	public static final Supplier<BlockEntityType<TerraformExtruderBlockEntity>> TERRAFORM_EXTRUDER =
		BLOCK_ENTITIES.register("terraform_extruder",
			() -> BlockEntityType.Builder
				.of((pos, state) -> new TerraformExtruderBlockEntity(TerraformBlockEntities.TERRAFORM_EXTRUDER.get(),
					pos, state), TerraformBlocks.TERRAFORM_EXTRUDER.get())
				.build(null));

	/**
	 * Every face answers, so a pipe or a pump may be attached wherever the build allows — including
	 * the face the shaft is on, where a Fluid Pipe and a Shaft cannot both be anyway, and the face
	 * the machine prints into, where nobody will put one. Filtering by face would only ever surprise
	 * someone.
	 */
	public static void registerCapabilities(RegisterCapabilitiesEvent event) {
		event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, TERRAFORM_EXTRUDER.get(),
			(be, context) -> be.getTankCapability());
	}
}
