package dev.starmine.world;

import dev.starmine.StarMine;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.HiveSavedData;
import dev.starmine.mineral.Deposits;
import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/** A normal paid player-versus-AI match, independent of the automated playtest harness. */
public final class Skirmish {
	public static final String TAG = "starmine_skirmish";
	/** Dimensions with a skirmish going on (owner 22.10: their land is not broken and no block is put in it). */
	private static final java.util.Set<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>> LOCKED = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private Skirmish() {}

	/**
	 * Owner 22.10: in a skirmish the land stays as it is — units dig no tunnels, fill no gaps, wear no blocks, blasts
	 * break nothing; only minerals and vespene are taken. True in the native map's world and in a dimension where a
	 * player plays a skirmish.
	 */
	public static boolean terrainLocked(net.minecraft.world.level.Level level) {
		return level != null && !level.isClientSide && (SkirmishMap.nativeWorld() && level.dimension() == net.minecraft.world.level.Level.OVERWORLD
			|| LOCKED.contains(level.dimension()));
	}

	/**
	 * A skirmish asked for: the races, or leaving one. Owner 22.10: `dimension` names where it is played ("" for the
	 * player's own dimension); `here` lays its arena by the player (a world made for the skirmish) instead of far off.
	 */
	public record Request(int playerRace, int aiRace, boolean leave, String dimension, boolean here) implements CustomPacketPayload {
		public Request(int playerRace, int aiRace, boolean leave) { this(playerRace, aiRace, leave, "", false); }
		public static final Type<Request> TYPE = new Type<>(StarMine.id("skirmish_request"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = new StreamCodec<>() {
			public Request decode(RegistryFriendlyByteBuf b) { return new Request(b.readVarInt(), b.readVarInt(), b.readBoolean(), b.readUtf(256), b.readBoolean()); }
			public void encode(RegistryFriendlyByteBuf b, Request r) {
				b.writeVarInt(r.playerRace); b.writeVarInt(r.aiRace); b.writeBoolean(r.leave); b.writeUtf(r.dimension, 256); b.writeBoolean(r.here);
			}
		};
		public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** Whether the player plays a skirmish, and (owner 22.10) its map's bounds on the ground (all 0: none known). */
	public record State(boolean active, int minX, int minZ, int maxX, int maxZ) implements CustomPacketPayload {
		public State(boolean active) { this(active, 0, 0, 0, 0); }
		public static final Type<State> TYPE = new Type<>(StarMine.id("skirmish_state"));
		public static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC = new StreamCodec<>() {
			public State decode(RegistryFriendlyByteBuf b) { return new State(b.readBoolean(), b.readInt(), b.readInt(), b.readInt(), b.readInt()); }
			public void encode(RegistryFriendlyByteBuf b, State s) {
				b.writeBoolean(s.active); b.writeInt(s.minX); b.writeInt(s.minZ); b.writeInt(s.maxX); b.writeInt(s.maxZ);
			}
		};
		public Type<? extends CustomPacketPayload> type() { return TYPE; }
		public boolean bounded() { return maxX > minX && maxZ > minZ; }
	}

	/**
	 * Owner 22.10: the whole map is explored at a skirmish's start and shown on the minimap with its relief, under the fog
	 * of war. The client has only the chunks near its camera, so the server paints the map: `argb` holds rows of colours
	 * from `row` on, `width` wide, of the map whose corner is (minX, minZ) and whose size is width x height.
	 */
	public record Overview(int minX, int minZ, int width, int height, int row, int[] argb) implements CustomPacketPayload {
		public static final Type<Overview> TYPE = new Type<>(StarMine.id("skirmish_overview"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Overview> CODEC = new StreamCodec<>() {
			public Overview decode(RegistryFriendlyByteBuf b) {
				int minX = b.readInt(), minZ = b.readInt(), width = b.readVarInt(), height = b.readVarInt(), row = b.readVarInt(), n = b.readVarInt();
				if (n < 0 || n > 1 << 18) throw new IllegalArgumentException("Skirmish overview slice too large: " + n);
				int[] argb = new int[n];
				for (int i = 0; i < n; i++) argb[i] = b.readInt();
				return new Overview(minX, minZ, width, height, row, argb);
			}
			public void encode(RegistryFriendlyByteBuf b, Overview o) {
				b.writeInt(o.minX); b.writeInt(o.minZ); b.writeVarInt(o.width); b.writeVarInt(o.height); b.writeVarInt(o.row); b.writeVarInt(o.argb.length);
				for (int c : o.argb) b.writeInt(c);
			}
		};
		public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** Rows of the overview in one packet (528 wide: about 50 kB). */
	private static final int OVERVIEW_ROWS = 24;
	/** The map's bounds per player playing a skirmish (minX, minZ, maxX, maxZ); the players already sent its overview. */
	private static final java.util.Map<java.util.UUID, int[]> BOUNDS = new java.util.concurrent.ConcurrentHashMap<>();
	private static final java.util.Set<java.util.UUID> SENT = java.util.concurrent.ConcurrentHashMap.newKeySet();

	public static void register() {
		PayloadTypeRegistry.playC2S().register(Request.TYPE, Request.CODEC);
		PayloadTypeRegistry.playS2C().register(State.TYPE, State.CODEC);
		PayloadTypeRegistry.playS2C().register(Overview.TYPE, Overview.CODEC);
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> SENT.remove(handler.getPlayer().getUUID()));
		ServerPlayNetworking.registerGlobalReceiver(Request.TYPE, (r, c) -> {
			ServerPlayer p = c.player();
			if (r.leave) { leave(p); return; }
			if (!p.hasPermissions(2)) { p.sendSystemMessage(Component.translatable("skirmish.starmine.operator")); return; }
			if (r.playerRace < 0 || r.playerRace >= 3 || r.aiRace < 0 || r.aiRace >= 3 || p.getTags().contains(TAG)) return;
			ServerLevel where = p.serverLevel();
			if (!r.dimension.isEmpty()) { // owner 22.10: the dimension the player chose
				var location = net.minecraft.resources.ResourceLocation.tryParse(r.dimension);
				ServerLevel named = location == null ? null
					: p.server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, location));
				if (named != null) where = named;
			}
			if (HiveSavedData.get(p.server).baseOf(p.getUUID(), where.dimension().location().toString()) != null) {
				p.sendSystemMessage(Component.translatable("skirmish.starmine.existing_base")); return;
			}
			start(p, where, UnitKind.Race.values()[r.playerRace], UnitKind.Race.values()[r.aiRace], r.here);
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 20 != 0) return;
			java.util.Set<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>> locked = new java.util.HashSet<>();
			for (ServerPlayer p : server.getPlayerList().getPlayers()) if (p.getTags().contains(TAG)) locked.add(p.serverLevel().dimension());
			LOCKED.retainAll(locked);
			LOCKED.addAll(locked);
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				if (SkirmishMap.nativeWorld() && !p.getTags().contains("starmine_skirmish_initialized")) {
					p.addTag("starmine_skirmish_initialized");
					start(p, SkirmishMap.playerRace(), SkirmishMap.aiRace());
				}
				boolean active = p.getTags().contains(TAG);
				if (active && !p.isSpectator()) p.setGameMode(GameType.SPECTATOR);
				if (active && server.getTickCount() % 200 == 0) {
					var data = HiveSavedData.get(server);
					var home = data.baseOf(p.getUUID(), p.serverLevel().dimension().location().toString());
					if (home != null) for (String tag : p.getTags()) if (tag.startsWith("starmine_match:")) {
						var enemy = data.get(java.util.UUID.fromString(tag.substring(15)));
						if (enemy != null && enemy.owner == null) {
							var mains = p.serverLevel().getEntitiesOfClass(StarUnit.class, new AABB(enemy.hatchery).inflate(128),
								u -> u.kind.main() && u.isAlive() && enemy.id.equals(u.hiveId()));
							if (!mains.isEmpty()) dev.starmine.ai.AiCommander.wave(mains.getFirst(), p.serverLevel(), enemy, home);
						}
					}
				}
				int[] bounds = active ? BOUNDS.computeIfAbsent(p.getUUID(), id -> boundsOf(p)) : null;
				if (!active) {
					BOUNDS.remove(p.getUUID());
					SENT.remove(p.getUUID());
				}
				if (ServerPlayNetworking.canSend(p, State.TYPE))
					ServerPlayNetworking.send(p, bounds == null ? new State(active) : new State(true, bounds[0], bounds[1], bounds[2], bounds[3]));
				if (bounds != null && SENT.add(p.getUUID())) sendOverview(p, bounds);
			}
		});
	}

