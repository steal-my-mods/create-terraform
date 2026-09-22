package com.createterraform.client;

import com.createterraform.CreateTerraform;
import com.createterraform.client.ponder.TerraformPonderPlugin;
import com.createterraform.registry.TerraformBlockEntities;
import com.createterraform.registry.TerraformFluids;

import net.createmod.ponder.foundation.PonderIndex;
import net.createmod.ponder.foundation.registration.PonderLocalization;
import net.createmod.ponder.foundation.registration.PonderSceneRegistry;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Everything the client needs: the fluid's appearance, the renderer that drives the Extruder's rig,
 * and the Ponder scenes.
 *
 * <p>No Flywheel visual yet, deliberately. Without one Create never skips the block entity renderer,
 * so the rig draws on every backend; adding a visual later means writing the same geometry twice and
 * keeping the two in step, which is only worth it once there are enough Extruders on screen for
 * instancing to pay. The same choice is why {@code ExtruderMovementBehaviour.renderInContraption} is
 * unguarded — there is no {@code ActorVisual} for it to stand aside for.
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
	 * The Extruder's rig. The casing is a plain block model and needs nothing; the spindle, the barrel
	 * and the mud all move, so they are partials drawn here.
	 */
	private static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
		event.registerBlockEntityRenderer(TerraformBlockEntities.TERRAFORM_EXTRUDER.get(),
			TerraformExtruderRenderer::new);
	}

	private static void clientSetup(FMLClientSetupEvent event) {
		event.enqueueWork(() -> {
			PonderIndex.addPlugin(new TerraformPonderPlugin());
			NeoForge.EVENT_BUS.addListener(TerraformClient::onClientTick);
			// Opaque, not translucent: substrate is rock in suspension and nothing is visible
			// through it. It is also what makes a full tank read as full across the room.
			ItemBlockRenderTypes.setRenderLayer(TerraformFluids.MINERAL_SUBSTRATE.get(), RenderType.solid());
			ItemBlockRenderTypes.setRenderLayer(TerraformFluids.FLOWING_MINERAL_SUBSTRATE.get(),
				RenderType.solid());
		});
	}

	/**
	 * Runs the check once, on the first client tick, because Ponder's index is not populated until
	 * after every mod's client setup has been through.
	 */
	private static boolean ponderChecked;

	private static void onClientTick(ClientTickEvent.Post event) {
		if (ponderChecked)
			return;
		ponderChecked = true;
		checkPonderScenes();
	}

	/**
	 * Compiles this mod's Ponder scenes at startup and reports anything wrong with them.
	 *
	 * <p>Both failures this guards against are silent: a scene whose structure is missing and a scene
	 * whose text has no lang key both load a perfectly clean client and only go wrong when a player
	 * opens them — at which point they see raw translation keys, or nothing. Compiling the scene is
	 * what populates the localization map, so asking Ponder what keys it wants means running the
	 * storyboard.
	 *
	 * <p>The structures are the other half and are covered by {@code thePonderStructuresAreValid};
	 * the headless compile below does not load one.
	 *
	 * <p>Development only. With {@code tools/generate_ponder.py} run, none of this can fire.
	 */
	private static void checkPonderScenes() {
		if (FMLEnvironment.production)
			return;
		if (!(PonderIndex.getLangAccess() instanceof PonderLocalization localization))
			return;

		// The static compileScene is the headless path -- it takes a null level, which is how
		// Create's own datagen compiles scenes to harvest their lang. Going through
		// SceneRegistryAccess.compile instead builds a PonderLevel, and that needs a world loaded,
		// so it throws at the title screen.
		int compiled = 0;
		try {
			for (var entry : PonderIndex.getSceneAccess()
				.getRegisteredEntries()) {
				if (!entry.getKey()
					.getNamespace()
					.equals(CreateTerraform.ID))
					continue;
				PonderSceneRegistry.compileScene(localization, entry.getValue(), null);
				compiled++;
			}
		} catch (Exception e) {
			CreateTerraform.LOGGER.warn("A Ponder scene failed to compile", e);
			return;
		}

		// A guard that passes because it inspected nothing is the failure mode it was written for.
		if (compiled == 0) {
			CreateTerraform.LOGGER.warn(
				"No Ponder scene compiled for {} -- the plugin may not have registered", CreateTerraform.ID);
			return;
		}

		int inspected = 0;
		for (var scene : localization.specific.entrySet()) {
			ResourceLocation sceneId = scene.getKey();
			if (!sceneId.getNamespace()
				.equals(CreateTerraform.ID))
				continue;
			for (var text : scene.getValue()
				.entrySet()) {
				String langKey = sceneId.getNamespace() + ".ponder." + sceneId.getPath() + "."
					+ text.getKey();
				inspected++;
				if (!I18n.exists(langKey))
					CreateTerraform.LOGGER.warn(
						"Ponder scene text has no translation: {} -- run tools/generate_ponder.py (\"{}\")",
						langKey, text.getValue());
			}
		}

		if (inspected == 0)
			CreateTerraform.LOGGER.warn("{} Ponder scene(s) compiled but registered no text to check",
				compiled);
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
