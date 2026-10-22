package dev.starmine.ability;

import dev.starmine.StarMine;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.faction.Faction;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import static dev.starmine.entity.UnitKind.BLOCKS_PER_UNIT;
import static dev.starmine.entity.UnitKind.SCALE;

/**
 * Terran abilities' effects (owner 14.10, docs/ABILITIES.md), SC2 LotV numbers. Cloak, siege mode, the
 * viking's landing, the hellbat, the thor's payload, the lowered depot, add-ons and the command center's
 * upgrades are common mechanics ({@link Cloak}, {@link Modes}); the medivac's and bunker's cargo is
 * {@link Transport}. Here: the stimpack, the KD8 charge, EMP, steady targeting, the nuke, Yamato, the
 * tactical jump, the SCV's repair, the MULE and the scanner sweep.
 *
 * Resources: the nuke, the orbital command, the fortress and the add-ons are paid from the base when
 * cast ({@link Ability#minerals}); a repair is paid while it goes, by the minute of work: SC2 charges a
 * quarter of the unit's cost for its whole health back, so each point of health costs that share, owed
 * as a fraction and paid by whole minerals ({@link #repairTick}); without minerals the repair stops.
 */
public final class TerranAbilities {
	/** SC2 Yamato: 240 damage. */
	static final double YAMATO_DAMAGE = 240;
	/** SC2 stimpack: 11 s. */
	public static final int STIM_TICKS = 11 * 20;
	/** SC2 KD8 charge: 5 damage and a knock back, radius 1.5. */
	public static final double KD8_DAMAGE = 5, KD8_RADIUS = 1.5 * BLOCKS_PER_UNIT;
	/** SC2 EMP: 100 shields and 100 energy, radius 1.5. */
	public static final double EMP_DRAIN = 100, EMP_RADIUS = 1.5 * BLOCKS_PER_UNIT + 0.5;
	/** SC2 steady targeting: 170 damage. */
	public static final double STEADY_DAMAGE = 170;
	/** SC2 nuke: 300 (+200 structures) within 4, half within 6, a quarter within 8. */
	public static final double NUKE_DAMAGE = 300, NUKE_STRUCTURE = 200, NUKE_RADIUS = 8 * BLOCKS_PER_UNIT;
	/** SC2 afterburners: 4 s. */
	public static final int AFTERBURNER_TICKS = 80;
	/** SC2 MULE: lives 64 s, mines 4 times as much. */
	public static final int MULE_TICKS = 64 * 20, MULE_YIELD = 4;
	/** SC2 scanner sweep: radius 13, 12.3 s. */
	public static final double SCAN_RADIUS = 13 * BLOCKS_PER_UNIT;
	public static final int SCAN_TICKS = 246;
	/** SC2 repair: a quarter of the cost for the whole health; the reach of an SCV. */
	public static final double REPAIR_SHARE = 0.25, REPAIR_REACH = 1.5;

	private TerranAbilities() {
	}

	/** Stimpack: +50 % speed and attack speed for 11 s (the health is paid by the engine). */
	static void stim(StarUnit unit, ServerLevel level) {
		Status.apply(unit.body(), Status.STIM, STIM_TICKS);
		Entity body = unit.body();
		level.sendParticles(ParticleTypes.ANGRY_VILLAGER, body.getX(), body.getY() + unit.getBbHeight(), body.getZ(), 2, 0.2, 0.1, 0.2, 0);
	}

	/** KD8 charge (after 1.5 s): foes around the point take 5 and are knocked away from it. */
	static void kd8(StarUnit unit, ServerLevel level, @Nullable Vec3 point) {
		if (point == null) return;
		level.sendParticles(ParticleTypes.EXPLOSION, point.x, point.y + 0.3, point.z, 1, 0, 0, 0, 0);
		level.playSound(null, point.x, point.y, point.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 0.6f, 1.6f);
		for (LivingEntity other : Abilities.around(unit, point, KD8_RADIUS, true)) {
			other.hurt(unit.attackSource(), (float) (KD8_DAMAGE * SCALE));
			Vec3 away = other.position().subtract(point).multiply(1, 0, 1);
			away = away.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : away.normalize();
			other.setDeltaMovement(away.x * 1.2, 0.4, away.z * 1.2);
			other.hurtMarked = true;
		}
	}

