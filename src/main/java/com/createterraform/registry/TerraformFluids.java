package com.createterraform.registry;

import com.createterraform.CreateTerraform;

import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.common.SoundActions;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * Mineral Substrate: rock in suspension, and what an Extruder actually spends.
 *
 * <p>A real fluid rather than a counter on the machine, so that every piece of Create's fluid
 * plumbing — pipes, pumps, tanks, the Mechanical Mixer that makes it, the contraption fluid storage
 * a moving Extruder drinks from — works on it without a line of code here.
 *
 * <p>It is <em>heavy</em>, and that is expressed the only ways NeoForge offers: a density well above
 * water's 1000, a viscosity above lava's 6000 so it creeps rather than pours, and a slope-find
 * distance and per-block decay that stop a spilled source spreading far. Swimming in it is not
 * survivable and is not meant to be.
 */
public class TerraformFluids {

	public static final DeferredRegister<FluidType> FLUID_TYPES =
		DeferredRegister.create(NeoForgeRegistries.Keys.FLUID_TYPES, CreateTerraform.ID);

	public static final DeferredRegister<Fluid> FLUIDS =
		DeferredRegister.create(Registries.FLUID, CreateTerraform.ID);

	public static final DeferredHolder<FluidType, FluidType> MINERAL_SUBSTRATE_TYPE =
		FLUID_TYPES.register("mineral_substrate", () -> new FluidType(FluidType.Properties.create()
			.descriptionId("fluid.createterraform.mineral_substrate")
			.density(2800)
			.viscosity(8000)
			.temperature(900)
			.motionScale(0.0023)
			.canDrown(true)
			.canSwim(false)
			.canExtinguish(false)
			.canConvertToSource(false)
			.supportsBoating(false)
			.canHydrate(false)
			.sound(SoundActions.BUCKET_FILL, SoundEvents.BUCKET_FILL_LAVA)
			.sound(SoundActions.BUCKET_EMPTY, SoundEvents.BUCKET_EMPTY_LAVA)));

	public static final DeferredHolder<Fluid, BaseFlowingFluid.Source> MINERAL_SUBSTRATE =
		FLUIDS.register("mineral_substrate", () -> new BaseFlowingFluid.Source(properties()));

	public static final DeferredHolder<Fluid, BaseFlowingFluid.Flowing> FLOWING_MINERAL_SUBSTRATE =
		FLUIDS.register("flowing_mineral_substrate", () -> new BaseFlowingFluid.Flowing(properties()));

	/**
	 * A fresh Properties per fluid, on purpose. The two fluids and the block reference each other, so
	 * the properties cannot be a constant initialised before any of them exists.
	 */
	private static BaseFlowingFluid.Properties properties() {
		return new BaseFlowingFluid.Properties(MINERAL_SUBSTRATE_TYPE, MINERAL_SUBSTRATE, FLOWING_MINERAL_SUBSTRATE)
			.bucket(TerraformItems.MINERAL_SUBSTRATE_BUCKET)
			.block(TerraformBlocks.MINERAL_SUBSTRATE)
			.slopeFindDistance(2)
			.levelDecreasePerBlock(2)
			.tickRate(30)
			.explosionResistance(100.0F);
	}

	/** The world block. Only a way to carry the fluid about; nothing in the mod reads it. */
	static LiquidBlock block() {
		return new LiquidBlock(MINERAL_SUBSTRATE.get(), BlockBehaviour.Properties.of()
			.mapColor(MapColor.TERRACOTTA_BROWN)
			.replaceable()
			.noCollission()
			.strength(100.0F)
			.pushReaction(PushReaction.DESTROY)
			.noLootTable()
			.liquid()
			.sound(SoundType.EMPTY));
	}
}
