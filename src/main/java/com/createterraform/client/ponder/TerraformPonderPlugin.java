package com.createterraform.client.ponder;

import com.createterraform.CreateTerraform;

import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.minecraft.resources.ResourceLocation;

/**
 * Hangs both scenes off the Terraform Extruder, because it is the only thing this mod adds that a
 * player holds.
 *
 * <p>Mineral Substrate gets none. Ponder is opened from an item in an inventory, and the fluid's
 * only item is a bucket of it; what there is to say about the fluid — that the machine burns it, and
 * roughly how fast — is said in the scenes that show the machine burning it.
 *
 * <p>No {@code onPonderLevelRestore} override. Create's is for {@code IMultiBlockEntityContainer}s,
 * which come back from a ponder capture still pointing their controller at the coordinates they were
 * taken from; nothing here is a multiblock.
 */
public class TerraformPonderPlugin implements PonderPlugin {

	@Override
	public String getModId() {
		return CreateTerraform.ID;
	}

	@Override
	public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
		helper.forComponents(CreateTerraform.asResource("terraform_extruder"))
			.addStoryBoard("terraform_extruder", ExtruderScenes::printing)
			.addStoryBoard("extruder_contraption", ExtruderScenes::onAContraption);
	}
}