	/** EMP: everyone in the area loses up to 100 shields and 100 energy (SC2 hits its own side too). */
	static void emp(StarUnit unit, ServerLevel level, @Nullable Vec3 point) {
		if (point == null) return;
		double radius = Abilities.researched(unit, dev.starmine.race.Research.ENHANCED_SHOCKWAVES) ? EMP_RADIUS + 0.5 * BLOCKS_PER_UNIT : EMP_RADIUS; // SC2 enhanced shockwaves: 2
		level.sendParticles(ParticleTypes.ELECTRIC_SPARK, point.x, point.y + 0.8, point.z, 60, radius / 2, 0.6, radius / 2, 0.3);
		Abilities.ring(level, point, radius, ParticleTypes.ELECTRIC_SPARK);
		for (LivingEntity other : Abilities.around(unit, point, radius, false)) {
			StarUnit victim = other instanceof StarUnit star ? star : other instanceof ServerPlayer player ? dev.starmine.possess.Possession.puppet(player) : null;
			if (victim == null) continue;
			victim.setEnergy((float) Math.max(0, victim.energy() - EMP_DRAIN));
			if (other == victim) victim.setShield((float) (victim.shield() - EMP_DRAIN * SCALE));
			else other.setAbsorptionAmount((float) Math.max(0, other.getAbsorptionAmount() - EMP_DRAIN * SCALE)); // a pilot's shields
		}
	}

	/** Steady targeting (after its 1.43 s channel): 170 to a biological target. */
	static void steadyTargeting(StarUnit unit, ServerLevel level, @Nullable LivingEntity target) {
		if (target == null || !target.isAlive()) return;
		Channels.beam(level, unit, target, ParticleTypes.CRIT);
		target.hurt(unit.attackSource(), (float) (STEADY_DAMAGE * SCALE));
	}

	/** The nuke is called: a warning heard round the red dot ({@link #nukeLaser} draws it while the ghost guides). */
	static void nukeMark(ServerLevel level, Vec3 point) {
		level.playSound(null, point.x, point.y, point.z, SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.HOSTILE, 2f, 0.5f);
	}

	/** The ghost's guiding laser (owner 17.10): a thin red beam from its eyes to the red dot where the nuke will land. */
	static void nukeLaser(ServerLevel level, StarUnit unit, Vec3 point) {
		DustParticleOptions beam = new DustParticleOptions(new Vector3f(1, 0.05f, 0.05f), 0.6f);
		DustParticleOptions dot = new DustParticleOptions(new Vector3f(1, 0, 0), 1.8f);
		Vec3 eyes = unit.body().getEyePosition(), to = point.add(0, 0.1, 0);
		double length = eyes.distanceTo(to);
		for (double step = 0; step <= length; step += 0.6) {
			Vec3 at = eyes.lerp(to, step / length);
			level.sendParticles(beam, at.x, at.y, at.z, 1, 0, 0, 0, 0);
		}
		level.sendParticles(dot, to.x, to.y, to.z, 3, 0.05, 0, 0.05, 0);
		if (unit.tickCount % 20 == 0) level.playSound(null, to.x, to.y, to.z, SoundEvents.NOTE_BLOCK_BIT.value(), SoundSource.HOSTILE, 1.5f, 1.6f);
	}

