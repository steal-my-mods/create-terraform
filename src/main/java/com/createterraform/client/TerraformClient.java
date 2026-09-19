package com.createterraform.client;

import com.createterraform.CreateTerraform;
import com.createterraform.registry.TerraformBlockEntities;
import com.createterraform.registry.TerraformFluids;

import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

/**
 * Everything the client needs: the fluid's appearance, and the renderer that drives the Extruder's
 * ram.
 *
 * <p>No Flywheel visual yet, deliberately. Without one Create never skips the block entity renderer,
 * so the ram draws on every backend; adding a visual later means writing the same geometry twice and
 * keeping the two in step, which is only worth it once there are enough Extruders on screen for
 * instancing to pay.
 */
public class TerraformClient {

	private static final ResourceLocation SUBSTRATE_STILL =
		CreateTerraform.asResource("fluid/mineral_substrate_still");
	private static final ResourceLocation SUBSTRATE_FLOW =
		CreateTerraform.asResource("fluid/mineral_substrate_flow");

	public static void init(IEventBus modBus) {
		modBus.addListener(TerraformClient::registerClientExtensions);
		modBus.addListener(TerraformClient::registerRenderers);
		modBus.addListener(TerraformClient::clientSetup);
		TerraformPartials.init();
	}

	/**
	 * The Extruder's ram. The casing is a plain block model and needs nothing; the head moves, so it
	 * is a partial drawn here.
	 */
	private static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
		event.registerBlockEntityRenderer(TerraformBlockEntities.TERRAFORM_EXTRUDER.get(),
			TerraformExtruderRenderer::new);
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
