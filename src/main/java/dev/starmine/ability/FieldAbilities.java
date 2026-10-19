package dev.starmine.ability;

import dev.starmine.creep.CreepGrowth;
import dev.starmine.creep.CreepSources;
import dev.starmine.creep.CreepTouch;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import static dev.starmine.entity.UnitKind.BLOCKS_PER_UNIT;

/**
 * The rest of SC2's abilities (owner 19.10): the infestor's microbial shroud, the crawlers' uproot and
 * root, the overlord's generate creep, the overseer's contaminate and oversight, the orbital command's
 * supply drop, the observer's surveillance. Uproot, generate creep, oversight and surveillance are modes
 * ({@link Modes}); here are their effects and what a unit in them does each tick.
 */
public final class FieldAbilities {
	/** SC2 microbial shroud: radius 3, 11 s; ground units under it take half from air attacks. */
	public static final double SHROUD_RADIUS = 3 * BLOCKS_PER_UNIT;
	public static final int SHROUD_TICKS = 11 * 20;
	/** SC2 contaminate: 30 s of no training and no research. */
	public static final int CONTAMINATE_TICKS = 30 * 20;
	/** SC2 calldown: extra supplies — a supply depot gives 8 more supply for good. */
	public static final int SUPPLY_DROP = 8;
	/** The tag of a supplied depot and the base's count of them ({@link Hive#levels}). */
	public static final String SUPPLIED = "starmine_supplied", SUPPLY_DROP_ID = "supply_drop";
	/** Blocks of creep an overlord spreads under it, and how long its creep outlives it there. */
	public static final int OVERLORD_CREEP_RADIUS = 4, OVERLORD_CREEP_TICKS = 200;
	/** SC2 crawler speed: 1.0, 2.5 on creep. */
	public static final double CRAWL_SPEED = 1.0, CRAWL_CREEP_SPEED = 2.5;

	private FieldAbilities() {
	}

	/** Microbial shroud: a cloud at the point for 11 s; the caster's ground units in it are shrouded while they stay. */
	static void shroud(StarUnit unit, ServerLevel level, @Nullable Vec3 point) {
		if (point == null) return;
		Delayed.repeat(level, 10, SHROUD_TICKS / 10, pulse -> {
			Abilities.ring(level, point, SHROUD_RADIUS, ParticleTypes.SPORE_BLOSSOM_AIR);
			level.sendParticles(ParticleTypes.SQUID_INK, point.x, point.y + 1.5, point.z, 12, SHROUD_RADIUS / 2, 0.6, SHROUD_RADIUS / 2, 0.01);
			for (LivingEntity other : Abilities.around(unit, point, SHROUD_RADIUS, false))
				if (dev.starmine.faction.Faction.allied(unit, other) && !(other instanceof StarUnit star && star.flying()))
					Status.apply(other, Status.SHROUDED, 15);
		});
	}

	/** Damage a shrouded ground unit takes: half from an air attacker. */
	public static float shrouded(StarUnit unit, net.minecraft.world.damagesource.DamageSource source, float amount) {
		if (!Status.has(unit, Status.SHROUDED) || unit.flying()) return amount;
		boolean air = source.getEntity() instanceof StarUnit attacker && attacker.flying()
			|| source.getEntity() instanceof LivingEntity living && !(living instanceof StarUnit) && living.isFallFlying();
		return air ? amount * 0.5f : amount;
	}

	/** Contaminate: a foe's building trains and researches nothing for 30 s. */
	static void contaminate(StarUnit unit, ServerLevel level, @Nullable LivingEntity target) {
		if (!(target instanceof StarUnit building)) return;
		Status.apply(building, Status.CONTAMINATED, CONTAMINATE_TICKS);
		level.sendParticles(ParticleTypes.ITEM_SLIME, building.getX(), building.getY() + building.getBbHeight() * 0.7, building.getZ(), 40,
			building.getBbWidth() / 3, building.getBbHeight() / 3, building.getBbWidth() / 3, 0.05);
	}

	/** Whether a building is contaminated (it then neither trains nor researches). */
	public static boolean contaminated(StarUnit unit) {
		return Status.has(unit, Status.CONTAMINATED);
	}