	public static void start(ServerPlayer p, UnitKind.Race own, UnitKind.Race enemy) {
		start(p, p.serverLevel(), own, enemy, false);
	}

	/** A skirmish in `level` (the player goes there); `here`: its arena by the player rather than far off. */
	public static void start(ServerPlayer p, ServerLevel level, UnitKind.Race own, UnitKind.Race enemy, boolean here) {
		List<BlockPos> starts = SkirmishMap.nativeWorld() ? SkirmishMap.starts() : level == p.server.overworld() ? MapOverlay.skirmishStarts() : List.of();
		boolean map = SkirmishMap.nativeWorld() || starts.size() >= 2 && starts.subList(0, 2).stream().allMatch(at ->
			level.getEntitiesOfClass(StarUnit.class, new AABB(at).inflate(32), u -> u.isAlive() && u.kind.structure()).isEmpty()
				&& dev.starmine.mineral.Minerals.coresNear(level, at, 36).size() >= 5);
		if (!map) {
			// A fresh remote area preserves the player's current base and builds in the selected dimension.
			int x = 100000 + Math.floorMod(p.getUUID().hashCode(), 1000) * 384;
			int z = 100000 + Math.floorMod((int) level.getGameTime(), 1000) * 256;
			if (here) { // owner 22.10: a world made for the skirmish: its arena by the player
				x = p.getBlockX() + 160;
				z = p.getBlockZ();
			}
			int y = Math.clamp(level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR, x, z), level.getMinBuildHeight() + 12, level.getMaxBuildHeight() - 40);
			for (BlockPos at : BlockPos.betweenClosed(x - 144, y - 4, z - 96, x + 144, y + 30, z + 96)) {
				var state = at.getY() < y - 1 ? Blocks.STONE.defaultBlockState() : at.getY() == y - 1 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState();
				if (!level.getBlockState(at).equals(state)) level.setBlock(at, state, 2);
			}
			starts = List.of(new BlockPos(x - 64, y, z), new BlockPos(x + 64, y, z));
			for (BlockPos start : starts) for (int dz : new int[] {-24, 64})
				Deposits.build(level, level.random, start.getX(), start.getZ() + dz, start.getX(), start.getZ());
		}
		if (map && !SkirmishMap.nativeWorld()) for (BlockPos start : starts.subList(0, 2)) {
			var cores = dev.starmine.mineral.Minerals.coresNear(level, start, 36);
			for (int i = 0; i < cores.size(); i++) {
				if (i >= 5) dev.starmine.mineral.Minerals.harvest(level, cores.get(i), Integer.MAX_VALUE);
				else if (level.getBlockEntity(cores.get(i)) instanceof dev.starmine.mineral.Minerals.Core core) core.setAmount(i < 3 ? 1300 : 800);
			}
		}
		if (SkirmishMap.nativeWorld()) {
			for (int cx = 0; cx <= 32; cx++) for (int cz = 0; cz <= 32; cz++) level.setChunkForced(cx, cz, true);
			p.addTag("starmine_native_chunks");
		} else for (BlockPos start : starts.subList(0, 2))
			for (int cx = (start.getX() >> 4) - 5; cx <= (start.getX() >> 4) + 5; cx++)
				for (int cz = (start.getZ() >> 4) - 5; cz <= (start.getZ() >> 4) + 5; cz++) {
					long key = net.minecraft.world.level.ChunkPos.asLong(cx, cz);
					if (!level.getForcedChunks().contains(key)) {
						level.setChunkForced(cx, cz, true);
						p.addTag("starmine_chunk:" + cx + ":" + cz);
					}
				}
		p.addTag(TAG);
		// owner 22.10: the map's bounds (the native map's 528 x 528, a generated arena round both starts) for its overview
		int[] bounds = SkirmishMap.nativeWorld() ? new int[] {0, 0, 528, 528}
			: new int[] {Math.min(starts.get(0).getX(), starts.get(1).getX()) - 112, Math.min(starts.get(0).getZ(), starts.get(1).getZ()) - 112,
				Math.max(starts.get(0).getX(), starts.get(1).getX()) + 112, Math.max(starts.get(0).getZ(), starts.get(1).getZ()) + 112};
		BOUNDS.put(p.getUUID(), bounds);
		SENT.remove(p.getUUID());
		p.addTag("starmine_bounds:" + bounds[0] + ":" + bounds[1] + ":" + bounds[2] + ":" + bounds[3]);
		p.addTag("starmine_return_mode:" + p.gameMode.getGameModeForPlayer().getName());
		p.addTag("starmine_return:" + p.getX() + ":" + p.getY() + ":" + p.getZ());
		p.addTag("starmine_return_dim:" + p.serverLevel().dimension().location());
		p.setGameMode(GameType.SPECTATOR);
		p.teleportTo(level, starts.getFirst().getX() + .5, starts.getFirst().getY(), starts.getFirst().getZ() + .5, 0, 0);
		p.addTag("starmine_match:" + spawn(level, own, starts.get(0), p.getUUID()));
		p.addTag("starmine_match:" + spawn(level, enemy, starts.get(1), null));
		ServerPlayNetworking.send(p, new State(true, bounds[0], bounds[1], bounds[2], bounds[3]));
		p.sendSystemMessage(Component.translatable(map ? "skirmish.starmine.map" : "skirmish.starmine.generated"));
	}

