package com.createterraform.registry;

import com.createterraform.CreateTerraform;
import com.createterraform.TerraformConfig;
import com.createterraform.extruder.ExtruderMovementBehaviour;
import com.createterraform.extruder.TerraformExtruderBlock;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.api.stress.BlockStressValues;

import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public class TerraformBlocks {

	public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(CreateTerraform.ID);

	public static final DeferredBlock<TerraformExtruderBlock> TERRAFORM_EXTRUDER =
		BLOCKS.register("terraform_extruder", () -> new TerraformExtruderBlock(BlockBehaviour.Properties.of()
			.mapColor(MapColor.DEEPSLATE)
			.strength(3.5F, 8.0F)
			.sound(SoundType.NETHERITE_BLOCK)
			// The casing is open at the front and inset at the sides, so neighbours must not cull
			// their faces against it.
			.noOcclusion()
			.requiresCorrectToolForDrops()));

	public static final DeferredBlock<LiquidBlock> MINERAL_SUBSTRATE =
		BLOCKS.register("mineral_substrate", TerraformFluids::block);

	/**
	 * The Extruder draws stress like any other Create machine, and a flat per-RPM impact rather than
	 * a generator's rating because that is what it is: a consumer. The number is deliberately on the
	 * heavy side — a row of Extruders on a pulley writes a great deal of world per second, and the
	 * stress bill is the only thing in the game that makes that a decision.
	 */
	public static void registerStressValues(FMLCommonSetupEvent event) {
		event.enqueueWork(() -> BlockStressValues.IMPACTS.register(TERRAFORM_EXTRUDER.get(),
			TerraformConfig::stressImpact));
	}

	/**
	 * Teaches Create that an Extruder keeps working once it is glued to something that moves.
	 *
	 * <p>Deferred to common setup because Create's registry is a plain identity map keyed on the
	 * block instance, so the block has to exist first; and enqueued because the map is not built for
	 * the parallel mod-loading threads.
	 */
	public static void registerMovementBehaviours(FMLCommonSetupEvent event) {
		event.enqueueWork(() -> MovementBehaviour.REGISTRY.register(TERRAFORM_EXTRUDER.get(),
			new ExtruderMovementBehaviour()));
	}
}