	/** Supply drop: a depot of the base gives 8 more supply for good (once a depot). */
	static void supplyDrop(StarUnit unit, ServerLevel level, @Nullable LivingEntity target) {
		if (!(target instanceof StarUnit depot) || depot.getTags().contains(SUPPLIED)) return;
		Hive base = Abilities.base(unit);
		if (base == null) return;
		depot.addTag(SUPPLIED);
		base.levels.merge(SUPPLY_DROP_ID, 1, Integer::sum);
		HiveSavedData.get(level.getServer()).setDirty();
		for (int i = 0; i < 8; i++) // the pod falls in flames
			level.sendParticles(ParticleTypes.FLAME, depot.getX(), depot.getY() + depot.getBbHeight() + 1 + i, depot.getZ(), 4, 0.2, 0.3, 0.2, 0.02);
		level.sendParticles(ParticleTypes.EXPLOSION, depot.getX(), depot.getY() + depot.getBbHeight(), depot.getZ(), 1, 0, 0, 0, 0);
	}

	/** A supplied depot is gone: its base loses the 8 supply. */
	public static void depotGone(StarUnit depot, Hive base) {
		if (!depot.getTags().contains(SUPPLIED)) return;
		base.levels.merge(SUPPLY_DROP_ID, -1, Integer::sum);
		if (base.levels.get(SUPPLY_DROP_ID) <= 0) base.levels.remove(SUPPLY_DROP_ID);
	}

	/** Supply the base's drops add. */
	public static int dropSupply(Hive base) {
		return base.levels.getOrDefault(SUPPLY_DROP_ID, 0) * SUPPLY_DROP;
	}

	/** A crawler pulled out of the ground. */
	public static boolean uprooted(StarUnit unit) {
		return unit.mode() == Modes.UPROOTED;
	}

	/** Whether an overlord spreads creep now. */
	public static boolean creeping(StarUnit unit) {
		return unit.mode() == Modes.CREEPING || unit.mode() == Modes.CREEPING_SACS;
	}

	/** A crawler roots only on creep (SC2). */
	static @Nullable String rootRefusal(StarUnit unit) {
		return CreepTouch.onCreep(unit) ? null : "message.starmine.need_creep";
	}

	/** Each tick: an uprooted crawler crawls to its order point; an overlord spreads creep under it. */
	static void tick(StarUnit unit, ServerLevel level) {
		if (uprooted(unit) && !unit.abilities.changingMode()) crawl(unit);
		if (creeping(unit) && !unit.abilities.changingMode()) creep(unit, level);
		else if (unit.abilities.creepSource != null) {
			CreepGrowth.forget(level, unit.abilities.creepSource);
			unit.abilities.creepSource = null;
		}
	}

	/** Straight on to its order point, a block up where it bumps into one; there it waits. */
	private static void crawl(StarUnit unit) {
		Vec3 to = unit.orderPos();
		Vec3 motion = unit.getDeltaMovement();
		if (to == null) {
			unit.setDeltaMovement(0, motion.y, 0);
			return;
		}
		double perTick = (CreepTouch.onCreep(unit) ? CRAWL_CREEP_SPEED : CRAWL_SPEED) * UnitKind.SPEED_SCALE * 1.6;
		double x = to.x - unit.getX(), z = to.z - unit.getZ(), length = Math.sqrt(x * x + z * z);
		if (length <= perTick) {
			unit.setPos(to.x, unit.getY(), to.z);
			unit.setDeltaMovement(0, motion.y, 0);
			unit.command(StarUnit.Order.NONE, null);
			return;
		}
		double dy = unit.horizontalCollision && unit.onGround() ? 0.42 : motion.y;
		unit.setDeltaMovement(x / length * perTick, dy, z / length * perTick);
		unit.setYRot((float) (Math.atan2(z, x) * 180 / Math.PI) - 90);
		unit.hurtMarked = true;
	}

	/** An overlord's creep: a source on the ground under it, moved along when it has flown on. */
	private static void creep(StarUnit unit, ServerLevel level) {
		BlockPos ground = BlockPos.containing(unit.getX(), unit.groundBelow(unit.getX(), unit.getZ()), unit.getZ());
		BlockPos source = unit.abilities.creepSource;
		if (source == null || source.distSqr(ground) > 4) {
			if (source != null) CreepGrowth.forget(level, source);
			unit.abilities.creepSource = source = ground;
		}
		if (unit.tickCount % 20 == 0) CreepSources.drip(level, source, OVERLORD_CREEP_RADIUS, OVERLORD_CREEP_TICKS);
		CreepGrowth.grow(level, source, OVERLORD_CREEP_RADIUS, 1);
	}
}
