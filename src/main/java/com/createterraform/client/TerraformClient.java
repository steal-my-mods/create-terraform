package com.createterraform.client;

import com.createterraform.CreateTerraform;
import com.createterraform.registry.TerraformFluids;

import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

/**
 * Everything the client needs, which for this mod is only the fluid.
 *
 * <p>The Extruder has no renderer and no Flywheel visual: nothing on it moves. Its whole animation is
 * the world changing in front of it, which every client already draws.
 */
public class TerraformClient {

	private static final ResourceLocation SUBSTRATE_STILL =
		CreateTerraform.asResource("fluid/mineral_substrate_still");
	private static final ResourceLocation SUBSTRATE_FLOW =
		CreateTerraform.asResource("fluid/mineral_substrate_flow");

	public static void init(IEventBus modBus) {
		modBus.addListener(TerraformClient::registerClientExtensions);
		modBus.addListener(TerraformClient::clientSetup);
	}

	private static void clientSetup(FMLClientSetupEvent event) {
		event.enqueueWork(() -> {
			// Opaque, not translucent: substrate is rock in suspension and nothing is visible
			// through it. It is also what makes a full tank read as full across the room.
			ItemBlockRenderTypes.setRenderLayer(TerraformFluids.MINERAL_SUBSTRATE.get(), RenderType.solid());
			ItemBlockRenderTypes.setRenderLayer(TerraformFluids.FLOWING_MINERAL_SUBSTRATE.get(),
				RenderType.solid());
		});
	}

	private static void registerClientExtensions(RegisterClientExtensionsEvent event) {
		event.registerFluidType(new IClientFluidTypeExtensions() {
			@Override
			public ResourceLocation getStillTexture() {
				return SUBSTRATE_STILL;
			}

			@Override
			public ResourceLocation getFlowingTexture() {
				return SUBSTRATE_FLOW;
			}

			@Override
			public int getTintColor() {
				return 0xFFFFFFFF;
			}
		}, TerraformFluids.MINERAL_SUBSTRATE_TYPE.get());
	}
}
