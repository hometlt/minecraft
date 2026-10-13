package dev.starmine.hive;

import dev.starmine.StarMine;
import dev.starmine.creep.CreepGrowth;
import dev.starmine.creep.CreepSources;
import dev.starmine.entity.ZergKind;
import dev.starmine.entity.ZergMob;
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
 * The hive's mind, ticked by its hatchery while the hatchery is loaded.
 *
 * Larvae: one every {@link #LARVA_TICKS} (owner: a minute) up to {@link #MAX_LARVAE}. Every
 * {@link #DECISION_TICKS} a larva may become a cocoon, in this order: a free drone when the hive has
 * no drone at all; an overlord when supply is nearly used up; drones up to the worker target (2, plus
 * one per 200 minerals collected, at most 8); otherwise zerglings. The hatchery trains one queen. Costs,
 * supply and times are SC2's ({@link ZergKind#economy()}). Building requirements (spawning pool for
 * zerglings and queens) come with the buildings stage; until then they are treated as met.
 * The hatchery stands on its thick-creep foundation ({@link HiveTerrain}) and grows creep around it.
 */
public final class HiveLogic {
	public static final int LARVA_TICKS = 60 * 20;
	public static final int MAX_LARVAE = 3;
	public static final int DECISION_TICKS = 100;
	public static final int MAX_WORKERS = 8;

	private HiveLogic() {
	}

	/** Makes a newly placed hatchery a hive, or refuses it when the world already has its maximum. */
	public static Hive found(ZergMob hatchery, ServerLevel level) {
		HiveSavedData data = HiveSavedData.get(level.getServer());
		Hive hive = data.get(hatchery.getUUID());
		if (hive != null) return hive;
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
		return hive;
	}

	public static void tick(ZergMob hatchery, ServerLevel level) {
		Hive hive = found(hatchery, level);
		if (hive == null) return;
		HiveSavedData data = HiveSavedData.get(level.getServer());
		hive.hatchery = hatchery.blockPosition();
		if (!hive.foundation) {
			HiveTerrain.lay(level, hive.hatchery, HiveTerrain.HATCHERY_FOUNDATION);
			hive.foundation = true;
		}
		CreepGrowth.grow(level, hive.hatchery, CreepSources.HATCHERY_RADIUS, HiveTerrain.HATCHERY_FOUNDATION + 1);
		if (hive.count(ZergKind.LARVA) < MAX_LARVAE && ++hive.larvaTimer >= LARVA_TICKS) {
			hive.larvaTimer = 0;
			spawn(level, hive, ZergKind.LARVA, around(hatchery, level), null);
		}
		if (hive.queenTicks >= 0 && --hive.queenTicks < 0) {
			hive.born(ZergKind.QUEEN, 1);
			spawn(level, hive, ZergKind.QUEEN, around(hatchery, level), null);
		}
		research(level, hive);
		if (hatchery.tickCount % 40 == 0) watchForThreats(hatchery, level, hive);
		if (hatchery.tickCount % 200 == 0) maybeRaid(hatchery, level, hive);
		if (++hive.decisionTimer >= DECISION_TICKS) {
			hive.decisionTimer = 0;
			decide(hatchery, level, hive);
		}
		data.setDirty();
	}

	private static void decide(ZergMob hatchery, ServerLevel level, Hive hive) {
		orderBuilding(hatchery, level, hive);
		if (hive.unlocked(ZergKind.QUEEN) && hive.count(ZergKind.QUEEN) + hive.pending(ZergKind.QUEEN) == 0 && hive.queenTicks < 0 && hive.canAfford(ZergKind.QUEEN, 1)) {
			hive.pay(ZergKind.QUEEN, 1);
			hive.expect(ZergKind.QUEEN, 1);
			hive.queenTicks = ZergKind.QUEEN.economy().trainTicks();
		}
		List<ZergMob> larvae = level.getEntitiesOfClass(ZergMob.class, new AABB(hatchery.blockPosition()).inflate(16),
			mob -> mob.kind == ZergKind.LARVA && hive.id.equals(mob.hiveId()) && mob.isAlive());
		if (larvae.isEmpty()) return;
		ZergMob larva = larvae.get(level.random.nextInt(larvae.size()));
		int drones = hive.count(ZergKind.DRONE) + hive.pending(ZergKind.DRONE);
		if (drones == 0) {
			morph(larva, level, hive, ZergKind.DRONE, 1, true);
			return;
		}
		double free = hive.supplyCap() + ZergKind.OVERLORD.economy().supply() * hive.pending(ZergKind.OVERLORD) - hive.supplyUsed();
		if (free < 2 && hive.pending(ZergKind.OVERLORD) == 0 && hive.supplyCap() < Hive.MAX_SUPPLY) {
			if (hive.canAfford(ZergKind.OVERLORD, 1)) morph(larva, level, hive, ZergKind.OVERLORD, 1, false);
			return;
		}
		int workers = Math.min(MAX_WORKERS, 2 + hive.mineralsCollected / 200);
		if (drones < workers && hive.canAfford(ZergKind.DRONE, 1)) {
			morph(larva, level, hive, ZergKind.DRONE, 1, false);
			return;
		}
		// warriors the hive's tech allows (SC2: pool, warren, nest, den, spire, pit, cavern)
		List<ZergKind> choices = new java.util.ArrayList<>();
		for (ZergKind kind : WARRIORS) {
			int count = kind == ZergKind.ZERGLING ? 2 : 1;
			if (hive.unlocked(kind) && hive.canAfford(kind, count)) choices.add(kind);
		}
		if (choices.isEmpty()) return;
		ZergKind pick = choices.get(level.random.nextInt(choices.size()));
		morph(larva, level, hive, pick, pick == ZergKind.ZERGLING ? 2 : 1, false);
	}

	private static final ZergKind[] WARRIORS = {ZergKind.ZERGLING, ZergKind.ROACH, ZergKind.RAVAGER, ZergKind.BANELING, ZergKind.HYDRALISK,
		ZergKind.LURKER, ZergKind.MUTALISK, ZergKind.CORRUPTOR, ZergKind.BROOD_LORD, ZergKind.INFESTOR, ZergKind.SWARM_HOST,
		ZergKind.VIPER, ZergKind.ULTRALISK};

	/** The order buildings come in (SC2-like), with how many of each. */
	private static final ZergKind[] BUILD_ORDER = {ZergKind.SPAWNING_POOL, ZergKind.EVOLUTION_CHAMBER, ZergKind.SPINE_CRAWLER,
		ZergKind.ROACH_WARREN, ZergKind.SPORE_CRAWLER, ZergKind.BANELING_NEST, ZergKind.HYDRALISK_DEN, ZergKind.SPINE_CRAWLER,
		ZergKind.SPIRE, ZergKind.INFESTATION_PIT, ZergKind.ULTRALISK_CAVERN};

	/**
	 * Buildings (owner): with 4 drones or more the hive sends one drone at a time to mutate into the next
	 * building it lacks and can afford, on a levelled site beside the hatchery.
	 */
	private static void orderBuilding(ZergMob hatchery, ServerLevel level, Hive hive) {
		long now = level.getGameTime();
		if (hive.buildOrder != null && now < hive.buildOrderUntil) return;
		hive.buildOrder = null;
		if (hive.count(ZergKind.DRONE) < 4) return;
		java.util.Map<ZergKind, Integer> wanted = new java.util.EnumMap<>(ZergKind.class);
		ZergKind next = null;
		for (ZergKind kind : BUILD_ORDER) {
			int want = wanted.merge(kind, 1, Integer::sum);
			if (hive.count(kind) + hive.pending(kind) >= want) continue;
			if (hive.unlocked(kind) && hive.canAfford(kind, 1)) next = kind;
			break;
		}
		if (next == null) return;
		BlockPos site = findSite(level, hatchery, next);
		if (site == null) return;
		final ZergKind building = next;
		level.getEntitiesOfClass(ZergMob.class, hatchery.getBoundingBox().inflate(48),
				mob -> mob.kind == ZergKind.DRONE && hive.id.equals(mob.hiveId()) && mob.isAlive() && mob.raidTarget() == null && mob.cargoBlocks == 0)
			.stream().min(java.util.Comparator.comparingDouble(mob -> mob.distanceToSqr(Vec3.atCenterOf(site))))
			.ifPresent(drone -> {
				drone.orderBuild(building, site);
				hive.buildOrder = building.name();
				hive.buildOrderUntil = now + 1200;
			});
	}

	/** A levelled site for a building on the ground around the hatchery, clear of other buildings. */
	private static BlockPos findSite(ServerLevel level, ZergMob hatchery, ZergKind kind) {
		int half = HiveTerrain.foundation(kind);
		for (int attempt = 0; attempt < 24; attempt++) {
			double angle = level.random.nextDouble() * Math.PI * 2;
			double distance = hatchery.kind.width / 2 + 2 + half + level.random.nextInt(7);
			int x = (int) Math.floor(hatchery.getX() + Math.cos(angle) * distance), z = (int) Math.floor(hatchery.getZ() + Math.sin(angle) * distance);
			BlockPos feet = groundNear(level, x, hatchery.getBlockY(), z);
			if (feet == null) continue;
			AABB area = new AABB(feet).inflate(half + 1, 3, half + 1);
			if (!level.getEntitiesOfClass(ZergMob.class, area, mob -> mob.kind.structure()).isEmpty()) continue;
			if (HiveTerrain.prepare(level, feet, half - 1, (int) Math.ceil(kind.height), false)) return feet;
		}
		return null;
	}

	/**
	 * The ground within 4 blocks up or down of `y` in the column (x, z): the highest free cell on a solid
	 * block. Not the world heightmap: hives under a roof, in a cave or under a test's barrier ceiling.
	 */
	private static BlockPos groundNear(ServerLevel level, int x, int y, int z) {
		for (int dy = 4; dy >= -4; dy--) {
			BlockPos feet = new BlockPos(x, y + dy, z);
			if (level.getBlockState(feet).blocksMotion() || level.getBlockState(feet.above()).blocksMotion()) continue;
			if (level.getBlockState(feet.below()).blocksMotion()) return feet;
		}
		return null;
	}

	/** A drone has reached its site: it becomes the building (paid now; built over the SC2 time). */
	public static boolean mutate(ZergMob drone, ServerLevel level, ZergKind kind, BlockPos site) {
		Hive hive = HiveSavedData.get(level.getServer()).get(drone.hiveId());
		if (hive == null || !hive.unlocked(kind) || !hive.canAfford(kind, 1)) return false;
		int half = HiveTerrain.foundation(kind);
		if (!HiveTerrain.prepare(level, site, half - 1, (int) Math.ceil(kind.height), true)) return false;
		hive.pay(kind, 1);
		hive.expect(kind, 1);
		HiveTerrain.lay(level, site, half);
		ZergMob building = spawn(level, hive, kind, Vec3.atBottomCenterOf(site), null);
		if (building != null) building.startConstruction(kind.economy().trainTicks());
		hive.buildOrder = null;
		drone.discard();
		return true;
	}

	/**
	 * Research: the hatchery becomes a lair (150/100, 57 s; needs the pool) and a hive (200/150, 71 s;
	 * needs the infestation pit); the evolution chamber improves attack and armor in turn (owner: attack
	 * 1, armor 1, attack 2… up to 3 each), each level a little dearer and longer.
	 */
	private static void research(ServerLevel level, Hive hive) {
		if (hive.tierTicks >= 0 && --hive.tierTicks < 0) hive.tier++;
		if (hive.tierTicks < 0 && hive.tier < 3) {
			boolean lair = hive.tier == 1 && hive.count(ZergKind.SPAWNING_POOL) > 0 && hive.minerals >= 150 && hive.vespene >= 100;
			boolean full = hive.tier == 2 && hive.count(ZergKind.INFESTATION_PIT) > 0 && hive.minerals >= 200 && hive.vespene >= 150;
			if (lair || full) {
				hive.minerals -= lair ? 150 : 200;
				hive.vespene -= lair ? 100 : 150;
				hive.tierTicks = lair ? 57 * 20 : 71 * 20;
			}
		}
		if (hive.upgradeTicks >= 0 && --hive.upgradeTicks < 0) {
			if (hive.attackLevel <= hive.armorLevel) hive.attackLevel++;
			else hive.armorLevel++;
		}
		if (hive.upgradeTicks < 0 && hive.count(ZergKind.EVOLUTION_CHAMBER) > 0 && (hive.attackLevel < 3 || hive.armorLevel < 3)) {
			boolean attack = hive.attackLevel <= hive.armorLevel;
			int levelNext = (attack ? hive.attackLevel : hive.armorLevel) + 1;
			int cost = attack ? 50 + 50 * levelNext : 75 + 75 * levelNext;
			if (hive.tier >= levelNext && hive.minerals >= cost && hive.vespene >= cost) {
				hive.minerals -= cost;
				hive.vespene -= cost;
				hive.upgradeTicks = (90 + 20 * levelNext) * 20;
			}
		}
	}

	/**
	 * A player (not in creative or spectator) or a hostile creature within 16 blocks of the hatchery is a
	 * threat: the hive's warriors attack it.
	 */
	private static void watchForThreats(ZergMob hatchery, ServerLevel level, Hive hive) {
		LivingEntity threat = null;
		double best = 16 * 16;
		for (LivingEntity other : level.getEntitiesOfClass(LivingEntity.class, hatchery.getBoundingBox().inflate(16),
			entity -> entity.isAlive() && !(entity instanceof ZergMob)
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
	 * player — for 3 min. Warriors kill what lives there; drones open the way through walls (not metal
	 * or very hard blocks), bridge gaps, steal food and break mechanisms and crops.
	 */
	private static void maybeRaid(ZergMob hatchery, ServerLevel level, Hive hive) {
		long now = level.getGameTime();
		if (now < hive.nextRaid) return;
		List<ZergMob> home = level.getEntitiesOfClass(ZergMob.class, hatchery.getBoundingBox().inflate(48),
			mob -> hive.id.equals(mob.hiveId()) && mob.isAlive() && mob.raidTarget() == null && !mob.kind.structure());
		List<ZergMob> warriors = home.stream().filter(m -> m.kind != ZergKind.DRONE && m.kind != ZergKind.LARVA && m.kind.attack != ZergKind.Attack.NONE).toList();
		List<ZergMob> drones = home.stream().filter(m -> m.kind == ZergKind.DRONE).toList();
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
		hive.nextRaid = now + RAID_COOLDOWN;
		if (target == null) return;
		long until = now + RAID_LENGTH;
		for (ZergMob warrior : warriors.subList(0, Math.min(12, warriors.size()))) warrior.joinRaid(target, until);
		for (ZergMob drone : drones.subList(0, Math.min(2, drones.size()))) drone.joinRaid(target, until);
	}

	/** The hive's warriors within 32 blocks of the alarm and without a target go for the attacker. */
	public static void alarm(ServerLevel level, java.util.UUID hiveId, Vec3 where, LivingEntity attacker) {
		for (ZergMob guard : level.getEntitiesOfClass(ZergMob.class, new AABB(where, where).inflate(32),
			mob -> hiveId.equals(mob.hiveId()) && mob.isAlive() && mob.getTarget() == null && mob.kind != ZergKind.DRONE
				&& mob.kind.attack != ZergKind.Attack.NONE && !mob.kind.structure()))
			guard.setTarget(attacker);
	}

	/** Larva to cocoon: pays (unless free), reserves supply, and the cocoon hatches after the SC2 time. */
	public static void morph(ZergMob larva, ServerLevel level, Hive hive, ZergKind kind, int count, boolean free) {
		if (!free) hive.pay(kind, count);
		hive.expect(kind, count);
		ZergMob egg = spawn(level, hive, ZergKind.EGG, larva.position(), null);
		if (egg != null) egg.becomeCocoon(kind, count, Math.max(20, kind.economy().trainTicks()));
		larva.discard();
	}

	/** A cocoon's units are born: they join the hive at the cocoon's place. */
	public static void hatch(ZergMob egg, ServerLevel level, Hive hive, ZergKind kind, int count) {
		hive.born(kind, count);
		for (int i = 0; i < count; i++) spawn(level, hive, kind, egg.position().add(level.random.nextGaussian() * 0.3, 0, level.random.nextGaussian() * 0.3), null);
	}

	public static ZergMob spawn(ServerLevel level, Hive hive, ZergKind kind, Vec3 at, ZergMob unused) {
		ZergMob mob = StarMine.type(kind).create(level);
		if (mob == null) return null;
		mob.moveTo(at.x, at.y, at.z, level.random.nextFloat() * 360, 0);
		mob.joinHive(hive);
		level.addFreshEntity(mob);
		return mob;
	}

	/** A spot on the ground just outside the hatchery. */
	private static Vec3 around(ZergMob hatchery, ServerLevel level) {
		double angle = level.random.nextDouble() * Math.PI * 2, radius = hatchery.getBbWidth() / 2 + 1;
		BlockPos column = BlockPos.containing(hatchery.getX() + Math.cos(angle) * radius, hatchery.getY() + 2, hatchery.getZ() + Math.sin(angle) * radius);
		BlockPos ground = column;
		for (int i = 0; i < 6 && level.getBlockState(ground.below()).isAir(); i++) ground = ground.below();
		return Vec3.atBottomCenterOf(ground);
	}
}
