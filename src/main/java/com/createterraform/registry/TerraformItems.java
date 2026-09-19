package com.createterraform.registry;

import com.createterraform.CreateTerraform;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class TerraformItems {

	public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(CreateTerraform.ID);

	public static final DeferredRegister<CreativeModeTab> TABS =
		DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CreateTerraform.ID);

	public static final DeferredItem<BlockItem> TERRAFORM_EXTRUDER =
		ITEMS.registerSimpleBlockItem(TerraformBlocks.TERRAFORM_EXTRUDER);

	/**
	 * A bucket, even though nobody pours Mineral Substrate into the world on purpose. It is how the
	 * fluid shows up in a recipe viewer, how a player moves a little of it without plumbing, and how
	 * a creative-mode test rig gets a machine running in one click.
	 */
	public static final DeferredItem<BucketItem> MINERAL_SUBSTRATE_BUCKET =
		ITEMS.registerItem("mineral_substrate_bucket",
			props -> new BucketItem(TerraformFluids.MINERAL_SUBSTRATE.get(), props),
			new Item.Properties().craftRemainder(Items.BUCKET)
				.stacksTo(1));

	public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("main",
		() -> CreativeModeTab.builder()
			.title(Component.translatable("itemGroup.createterraform"))
			.icon(() -> TERRAFORM_EXTRUDER.get()
				.getDefaultInstance())
			.displayItems((params, output) -> {
				output.accept(TERRAFORM_EXTRUDER.get());
				output.accept(MINERAL_SUBSTRATE_BUCKET.get());
			})
			.build());
}
