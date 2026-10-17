package dev.starmine.world;

import dev.starmine.StarMine;
import dev.starmine.block.StarMineBlocks;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveSavedData;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The heroic transport (owner 17.10): when the guardian of a main base is freed it calls its race's transport —
 * an overlord, a medivac or a warp prism, the player's — which waits by the hero. A click on it with the command
 * panel takes the player aboard: it rises into the sky, passes into the stratosphere, flies to the hangar under
 * its race's ship ({@link #hangar}) and sets the player down there, then waits in the hangar. A click there brings
 * the player back down. Should the player leave the stratosphere another way, the transport goes back to wait where
 * it took off. In the overworld it is marked on the minimap, and it takes the command panel's orders like any unit
 * (move it where it should wait).
 */
public final class HeroicTransport {
	public static final String TAG = "starmine_heroic";
	/** The hangar under a ship: half its width, its height; how fast the transport flies (blocks a tick). */
	public static final int HANGAR_HALF = 9, HANGAR_HEIGHT = 8;
	static final double SPEED = 1.2;
	private static final Map<UUID, Flight> FLIGHTS = new HashMap<>();

	private enum Phase { RISE, FLY, IN, DOWN }

	/** A flight under way: the transport, its passenger, where it is in it. */
	private static final class Flight {
		final UUID player;
		Phase phase;
		int ticks, missing;

		Flight(UUID player, Phase phase) {
			this.player = player;
			this.phase = phase;
		}
	}

	private HeroicTransport() {
	}

	public static UnitKind kindOf(UnitKind.Race race) {
		return switch (race) {
			case ZERG -> UnitKind.OVERLORD;
			case TERRAN -> UnitKind.MEDIVAC;
			case PROTOSS -> UnitKind.WARP_PRISM;
		};
	}

