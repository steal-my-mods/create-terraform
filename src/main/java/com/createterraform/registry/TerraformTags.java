package com.createterraform.registry;

import com.createterraform.CreateTerraform;

import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/**
 * The two block tags the Extruder reads before it writes anything.
 *
 * <p>Both are this mod's own namespace rather than {@code c}, deliberately: they encode <em>this
 * machine's</em> policy, not a cross-mod fact about a block. Whether bedrock may be printed is not a
 * property of bedrock.
 *
 * <p>Both are datapack-extensible, which is the whole reason they are tags. A pack that adds a mod
 * with an indestructible block, or that wants the Extruder to bulldoze its own custom stone, changes
 * a JSON file instead of asking for a release.
 */
public final class TerraformTags {

	/**
	 * Blocks the Extruder will never print, whatever the strata say. Printed as air instead.
	 *
	 * <p>Ships with the four vanilla blocks a player is not supposed to be handed — bedrock, the end
	 * portal frame, reinforced deepslate and the barrier. It does not need to list fluids, block
	 * entities or anything else with no destroy time: {@link com.createterraform.strata.PlacementRules}
	 * refuses those on their own properties, which works for mods this tag has never heard of.
	 */
	public static final TagKey<Block> EXTRUDER_BLACKLIST =
		TagKey.create(Registries.BLOCK, CreateTerraform.asResource("extruder_blacklist"));

	/**
	 * Blocks the Extruder may write over, on top of anything already {@code canBeReplaced}.
	 *
	 * <p>This is what makes a stationary Extruder a generator rather than a machine that prints one
	 * block and stops: it has to be allowed to overwrite the rock it printed last time. So the rule
	 * is "an Extruder may overwrite the kind of thing an Extruder prints" — base stone, deepslate and
	 * ore — and nothing else. A player's cobblestone wall is safe; a player's andesite wall, being
	 * exactly what the machine prints, is not, and putting an Extruder against one is a decision the
	 * player made.
	 */
	public static final TagKey<Block> EXTRUDER_REPLACEABLE =
		TagKey.create(Registries.BLOCK, CreateTerraform.asResource("extruder_replaceable"));

	private TerraformTags() {
		throw new AssertionError("No instances");
	}
}
