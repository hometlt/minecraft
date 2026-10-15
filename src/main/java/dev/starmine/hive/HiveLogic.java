package dev.starmine.hive;

import dev.starmine.StarMine;
import dev.starmine.creep.CreepGrowth;
import dev.starmine.creep.CreepSources;
import dev.starmine.entity.UnitKind;
import dev.starmine.entity.StarUnit;
import dev.starmine.colony.BaseLogic;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * What only zerg hives do, ticked by a hatchery while it is loaded: founding a hive (a player's
 * hatchery joins its player's base, {@link dev.starmine.colony.Founding}; a wild one founds a wild hive,
 * at most {@link HiveSavedData#MAX_HIVES}), its thick-creep foundation ({@link HiveTerrain}) and creep,
 * larvae (one every {@link #LARVA_TICKS} up to {@link #MAX_LARVAE}), cocoons ({@link #morph},
 * {@link #hatch}), drones becoming buildings ({@link #mutate}), alarms and raids on villages and
 * players. A wild hive's decisions — buildings, gas, research, what its larvae become, waves — are the
 * common AI's ({@link dev.starmine.ai.AiCommander}); a player's hive decides nothing by itself.
 */
public final class HiveLogic {
	public static final int LARVA_TICKS = 60 * 20;
	public static final int MAX_LARVAE = 3;
	public static final int DECISION_TICKS = 100;
	public static final int MAX_WORKERS = 8;

	private HiveLogic() {
	}

	/** Makes a newly placed hatchery a hive, or refuses it when the world already has its maximum. */
	public static Hive found(StarUnit hatchery, ServerLevel level) {
		HiveSavedData data = HiveSavedData.get(level.getServer());
		Hive hive = hatchery.hiveId() != null ? data.get(hatchery.hiveId()) : data.get(hatchery.getUUID());
		if (hive != null) return hive;
		if (hatchery.owner().isPresent()) { // a player's hatchery: their base (dev.starmine.colony.Founding)
			if (!HiveTerrain.prepare(level, hatchery.blockPosition(), (int) Math.ceil(hatchery.kind.width / 2), (int) Math.ceil(hatchery.kind.height), true)) {
				for (var player : level.getPlayers(p -> p.distanceToSqr(hatchery) < 64 * 64))
					player.sendSystemMessage(Component.translatable("message.starmine.site_blocked"));
				hatchery.discard();
				return null;
			}
			return dev.starmine.colony.Founding.found(hatchery, level);
		}
		if (data.full()) {
			for (var player : level.getPlayers(p -> p.distanceToSqr(hatchery) < 64 * 64))
				player.sendSystemMessage(Component.translatable("message.starmine.hive_limit", HiveSavedData.MAX_HIVES));
			hatchery.discard();
			return null;
		}
		// owner: the site is levelled; trees, fences or harder blocks forbid it
		if (!HiveTerrain.prepare(level, hatchery.blockPosition(), (int) Math.ceil(hatchery.kind.width / 2), (int) Math.ceil(hatchery.kind.height), true)) {
			for (var player : level.getPlayers(p -> p.distanceToSqr(hatchery) < 64 * 64))
				player.sendSystemMessage(Component.translatable("message.starmine.site_blocked"));
			hatchery.discard();
			return null;
		}
		hive = new Hive(hatchery.getUUID(), level.dimension().location().toString(), hatchery.blockPosition());
		data.add(hive);
		hatchery.joinHive(hive);
		hive.join(UnitKind.HATCHERY);
		// a wild hatchery starts as a wild command center or nexus does: 50 minerals and 4 drones (it
		// started with none, and its AI grew them one a minute from larvae: its first wave came last)
		hive.minerals = dev.starmine.colony.Founding.START_MINERALS;
		dev.starmine.colony.Founding.startWorkers(level, hive, hatchery);
		return hive;
	}

	public static void tick(StarUnit hatchery, ServerLevel level) {
		Hive hive = found(hatchery, level);
		if (hive == null) return;
		HiveSavedData data = HiveSavedData.get(level.getServer());
		if (hive.id.equals(hatchery.getUUID())) hive.hatchery = hatchery.blockPosition(); // the base's first hatchery anchors it
		if (!hive.foundation) {
			HiveTerrain.lay(level, hive.hatchery, HiveTerrain.HATCHERY_FOUNDATION);
			hive.foundation = true;
		}
		CreepGrowth.grow(level, hive.hatchery, CreepSources.HATCHERY_RADIUS, HiveTerrain.HATCHERY_FOUNDATION + 1);
		if (hive.count(UnitKind.LARVA) < MAX_LARVAE && ++hive.larvaTimer >= LARVA_TICKS) {
			hive.larvaTimer = 0;
			spawn(level, hive, UnitKind.LARVA, around(hatchery, level), null);
		}
		if (hive.queenTicks >= 0 && --hive.queenTicks < 0) {
			hive.born(UnitKind.QUEEN, 1);
			spawn(level, hive, UnitKind.QUEEN, around(hatchery, level), null);
		}
		if (hive.owner != null) { // a player's hive: the player decides (command view), not the hive's AI
			data.setDirty();
			return;
		}
		finishOldResearch(hive);
		if (hatchery.tickCount % 40 == 0) watchForThreats(hatchery, level, hive);
		if (hatchery.tickCount % 200 == 0) maybeRaid(hatchery, level, hive);
		if (++hive.decisionTimer >= DECISION_TICKS) { // buildings, gas, research, larvae: the common AI
			hive.decisionTimer = 0;
			dev.starmine.ai.AiCommander.decide(hatchery, level, hive);
		}
		data.setDirty();
	}

	/**
	 * The ground within 4 blocks up or down of `y` in the column (x, z): the highest free cell on a solid
	 * block. Not the world heightmap: hives under a roof, in a cave or under a test's barrier ceiling.
	 */
	public static BlockPos groundNear(ServerLevel level, int x, int y, int z) {
		for (int dy = 4; dy >= -4; dy--) {
			BlockPos feet = new BlockPos(x, y + dy, z);
			if (level.getBlockState(feet).blocksMotion() || level.getBlockState(feet.above()).blocksMotion()) continue;
			if (level.getBlockState(feet.below()).blocksMotion()) return feet;
		}
		return null;
	}

	/** A drone has reached its site: it becomes the building (paid now; built over the SC2 time). */
	public static StarUnit mutate(StarUnit drone, ServerLevel level, UnitKind kind, BlockPos site) {
		Hive hive = HiveSavedData.get(level.getServer()).get(drone.hiveId());
		if (hive == null || !hive.unlocked(kind) || !hive.canAfford(kind, 1)) return null;
		int half = HiveTerrain.foundation(kind);
		if (kind.gas()) { // an extractor grows on a vein outlet, no foundation
			BlockPos outlet = dev.starmine.vespene.Vespene.outletUnder(level, site);
			if (outlet == null) return null;
			site = outlet.above();
			if (!level.getEntitiesOfClass(StarUnit.class, new AABB(site).inflate(1, 2, 1), mob -> mob.kind.gas() && mob.isAlive()).isEmpty()) return null;
		} else if (!HiveTerrain.prepare(level, site, half - 1, (int) Math.ceil(kind.height), true)) {
			return null;
		}
		hive.pay(kind, 1);
		hive.expect(kind, 1);
		if (!kind.gas()) HiveTerrain.lay(level, site, half);
		StarUnit building = spawn(level, hive, kind, Vec3.atBottomCenterOf(site), null);
		if (building != null) building.startConstruction(kind.economy().trainTicks());
		hive.buildOrder = null;
		drone.discard();
		return building;
	}

	/**
	 * A player (not in creative or spectator) or a hostile creature within 16 blocks of the hatchery is a
	 * threat: the hive's warriors attack it.
	 */
	/** Lair, hive and upgrades started by the old hive AI (saved before research moved to buildings) still finish. */
	private static void finishOldResearch(Hive hive) {
		if (hive.tierTicks >= 0 && --hive.tierTicks < 0) hive.tier++;
		if (hive.upgradeTicks >= 0 && --hive.upgradeTicks < 0) {
			if (hive.attackLevel <= hive.armorLevel) hive.attackLevel++;
			else hive.armorLevel++;
		}
	}

	private static void watchForThreats(StarUnit hatchery, ServerLevel level, Hive hive) {
		LivingEntity threat = null;
		double best = 16 * 16;
		for (LivingEntity other : level.getEntitiesOfClass(LivingEntity.class, hatchery.getBoundingBox().inflate(16),
			entity -> entity.isAlive() && !(entity instanceof StarUnit)
				&& (entity instanceof Player player ? !player.isCreative() && !player.isSpectator() : entity instanceof Enemy))) {
			double distance = other.distanceToSqr(hatchery);
			if (distance < best) {
				best = distance;
				threat = other;
			}
		}
		if (threat != null) alarm(level, hive.id, hatchery.position(), threat);
	}

	public static final int RAID_COOLDOWN = 5 * 60 * 20, RAID_LENGTH = 3 * 60 * 20, RAID_RANGE = 96;

	/**
	 * Raids (owner): with at least 4 warriors and 2 drones at home the hive sends a party every 5 min to
	 * the nearest settlement within 96 blocks — beds, workstations, bells (points of interest), else a
	 * player, else where its changelings last saw villagers or a player — for 3 min. Warriors kill what
	 * lives there; drones open the way through walls (not metal or very hard blocks), bridge gaps, steal
	 * food and break mechanisms and crops.
	 */

	private static void maybeRaid(StarUnit hatchery, ServerLevel level, Hive hive) {
		long now = level.getGameTime();
		List<StarUnit> home = level.getEntitiesOfClass(StarUnit.class, hatchery.getBoundingBox().inflate(48),
			mob -> hive.id.equals(mob.hiveId()) && mob.isAlive() && mob.raidTarget() == null && !mob.kind.structure() && mob.nest() == null);
		List<StarUnit> warriors = home.stream().filter(m -> m.kind != UnitKind.DRONE && m.kind != UnitKind.LARVA && m.kind.attack != UnitKind.Attack.NONE).toList();
		if (now >= hive.nextWave && dev.starmine.ai.AiCommander.wave(hatchery, level, hive)) return;
		if (now < hive.nextRaid) return;
		List<StarUnit> drones = home.stream().filter(m -> m.kind == UnitKind.DRONE).toList();
		if (warriors.size() < 4 || drones.size() < 2) return;
		BlockPos target = level.getPoiManager()
			.findClosest(type -> true, hatchery.blockPosition(), RAID_RANGE, net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.ANY)
			.filter(pos -> pos.distSqr(hatchery.blockPosition()) > 24 * 24)
			.orElse(null);
		if (target == null) {
			Player player = level.getNearestPlayer(hatchery.getX(), hatchery.getY(), hatchery.getZ(), RAID_RANGE,
				entity -> entity instanceof Player p && !p.isCreative() && !p.isSpectator());
			if (player != null) target = player.blockPosition();
		}
		if (target == null && hive.scouted != null && now < hive.scoutedUntil) target = hive.scouted; // what its changelings saw
		hive.nextRaid = now + RAID_COOLDOWN;
		if (target == null) return;
		long until = now + RAID_LENGTH;
		for (StarUnit warrior : warriors.subList(0, Math.min(12, warriors.size()))) warrior.joinRaid(target, until);
		for (StarUnit drone : drones.subList(0, Math.min(2, drones.size()))) drone.joinRaid(target, until);
	}

	/** The hive's warriors within 32 blocks of the alarm and without a target go for the attacker. */
	public static void alarm(ServerLevel level, java.util.UUID hiveId, Vec3 where, LivingEntity attacker) {
		for (StarUnit guard : level.getEntitiesOfClass(StarUnit.class, new AABB(where, where).inflate(32),
			mob -> hiveId.equals(mob.hiveId()) && mob.isAlive() && mob.getTarget() == null && mob.kind != UnitKind.DRONE
				&& mob.kind.attack != UnitKind.Attack.NONE && !mob.kind.structure()))
			guard.setTarget(attacker);
	}

	/** Larva to cocoon: pays (unless free), reserves supply, and the cocoon hatches after the SC2 time. */
	public static void morph(StarUnit larva, ServerLevel level, Hive hive, UnitKind kind, int count, boolean free) {
		if (!free) hive.pay(kind, count);
		hive.expect(kind, count);
		StarUnit egg = spawn(level, hive, UnitKind.EGG, larva.position(), null);
		if (egg != null) egg.becomeCocoon(kind, count, Math.max(20, kind.economy().trainTicks()));
		larva.discard();
	}

	/** A cocoon's units are born: they join the hive at the cocoon's place. */
	public static void hatch(StarUnit egg, ServerLevel level, Hive hive, UnitKind kind, int count) {
		hive.born(kind, count);
		for (int i = 0; i < count; i++) spawn(level, hive, kind, egg.position().add(level.random.nextGaussian() * 0.3, 0, level.random.nextGaussian() * 0.3), null);
	}

	public static StarUnit spawn(ServerLevel level, Hive hive, UnitKind kind, Vec3 at, StarUnit unused) {
		StarUnit mob = StarMine.type(kind).create(level);
		if (mob == null) return null;
		mob.moveTo(at.x, at.y, at.z, level.random.nextFloat() * 360, 0);
		mob.joinHive(hive);
		level.addFreshEntity(mob);
		return mob;
	}

	/** A nest dweller of a hall (not counted in the hive); `hive` may be gone, then it is on its own. */
	public static StarUnit spawnNest(ServerLevel level, Hive hive, UnitKind kind, Vec3 at, BlockPos hall) {
		StarUnit mob = StarMine.type(kind).create(level);
		if (mob == null) return null;
		mob.moveTo(at.x, at.y, at.z, level.random.nextFloat() * 360, 0);
		mob.makeNest(hall);
		if (hive != null) mob.joinHive(hive);
		level.addFreshEntity(mob);
		return mob;
	}

	/** A spot on the ground just outside the hatchery. */
	private static Vec3 around(StarUnit hatchery, ServerLevel level) {
		double angle = level.random.nextDouble() * Math.PI * 2, radius = hatchery.getBbWidth() / 2 + 1;
		BlockPos column = BlockPos.containing(hatchery.getX() + Math.cos(angle) * radius, hatchery.getY() + 2, hatchery.getZ() + Math.sin(angle) * radius);
		BlockPos ground = column;
		for (int i = 0; i < 6 && level.getBlockState(ground.below()).isAir(); i++) ground = ground.below();
		return Vec3.atBottomCenterOf(ground);
	}
}
