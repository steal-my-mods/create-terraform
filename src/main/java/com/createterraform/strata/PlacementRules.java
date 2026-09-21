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

	/**
	 * What a <b>stationary</b> Extruder places with: the ordinary flags, neighbour updates included.
	 *
	 * <p>Because something has to be told. Create's block breakers park when there is nothing in
	 * front of them — {@code ticksUntilNextProgress = -1} — and wake on their <em>lazy</em> tick,
	 * which is every ten. So a Mechanical Drill aimed at a freshly printed block sat up to half a
	 * second doing nothing, for no reason a player could see. {@code DrillBlock} overrides
	 * {@code neighborChanged}, so an update reaches it the same tick.
	 *
	 * <p>Safe here in a way it is not on a contraption. The cascade this mod worries about needs
	 * volume, and a stationary Extruder prints into free space only, at a Deployer's rate, never a
	 * liquid — at most four blocks a second and only when a harvester has just cleared the last one.
	 * A contraption keeps {@link #PLACEMENT_FLAGS}: it prints continuously, over rock, from every
	 * machine on the assembly at once, and nothing downstream of it is waiting to be woken.
	 */
	public static final int STATIONARY_FLAGS = Block.UPDATE_ALL;

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
	 * Whether an Extruder may print at a coordinate <em>at all</em>: the floor both modes share.
	 *
	 * <p>Block entities are refused whatever any tag says. A chest is somebody's belongings and the
	 * machine does not get a vote.
	 */
	private static boolean permitted(BlockState existing) {
		return !existing.hasBlockEntity();
	}

	/**
	 * Whether a <b>stationary</b> Extruder may print at a coordinate: only into space that is already
	 * free — air, water, grass, snow.
	 *
	 * <p>It may <em>not</em> write over rock, including its own output, and that is the whole
	 * difference between the two modes. The governing rule is that <b>overwriting is safe exactly when
	 * you only visit a coordinate once.</b> A stationary machine returns to the same coordinate
	 * forever, so a machine that overwrote there would be racing whatever is trying to consume what it
	 * makes — and consuming what it makes is the entire point of a stationary setup. Three Mechanical
	 * Drills on the target block never finish, because the block they are part-way through breaking
	 * keeps turning into a different block and their progress goes with it.
	 *
	 * <p>So the harvester sets the pace. The machine prints one block and waits at
	 * {@code ExtruderIdleReason#OBSTRUCTED} until something takes it away, which is synced and shows
	 * under goggles. An Extruder with nothing attached printing one block and stopping is correct
	 * behaviour, not a stall.
	 */
	public static boolean canPrintInto(BlockState existing) {
		return permitted(existing) && existing.canBeReplaced();
	}

	/**
	 * Whether a <b>moving</b> Extruder may write over what is already at a coordinate: replaceable
	 * space, plus the stone and ore listed in {@link TerraformTags#EXTRUDER_REPLACEABLE}.
	 *
	 * <p>This one may overwrite rock, because it has the guarantee the stationary machine cannot have:
	 * {@link StrataMemory} records every coordinate it prints into and the Seismic Shift fires on a
	 * revisit, so it prints and leaves. It can never race a harvester because it is never there twice.
	 *
	 * <p>It also has to. A contraption Extruder confined to empty space would only work in caves and
	 * tunnels, and painting displaced strata over solid ground is the machine's whole job.
	 */
	public static boolean canDisplace(BlockState existing) {
		return permitted(existing)
			&& (existing.canBeReplaced() || existing.is(TerraformTags.EXTRUDER_REPLACEABLE));
	}
}