	/**
	 * The nuke lands: SC2 300 (+200 to buildings) near the middle, less further out, to everyone (owner 19.10: the harm is
	 * SC2's alone — a marine dies, a thor lives with a quarter); a great blast and a crater as wide as its middle, its
	 * blocks broken as the game rule `starmineNukeGriefing` says (on by default), the blasts themselves hurting no one.
	 */
	static void nuke(StarUnit unit, ServerLevel level, @Nullable Vec3 point) {
		if (point == null) return;
		level.sendParticles(ParticleTypes.FLASH, point.x, point.y + 3, point.z, 3, 1, 1, 1, 0);
		level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, point.x, point.y + 1, point.z, 14, NUKE_RADIUS / 3, 1.5, NUKE_RADIUS / 3, 0);
		level.sendParticles(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, point.x, point.y + 6, point.z, 160, 2.5, 8, 2.5, 0.06); // the stem
		level.sendParticles(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, point.x, point.y + 18, point.z, 160, 7, 2, 7, 0.03); // the cap
		level.sendParticles(ParticleTypes.LAVA, point.x, point.y + 1, point.z, 60, NUKE_RADIUS / 4, 1, NUKE_RADIUS / 4, 0.3);
		level.playSound(null, point.x, point.y, point.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 12f, 0.4f);
		level.playSound(null, point.x, point.y, point.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.HOSTILE, 10f, 0.5f);
		for (LivingEntity other : Abilities.around(unit, point, NUKE_RADIUS, false)) {
			double distance = Abilities.horizontal(other.position(), point) / BLOCKS_PER_UNIT;
			double share = distance <= 4 ? 1 : distance <= 6 ? 0.5 : 0.25;
			double damage = NUKE_DAMAGE + (other instanceof StarUnit star && star.kind.structure() ? NUKE_STRUCTURE : 0);
			other.hurt(unit.attackSource(), (float) (damage * share * SCALE));
		}
		// owner 19.10: a great blast — a crater over its middle (9 blocks round) and a ring of blasts round it; the game rule off
		// leaves the land be. The blasts hurt no one: the harm above is SC2's
		Level.ExplosionInteraction blocks = level.getGameRules().getBoolean(StarMine.NUKE_GRIEFING) && !dev.starmine.world.Skirmish.terrainLocked(level)
			? Level.ExplosionInteraction.TNT : Level.ExplosionInteraction.NONE;
		level.explode(unit, null, HARMLESS, point.x, point.y + 0.5, point.z, NUKE_BLAST, false, blocks);
		for (int i = 0; i < 6; i++) {
			double angle = i * Math.PI / 3, r = 4 * BLOCKS_PER_UNIT / 2;
			level.explode(unit, null, HARMLESS, point.x + Math.cos(angle) * r, point.y + 0.5, point.z + Math.sin(angle) * r, NUKE_RING_BLAST, false, blocks);
		}
	}

	/** The nuke's blasts: its middle's (a crater about 9 blocks round) and the ring's round it. */
	public static final float NUKE_BLAST = 7f, NUKE_RING_BLAST = 4.5f;

	/** Blasts that break blocks and throw no one about, hurting nobody (the nuke's harm is counted apart, SC2's). */
	private static final net.minecraft.world.level.ExplosionDamageCalculator HARMLESS = new net.minecraft.world.level.ExplosionDamageCalculator() {
		@Override
		public boolean shouldDamageEntity(net.minecraft.world.level.Explosion explosion, net.minecraft.world.entity.Entity entity) {
			return false;
		}

		@Override
		public float getKnockbackMultiplier(net.minecraft.world.entity.Entity entity) {
			return 0f;
		}
	};

	/** SC2 lock on: 400 damage over 14 s while the target stays within 15. */
	public static final double LOCK_DAMAGE = 400, LOCK_RANGE = 15 * BLOCKS_PER_UNIT;
	public static final int LOCK_TICKS = 14 * 20;
	/** SC2 auto-turret: 10 s; interference matrix 11 s; anti-armor missile 21 s over radius 2.88. */
	public static final int TURRET_TICKS = 10 * 20, INTERFERENCE_TICKS = 11 * 20, ARMOR_BROKEN_TICKS = 21 * 20;
	public static final double ANTI_ARMOR_RADIUS = 2.88 * BLOCKS_PER_UNIT;

	/** The cyclone locks on: it may move and keeps firing at its target while it is within reach. */
	static void lockOn(StarUnit unit, @Nullable LivingEntity target) {
		if (target == null) return;
		unit.abilities.lockOn = target.getUUID();
		unit.abilities.lockTicks = 0;
	}

	static void lockOnTick(StarUnit unit, ServerLevel level) {
		AbilityState state = unit.abilities;
		LivingEntity target = level.getEntity(state.lockOn) instanceof LivingEntity living && living.isAlive() ? living : null;
		if (target == null || ++state.lockTicks > LOCK_TICKS || unit.distanceTo(target) > LOCK_RANGE || Status.has(unit, Status.INTERFERED)) {
			state.lockOn = null;
			return;
		}
		if (state.lockTicks % 10 == 0) {
			target.hurt(unit.attackSource(), (float) (LOCK_DAMAGE / (LOCK_TICKS / 10.0) * SCALE));
			Channels.beam(level, unit, target, ParticleTypes.FLAME);
		}
	}

	/** The raven puts down an auto-turret at the point: 18 a shot, range 6, for 10 s. */
	static void autoTurret(StarUnit unit, ServerLevel level, @Nullable Vec3 point) {
		if (point == null) return;
		StarUnit turret = dev.starmine.StarMine.type(UnitKind.AUTO_TURRET).create(level);
		if (turret == null) return;
		Vec3 at = Abilities.standable(turret, level, point);
		if (at == null) at = point;
		turret.moveTo(at.x, at.y, at.z, unit.getYRot(), 0);
		ZergAbilities.summon(unit, turret, level);
		Status.apply(turret, Status.TEMPORARY, TURRET_TICKS);
		level.addFreshEntity(turret);
		level.sendParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y + 0.5, at.z, 20, 0.4, 0.4, 0.4, 0.1);
	}

	/** Interference matrix: a mechanical or psionic unit neither fights nor casts for 11 s. */
	static void interference(StarUnit unit, ServerLevel level, @Nullable LivingEntity target) {
		if (target == null) return;
		Status.apply(target, Status.INTERFERED, INTERFERENCE_TICKS);
		Channels.beam(level, unit, target, ParticleTypes.ELECTRIC_SPARK);
	}

	/** Anti-armor missile: the foes round the point lose 3 armor for 21 s. */
	static void antiArmor(StarUnit unit, ServerLevel level, @Nullable Vec3 point) {
		if (point == null) return;
		level.sendParticles(ParticleTypes.EXPLOSION, point.x, point.y + 0.5, point.z, 2, 0.5, 0.3, 0.5, 0);
		Abilities.ring(level, point, ANTI_ARMOR_RADIUS, ParticleTypes.ELECTRIC_SPARK);
		for (LivingEntity other : Abilities.around(unit, point, ANTI_ARMOR_RADIUS, true)) Status.apply(other, Status.ARMOR_BROKEN, ARMOR_BROKEN_TICKS);
	}

	/** After its 2 s channel, the Yamato cannon hits the target: a bolt of light and 240 SC2 damage. */
	static void yamato(StarUnit unit, ServerLevel level, @Nullable LivingEntity target) {
		if (target == null || !target.isAlive()) return;
		Vec3 from = unit.body().position().add(0, unit.getBbHeight() / 2, 0), to = target.position().add(0, target.getBbHeight() / 2, 0);
		int steps = (int) Math.max(4, from.distanceTo(to) * 2);
		for (int i = 0; i <= steps; i++) {
			Vec3 point = from.lerp(to, i / (double) steps);
			level.sendParticles(ParticleTypes.FLAME, point.x, point.y, point.z, 3, 0.1, 0.1, 0.1, 0.01);
		}
		level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, to.x, to.y, to.z, 1, 0, 0, 0, 0);
		level.playSound(null, to.x, to.y, to.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 2f, 0.7f);
		target.hurt(unit.attackSource(), (float) (YAMATO_DAMAGE * SCALE));
	}

	/** After its 4 s warning, the battlecruiser jumps to the point (over the ground there). */
	/**
	 * The battlecruiser charges its jump (owner 17.10): it stops, holds fire and does not move ({@link Status#WARPING});
	 * blue sparks gather round its hull and swirl faster, and a shimmer marks where it will come out.
	 */
	static void jumpCharge(ServerLevel level, StarUnit unit, Vec3 point, int ticks) {
		unit.getNavigation().stop();
		unit.setTarget(null);
		Status.apply(unit, Status.WARPING, ticks);
		DustParticleOptions blue = new DustParticleOptions(new Vector3f(0.35f, 0.7f, 1f), 1.4f);
		level.playSound(null, unit.getX(), unit.getY(), unit.getZ(), SoundEvents.BEACON_POWER_SELECT, SoundSource.HOSTILE, 3f, 0.6f);
		Delayed.repeat(level, 2, ticks / 2, n -> {
			if (!unit.isAlive()) return;
			var body = unit.body();
			double share = n * 2.0 / ticks, radius = (body.getBbWidth() / 2 + 1.5) * (1.2 - share * 0.8);
			for (int k = 0; k < 10; k++) {
				double angle = k * Math.PI / 5 + n * (0.2 + share * 0.6);
				level.sendParticles(blue, body.getX() + Math.cos(angle) * radius, body.getY() + body.getBbHeight() * (0.2 + 0.6 * ((k + n) % 5) / 4.0),
					body.getZ() + Math.sin(angle) * radius, 1, 0, 0, 0, 0);
			}
			level.sendParticles(ParticleTypes.ELECTRIC_SPARK, body.getX(), body.getY() + body.getBbHeight() / 2, body.getZ(), 4,
				body.getBbWidth() / 2, body.getBbHeight() / 3, body.getBbWidth() / 2, 0.2);
			if (n % 5 == 0) level.sendParticles(ParticleTypes.REVERSE_PORTAL, point.x, point.y + 2, point.z, 20, 1.5, 1, 1.5, 0.05);
		});
	}

	static void tacticalJump(StarUnit unit, ServerLevel level, @Nullable Vec3 point) {
		if (point == null) return;
		var body = unit.body();
		level.sendParticles(ParticleTypes.PORTAL, body.getX(), body.getY() + 1, body.getZ(), 80, 2, 1, 2, 0.5);
		double ground = unit.groundBelow(point.x, point.z);
		body.teleportTo(point.x, ground + unit.kind.hoverHeight(), point.z);
		unit.getNavigation().stop();
		unit.command(StarUnit.Order.NONE, null);
		level.sendParticles(ParticleTypes.REVERSE_PORTAL, body.getX(), body.getY() + 1, body.getZ(), 80, 2, 1, 2, 0.3);
	}

	/** The SCV takes up the repair: it walks to the target and mends it while the base pays ({@link #repairTick}). */
	/** The bunker comes down (SC2 salvage, 5 s after the order): its garrison walks out and its base gets 75 % of its cost back. */
	static void salvage(StarUnit bunker, ServerLevel level) {
		if (!bunker.isAlive()) return;
		Transport.unloadHere(bunker);
		Hive base = Abilities.base(bunker);
		if (base != null) {
			base.minerals += bunker.kind.economy().minerals() * 3 / 4;
			base.vespene += bunker.kind.economy().vespene() * 3 / 4;
			HiveSavedData.get(level.getServer()).setDirty();
		}
		level.sendParticles(ParticleTypes.LARGE_SMOKE, bunker.getX(), bunker.getY() + 0.5, bunker.getZ(), 30, bunker.getBbWidth() / 2, 0.4, bunker.getBbWidth() / 2, 0.02);
		bunker.discard();
	}

	static void repair(StarUnit unit, @Nullable LivingEntity target) {
		if (target == null) return;
		unit.command(StarUnit.Order.NONE, null);
		unit.abilities.repairTarget = target.getUUID();
		unit.abilities.repairDebt = 0;
	}

	/** Preserve the wild economy's last miners and the next replacement worker's price. */
	static int repairMineralReserve(Hive base) {
		if (base.owner != null) return 0;
		int workers = base.count(UnitKind.SCV);
		return workers <= 3 ? Integer.MAX_VALUE : workers < 6 ? UnitKind.SCV.economy().minerals() : 0;
	}

	/** Repair at the SC2 pace, paying the repair share of its cost as minerals become due. */
	static void repairTick(StarUnit unit, ServerLevel level) {
		AbilityState state = unit.abilities;
		Entity found = level.getEntity(state.repairTarget);
		Hive base = Abilities.base(unit);
		if (!(found instanceof StarUnit target) || !target.isAlive() || target.getHealth() >= target.getMaxHealth() || base == null
			|| unit.order() != StarUnit.Order.NONE || unit.getTarget() != null
			|| base.owner == null && base.minerals <= repairMineralReserve(base)) {
			state.repairTarget = null;
			return;
		}
		double reach = REPAIR_REACH + target.getBbWidth() / 2 + unit.getBbWidth() / 2;
		if (unit.body().distanceTo(target) > reach + target.getBbHeight() / 2) {
			if (unit.tickCount % 10 == 0) unit.getNavigation().moveTo(target, 1.0);
			return;
		}
		unit.getNavigation().stop();
		unit.getLookControl().setLookAt(target);
		UnitKind.Economy economy = target.kind.economy();
		double perTick = target.getMaxHealth() / Math.max(20.0, economy.trainTicks());
		double owed = perTick / target.getMaxHealth() * economy.minerals() * REPAIR_SHARE;
		if (state.repairDebt + owed >= 1) {
			int pay = (int) Math.floor(state.repairDebt + owed);
			if (base.minerals < pay) {
				state.repairTarget = null;
				return;
			}
			base.minerals -= pay;
			state.repairDebt -= pay;
			HiveSavedData.get(level.getServer()).setDirty();
		}
		state.repairDebt += owed;
		target.heal((float) perTick);
		if (unit.tickCount % 8 == 0) {
			unit.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
			level.sendParticles(ParticleTypes.ELECTRIC_SPARK, target.getX(), target.getY() + target.getBbHeight() / 2, target.getZ(), 4,
				target.getBbWidth() / 3, 0.3, target.getBbWidth() / 3, 0.1);
		}
	}

	/** Calldown: a MULE lands at the point and mines there for 64 s (4 times as much per load). */
	static void mule(StarUnit unit, ServerLevel level, @Nullable Vec3 point) {
		if (point == null) return;
		StarUnit mule = StarMine.type(UnitKind.SCV).create(level);
		if (mule == null) return;
		mule.moveTo(point.x, unit.groundBelow(point.x, point.z), point.z, level.random.nextFloat() * 360, 0);
		ZergAbilities.summon(unit, mule, level);
		Status.apply(mule, Status.TEMPORARY, MULE_TICKS);
		level.addFreshEntity(mule);
		mule.commandWork(StarUnit.Work.GATHER, BlockPos.containing(point));
		level.sendParticles(ParticleTypes.FLAME, mule.getX(), mule.getY() + 1, mule.getZ(), 20, 0.3, 1, 0.3, 0.05);
	}

	/** Scanner sweep: the base's side sees the area — cloaked and burrowed units in it too — for 12.3 s. */
	static void scan(StarUnit unit, ServerLevel level, @Nullable Vec3 point) {
		if (point == null) return;
		Cloak.scan(level, unit, point, SCAN_RADIUS, SCAN_TICKS);
		Abilities.ring(level, point, SCAN_RADIUS, ParticleTypes.GLOW);
	}

	/** Who a bunker's garrison may shoot (its foes); for the AI's loading. */
	static boolean foeNear(StarUnit bunker, double range) {
		return !bunker.level().getEntitiesOfClass(LivingEntity.class, bunker.getBoundingBox().inflate(range),
			other -> Faction.hostile(bunker, other) && other.distanceTo(bunker) <= range).isEmpty();
	}
}
