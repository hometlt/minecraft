package dev.starmine.hive;

import dev.starmine.StarMine;
import dev.starmine.entity.ZergKind;
import dev.starmine.entity.ZergMob;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
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
		hive = new Hive(hatchery.getUUID(), level.dimension().location().toString(), hatchery.blockPosition());
		data.add(hive);
		return hive;
	}

	public static void tick(ZergMob hatchery, ServerLevel level) {
		Hive hive = found(hatchery, level);
		if (hive == null) return;
		HiveSavedData data = HiveSavedData.get(level.getServer());
		hive.hatchery = hatchery.blockPosition();
		if (hive.count(ZergKind.LARVA) < MAX_LARVAE && ++hive.larvaTimer >= LARVA_TICKS) {
			hive.larvaTimer = 0;
			spawn(level, hive, ZergKind.LARVA, around(hatchery, level), null);
		}
		if (hive.queenTicks >= 0 && --hive.queenTicks < 0) {
			hive.born(ZergKind.QUEEN, 1);
			spawn(level, hive, ZergKind.QUEEN, around(hatchery, level), null);
		}
		if (++hive.decisionTimer >= DECISION_TICKS) {
			hive.decisionTimer = 0;
			decide(hatchery, level, hive);
		}
		data.setDirty();
	}

	private static void decide(ZergMob hatchery, ServerLevel level, Hive hive) {
		if (hive.count(ZergKind.QUEEN) + hive.pending(ZergKind.QUEEN) == 0 && hive.queenTicks < 0 && hive.canAfford(ZergKind.QUEEN, 1)) {
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
		if (hive.canAfford(ZergKind.ZERGLING, 2)) morph(larva, level, hive, ZergKind.ZERGLING, 2, false);
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
