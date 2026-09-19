package com.createterraform.strata;

import com.createterraform.registry.TerraformTags;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What an Extruder is allowed to print, and over what.
 *
 * <p>Both halves are deliberately conservative, and both are datapack-extensible through
 * {@link TerraformTags} rather than hard-coded, because the interesting cases are all in other
 * people's mods.
 */
public final class PlacementRules {

	/**
	 * {@code UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE}: tell the client, and nothing else.
	 *
	 * <p>The omissions are the point. Without {@code UPDATE_NEIGHBORS} the placement raises no
	 * neighbour-changed events, so nothing schedules a fluid tick, a redstone recalculation or an
	 * observer pulse; {@code UPDATE_KNOWN_SHAPE} additionally suppresses the shape updates that a
	 * new block would otherwise push into its six neighbours. A machine printing several blocks a
	 * second next to standing water is the exact shape of a cascading-update server stall, and this
	 * is the flag combination that makes that impossible rather than merely unlikely.
	 *
	 * <p>What it costs: a printed block does not wake a hopper under it and does not update
	 * lighting. Lighting catches up on the next chunk relight; the rest is the trade.
	 */
	public static final int PLACEMENT_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

	private PlacementRules() {
		throw new AssertionError("No instances");
	}

	/**
	 * The state that may actually be written, given what the sampler came back with.
	 *
	 * <p>Everything refused becomes air rather than cancelling the placement, so a machine printing
	 * through an aquifer prints the cave and keeps moving instead of stalling at the water line.
	 *
	 * <ul>
	 * <li><b>Anything holding a fluid.</b> Not "water and lava" — {@code getFluidState} also catches
	 * waterlogged blocks and any modded fluid, and a single source block written with no neighbour
	 * updates is a source block that will never be told to flow. No liquid is ever placed, full
	 * stop.
	 * <li><b>Anything with a block entity.</b> Spawners, chests, shulker boxes, and every modded
	 * machine. A block entity written by {@code setBlock} alone arrives with no data, and a printed
	 * spawner would be a mob farm the machine was never meant to be.
	 * <li><b>Anything a player is not supposed to be handed</b> — bedrock, end portal frames,
	 * reinforced deepslate, barriers — via {@link TerraformTags#EXTRUDER_BLACKLIST}, plus a
	 * catch-all for blocks with no destroy time at all, which is what "unbreakable" means in
	 * practice and covers mods that add their own.
	 * </ul>
	 */
	public static BlockState sanitise(BlockState sampled) {
		if (sampled.isAir())
			return Blocks.AIR.defaultBlockState();
		if (!sampled.getFluidState()
			.isEmpty())
			return Blocks.AIR.defaultBlockState();
		if (sampled.hasBlockEntity())
			return Blocks.AIR.defaultBlockState();
		if (sampled.is(TerraformTags.EXTRUDER_BLACKLIST))
			return Blocks.AIR.defaultBlockState();
		if (sampled.getBlock()
			.defaultDestroyTime() < 0)
			return Blocks.AIR.defaultBlockState();
		return sampled;
	}

	/**
	 * Whether the Extruder may write over what is already at a coordinate.
	 *
	 * <p>A stationary Extruder has to be able to overwrite its own output — that is what makes it a
	 * generator rather than a machine that prints one block and stops — so "air only" is not an
	 * option. The rule instead is <em>an Extruder may overwrite the kind of thing an Extruder
	 * prints</em>: replaceable blocks, and the stone and ore listed in
	 * {@link TerraformTags#EXTRUDER_REPLACEABLE}.
	 *
	 * <p>Block entities are refused whatever the tags say. A chest is somebody's belongings and the
	 * machine does not get a vote.
	 */
	public static boolean canOverwrite(BlockState existing) {
		if (existing.hasBlockEntity())
			return false;
		return existing.canBeReplaced() || existing.is(TerraformTags.EXTRUDER_REPLACEABLE);
	}
}
