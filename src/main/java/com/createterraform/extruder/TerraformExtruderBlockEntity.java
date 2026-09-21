package com.createterraform.extruder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.createterraform.CreateTerraform;
import com.createterraform.TerraformConfig;
import com.createterraform.TerraformLang;
import com.createterraform.registry.TerraformFluids;
import com.createterraform.strata.PlacementRules;
import com.createterraform.strata.StrataSignature;
import com.createterraform.strata.StrataSlice;
import com.createterraform.strata.VirtualStrata;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;
import com.simibubi.create.foundation.utility.CreateLang;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

/**
 * A Terraform Extruder standing still.
 *
 * <p>Given rotation and Mineral Substrate it prints into the block it faces, on an interval that
 * scales with RPM: {@code cycleTicks × referenceRpm / rpm}, floored so that no amount of gearing
 * turns it into a twenty-blocks-a-second machine.
 *
 * <h2>The core sample</h2>
 * A machine that never moves has one coordinate to print into, so with a fixed Strata Signature it
 * would print the same block for ever: it has to reroll. But under the virtual chunk pipeline a
 * reroll means a whole new 3x3 region — nine chunks of terrain — and doing that once per placement
 * is a stopped server, not a slow one.
 *
 * <p>So the machine works from a <b>core sample</b> instead. One signature buys one
 * {@link StrataSlice}: every printable block at this machine's own altitude across a 48x48 footprint
 * of virtual world, filtered, shuffled, and held as a queue. Each cycle pops one. A sample is
 * typically a couple of thousand blocks — minutes to hours of running — and the heavy work is paid
 * once for all of them.
 *
 * <p>The player-visible behaviour is the one the machine always had: a randomised, biome-accurate
 * ore generator whose next block is never predictable. What changed is that the randomness is drawn
 * ahead of time rather than rolled on the spot, and the ore ratios are now exactly the ratios that
 * depth really has, for modded ores as much as vanilla ones, because the pool is a real slice of a
 * real generated world rather than anything this mod computed.
 *
 * <h2>Double buffering</h2>
 * Cutting a sample takes long enough to matter, so it is never done on the server thread. When the
 * queue falls to {@link TerraformConfig#sliceLowWaterMark()} the machine dispatches the next one to
 * a worker and carries on printing from what it has; the finished sample is collected on a later
 * tick, on the server thread, by the machine that asked for it. A machine that is running properly
 * therefore never waits, and one that has just been placed waits once.
 *
 * <p>A sample that comes back with nothing in it means there is no rock at this altitude — an
 * Extruder bolted to a hillside samples sky. That is reported as {@link ExtruderIdleReason#BARREN}
 * and retried on a cooldown rather than immediately, because a machine in the open would otherwise
 * spend a worker thread for ever discovering the same thing.
 */
public class TerraformExtruderBlockEntity extends KineticBlockEntity {

	/** Longest a cycle may be stretched to, so a machine crawling at 1 RPM still eventually fires. */
	private static final int MAX_CYCLE_TICKS = 1200;

	/** How long the first retry after a barren sample waits, doubling from there. */
	private static final int FIRST_BARREN_RETRY = 10;

	/**
	 * The three countdowns a Mechanical Deployer spends on one placement, in its own timer units:
	 * extending, retracting, then waiting. Create's numbers, not ours.
	 */
	private static final int[] DEPLOYER_PHASES = {1000, 1000, 500};

	/** Blocks in front the machine works at. Create's Deployer uses the same two. */
	public static final int REACH = 2;

	private SmartFluidTankBehaviour tank;

	private int timer;
	private ExtruderIdleReason idleReason = ExtruderIdleReason.NO_ROTATION;
	private BlockState lastPrinted = Blocks.AIR.defaultBlockState();

	/**
	 * The core sample, spent from the end. An {@link ArrayList} rather than a deque because removing
	 * the last element is O(1) either way and this one has to be written to NBT by the thousand.
	 */
	private final List<BlockState> sample = new ArrayList<>();
	/** The altitude {@link #sample} was cut at. A sample cut for another Y is not this machine's. */
	private int sampleY = Integer.MIN_VALUE;

	/** The sample being cut on a worker thread, collected on a later tick. Never persisted. */
	private CompletableFuture<StrataSlice> pending;
	/** Ticks to wait before surveying again after a sample came back empty. */
	private int barrenCooldown;
	/**
	 * Consecutive barren samples, which sets how long the next wait is.
	 *
	 * <p>Not saved. A machine reloaded next to good rock should try again straight away rather than
	 * inherit a backoff earned somewhere else.
	 */
	private int barrenStreak;