	/** The map's bounds of a player's skirmish: its tag's (kept over a restart), the native map's, or none. */
	private static int[] boundsOf(ServerPlayer p) {
		for (String tag : p.getTags())
			if (tag.startsWith("starmine_bounds:")) {
				String[] v = tag.substring(16).split(":");
				if (v.length == 4) try {
					return new int[] {Integer.parseInt(v[0]), Integer.parseInt(v[1]), Integer.parseInt(v[2]), Integer.parseInt(v[3])};
				} catch (NumberFormatException ignored) {
				}
			}
		return SkirmishMap.nativeWorld() ? new int[] {0, 0, 528, 528} : null;
	}

	/** The map painted as the minimap paints land (map colours shaded by the height to the north), sent in slices. */
	private static void sendOverview(ServerPlayer p, int[] b) {
		if (!ServerPlayNetworking.canSend(p, Overview.TYPE)) return;
		ServerLevel level = p.serverLevel();
		int width = b[2] - b[0], height = b[3] - b[1];
		if (width <= 0 || height <= 0 || width > 2048 || height > 2048) return;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		var surface = net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE;
		for (int row0 = 0; row0 < height; row0 += OVERVIEW_ROWS) {
			int rows = Math.min(OVERVIEW_ROWS, height - row0);
			int[] argb = new int[rows * width];
			for (int r = 0; r < rows; r++)
				for (int c = 0; c < width; c++) {
					int x = b[0] + c, z = b[1] + row0 + r;
					if (!level.hasChunk(x >> 4, z >> 4)) continue; // left transparent: drawn from the client's own chunks
					int top = level.getHeight(surface, x, z) - 1, north = level.getHeight(surface, x, z - 1) - 1;
					var colour = level.getBlockState(pos.set(x, top, z)).getMapColor(level, pos);
					var shade = top > north ? net.minecraft.world.level.material.MapColor.Brightness.HIGH
						: top < north ? net.minecraft.world.level.material.MapColor.Brightness.LOW : net.minecraft.world.level.material.MapColor.Brightness.NORMAL;
					int abgr = colour == net.minecraft.world.level.material.MapColor.NONE ? 0xFF183020 : colour.calculateRGBColor(shade); // map colours come as ABGR
					argb[r * width + c] = (abgr & 0xFF00FF00) | (abgr & 0xFF) << 16 | (abgr >> 16) & 0xFF;
				}
			ServerPlayNetworking.send(p, new Overview(b[0], b[1], width, height, row0, argb));
		}
	}

