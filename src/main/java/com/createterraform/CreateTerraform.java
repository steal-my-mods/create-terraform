package com.createterraform;

import com.createterraform.registry.TerraformAttachments;
import com.createterraform.registry.TerraformBlockEntities;
import com.createterraform.registry.TerraformBlocks;
import com.createterraform.registry.TerraformFluids;
import com.createterraform.registry.TerraformItems;
import com.createterraform.registry.TerraformMountedStorage;
import com.createterraform.strata.VirtualChunkCache;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Create: Terraform — industrial terraforming.
 *
 * <p>One machine: the Terraform Extruder. Given rotation and Mineral Substrate it asks the world's
 * own chunk generator what rock belongs at a coordinate and prints it, without ever generating a
 * chunk. Standing still it rerolls its Strata Signature on every placement, which makes it a
 * randomised ore generator; bolted to a contraption it shares one signature with every other
 * Extruder on board, which makes a print come out as one continuous piece of world.
 *
 * <p>The interesting code is in three places and nowhere else:
 * {@link com.createterraform.strata.VirtualStrata} (what block belongs where),
 * {@link com.createterraform.extruder.TerraformExtruderBlockEntity} (standing still) and
 * {@link com.createterraform.extruder.ExtruderMovementBehaviour} (moving, and the Seismic Shift).
 */
@Mod(CreateTerraform.ID)
public class CreateTerraform {

	public static final String ID = "createterraform";
	public static final Logger LOGGER = LoggerFactory.getLogger("Create: Terraform");

	public CreateTerraform(IEventBus modBus, ModContainer container) {
		TerraformFluids.FLUID_TYPES.register(modBus);
		TerraformFluids.FLUIDS.register(modBus);
		TerraformBlocks.BLOCKS.register(modBus);
		TerraformBlockEntities.BLOCK_ENTITIES.register(modBus);
		TerraformItems.ITEMS.register(modBus);
		TerraformItems.TABS.register(modBus);
		TerraformAttachments.ATTACHMENT_TYPES.register(modBus);
		TerraformMountedStorage.MOUNTED_FLUID_STORAGE_TYPES.register(modBus);

		modBus.addListener(TerraformBlockEntities::registerCapabilities);
		modBus.addListener(TerraformBlocks::registerStressValues);
		modBus.addListener(TerraformBlocks::registerMovementBehaviours);
		modBus.addListener(TerraformMountedStorage::registerBlockAssociation);

		// The virtual chunk cache holds real generated chunks, so it has to go when the level
		// does -- a server that has visited a hundred dimensions must not be paying for
		// ninety-nine of them.
		NeoForge.EVENT_BUS.addListener(VirtualChunkCache::onLevelUnload);

		if (FMLEnvironment.dist == Dist.CLIENT)
			com.createterraform.client.TerraformClient.init(modBus);

		container.registerConfig(ModConfig.Type.SERVER, TerraformConfig.SPEC);
	}

	public static ResourceLocation asResource(String path) {
		return ResourceLocation.fromNamespaceAndPath(ID, path);
	}
}
