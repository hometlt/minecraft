package dev.starmine.colony;

import dev.starmine.StarMine;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveResources;
import dev.starmine.hive.HiveSavedData;
import dev.starmine.mineral.MineralBlock;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The summoning beacon (owner 22.10), the same for every race with its own resource crate ({@link PlatformBlock}):
 * <ol>
 * <li>the crate stands at least {@link #KEEP_OFF} blocks from minerals and vespene (it cannot be put nearer);</li>
 * <li>eight mineral blocks ({@link MineralBlock}, rich ones too) round it on its level make the ring;</li>
 * <li>the command panel used on the crate, while the player has no base in this world, lights the beacon: a beam goes up
 * and the race's transport flies in with a worker;</li>
 * <li>come, it takes the ring's mineral blocks up (a beam, the blocks going up one by one) and lands the worker; the crate
 * stays, now the base's store — workers bring what they gather to it as to a main building, and it opens as the base's
 * store, as every resource crate and main building of the base does (an ender chest's way, {@link Storage}).</li>
 * </ol>
 * With 400 minerals in the store a command center (hatchery, nexus) may be built. A ring broken before the transport
 * comes: it flies off with its worker.
 */
public final class Pedestal {
	/** How near minerals and vespene a resource crate may stand, at the nearest (blocks). */
	public static final int KEEP_OFF = 9;
	/** Where the transport comes from: this far aside and up; ticks it may take; ticks it flies off before it is gone. */
	static final double FROM = 18, FROM_UP = 14;
	static final int ARRIVE_TICKS = 1200, LEAVE_TICKS = 60;

	private Pedestal() {
	}

	public static void load() {
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(Pedestal::serverTick);
	}

	/** The eight places of the ring round a crate at `crate`: its level, every neighbour. */
	static List<BlockPos> ring(BlockPos crate) {
		List<BlockPos> ring = new ArrayList<>();
		for (int dx = -1; dx <= 1; dx++)
			for (int dz = -1; dz <= 1; dz++)
				if (dx != 0 || dz != 0) ring.add(crate.offset(dx, 0, dz));
		return ring;
	}

	/** Whether the ring of mineral blocks round the crate is whole. */
	static boolean ringWhole(Level level, BlockPos crate) {
		for (BlockPos pos : ring(crate)) if (!(level.getBlockState(pos).getBlock() instanceof MineralBlock)) return false;
		return true;
	}

	/** Minerals (crystals) or vespene (an outlet, a geyser's cone) within {@link #KEEP_OFF} blocks of `pos`. */
	public static boolean nearResources(Level level, BlockPos pos) {
		for (BlockPos at : BlockPos.betweenClosed(pos.offset(-KEEP_OFF, -KEEP_OFF, -KEEP_OFF), pos.offset(KEEP_OFF, KEEP_OFF, KEEP_OFF))) {
			BlockState state = level.getBlockState(at);
			if (dev.starmine.mineral.Minerals.isCrystal(state) || dev.starmine.vespene.Vespene.isOutlet(state) || state.is(dev.starmine.vespene.Vespene.ROCK))
				return true;
		}
		return false;
	}

	/**
	 * The command panel used on a resource crate: the beacon lights and the transport comes when the ring is whole and the
	 * player has no base here. Else why not, as a lang key (null: called).
	 */
	public static @Nullable String use(ServerLevel level, BlockPos crate, UnitKind.Race race, Player player) {
		if (HiveSavedData.get(level.getServer()).baseOf(player.getUUID(), level.dimension().location().toString()) != null)
			return "message.starmine.beacon_has_base";
		if (!ringWhole(level, crate)) return "message.starmine.beacon_ring";
		if (CALLS.stream().anyMatch(call -> call.crate.equals(crate) && call.dimension == level.dimension())) return "message.starmine.platform_called";
		if (level.getBlockEntity(crate) instanceof PlatformBlockEntity box) {
			box.called = true;
			box.setChanged();
		}
		call(level, player.getUUID(), race, crate);
		return null;
	}

	// ---- the transport ----

	private enum Phase { COMING, LIFTING, LEAVING }

	private static final class Call {
		final ResourceKey<Level> dimension;
		final BlockPos crate;
		final UUID player, transport, worker;
		final UnitKind.Race race;
		Phase phase = Phase.COMING;
		long since;
		boolean landed;
		/** The ring's blocks still to be taken up. */
		final List<BlockPos> blocks = new ArrayList<>();

		Call(ResourceKey<Level> dimension, BlockPos crate, UUID player, UnitKind.Race race, UUID transport, UUID worker, long since) {
			this.dimension = dimension;
			this.crate = crate.immutable();
			this.player = player;
			this.race = race;
			this.transport = transport;
			this.worker = worker;
			this.since = since;
		}
	}

	private static final List<Call> CALLS = new ArrayList<>();

	static void call(ServerLevel level, UUID player, UnitKind.Race race, BlockPos crate) {
		StarUnit transport = StarMine.type(PlatformBlock.transportOf(race)).create(level), worker = StarMine.type(UnitKind.workerOf(race)).create(level);
		if (transport == null || worker == null) return;
		double angle = level.random.nextDouble() * Math.PI * 2;
		Vec3 from = Vec3.atBottomCenterOf(crate).add(Math.cos(angle) * FROM, FROM_UP, Math.sin(angle) * FROM);
		transport.moveTo(from.x, from.y, from.z, 0, 0);
		transport.setOwner(player);
		worker.moveTo(from.x, from.y, from.z, 0, 0);
		worker.setOwner(player);
		level.addFreshEntity(transport);
		level.addFreshEntity(worker);
		worker.startRiding(transport, true);
		CALLS.add(new Call(level.dimension(), crate, player, race, transport.getUUID(), worker.getUUID(), level.getGameTime()));
		transport.command(StarUnit.Order.MOVE, hover(crate, race));
		level.playSound(null, crate, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 1.2f, 1.0f);
	}

	/** Where the transport hangs while it takes the ring up: over it, beside the crate. */
	private static Vec3 hover(BlockPos crate, UnitKind.Race race) {
		return Vec3.atBottomCenterOf(crate).add(UnitKind.workerOf(race).width + 3, 4, 0);
	}

	static void serverTick(MinecraftServer server) {
		for (Iterator<Call> it = CALLS.iterator(); it.hasNext(); ) {
			Call call = it.next();
			ServerLevel level = server.getLevel(call.dimension);
			if (level == null || !(level.getEntity(call.transport) instanceof StarUnit transport) || !transport.isAlive()) {
				if (level != null && level.getEntity(call.worker) instanceof StarUnit worker && worker.isPassenger() && !call.landed) worker.discard();
				if (level != null) release(level, call.crate);
				it.remove();
				continue;
			}
			long now = level.getGameTime();
			if (call.phase != Phase.LEAVING && now % 20 == 0) // the beacon: a beam up from the crate while the transport comes
				dev.starmine.entity.Fx.beam(level, dev.starmine.entity.Fx.Style.BEAM, Vec3.atCenterOf(call.crate),
					Vec3.atCenterOf(call.crate).add(0, 60, 0), beamColour(call.race), 24, 0.8f);
			switch (call.phase) {
				case LEAVING -> {
					if (now - call.since > LEAVE_TICKS) {
						if (!call.landed && level.getEntity(call.worker) instanceof StarUnit worker) worker.discard(); // it leaves with the transport
						transport.discard();
						it.remove();
					}
				}
				case COMING -> {
					Vec3 hover = hover(call.crate, call.race);
					if (now - call.since > ARRIVE_TICKS) transport.teleportTo(hover.x, Math.max(transport.getY(), hover.y), hover.z); // it could not come: it is there
					if (Math.hypot(transport.getX() - hover.x, transport.getZ() - hover.z) > 2.5) {
						if (transport.order() != StarUnit.Order.MOVE) transport.command(StarUnit.Order.MOVE, hover);
						continue;
					}
					if (!(level.getBlockState(call.crate).getBlock() instanceof PlatformBlock) || !ringWhole(level, call.crate)) {
						leave(level, call, transport, now); // the ring or the crate broken before it came: off with its worker
						tell(level, call, "message.starmine.beacon_broken");
						release(level, call.crate);
						continue;
					}
					transport.command(StarUnit.Order.HOLD, transport.position());
					call.blocks.addAll(ring(call.crate));
					call.phase = Phase.LIFTING;
					call.since = now;
				}
				case LIFTING -> {
					Vec3 from = transport.position().add(0, transport.getBbHeight() * 0.3, 0);
					if (now % 4 == 0 && !call.blocks.isEmpty())
						dev.starmine.entity.Fx.beam(level, dev.starmine.entity.Fx.Style.BEAM, from, Vec3.atCenterOf(call.blocks.getFirst()), beamColour(call.race), 6, 1.0f);
					if (now % 3 == 0 && !call.blocks.isEmpty()) take(level, call.blocks.removeFirst(), from);
					if (!call.blocks.isEmpty()) continue;
					found(level, call, transport);
					leave(level, call, transport, now);
				}
			}
		}
	}

	/** The ring is up: the crate becomes the base's store, the base is founded there, the worker lands and joins it. */
	private static void found(ServerLevel level, Call call, StarUnit transport) {
		BlockPos crate = call.crate;
		// what is not a resource in the crate stays the player's: it drops by the crate
		if (level.getBlockEntity(crate) instanceof PlatformBlockEntity box)
			for (int i = 0; i < box.getContainerSize(); i++) {
				ItemStack stack = box.getItem(i);
				if (!stack.isEmpty() && !HiveResources.yieldOfItem(stack).any()) {
					net.minecraft.world.Containers.dropItemStack(level, crate.getX() + 0.5, crate.getY() + 1, crate.getZ() + 0.5, box.removeItemNoUpdate(i));
				}
			}
		StarUnit store = Storage.fromBlock(level, crate, call.race, call.player); // the crate, as a building: the base's store
		HiveSavedData data = HiveSavedData.get(level.getServer());
		String dimension = level.dimension().location().toString();
		Hive base = data.baseOf(call.player, dimension);
		if (base == null) base = Storage.found(level, call.player, call.race, crate);
		if (store != null && store.hiveId() == null) Storage.join(level, store, base);
		dev.starmine.ability.Transport.unloadHere(transport);
		call.landed = true;
		if (level.getEntity(call.worker) instanceof StarUnit worker && worker.isAlive()) {
			worker.teleportTo(crate.getX() + 2.5, crate.getY(), crate.getZ() + 0.5);
			worker.joinHive(base);
		}
		data.setDirty();
		level.playSound(null, crate, SoundEvents.BEACON_POWER_SELECT, SoundSource.BLOCKS, 1.2f, 1.0f);
		tell(level, call, "message.starmine.beacon_founded");
	}

	/** The transport flies off, up and away. */
	private static void leave(ServerLevel level, Call call, StarUnit transport, long now) {
		call.phase = Phase.LEAVING;
		call.since = now;
		transport.command(StarUnit.Order.MOVE, transport.position().add(FROM, FROM_UP * 2, 0));
	}

	/** One mineral block of the ring goes up into the transport: its crumbs and a stream of light. */
	private static void take(ServerLevel level, BlockPos pos, Vec3 to) {
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof MineralBlock)) return;
		level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
		Vec3 at = Vec3.atCenterOf(pos), up = to.subtract(at);
		level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), at.x, at.y, at.z, 12, 0.3, 0.3, 0.3, 0.05);
		for (int i = 1; i <= 5; i++) {
			Vec3 p = at.add(up.scale(i / 6.0));
			level.sendParticles(ParticleTypes.END_ROD, p.x, p.y, p.z, 1, 0.05, 0.05, 0.05, 0.01);
		}
		level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 0.9f, 0.6f + level.random.nextFloat() * 0.6f);
	}

	private static int beamColour(UnitKind.Race race) {
		return switch (race) {
			case ZERG -> 0xB060FF;
			case TERRAN -> 0x60C0FF;
			case PROTOSS -> 0xFFE070;
		};
	}

	/** The crate of a beacon no transport comes for any more takes calls again. */
	private static void release(ServerLevel level, BlockPos crate) {
		if (level.getBlockEntity(crate) instanceof PlatformBlockEntity box && box.called) {
			box.called = false;
			box.setChanged();
		}
	}

	private static void tell(ServerLevel level, Call call, String key) {
		ServerPlayer who = level.getServer().getPlayerList().getPlayer(call.player);
		if (who != null) who.sendSystemMessage(Component.translatable(key));
	}

	/** Whether a transport is on its way to the crate at `crate`. */
	public static boolean coming(Level level, BlockPos crate) {
		return CALLS.stream().anyMatch(call -> call.crate.equals(crate) && call.dimension == level.dimension());
	}
}