	/** Blocks written since this machine was placed. Diagnostic; not persisted, not synced. */
	private long printed;
	/**
	 * Core samples this machine has cut for itself. Diagnostic; the tests read it.
	 *
	 * <p>Counted where a survey lands rather than in {@link #acceptSample}, because a sample handed
	 * to the machine from outside is not a survey the machine paid for — and a test that feeds a
	 * machine to watch it print must still be able to assert that it never went surveying.
	 */
	private long surveys;

	/** What the client was last told, so the lazy sync sends a packet only when something moved. */
	private ExtruderIdleReason sentIdleReason;
	private BlockState sentLastPrinted;

	public TerraformExtruderBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
	}

	@Override
	public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
		super.addBehaviours(behaviours);
		tank = SmartFluidTankBehaviour.single(this, TerraformConfig.tankCapacity());
		tank.getPrimaryHandler()
			.setValidator(stack -> stack.getFluid()
				.isSame(TerraformFluids.MINERAL_SUBSTRATE.get()));
		behaviours.add(tank);
	}

	// --- accessors ----------------------------------------------------------------------

	public IFluidHandler getTankCapability() {
		return tank.getCapability();
	}

	/** What is in the on-board tank. Read when the machine is mounted onto a contraption. */
	public FluidStack getTankContents() {
		return tank.getPrimaryHandler()
			.getFluid();
	}

	public int getTankCapacity() {
		return tank.getPrimaryHandler()
			.getCapacity();
	}

	/** Put back what a contraption did not spend. See {@link ExtruderMountedStorage#unmount}. */
	public void setTankContents(FluidStack contents) {
		tank.getPrimaryHandler()
			.setFluid(contents);
		notifyUpdate();
	}

	/** Blocks this machine has written. */
	public long getPrinted() {
		return printed;
	}

	/** Core samples this machine has cut. */
	public long getSurveys() {
		return surveys;
	}

	/** Blocks left in the current core sample. Shown on the goggle overlay. */
	public int getSampleSize() {
		return sample.size();
	}

	/**
	 * How full the tank is, 0 to 1, for the sight band.
	 *
	 * <p>Read from the tank behaviour rather than from anything this class syncs: a
	 * {@code SmartFluidTankBehaviour} already keeps the client's copy up to date on its own, and
	 * already smooths it, so the gauge moves rather than stepping.
	 */
	public float getFillLevel() {
		int capacity = tank.getPrimaryHandler()
			.getCapacity();
		if (capacity <= 0)
			return 0F;
		return Math.min(1F, tank.getPrimaryHandler()
			.getFluidAmount() / (float) capacity);
	}

	public ExtruderIdleReason getIdleReason() {
		return idleReason;
	}

	public BlockState getLastPrinted() {
		return lastPrinted;
	}

	/**
	 * Hands the machine a core sample.
	 *
	 * <p>The seam between the worker thread and the server thread: the background task builds one of
	 * these and this is the only thing that ever reads it, on the server thread, in
	 * {@link #collectFinishedSurvey}. It is public because the tests drive a machine through a sample
	 * they built themselves, which is the only way to say anything about the printing path without
	 * also depending on what the world happens to contain at the test rig's altitude.
	 */
	public void acceptSample(StrataSlice slice) {
		if (slice.y() != sampleY) {
			sample.clear();
			sampleY = slice.y();
		}
		sample.addAll(slice.states());
		setChanged();
	}

	// --- ticking ------------------------------------------------------------------------

	@Override
	public void tick() {
		super.tick();
		if (!(level instanceof ServerLevel serverLevel))
			return;

		collectFinishedSurvey();
		if (barrenCooldown > 0)
			barrenCooldown--;

		if (getSpeed() == 0) {
			idleReason = ExtruderIdleReason.NO_ROTATION;
			timer = 0;
			return;
		}

		requestSurveyIfLow(serverLevel);

		if (timer > 0) {
			timer--;
			return;
		}

		timer = cycleTicks();
		print(serverLevel);
	}

	/**
	 * Ticks between placements at the current speed.
	 *
	 * <p>Inversely proportional, so the number a player reads on a stress gauge translates directly:
	 * twice the RPM is twice the throughput. The floor is the thing that matters — without it a
	 * geared-up row of these writes a block per machine per tick, which is not a balance problem so
	 * much as a tick-time one.
	 */
	/**
	 * Ticks between placements: <b>a Mechanical Deployer's, exactly</b>.
	 *
	 * <p>Not approximated — this is Create's own arithmetic, read out of
	 * {@code DeployerBlockEntity}. Its {@code timer} counts down by
	 * {@code getTimerSpeed() = clamp(|rpm| * 2, 8, 512)} every tick, and one placement is three
	 * phases of it: a thousand units extending, {@code activate()}, a thousand retracting, five
	 * hundred waiting. Each phase is ceilinged separately because each is a separate countdown, and
	 * summing first would come out a tick short at some speeds.
	 *
	 * <p>Doing it this way rather than with our own interval-at-a-reference-RPM formula matters for
	 * more than parity of the headline number: the clamp is what gives the curve its shape. Below
	 * 4 RPM speed stops buying anything, and above 256 RPM it stops too, which is why a geared-up
	 * Extruder cannot print every tick and no separate floor is needed. Our own formula had a
	 * hand-placed floor and no ceiling on the useful speed, and ran about twice a Deployer's rate
	 * across the whole band.
	 */
	public int cycleTicks() {
		float rpm = Math.abs(getSpeed());
		if (rpm == 0.0F)
			return MAX_CYCLE_TICKS;

		int timerSpeed = (int) Mth.clamp(rpm * 2.0F, 8.0F, 512.0F);
		int ticks = 0;
		for (int phase : DEPLOYER_PHASES)
			ticks += Mth.ceil((float) phase / timerSpeed);

		return Mth.clamp(Mth.ceil(ticks * TerraformConfig.cycleScale()), 1, MAX_CYCLE_TICKS);
	}

	private void print(ServerLevel serverLevel) {
		BlockPos target = target();
		BlockState existing = serverLevel.getBlockState(target);

		if (!PlacementRules.canPrintInto(existing)) {
			idleReason = ExtruderIdleReason.OBSTRUCTED;
			return;
		}
		// Do not bury the drop. A harvester breaking the printed block leaves an item entity standing
		// in the space for a moment, and a machine that refilled it that same tick would seal the item
		// inside a solid block -- which is why a Chute under the target almost never caught anything.
		// Waiting a cycle is free: the machine is gated on the harvester anyway.
		if (!serverLevel.getEntitiesOfClass(ItemEntity.class, new AABB(target))
			.isEmpty()) {
			idleReason = ExtruderIdleReason.OBSTRUCTED;
			return;
		}
		if (!hasSubstrate()) {
			idleReason = ExtruderIdleReason.NO_SUBSTRATE;
			return;
		}
		if (target.getY() != sampleY || sample.isEmpty()) {
			idleReason = barrenCooldown > 0 ? ExtruderIdleReason.BARREN : ExtruderIdleReason.SURVEYING;
			return;
		}

		idleReason = ExtruderIdleReason.NONE;
		BlockState strata = sample.remove(sample.size() - 1);
		lastPrinted = strata;
		setChanged();
		if (strata == existing)
			return;

		drainSubstrate();
		serverLevel.setBlock(target, strata, PlacementRules.STATIONARY_FLAGS);
		printed++;
		serverLevel.playSound(null, target, strata.getSoundType()
			.getPlaceSound(), SoundSource.BLOCKS, 0.35F, 0.7F);
	}

	// --- surveying ----------------------------------------------------------------------

	/**
	 * Dispatches the next core sample when the current one is running low.
	 *
	 * <p>Only while the machine is actually working: an unpowered or dry Extruder surveys nothing, so
	 * a shed full of idle machines costs no worker threads. The low-water mark is what makes this
	 * double buffering rather than a stall — the sample still has hundreds of blocks in it when the
	 * next one is asked for, and a machine at any sane speed will not run out before it lands.
	 */
	private void requestSurveyIfLow(ServerLevel serverLevel) {
		if (pending != null || barrenCooldown > 0)
			return;
		BlockPos target = target();
		if (target.getY() == sampleY && sample.size() > TerraformConfig.sliceLowWaterMark())
			return;
		if (!hasSubstrate())
			return;

		// A brand new signature for every sample: this is what stops a stationary machine printing
		// the same rock twice, and it is the stationary answer to the contraption's Seismic Shift.
		long signature = StrataSignature.roll(serverLevel.random);
		pending = CompletableFuture
			.supplyAsync(() -> StrataSlice.generate(serverLevel, signature, target), VirtualStrata.worker())
			.exceptionally(failure -> {
				// A modded feature that cannot cope with a world it does not recognise should cost
				// this machine one core sample, not the server.
				CreateTerraform.LOGGER.error("Could not cut a core sample for the Extruder at {}",
					worldPosition, failure);
				return new StrataSlice(signature, target.getY(), List.of());
			});
	}

	/** The worker's result, taken on the server thread by the machine that asked for it. */
	private void collectFinishedSurvey() {
		if (pending == null || !pending.isDone())
			return;
		StrataSlice slice = pending.join();
		pending = null;
		surveys++;

		if (slice.isEmpty()) {
			// Backed off rather than flat, because the flat wait was the whole of "I placed it and
			// nothing happened". Y is never displaced, so whether a signature finds rock is decided by
			// the height the machine sits at: underground nearly all of them land, at the surface most
			// sample sky and come back empty. A machine placed up top could therefore spend several
			// flat ten-second waits in a row before its first block, in silence.
			//
			// So the first retry is a second, and only a machine that keeps missing works its way up
			// to the configured ceiling. One that is merely unlucky is not punished for it.
			barrenStreak = Math.min(barrenStreak + 1, 8);
			barrenCooldown = Math.min(TerraformConfig.barrenRetryTicks(), FIRST_BARREN_RETRY << barrenStreak);
			idleReason = ExtruderIdleReason.BARREN;
			return;
		}
		barrenStreak = 0;
		acceptSample(slice);
	}

	/**
	 * Two blocks out, not one.
	 *
	 * <p>Every Create machine of this shape works at arm's length: {@code DeployerBlockEntity}
	 * acts on {@code worldPosition.relative(facing, 2)}, and a Mechanical Press, a Spout and a
	 * Mixer all leave the block directly in front of them empty for the working part to travel
	 * through. Reaching only one block would make this the odd machine out, and would leave nowhere
	 * for the ram to be seen moving.
	 */
	/**
	 * The machine plus everything its barrel reaches, which is what gets frustum-culled together.
	 *
	 * <p>Without this the box is the machine's own cell, and the barrel — which stands most of a
	 * block outside it — vanishes the moment that cell leaves the view frustum. Walking up to the
	 * business end is exactly when that happens, so the tube disappeared precisely when a player was
	 * looking at it. Create's Deployer overrides this for the same reason and with the same number:
	 * its hand reaches two blocks and it inflates by three.
	 */
	@Override
	protected AABB createRenderBoundingBox() {
		return super.createRenderBoundingBox()
			.inflate(3);
	}

	public BlockPos target() {
		return worldPosition.relative(getFacing(), REACH);
	}

	private Direction getFacing() {
		return TerraformExtruderBlock.getFacing(getBlockState());
	}

	private boolean hasSubstrate() {
		return !tank.getPrimaryHandler()
			.drain(substrate(), FluidAction.SIMULATE)
			.isEmpty();
	}

	private void drainSubstrate() {
		tank.getPrimaryHandler()
			.drain(substrate(), FluidAction.EXECUTE);
	}

	private static FluidStack substrate() {
		return new FluidStack(TerraformFluids.MINERAL_SUBSTRATE.get(), TerraformConfig.substratePerBlock());
	}

	// --- persistence --------------------------------------------------------------------

	/**
	 * The overlay is the only place a player sees why a machine is quiet, so it has to be current —
	 * but a packet per placement, at up to ten placements a second, is not a price worth paying for
	 * it. The lazy tick is Create's own every-ten-ticks hook, and sending only on a change means an
	 * idle machine sends nothing at all.
	 */
	@Override
	public void lazyTick() {
		super.lazyTick();
		if (level == null || level.isClientSide)
			return;
		if (idleReason == sentIdleReason && lastPrinted == sentLastPrinted)
			return;
		sentIdleReason = idleReason;
		sentLastPrinted = lastPrinted;
		sendData();
	}

	@Override
	protected void write(CompoundTag tag, Provider registries, boolean clientPacket) {
		super.write(tag, registries, clientPacket);
		tag.putInt("IdleReason", idleReason.ordinal());
		tag.putInt("Timer", timer);
		tag.put("LastPrinted", NbtUtils.writeBlockState(lastPrinted));
		// The client is shown a number, not a pool: the overlay wants "1,842 blocks left", and
		// syncing the states themselves would put a couple of thousand of them on the wire every
		// time anything about the machine changed.
		tag.putInt("SampleSize", sample.size());
		if (!clientPacket) {
			tag.putInt("SampleY", sampleY);
			tag.put("Sample", writeSample());
		}
	}

	@Override
	protected void read(CompoundTag tag, Provider registries, boolean clientPacket) {
		super.read(tag, registries, clientPacket);
		idleReason = ExtruderIdleReason.byOrdinal(tag.getInt("IdleReason"));
		timer = tag.getInt("Timer");
		// The built-in registry rather than the level's: blocks are not a datapack registry, and
		// this is read on the client too, where the level lookup would be the wrong one to reach for.
		lastPrinted = tag.contains("LastPrinted")
			? NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), tag.getCompound("LastPrinted"))
			: Blocks.AIR.defaultBlockState();
		clientSampleSize = tag.getInt("SampleSize");
		if (!clientPacket) {
			sampleY = tag.contains("SampleY") ? tag.getInt("SampleY") : Integer.MIN_VALUE;
			readSample(tag.getCompound("Sample"));
		}
	}

	/** What the client last heard about {@link #sample}'s size. Display only. */
	private int clientSampleSize;

	/**
	 * A core sample as a palette and a run of counts rather than a list of states.
	 *
	 * <p>Two thousand block states written out one compound tag apiece is a hundred kilobytes of NBT
	 * per machine, and a shed full of Extruders would make chunk saves visibly slow. A sample is a
	 * bag rather than a sequence — the order it was shuffled into carries no meaning, and it is
	 * reshuffled on load anyway — so it compresses to one entry per distinct block and a count, which
	 * for any real slice is a few dozen entries.
	 */
	private ListTag writeSample() {
		Map<BlockState, Integer> counts = new LinkedHashMap<>();
		for (BlockState state : sample)
			counts.merge(state, 1, Integer::sum);

		ListTag entries = new ListTag();
		counts.forEach((state, count) -> {
			CompoundTag entry = new CompoundTag();
			entry.put("State", NbtUtils.writeBlockState(state));
			entry.putInt("Count", count);
			entries.add(entry);
		});
		return entries;
	}

	private void readSample(Tag stored) {
		sample.clear();
		if (!(stored instanceof ListTag entries))
			return;
		for (int i = 0; i < entries.size(); i++) {
			CompoundTag entry = entries.getCompound(i);
			BlockState state = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), entry.getCompound("State"));
			int count = entry.getInt("Count");
			for (int n = 0; n < count; n++)
				sample.add(state);
		}
		// Reshuffled rather than restored in order, because the counted form has no order to
		// restore. Seeded from the machine's own position so a reload is deterministic.
		VirtualStrata.shuffle(sample, worldPosition.asLong());
	}

	// --- goggles ------------------------------------------------------------------------

	@Override
	public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
		TerraformLang.translate("tooltip.terraform_extruder.title")
			.forGoggles(tooltip);

		TerraformLang.translate("tooltip.terraform_extruder.substrate")
			.style(ChatFormatting.GRAY)
			.forGoggles(tooltip, 1);
		CreateLang.number(tank.getPrimaryHandler()
			.getFluidAmount())
			.translate("generic.unit.millibuckets")
			.text(" / ")
			.add(CreateLang.number(tank.getPrimaryHandler()
				.getCapacity())
				.translate("generic.unit.millibuckets"))
			.style(ChatFormatting.AQUA)
			.forGoggles(tooltip, 2);

		TerraformLang.translate("tooltip.terraform_extruder.sample")
			.style(ChatFormatting.GRAY)
			.forGoggles(tooltip, 1);
		CreateLang.number(level != null && level.isClientSide ? clientSampleSize : sample.size())
			.style(ChatFormatting.GOLD)
			.forGoggles(tooltip, 2);

		if (idleReason == ExtruderIdleReason.NONE) {
			TerraformLang.translate("tooltip.terraform_extruder.interval")
				.style(ChatFormatting.GRAY)
				.forGoggles(tooltip, 1);
			CreateLang.number(20.0 / cycleTicks())
				.text("/s")
				.style(ChatFormatting.GREEN)
				.forGoggles(tooltip, 2);

			if (!lastPrinted.isAir()) {
				TerraformLang.translate("tooltip.terraform_extruder.last")
					.style(ChatFormatting.GRAY)
					.forGoggles(tooltip, 1);
				CreateLang.blockName(lastPrinted)
					.style(ChatFormatting.GOLD)
					.forGoggles(tooltip, 2);
			}
		} else {
			TerraformLang.translate(idleReason.translationKey())
				.style(ChatFormatting.RED)
				.forGoggles(tooltip, 1);
		}

		// Create's stress lines go underneath; the return says this block filled the overlay, which
		// it has whether or not there was any stress worth quoting.
		super.addToGoggleTooltip(tooltip, isPlayerSneaking);
		return true;
	}
}
