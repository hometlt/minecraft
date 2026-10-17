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
 * Amun's invasion (owner 17.10): a world that plays a month (game rule `starmineAmunDays`, 30 Minecraft days) and has
 * not ended Amun is invaded — in the overworld and the Nether:
 * - void shards fall near the players ({@link #SHARDS_PER_WORLD} at most in a world, one near each player at a time):
 *   round a shard the light dies (darkness) and Amun's troops grow fierce ({@link #shardTick}); broken, a shard leaves
 *   void shards to make a void gate of ({@link VoidGate});
 * - void rifts open round each shard and send out Amun's troops of all three races ({@link VoidRift}) — Amun's, who
 *   fight only the players and their units, while the races leave them be ({@link dev.starmine.faction.Faction});
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
	private static final DustParticleOptions BLACK = new DustParticleOptions(new Vector3f(0.05f, 0.02f, 0.05f), 1.1f);
	private static final DustParticleOptions RED = new DustParticleOptions(new Vector3f(0.85f, 0.05f, 0.08f), 0.9f);

	/** In the game tests the invasion starts only when its own test asks ({@link #clockTested}). */
	static final boolean TESTING = Boolean.getBoolean("fabric-api.gametest");
	static boolean clockTested;

	private AmunInvasion() {
	}

	public static void register() {
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(AmunInvasion::tick);
	}

	/** Whether Amun invades the worlds now. */
	public static boolean invading(MinecraftServer server) {
		return VoidWorld.State.get(server).invading;
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
		long days = overworld.getDayTime() / 24000L;
		if (!state.invading) {
			if (TESTING && !clockTested) return; // the test world grows old over many runs: its clock starts only for its own test
			if (days < overworld.getGameRules().getInt(StarMine.AMUN_DAYS)) return;
			state.invading = true;
			state.setDirty();
			for (ServerPlayer player : server.getPlayerList().getPlayers())
				player.displayClientMessage(Component.translatable("message.starmine.amun_invades"), false);
		}
		int t = server.getTickCount();
		for (ServerLevel level : new ServerLevel[] {overworld, server.getLevel(Level.NETHER)}) {
			if (level == null || level.players().isEmpty()) continue;
			if (t % SHARD_EVERY < 20) shards(level);
			if (t % RAID_EVERY < 20) raid(level);
			if (t % FERRY_EVERY < 20) ferry(level);
		}
	}

	// ---- shards and rifts ----

	/** A shard falls near each player who has none near (as long as the world holds fewer than its share). */
	static void shards(ServerLevel level) {
		List<VoidShard> all = level.getEntitiesOfClass(VoidShard.class, new AABB(level.getWorldBorder().getMinX(), level.getMinBuildHeight(),
			level.getWorldBorder().getMinZ(), level.getWorldBorder().getMaxX(), level.getMaxBuildHeight(), level.getWorldBorder().getMaxZ()), VoidShard::isAlive);
		int count = all.size();
		for (ServerPlayer player : level.players()) {
			if (player.isSpectator() || count >= SHARDS_PER_WORLD) continue;
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

	/** Each second of a shard outside the Void: darkness on the players round it, fury on Amun's troops. */
	public static void shardTick(VoidShard shard, ServerLevel level) {
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
	static LivingEntity foe(ServerLevel level, StarUnit unit, double range) {
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
			level.getWorldBorder().getMinZ(), level.getWorldBorder().getMaxX(), level.getMaxBuildHeight(), level.getWorldBorder().getMaxZ()), VoidRift::isAlive)) {
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
			level.getWorldBorder().getMinZ(), level.getWorldBorder().getMaxX(), level.getMaxBuildHeight(), level.getWorldBorder().getMaxZ()), VoidRift::isAlive)) {
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