	public static boolean is(Entity entity) {
		return entity instanceof StarUnit unit && unit.getTags().contains(TAG);
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(HeroicTransport::tick);
		ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, from, to) -> {
			if (Stratosphere.is(from) && !Stratosphere.is(to)) leftSky(player);
		});
	}

	// ---- the hangar ----

	/**
	 * A hangar under a ship (built with it): a hall {@link #HANGAR_HALF}·2+1 wide under the hull below its pad, open
	 * on one side for the transport, lit, with a ladder up through the hull to the pad's deck.
	 */
	public static void hangar(ServerLevel level, SkyShips.Ship ship, UnitKind.Race race) {
		BlockPos pad = ship.pad();
		int bottom = pad.getY();
		for (int y = pad.getY() - 1; y > pad.getY() - 90 && y > level.getMinBuildHeight() + 12; y--)
			if (!level.getBlockState(new BlockPos(pad.getX(), y, pad.getZ())).isAir()) bottom = y;
		int floorY = bottom - HANGAR_HEIGHT - 1;
		BlockPos floor = new BlockPos(pad.getX(), floorY, pad.getZ());
		BlockState wall = switch (race) {
			case ZERG -> StarMineBlocks.ZERG_FLESH.defaultBlockState();
			case TERRAN -> Blocks.POLISHED_DEEPSLATE.defaultBlockState();
			case PROTOSS -> Blocks.SMOOTH_QUARTZ.defaultBlockState();
		};
		BlockState trim = switch (race) {
			case ZERG -> StarMineBlocks.GLOWING_ZERG_FLESH.defaultBlockState();
			case TERRAN -> Blocks.IRON_BLOCK.defaultBlockState();
			case PROTOSS -> Blocks.GOLD_BLOCK.defaultBlockState();
		};
		BlockState light = switch (race) {
			case ZERG -> Blocks.SHROOMLIGHT.defaultBlockState();
			case TERRAN -> Blocks.SEA_LANTERN.defaultBlockState();
			case PROTOSS -> Blocks.SEA_LANTERN.defaultBlockState();
		};
		int h = HANGAR_HALF;
		for (int dx = -h - 1; dx <= h + 1; dx++)
			for (int dz = -h - 1; dz <= h + 1; dz++)
				for (int dy = 0; dy <= HANGAR_HEIGHT + 1; dy++) {
					BlockPos at = floor.offset(dx, dy, dz);
					boolean shell = Math.abs(dx) == h + 1 || Math.abs(dz) == h + 1 || dy == 0 || dy == HANGAR_HEIGHT + 1;
					boolean door = dx == h + 1 && Math.abs(dz) <= 5 && dy >= 1 && dy <= 6; // open to the east, for the transport
					BlockState state = !shell || door ? Blocks.AIR.defaultBlockState()
						: dy == HANGAR_HEIGHT + 1 && dx % 4 == 0 && dz % 4 == 0 ? light
						: dy == 0 && (Math.abs(dx) == 3 && Math.abs(dz) <= 3 || Math.abs(dz) == 3 && Math.abs(dx) <= 3) ? trim : wall;
					level.setBlock(at, state, Block.UPDATE_CLIENTS);
				}
		// the ladder up to the deck of the pad, beside the pad
		BlockPos shaft = new BlockPos(pad.getX() - 4, 0, pad.getZ());
		for (int y = floorY + 1; y <= pad.getY(); y++) {
			BlockPos at = new BlockPos(shaft.getX(), y, shaft.getZ());
			if (level.getBlockState(at.west()).isAir()) level.setBlock(at.west(), wall, Block.UPDATE_CLIENTS);
			level.setBlock(at, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.EAST), Block.UPDATE_CLIENTS);
		}
		State data = State.get(level.getServer());
		data.hangars.put(race.name(), floor.above().immutable());
		data.hangarDims.put(race.name(), level.dimension().location().toString());
		data.setDirty();
	}

	// ---- the transport ----

	/** A freed guardian calls its transport: it waits by the hero, the player's. */
	public static @Nullable StarUnit summon(ServerLevel level, StarUnit hero, ServerPlayer player) {
		UnitKind kind = kindOf(hero.kind.race);
		StarUnit transport = StarMine.type(kind).create(level);
		if (transport == null) return null;
		double a = level.random.nextDouble() * Math.PI * 2;
		int x = (int) Math.floor(hero.getX() + Math.cos(a) * 6), z = (int) Math.floor(hero.getZ() + Math.sin(a) * 6);
		double y = hero.getY(); // the hero's ground (on a ship's arena or a base's plaza)
		transport.moveTo(x + 0.5, y + 3, z + 0.5, hero.getYRot(), 0);
		Hive base = HiveSavedData.get(level.getServer()).baseOf(player.getUUID(), level.dimension().location().toString());
		if (base != null) transport.joinHive(base);
		transport.setOwner(player.getUUID());
		transport.addTag(TAG);
		transport.addTag(TAG + "_" + hero.kind.race.name().toLowerCase(java.util.Locale.ROOT));
		transport.setInvulnerable(true);
		transport.setCustomName(Component.translatable("entity.starmine.heroic_transport." + hero.kind.race.name().toLowerCase(java.util.Locale.ROOT)));
		level.addFreshEntity(transport);
		transport.command(StarUnit.Order.HOLD, null); // it waits where it came, until sent elsewhere
		level.sendParticles(ParticleTypes.END_ROD, x + 0.5, y + 4, z + 0.5, 40, 1, 1, 1, 0.1);
		level.playSound(null, transport.blockPosition(), SoundEvents.BEACON_POWER_SELECT, SoundSource.NEUTRAL, 1.5f, 1.2f);
		player.displayClientMessage(Component.translatable("message.starmine.transport_called"), false);
		return transport;
	}

	/** `/starmine heroic <race>`: the race's ship over the player (if it is not there yet) and a heroic transport by them. */
	public static void forPlayer(ServerPlayer player, UnitKind.Race race) {
		ServerLevel level = player.serverLevel();
		HiveSavedData data = HiveSavedData.get(level.getServer());
		if (!State.get(level.getServer()).inSky(race) && !data.structuresDone.contains("SHIP:" + race.name())) {
			data.structuresDone.add("SHIP:" + race.name());
			RaceBases.ship(level.getServer().overworld(), race, player.blockPosition());
		}
		StarUnit stand = StarMine.type(UnitKind.guardianOf(race)).create(level);
		if (stand == null) return;
		stand.moveTo(player.getX(), player.getY(), player.getZ());
		summon(level, stand, player);
		stand.discard();
	}

	/** `/starmine heroic board`: the player boards their nearest heroic transport. */
	public static boolean boardNearest(ServerPlayer player) {
		return player.serverLevel().getEntitiesOfClass(StarUnit.class, player.getBoundingBox().inflate(48), unit -> is(unit)
			&& player.getUUID().equals(unit.owner().orElse(null))).stream().min(java.util.Comparator.comparingDouble(player::distanceToSqr))
			.map(unit -> board(player, unit)).orElse(false);
	}

	/** The race a heroic transport flies to. */
	static @Nullable UnitKind.Race raceOf(StarUnit transport) {
		for (UnitKind.Race race : UnitKind.Race.values())
			if (transport.getTags().contains(TAG + "_" + race.name().toLowerCase(java.util.Locale.ROOT))) return race;
		return null;
	}

	/** Whether a player may ride this transport (a heroic one, theirs). */
	public static boolean carries(StarUnit transport, Entity passenger) {
		return passenger instanceof ServerPlayer player && is(transport) && player.getUUID().equals(transport.owner().orElse(null));
	}

	/** A click with the command panel: aboard, up to the ship's hangar (from the overworld) or down home (from the stratosphere). */
	public static boolean board(ServerPlayer player, StarUnit transport) {
		if (!is(transport) || FLIGHTS.containsKey(transport.getUUID())) return false;
		UnitKind.Race race = raceOf(transport);
		if (race == null) return false;
		ServerLevel level = (ServerLevel) transport.level();
		boolean up = !Stratosphere.is(level);
		if (up) {
			if (Stratosphere.level(level.getServer()) == null || !State.get(level.getServer()).inSky(race)) {
				player.displayClientMessage(Component.translatable("message.starmine.transport_no_ship"), true);
				return false;
			}
			State data = State.get(level.getServer());
			data.homes.put(transport.getUUID(), transport.blockPosition()); // where it waits when it comes back
			data.setDirty();
		}
		if (player.isPassenger()) player.stopRiding();
		if (!player.startRiding(transport, true)) return false;
		transport.command(StarUnit.Order.NONE, null);
		FLIGHTS.put(transport.getUUID(), new Flight(player.getUUID(), up ? Phase.RISE : Phase.DOWN));
		level.playSound(null, transport.blockPosition(), SoundEvents.ELYTRA_FLYING, SoundSource.NEUTRAL, 0.6f, 1.4f);
		player.displayClientMessage(Component.translatable(up ? "message.starmine.transport_up" : "message.starmine.transport_down"), true);
		return true;
	}

	private static void tick(MinecraftServer server) {
		if (!FLIGHTS.isEmpty())
			for (Map.Entry<UUID, Flight> entry : java.util.List.copyOf(FLIGHTS.entrySet())) {
				Flight flight = entry.getValue();
				if (++flight.ticks > 20 * 120 || !step(server, entry.getKey(), flight)) FLIGHTS.remove(entry.getKey());
			}
		if (server.getTickCount() % 40 == 0) waitAtHome(server);
	}

	/** One tick of a flight; false when it is over. */
	private static boolean step(MinecraftServer server, UUID id, Flight flight) {
		ServerLevel sky = Stratosphere.level(server);
		StarUnit transport = find(server, id);
		ServerPlayer player = server.getPlayerList().getPlayer(flight.player);
		if (sky == null) return false;
		if (transport == null) return ++flight.missing < 100; // just moved between worlds: in the world again in a moment
		flight.missing = 0;
		ServerLevel level = (ServerLevel) transport.level();
		UnitKind.Race race = raceOf(transport);
		State data = State.get(server);
		BlockPos hangar = race == null ? null : data.hangars.get(race.name());
		transport.noPhysics = true;
		transport.setDeltaMovement(Vec3.ZERO);
		switch (flight.phase) {
			case RISE -> { // straight up from where it waited, into the stratosphere
				transport.setPos(transport.getX(), transport.getY() + SPEED, transport.getZ());
				if (transport.tickCount % 4 == 0)
					level.sendParticles(ParticleTypes.CLOUD, transport.getX(), transport.getY() - 1, transport.getZ(), 4, 0.6, 0.2, 0.6, 0.02);
				if (transport.getY() < Stratosphere.CLIMB_Y - 4) return true;
				sky.getChunk(transport.getBlockX() >> 4, transport.getBlockZ() >> 4); // loaded, so it is there at once
				Entity moved = transport.changeDimension(new DimensionTransition(sky, new Vec3(transport.getX(), Stratosphere.ARRIVE_Y, transport.getZ()),
					Vec3.ZERO, transport.getYRot(), 0, DimensionTransition.DO_NOTHING));
				if (moved == null) return false;
				flight.phase = Phase.FLY;
				if (player != null) player.displayClientMessage(Component.translatable("message.starmine.stratosphere_up"), true);
				return true;
			}
			case FLY, IN -> {
				if (hangar == null) return false;
				Vec3 door = Vec3.atBottomCenterOf(hangar).add(HANGAR_HALF + 10, 1, 0);
				Vec3 goal = flight.phase == Phase.FLY ? door : Vec3.atBottomCenterOf(hangar).add(0, 1, 0);
				if (moveTowards(transport, goal)) {
					if (flight.phase == Phase.FLY) {
						flight.phase = Phase.IN;
						return true;
					}
					land(transport, player, Vec3.atBottomCenterOf(hangar).add(-3, 0, 0));
					return false;
				}
				return true;
			}
			case DOWN -> {
				if (Stratosphere.is(level)) { // out of the hangar and down into the overworld, over where it waited
					BlockPos home = data.homes.getOrDefault(id, transport.blockPosition());
					ServerLevel overworld = server.overworld();
					overworld.getChunk(home.getX() >> 4, home.getZ() >> 4);
					Entity moved = transport.changeDimension(new DimensionTransition(overworld,
						new Vec3(home.getX() + 0.5, Math.min(Stratosphere.CLIMB_Y - 10, overworld.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, home.getX(), home.getZ()) + 60),
							home.getZ() + 0.5), Vec3.ZERO, transport.getYRot(), 0, DimensionTransition.DO_NOTHING));
					return moved != null;
				}
				int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, transport.getBlockX(), transport.getBlockZ());
				if (transport.getY() > ground + 3) {
					transport.setPos(transport.getX(), transport.getY() - SPEED, transport.getZ());
					return true;
				}
				land(transport, player, transport.position().add(2, 0, 0));
				return false;
			}
		}
		return false;
	}

	/** Moves a step towards `goal`; true once there. */
	private static boolean moveTowards(StarUnit transport, Vec3 goal) {
		Vec3 way = goal.subtract(transport.position());
		if (way.lengthSqr() <= SPEED * SPEED) {
			transport.setPos(goal);
			return true;
		}
		Vec3 step = way.normalize().scale(SPEED);
		transport.setPos(transport.position().add(step));
		transport.setYRot((float) Math.toDegrees(Math.atan2(-step.x, step.z)));
		transport.yBodyRot = transport.getYRot();
		return false;
	}

	/** The flight is over: the player steps off at `at`; the transport stays, holding. */
	private static void land(StarUnit transport, @Nullable ServerPlayer player, Vec3 at) {
		transport.noPhysics = false;
		transport.command(StarUnit.Order.HOLD, null);
		if (player != null && player.getVehicle() == transport) {
			player.stopRiding();
			player.teleportTo(at.x, at.y, at.z);
			player.fallDistance = 0;
		}
		transport.level().playSound(null, transport.blockPosition(), SoundEvents.ARMOR_EQUIP_IRON.value(), SoundSource.NEUTRAL, 1f, 0.8f);
	}

	private static @Nullable StarUnit find(MinecraftServer server, UUID id) {
		for (ServerLevel level : server.getAllLevels())
			if (level.getEntity(id) instanceof StarUnit unit && unit.isAlive()) return unit;
		return null;
	}

	/** A player left the stratosphere: their transports waiting in its hangars go back to wait where they took off. */
	static void leftSky(ServerPlayer player) {
		ServerLevel sky = Stratosphere.level(player.getServer());
		if (sky == null) return;
		for (Entity entity : sky.getAllEntities())
			if (entity instanceof StarUnit unit && is(unit) && player.getUUID().equals(unit.owner().orElse(null)) && !FLIGHTS.containsKey(unit.getUUID()))
				home(player.getServer(), unit);
	}

	/** Now and then: a transport in the stratosphere whose player is not there goes home (it may have been unloaded when they left). */
	private static void waitAtHome(MinecraftServer server) {
		ServerLevel sky = Stratosphere.level(server);
		if (sky == null) return;
		for (Entity entity : sky.getAllEntities()) {
			if (!(entity instanceof StarUnit unit) || !is(unit) || FLIGHTS.containsKey(unit.getUUID())) continue;
			ServerPlayer owner = unit.owner().map(server.getPlayerList()::getPlayer).orElse(null);
			if (owner == null || !Stratosphere.is(owner.level())) home(server, unit);
		}
	}

	/** Back to the overworld where it took off, to wait there. */
	static void home(MinecraftServer server, StarUnit transport) {
		BlockPos home = State.get(server).homes.getOrDefault(transport.getUUID(), transport.blockPosition());
		ServerLevel overworld = server.overworld();
		overworld.getChunk(home.getX() >> 4, home.getZ() >> 4);
		int y = overworld.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, home.getX(), home.getZ()) + 3;
		transport.noPhysics = false;
		Entity moved = transport.changeDimension(new DimensionTransition(overworld, new Vec3(home.getX() + 0.5, y, home.getZ() + 0.5), Vec3.ZERO,
			transport.getYRot(), 0, DimensionTransition.DO_NOTHING));
		if (moved instanceof StarUnit unit) unit.command(StarUnit.Order.HOLD, null);
	}

	/** Where a player's heroic transports wait in the overworld (for the minimap); loaded ones. */
	public static java.util.List<BlockPos> waiting(ServerPlayer player) {
		java.util.List<BlockPos> out = new java.util.ArrayList<>();
		for (Entity entity : player.server.overworld().getAllEntities())
			if (entity instanceof StarUnit unit && is(unit) && player.getUUID().equals(unit.owner().orElse(null))) out.add(unit.blockPosition());
		State data = State.get(player.server);
		if (out.isEmpty()) out.addAll(data.homes.values()); // unloaded: where they took off last
		return out;
	}

	/** What the world remembers: the ships' hangars by race, and where each transport took off. */
	public static final class State extends SavedData {
		public final Map<String, BlockPos> hangars = new LinkedHashMap<>();
		public final Map<UUID, BlockPos> homes = new LinkedHashMap<>();
		/** The dimension of each hangar (a ship built by command in the overworld has one there: no transport flies to it). */
		public final Map<String, String> hangarDims = new LinkedHashMap<>();

		/** Whether the race's ship hangs in the stratosphere with its hangar. */
		public boolean inSky(UnitKind.Race race) {
			return hangars.containsKey(race.name()) && Stratosphere.KEY.location().toString().equals(hangarDims.getOrDefault(race.name(), Stratosphere.KEY.location().toString()));
		}
		private static final SavedData.Factory<State> FACTORY = new SavedData.Factory<>(State::new, State::load, null);

		public static State get(MinecraftServer server) {
			return server.overworld().getDataStorage().computeIfAbsent(FACTORY, "starmine_transports");
		}

		@Override
		public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
			CompoundTag h = new CompoundTag();
			hangars.forEach((race, pos) -> h.putLong(race, pos.asLong()));
			tag.put("hangars", h);
			CompoundTag m = new CompoundTag();
			homes.forEach((id, pos) -> m.putLong(id.toString(), pos.asLong()));
			tag.put("homes", m);
			CompoundTag d = new CompoundTag();
			hangarDims.forEach(d::putString);
			tag.put("hangarDims", d);
			return tag;
		}

		static State load(CompoundTag tag, HolderLookup.Provider registries) {
			State state = new State();
			CompoundTag h = tag.getCompound("hangars");
			for (String race : h.getAllKeys()) state.hangars.put(race, BlockPos.of(h.getLong(race)));
			CompoundTag m = tag.getCompound("homes");
			for (String id : m.getAllKeys()) state.homes.put(UUID.fromString(id), BlockPos.of(m.getLong(id)));
			CompoundTag d = tag.getCompound("hangarDims");
			for (String race : d.getAllKeys()) state.hangarDims.put(race, d.getString(race));
			return state;
		}
	}
}