	private static java.util.UUID spawn(ServerLevel level, UnitKind.Race race, BlockPos pos, java.util.UUID owner) {
		StarUnit main = StarMine.type(race.rules().main()).create(level);
		main.moveTo(pos.getX() + .5, pos.getY(), pos.getZ() + .5, 0, 0);
		if (owner != null) main.setOwner(owner);
		level.addFreshEntity(main);
		return main.getUUID();
	}

	private static void leave(ServerPlayer p) {
		if (!p.getTags().contains(TAG)) return;
		p.removeTag(TAG);
		AABB matchArea = new AABB(p.blockPosition()).inflate(512);
		ServerLevel arena = p.serverLevel(); // the player may be sent home before the match is cleared
		GameType previous = GameType.SURVIVAL;
		ServerLevel back = p.serverLevel();
		for (String tag : p.getTags())
			if (tag.startsWith("starmine_return_dim:")) {
				var location = net.minecraft.resources.ResourceLocation.tryParse(tag.substring(20));
				ServerLevel named = location == null ? null
					: p.server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, location));
				if (named != null) back = named;
			}
		ServerLevel home = back;
		for (String tag : List.copyOf(p.getTags())) {
			if (tag.startsWith("starmine_bounds:") || tag.startsWith("starmine_return_dim:")) p.removeTag(tag);
			if (tag.equals("starmine_native_chunks")) {
				for (int cx = 0; cx <= 32; cx++) for (int cz = 0; cz <= 32; cz++) arena.setChunkForced(cx, cz, false);
				p.removeTag(tag);
			}
			if (tag.startsWith("starmine_chunk:")) {
				String[] chunk = tag.substring(15).split(":");
				if (chunk.length == 2) try { arena.setChunkForced(Integer.parseInt(chunk[0]), Integer.parseInt(chunk[1]), false); } catch (NumberFormatException ignored) {}
				p.removeTag(tag);
			}
			if (tag.startsWith("starmine_match:")) {
				java.util.UUID id = java.util.UUID.fromString(tag.substring(15));
				for (var u : arena.getEntitiesOfClass(StarUnit.class, matchArea, unit -> id.equals(unit.hiveId()) || id.equals(unit.getUUID()))) u.discard();
				HiveSavedData.get(p.server).remove(id);
				p.removeTag(tag);
			}
			if (tag.startsWith("starmine_return_mode:")) { previous = GameType.byName(tag.substring(21), GameType.SURVIVAL); p.removeTag(tag); }
			if (tag.startsWith("starmine_return:")) {
				String[] xyz = tag.substring(16).split(":");
				if (xyz.length == 3) try {
					p.teleportTo(home, Double.parseDouble(xyz[0]), Double.parseDouble(xyz[1]), Double.parseDouble(xyz[2]), p.getYRot(), p.getXRot());
				} catch (NumberFormatException ignored) {}
				p.removeTag(tag);
			}
		}
		p.setGameMode(previous);
		ServerPlayNetworking.send(p, new State(false));
	}
}
