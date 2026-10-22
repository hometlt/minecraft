package dev.starmine.world;

import dev.starmine.StarMine;
import dev.starmine.ability.Status;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveSavedData;
import dev.starmine.hive.HiveStructure;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Amun's invasion (owner 17.10, 19.10): Amun wakes when a player takes the first part of a key from a hero — a world
 * where nobody fights the commanders is left alone. He invades the world whose heroes lost their parts (the overworld,
 * the Nether, the End each by its own keys), the harder the more parts were taken there ({@link #keys}): with one, few
 * small shards, seldom; with three, full waves. Before he comes the sky of that world reddens ({@link #WARNING} ticks,
 * {@link AmunSky}) and all hear that Amun has woken. Until he is dead:
 * - void shards fall near the players by night ({@link #SHARDS_PER_WORLD} at most in a world, one near each player at a
 *   time): round a shard the light dies (darkness) and Amun's troops grow fierce, and the shard itself sends out his
 *   troops now and then until it is broken ({@link #shardTick}); broken, a shard leaves void shards to make a void gate
 *   of ({@link VoidGate});
 * - void rifts open round each shard and send out Amun's troops of all three races ({@link VoidRift}) — Amun's, who
 *   fight only the players and their units, while the races leave them be ({@link dev.starmine.faction.Faction});
 * - the invasion grows with the nights it goes on ({@link #power}): at first few troops and small ones (zerglings,
 *   marines, zealots), seldom; later more, stronger and more often ({@link #troops}, {@link #every});
 * - shards and rifts work only within {@link #PLAYER_REACH} of a player (16 chunks), and show on the command view's
 *   minimap ({@link #marks});
 *   every few minutes they march on the players' bases ({@link #raid});
 * - Amun's workers go with them and make the way to the player wherever they are: they tunnel, bridge and build steps
 *   ({@link #sap}); where there is no way by land, transports come and carry the troops over ({@link #ferry});
 * - Amun's troops wear his curse (black and red motes) and leave nothing — no experience, no loot — like everything
 *   that dies in the Void.
 */
public final class AmunInvasion {
	public static final String SAPPER = "starmine_amun_sapper", FERRY = "starmine_amun_ferry";
	public static final int SHARDS_PER_WORLD = 6, SHARD_EVERY = 600, RAID_EVERY = 6000, FERRY_EVERY = 200;
	/** Round a shard: darkness to players, fury to Amun's troops. */
	public static final double SHARD_REACH = 24;
	/** Shards and rifts work only this near a player (16 chunks); their marks show this far. */
	public static final int PLAYER_REACH = 256;
	/** Amun's troops round a shard or a rift at most. */
	public static final int CAP = 8;
	/** His troops by strength: the invasion's power from which each comes in. */
	private static final int[] TIER_POWER = {0, 2, 5, 9};
	private static final List<List<UnitKind>> TIERS = List.of(
		List.of(UnitKind.ZERGLING, UnitKind.MARINE, UnitKind.ZEALOT),
		List.of(UnitKind.ROACH, UnitKind.BANELING, UnitKind.MARAUDER, UnitKind.STALKER, UnitKind.ADEPT),
		List.of(UnitKind.HYDRALISK, UnitKind.MUTALISK, UnitKind.SIEGE_TANK, UnitKind.IMMORTAL),
		List.of(UnitKind.ULTRALISK, UnitKind.THOR, UnitKind.ARCHON, UnitKind.VOID_RAY));
	private static final DustParticleOptions BLACK = new DustParticleOptions(new Vector3f(0.05f, 0.02f, 0.05f), 1.1f);
	private static final DustParticleOptions RED = new DustParticleOptions(new Vector3f(0.85f, 0.05f, 0.08f), 0.9f);

	/** Ticks the sky of a world is red before Amun's troops come there. */
	public static final int WARNING = 200;

	private AmunInvasion() {
	}

	public static void register() {
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(AmunInvasion::tick);
	}

	/** Whether Amun invades some world now. */
	public static boolean invading(MinecraftServer server) {
		return VoidWorld.State.get(server).invading;
	}

	/** Whether Amun invades this level now (his troops are there, its warning over). */
	public static boolean invading(ServerLevel level) {
		VoidWorld.State state = VoidWorld.State.get(level.getServer());
		Artifact.World world = Artifact.World.of(level);
		Long since = world == null ? null : state.awake.get(world.name());
		return !state.amunDead && since != null && level.getGameTime() >= since;
	}

	/** The parts of a world's key its heroes have lost: the races of its freed heroes (0-3). */
	public static int keys(VoidWorld.State state, Artifact.World world) {
		java.util.Set<UnitKind.Race> races = java.util.EnumSet.noneOf(UnitKind.Race.class);
		for (String freed : state.freed) {
			UnitKind hero;
			try {
				hero = switch (UnitKind.Race.valueOf(freed)) { // a ship's hero, by its race
					case ZERG -> UnitKind.KERRIGAN;
					case TERRAN -> UnitKind.RAYNOR;
					case PROTOSS -> UnitKind.ARTANIS;
				};
			} catch (IllegalArgumentException notRace) {
				try {
					hero = UnitKind.valueOf(freed);
				} catch (IllegalArgumentException unknown) {
					continue;
				}
			}
			if (Artifact.World.ofHero(hero) == world) races.add(hero.race);
		}
		return races.size();
	}

	/** A hero's part was taken: Amun looks at the worlds with the next second. */
	static void keyTaken(MinecraftServer server) {
		VoidWorld.State.get(server).setDirty();
	}

	static void tick(MinecraftServer server) {
		if (server.getTickCount() % 20 != 7) return;
		VoidWorld.State state = VoidWorld.State.get(server);
		if (state.amunDead) {
			if (state.invading) {
				state.invading = false;
				state.setDirty();
			}
			return;
		}
		ServerLevel overworld = server.overworld();
		long now = overworld.getGameTime();
		int t = server.getTickCount();
		for (Artifact.World world : Artifact.World.values()) {
			ServerLevel level = server.getLevel(world.dimension());
			if (level == null || keys(state, world) == 0) continue;
			Long since = state.awake.get(world.name());
			if (since == null) { // owner 19.10: the first part of this world's key is taken — Amun wakes; its sky reddens first
				state.awake.put(world.name(), now + WARNING);
				state.invading = true;
				state.setDirty();
				for (ServerPlayer player : level.players()) AmunSky.redden(player, WARNING + 100);
				continue;
			}
			if (now < since) continue;
			if (state.told.add(world.name())) {
				state.setDirty();
				for (ServerPlayer player : server.getPlayerList().getPlayers())
					player.displayClientMessage(Component.translatable("message.starmine.amun_awakened",
						Component.translatable("world.starmine." + world.name().toLowerCase(java.util.Locale.ROOT))), false);
			}
			if (level.players().isEmpty()) continue;
			if (t % SHARD_EVERY < 20 && night(overworld.getDayTime()) // owner 17.10: they fall by night; with one part, seldom
				&& (keys(state, world) > 1 || level.random.nextInt(3) == 0)) shards(level);
			if (t % RAID_EVERY < 20) raid(level);
			if (t % FERRY_EVERY < 20) ferry(level);
		}
	}

	// ---- the invasion's strength ----

	/**
	 * How strong the invasion of a level is (owner 19.10): by the parts of its world's key taken — one: small troops,
	 * seldom; two: stronger; three: all of them — and a little more each two days it goes on there (up to three).
	 */
	public static int power(ServerLevel level) {
		VoidWorld.State state = VoidWorld.State.get(level.getServer());
		Artifact.World world = Artifact.World.of(level);
		if (world == null) return 0;
		int keys = keys(state, world);
		if (keys == 0) return 0;
		Long since = state.awake.get(world.name());
		int days = since == null ? 0 : (int) Math.max(0, (level.getGameTime() - since) / 48000L);
		return (keys - 1) * 4 + Math.min(3, days);
	}

	/** How many of Amun's shards a world holds at most: two for each part of its key taken. */
	public static int shardsFor(ServerLevel level) {
		Artifact.World world = Artifact.World.of(level);
		return world == null ? 0 : Math.min(SHARDS_PER_WORLD, 2 * keys(VoidWorld.State.get(level.getServer()), world));
	}

	/** Whether it is night at this time of the overworld's day (the Nether keeps the overworld's clock). */
	public static boolean night(long dayTime) {
		long t = Math.floorMod(dayTime, 24000L);
		return t >= 13000 && t < 23000;
	}

	/** Ticks between the waves of a shard or a rift at this power: seldom at first, more often later. */
	public static int every(int power) {
		return Math.max(200, 900 - power * 60);
	}

	/** A wave of Amun's troops at this power: one or two small ones at first; more of them, and stronger, later. */
	public static List<UnitKind> troops(int power, net.minecraft.util.RandomSource random) {
		List<UnitKind> pool = new java.util.ArrayList<>();
		for (int i = 0; i < TIERS.size(); i++) if (power >= TIER_POWER[i]) pool.addAll(TIERS.get(i));
		int count = Math.min(5, 1 + random.nextInt(2 + Math.min(power, 12) / 3));
		List<UnitKind> wave = new java.util.ArrayList<>();
		for (int i = 0; i < count; i++) wave.add(pool.get(random.nextInt(pool.size())));
		return wave;
	}

	/** Whether a player (not a spectator) is within {@link #PLAYER_REACH} of `at` (flat). */
	public static boolean nearPlayer(ServerLevel level, Vec3 at) {
		for (ServerPlayer player : level.players())
			if (!player.isSpectator() && Mth.square(player.getX() - at.x) + Mth.square(player.getZ() - at.z) < (double) PLAYER_REACH * PLAYER_REACH) return true;
		return false;
	}

	/**
	 * A wave of Amun's troops out of `source` (a shard or a rift of his invasion), unless {@link #CAP} of them are round it
	 * already; a worker of his goes with every other wave to make the way ({@link #sap}). How many came out.
	 */
	static int wave(ServerLevel level, net.minecraft.world.entity.Mob source, boolean worker) {
		AABB area = source.getBoundingBox().inflate(VoidRift.AREA, 8, VoidRift.AREA);
		int near = level.getEntitiesOfClass(StarUnit.class, area, unit -> unit.isAlive() && unit.getTags().contains(VoidForces.TAG)).size();
		if (near >= CAP) return 0;
		List<UnitKind> wave = troops(power(level), level.random);
		BlockPos ground = BlockPos.containing(source.getX(), groundOf(level, source.blockPosition()), source.getZ());
		LivingEntity foe = foe(level, source, 64);
		int made = 0;
		for (int i = 0; i < wave.size() && near + made < CAP; i++) {
			if (source instanceof VoidRift rift && !rift.admits(wave.get(i), wave.get(i).flying)) continue;
			double a = level.random.nextDouble() * Math.PI * 2;
			Vec3 at = dev.starmine.entity.Spots.near(level, wave.get(i), Vec3.atBottomCenterOf(ground).add(Math.cos(a) * 3, 0, Math.sin(a) * 3));
			StarUnit unit = dev.starmine.hive.HiveLogic.spawnNest(level, null, wave.get(i), at, ground);
			if (unit == null) continue;
			unit.addTag(VoidForces.TAG);
			if (foe != null) unit.setTarget(foe);
			made++;
		}
		if (worker && near + made < CAP) {
			UnitKind kind = List.of(UnitKind.SCV, UnitKind.DRONE, UnitKind.PROBE).get(level.random.nextInt(3));
			StarUnit sapper = dev.starmine.hive.HiveLogic.spawnNest(level, null, kind, dev.starmine.entity.Spots.near(level, kind, Vec3.atBottomCenterOf(ground)), ground);
			if (sapper != null) {
				sapper.addTag(VoidForces.TAG);
				sapper.addTag(SAPPER);
			}
		}
		level.sendParticles(net.minecraft.core.particles.ParticleTypes.REVERSE_PORTAL, source.getX(), source.getY() + 1, source.getZ(), 50, 1.2, 1.5, 1.2, 0.2);
		return made;
	}

	/** The shards and rifts of the invasion a player sees on the minimap: within {@link #PLAYER_REACH} in their world (`true`: a shard). */
	public static List<java.util.Map.Entry<Boolean, BlockPos>> marks(ServerPlayer player) {
		List<java.util.Map.Entry<Boolean, BlockPos>> marks = new java.util.ArrayList<>();
		if (!(player.level() instanceof ServerLevel level) || VoidWorld.is(level) || !invading(level)) return marks;
		AABB area = player.getBoundingBox().inflate(PLAYER_REACH, 384, PLAYER_REACH);
		for (VoidShard shard : level.getEntitiesOfClass(VoidShard.class, area, VoidShard::isAlive)) marks.add(java.util.Map.entry(true, shard.blockPosition()));
		for (VoidRift rift : level.getEntitiesOfClass(VoidRift.class, area, r -> r.isAlive() && r.invasion())) marks.add(java.util.Map.entry(false, rift.blockPosition()));
		return marks;
	}

	// ---- shards and rifts ----

	/** A shard falls near each player who has none near (as long as the world holds fewer than its share). */
	static void shards(ServerLevel level) {
		List<VoidShard> all = level.getEntitiesOfClass(VoidShard.class, new AABB(level.getWorldBorder().getMinX(), level.getMinBuildHeight(),
			level.getWorldBorder().getMinZ(), level.getWorldBorder().getMaxX(), level.getMaxBuildHeight(), level.getWorldBorder().getMaxZ()), VoidShard::isAlive);
		int count = all.size(), most = shardsFor(level);
		for (ServerPlayer player : level.players()) {
			if (player.isSpectator() || count >= most) continue;
			if (all.stream().anyMatch(shard -> shard.distanceTo(player) < 160)) continue;
			if (fall(level, player.blockPosition()) != null) count++;
		}
	}

	/** A shard falls 64-112 blocks from `near`, floating over the ground, with two rifts round it; the shard, or null. */
	public static VoidShard fall(ServerLevel level, BlockPos near) {
		return fall(level, near, 64 + level.random.nextDouble() * 48);
	}

	/** {@link #fall(ServerLevel, BlockPos)} at a given distance from `near`. */
	public static VoidShard fall(ServerLevel level, BlockPos near, double distance) {
		double angle = level.random.nextDouble() * Math.PI * 2;
		BlockPos column = near.offset((int) (Math.cos(angle) * distance), 0, (int) (Math.sin(angle) * distance));
		int ground = groundOf(level, column);
		if (ground <= level.getMinBuildHeight() + 4) return null; // the End's void: nowhere to stand
		VoidShard shard = VoidForces.SHARD.create(level);
		if (shard == null) return null;
		shard.moveTo(column.getX() + 0.5, ground + 5, column.getZ() + 0.5, 0, 0);
		level.addFreshEntity(shard);
		for (int i = 0; i < 2; i++) {
			double a = angle + Math.PI * (0.5 + i);
			BlockPos at = column.offset((int) (Math.cos(a) * 10), 0, (int) (Math.sin(a) * 10));
			VoidRift rift = VoidForces.RIFT.create(level);
			if (rift == null) continue;
			rift.moveTo(at.getX() + 0.5, groundOf(level, at) + 1, at.getZ() + 0.5, 0, 0);
			level.addFreshEntity(rift);
		}
		level.sendParticles(net.minecraft.core.particles.ParticleTypes.EXPLOSION_EMITTER, column.getX() + 0.5, ground + 5, column.getZ() + 0.5, 2, 1, 1, 1, 0);
		for (ServerPlayer player : level.players())
			if (player.blockPosition().closerThan(column, 200)) player.displayClientMessage(Component.translatable("message.starmine.amun_shard_fell"), true);
		return shard;
	}

	private static int groundOf(ServerLevel level, BlockPos column) {
		level.getChunk(column.getX() >> 4, column.getZ() >> 4);
		return level.dimensionType().hasCeiling() ? HiveStructure.caveFloor(level, column.getX(), column.getZ())
			: level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ());
	}

	/**
	 * Each second of a shard outside the Void, near a player: darkness on the players round it, fury on Amun's troops;
	 * now and then a wave of his troops out of it ({@link #every}).
	 */
	public static void shardTick(VoidShard shard, ServerLevel level) {
		if (!nearPlayer(level, shard.position())) return; // owner 17.10: only near the players
		shard.waveIn -= 20;
		if (shard.waveIn <= 0) {
			shard.waveIn = every(power(level));
			wave(level, shard, false);
		}
		AABB area = shard.getBoundingBox().inflate(SHARD_REACH);
		for (Player player : level.getEntitiesOfClass(Player.class, area, p -> p.distanceTo(shard) <= SHARD_REACH && !p.isSpectator()))
			player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 60, 0, true, false));
		for (StarUnit unit : level.getEntitiesOfClass(StarUnit.class, area, u -> u.isAlive() && dev.starmine.faction.Faction.amun(u)))
			Status.apply(unit, Status.STIM, 40);
	}

	// ---- Amun's troops ----

	/** Every few ticks of one of Amun's units: his curse on it; a worker makes the way. */
	public static void unitTick(StarUnit unit, ServerLevel level) {
		if (unit.tickCount % 5 == 0) {
			double w = unit.getBbWidth() * 0.5, h = unit.getBbHeight();
			level.sendParticles(BLACK, unit.getX(), unit.getY() + h * 0.5, unit.getZ(), 2, w, h * 0.3, w, 0.01);
			level.sendParticles(RED, unit.getX(), unit.getY() + h * 0.5, unit.getZ(), 1, w, h * 0.3, w, 0.01);
		}
		if (unit.getTags().contains(SAPPER)) {
			if (unit.tickCount % 10 == 0) sap(unit, level);
			Vec3 step = unit.abilities.digTo;
			if (step != null) { // it walks onto its step itself, each tick (its work goals would lead it elsewhere)
				unit.getNavigation().stop();
				unit.getMoveControl().setWantedPosition(step.x, step.y, step.z, 1.0);
				if (step.y > unit.getY() + 0.5 && unit.onGround() && unit.horizontalCollision) unit.getJumpControl().jump();
			}
		}
		if (unit.getTags().contains(FERRY) && unit.tickCount % 10 == 0) unload(unit, level);
	}

	/** The foe Amun's troops go for: the nearest player or player's unit within `range`. */
	static LivingEntity foe(ServerLevel level, net.minecraft.world.entity.Entity unit, double range) {
		return level.getEntitiesOfClass(LivingEntity.class, unit.getBoundingBox().inflate(range, 32, range), Amun::foe).stream()
			.min(java.util.Comparator.comparingDouble(unit::distanceToSqr)).orElse(null);
	}

	/**
	 * A worker of Amun's makes the way to the player when the way by land does not reach: a step at a time, it digs the
	 * two blocks ahead (and one more over a step up), lays a block under the step over a drop or water, and a step to
	 * climb when the player is higher.
	 */
	static void sap(StarUnit worker, ServerLevel level) {
		LivingEntity foe = foe(level, worker, 96);
		if (foe == null) return;
		Vec3 step = worker.abilities.digTo;
		if (step != null) { // walking onto the step it made: the next one when it is there
			double dx = worker.getX() - step.x, dz = worker.getZ() - step.z;
			if (dx * dx + dz * dz > 0.3 && ++worker.abilities.digTicks < 6) return;
			worker.abilities.digTo = null;
		}
		Path path = worker.getNavigation().createPath(foe, 1);
		if (path != null && path.canReach()) {
			worker.command(StarUnit.Order.MOVE, foe.position());
			return;
		}
		Vec3 to = foe.position().subtract(worker.position());
		Direction dir = Math.abs(to.x) > Math.abs(to.z) ? (to.x > 0 ? Direction.EAST : Direction.WEST) : (to.z > 0 ? Direction.SOUTH : Direction.NORTH);
		BlockPos feet = worker.blockPosition(), next = feet.relative(dir);
		boolean up = foe.getY() > worker.getY() + 1.5, down = foe.getY() < worker.getY() - 1.5;
		if (up) next = next.above();
		else if (down && level.getBlockState(next.below()).isAir()) next = next.below();
		int wide = Math.max(1, (int) Math.ceil(worker.getBbWidth())), high = Math.max(3, (int) Math.ceil(worker.getBbHeight()) + 1);
		Direction side = dir.getClockWise();
		for (int w = 0; w < wide; w++) { // a way as wide and tall as the worker (and so as its army's small units)
			BlockPos cell = next.relative(side, w);
			for (int dy = 0; dy < high; dy++) dig(level, cell.above(dy));
			if (up) dig(level, feet.relative(side, w).above(high - 1)); // room over its head to step up
			BlockState floor = level.getBlockState(cell.below());
			if (floor.isAir() || !floor.getFluidState().isEmpty() || floor.canBeReplaced())
				level.setBlock(cell.below(), (level.dimension() == Level.NETHER ? Blocks.BLACKSTONE : Blocks.COBBLESTONE).defaultBlockState(), Block.UPDATE_ALL);
		}
		Vec3 onto = Vec3.atBottomCenterOf(next).add(Vec3.atLowerCornerOf(side.getNormal()).scale((wide - 1) / 2.0));
		worker.abilities.digTo = onto;
		worker.abilities.digTicks = 0;
		worker.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
	}

	private static void dig(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (state.isAir() || state.getDestroySpeed(level, pos) < 0 || level.getBlockEntity(pos) != null || BaseGround.protectedTop(state)) return;
		level.destroyBlock(pos, false);
	}

	/**
	 * Every few minutes Amun's troops round each rift march on the nearest base of a player in the world (its main
	 * building), attacking what they meet on the way; a worker goes with them to make the way.
	 */
	static void raid(ServerLevel level) {
		HiveSavedData data = HiveSavedData.get(level.getServer());
		String dimension = level.dimension().location().toString();
		for (VoidRift rift : level.getEntitiesOfClass(VoidRift.class, new AABB(level.getWorldBorder().getMinX(), level.getMinBuildHeight(),
			level.getWorldBorder().getMinZ(), level.getWorldBorder().getMaxX(), level.getMaxBuildHeight(), level.getWorldBorder().getMaxZ()), rift -> rift.isAlive() && rift.invasion())) {
			Hive base = data.all().stream().filter(h -> h.owner != null && h.dimension.equals(dimension))
				.min(java.util.Comparator.comparingDouble(h -> h.hatchery.distSqr(rift.blockPosition()))).orElse(null);
			if (base == null || base.hatchery.distSqr(rift.blockPosition()) > 600 * 600) continue;
			Vec3 goal = Vec3.atBottomCenterOf(base.hatchery);
			for (StarUnit unit : level.getEntitiesOfClass(StarUnit.class, rift.getBoundingBox().inflate(VoidRift.AREA, 12, VoidRift.AREA),
				u -> u.isAlive() && dev.starmine.faction.Faction.amun(u) && !u.kind.structure()))
				unit.command(unit.getTags().contains(SAPPER) ? StarUnit.Order.MOVE : StarUnit.Order.ATTACK_MOVE, goal);
		}
	}

	/**
	 * Where Amun's troops near a rift cannot reach their foe by land (an island, a tower, over a sea), a transport of their
	 * race comes out of the rift, takes up to four of them and flies them to the foe ({@link #unload}).
	 */
	static void ferry(ServerLevel level) {
		for (VoidRift rift : level.getEntitiesOfClass(VoidRift.class, new AABB(level.getWorldBorder().getMinX(), level.getMinBuildHeight(),
			level.getWorldBorder().getMinZ(), level.getWorldBorder().getMaxX(), level.getMaxBuildHeight(), level.getWorldBorder().getMaxZ()), rift -> rift.isAlive() && rift.invasion())) {
			List<StarUnit> stuck = level.getEntitiesOfClass(StarUnit.class, rift.getBoundingBox().inflate(VoidRift.AREA, 12, VoidRift.AREA),
				u -> u.isAlive() && dev.starmine.faction.Faction.amun(u) && !u.flying() && !u.kind.structure() && !u.isPassenger() && !u.kind.worker());
			if (stuck.isEmpty()) continue;
			LivingEntity foe = foe(level, stuck.get(0), 96);
			if (foe == null || foe.distanceTo(stuck.get(0)) < 12) continue;
			Path path = stuck.get(0).getNavigation().createPath(foe, 1);
			if (path != null && path.canReach()) continue;
			UnitKind kind = switch (stuck.get(0).kind.race) {
				case ZERG -> UnitKind.OVERLORD;
				case TERRAN -> UnitKind.MEDIVAC;
				case PROTOSS -> UnitKind.WARP_PRISM;
			};
			if (!rift.admits(kind, true)) continue;
			StarUnit transport = StarMine.type(kind).create(level);
			if (transport == null) continue;
			transport.moveTo(rift.getX(), rift.getY() + 4, rift.getZ(), 0, 0);
			transport.addTag(VoidForces.TAG);
			transport.addTag(FERRY);
			level.addFreshEntity(transport);
			for (int i = 0; i < Math.min(4, stuck.size()); i++) stuck.get(i).startRiding(transport, true);
			transport.command(StarUnit.Order.MOVE, foe.position());
		}
	}

	/** A transport of Amun's by its foe puts its troops down there. */
	static void unload(StarUnit transport, ServerLevel level) {
		if (transport.getPassengers().isEmpty()) return;
		LivingEntity foe = foe(level, transport, 96);
		if (foe == null) return;
		double dx = foe.getX() - transport.getX(), dz = foe.getZ() - transport.getZ();
		if (dx * dx + dz * dz > 36) {
			if (transport.tickCount % 40 == 0) transport.command(StarUnit.Order.MOVE, foe.position());
			return;
		}
		for (net.minecraft.world.entity.Entity rider : List.copyOf(transport.getPassengers())) {
			rider.stopRiding();
			rider.teleportTo(foe.getX() + level.random.nextGaussian(), foe.getY(), foe.getZ() + level.random.nextGaussian());
			if (rider instanceof StarUnit unit) unit.setTarget(foe);
		}
	}
}
