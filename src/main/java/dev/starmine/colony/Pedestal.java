package dev.starmine.colony;

import dev.starmine.StarMine;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveSavedData;
import dev.starmine.mineral.MineralBlock;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The founding pedestal (owner 19.10): a race's block ({@link PlatformBlock}) on top of a step pyramid of mineral blocks
 * ({@link MineralBlock}, rich ones too) — 3 x 3 for a player's first base, a layer of 5 x 5 under it for the second,
 * 7 x 7 more for the third and every one after ({@link #layers}). A right click on the race's block, while the player
 * has no base in this world, takes the pedestal away and calls a transport of the race with a worker; landed, the
 * worker founds the player's base there and builds its main building at once (the pedestal's minerals pay for it).
 */
public final class Pedestal {
	/** Where the transport comes from: this far aside and up; ticks it may take; ticks it flies off before it is gone. */
	static final double FROM = 18, FROM_UP = 14;
	static final int ARRIVE_TICKS = 1200, LEAVE_TICKS = 60;

	private Pedestal() {
	}

	public static void load() {
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(Pedestal::serverTick);
	}

	/** Layers of mineral blocks under the race's block this player's next founding needs: 1, 2, then 3. */
	public static int layers(ServerLevel level, UUID player) {
		return Math.min(3, 1 + Counted.of(level).foundings(player));
	}

	/** How many layers of mineral blocks stand under the race's block at `top` (each 2 wider, 3 x 3 first). */
	static int pyramid(ServerLevel level, BlockPos top) {
		int layers = 0;
		for (int layer = 1; layer <= 3; layer++) {
			int half = layer;
			BlockPos centre = top.below(layer);
			for (int dx = -half; dx <= half; dx++)
				for (int dz = -half; dz <= half; dz++)
					if (!(level.getBlockState(centre.offset(dx, 0, dz)).getBlock() instanceof MineralBlock)) return layers;
			layers = layer;
		}
		return layers;
	}

	/**
	 * A right click on a race's block: when it crowns a big enough pyramid and the player has no base here, the pedestal
	 * goes and the transport comes. Else why not, as a lang key (null: called).
	 */
	public static @Nullable String use(ServerLevel level, BlockPos top, UnitKind.Race race, Player player) {
		if (HiveSavedData.get(level.getServer()).baseOf(player.getUUID(), level.dimension().location().toString()) != null)
			return "message.starmine.pedestal_has_base";
		int need = layers(level, player.getUUID()), have = pyramid(level, top);
		if (have < need) return need == 1 ? "message.starmine.pedestal_first" : "message.starmine.pedestal_more";
		for (int layer = need; layer >= 1; layer--) // the pedestal goes: its race's block and the minerals it needed
			for (BlockPos pos : BlockPos.betweenClosed(top.below(layer).offset(-layer, 0, -layer), top.below(layer).offset(layer, 0, layer)))
				level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
		level.setBlock(top, Blocks.AIR.defaultBlockState(), 3);
		Counted.of(level).add(player.getUUID());
		BlockPos site = top.below(need);
		call(level, player.getUUID(), race, site);
		return null;
	}

	/** Messages {@link #use} gives, with their numbers: the side of the pyramid's widest layer. */
	public static Object[] args(ServerLevel level, Player player) {
		int side = 1 + 2 * layers(level, player.getUUID());
		return new Object[] {side, side};
	}

	// ---- the transport ----

	private record Call(ResourceKey<Level> dimension, BlockPos site, UUID player, UnitKind.Race race, UUID transport, UUID worker, long[] since, boolean[] landed) {
	}

	private static final List<Call> CALLS = new ArrayList<>();

	static void call(ServerLevel level, UUID player, UnitKind.Race race, BlockPos site) {
		StarUnit transport = StarMine.type(PlatformBlock.transportOf(race)).create(level), worker = StarMine.type(UnitKind.workerOf(race)).create(level);
		if (transport == null || worker == null) return;
		double angle = level.random.nextDouble() * Math.PI * 2;
		Vec3 from = Vec3.atBottomCenterOf(site).add(Math.cos(angle) * FROM, FROM_UP, Math.sin(angle) * FROM);
		transport.moveTo(from.x, from.y, from.z, 0, 0);
		transport.setOwner(player);
		worker.moveTo(from.x, from.y, from.z, 0, 0);
		worker.setOwner(player);
		level.addFreshEntity(transport);
		level.addFreshEntity(worker);
		worker.startRiding(transport, true);
		transport.command(StarUnit.Order.MOVE, Vec3.atBottomCenterOf(site));
		CALLS.add(new Call(level.dimension(), site.immutable(), player, race, transport.getUUID(), worker.getUUID(), new long[] {level.getGameTime()}, new boolean[1]));
	}

	static void serverTick(MinecraftServer server) {
		for (Iterator<Call> it = CALLS.iterator(); it.hasNext(); ) {
			Call call = it.next();
			ServerLevel level = server.getLevel(call.dimension);
			if (level == null || !(level.getEntity(call.transport) instanceof StarUnit transport) || !transport.isAlive()) {
				it.remove();
				continue;
			}
			long now = level.getGameTime();
			if (call.landed[0]) {
				if (now - call.since[0] > LEAVE_TICKS) {
					transport.discard();
					it.remove();
				}
				continue;
			}
			Vec3 site = Vec3.atBottomCenterOf(call.site).add(UnitKind.workerOf(call.race).width + 3, 0, 0); // beside where the base will stand
			if (now - call.since[0] > ARRIVE_TICKS) transport.teleportTo(site.x, transport.getY(), site.z); // it could not come: it is there
			if (Math.hypot(transport.getX() - site.x, transport.getZ() - site.z) > 2.5) {
				if (transport.order() != StarUnit.Order.MOVE) transport.command(StarUnit.Order.MOVE, site);
				continue;
			}
			dev.starmine.ability.Transport.unloadHere(transport);
			call.landed[0] = true;
			call.since[0] = now;
			transport.command(StarUnit.Order.MOVE, site.add(FROM, FROM_UP * 2, 0));
			if (level.getEntity(call.worker) instanceof StarUnit worker && worker.isAlive()) arrive(level, call, worker);
		}
	}

	/** The worker is down: the player's base is founded at the pedestal and the worker builds its main building there. */
	static void arrive(ServerLevel level, Call call, StarUnit worker) {
		HiveSavedData data = HiveSavedData.get(level.getServer());
		String dimension = level.dimension().location().toString();
		Hive base = data.baseOf(call.player, dimension);
		if (base == null) base = Storage.found(level, call.player, call.race, call.site);
		UnitKind main = PlatformBlock.mainOf(call.race);
		base.minerals += main.economy().minerals(); // the pedestal's minerals pay for it
		base.vespene += main.economy().vespene();
		worker.joinHive(base);
		worker.orderBuild(main, call.site);
		data.setDirty();
		ServerPlayer who = level.getServer().getPlayerList().getPlayer(call.player);
		if (who != null) who.sendSystemMessage(Component.translatable("message.starmine.pedestal_founded"));
	}

	/** How many bases each player founded at a pedestal (the next one needs a bigger pyramid). */
	static final class Counted extends SavedData {
		private static final Factory<Counted> FACTORY = new Factory<>(Counted::new, Counted::load, null);
		private final CompoundTag counts = new CompoundTag();

		static Counted of(ServerLevel level) {
			return level.getServer().overworld().getDataStorage().computeIfAbsent(FACTORY, "starmine_foundings");
		}

		int foundings(UUID player) {
			return counts.getInt(player.toString());
		}

		void add(UUID player) {
			counts.putInt(player.toString(), foundings(player) + 1);
			setDirty();
		}

		@Override
		public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
			tag.put("counts", counts.copy());
			return tag;
		}

		private static Counted load(CompoundTag tag, HolderLookup.Provider registries) {
			Counted counted = new Counted();
			counted.counts.merge(tag.getCompound("counts"));
			return counted;
		}
	}
}
