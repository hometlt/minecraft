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
	/** SC2 KD8 charge: 5 damage and a knock back, radius 1.5 (2 blocks). */
	public static final double KD8_DAMAGE = 5, KD8_RADIUS = 2;
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
		level.sendParticles(ParticleTypes.ELECTRIC_SPARK, point.x, point.y + 0.8, point.z, 60, EMP_RADIUS / 2, 0.6, EMP_RADIUS / 2, 0.3);
		Abilities.ring(level, point, EMP_RADIUS, ParticleTypes.ELECTRIC_SPARK);
		for (LivingEntity other : Abilities.around(unit, point, EMP_RADIUS, false)) {
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

	/** The nuke's red dot while the ghost guides it (14 s). */
	static void nukeMark(ServerLevel level, Vec3 point) {
		DustParticleOptions red = new DustParticleOptions(new Vector3f(1, 0, 0), 1.5f);
		Delayed.repeat(level, 10, 28, n -> level.sendParticles(red, point.x, point.y + 0.1, point.z, 3, 0.05, 0, 0.05, 0));
		level.playSound(null, point.x, point.y, point.z, SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.HOSTILE, 2f, 0.5f);
	}

	/**
	 * The nuke lands: SC2 300 (+200 to buildings) near the middle, less further out, to everyone; blocks
	 * break only under the game rule `starmineNukeGriefing` (off by default).
	 */
	static void nuke(StarUnit unit, ServerLevel level, @Nullable Vec3 point) {
		if (point == null) return;
		level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, point.x, point.y + 1, point.z, 4, 2, 1, 2, 0);
		level.sendParticles(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, point.x, point.y + 2, point.z, 60, 3, 4, 3, 0.05);
		level.playSound(null, point.x, point.y, point.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 6f, 0.5f);
		for (LivingEntity other : Abilities.around(unit, point, NUKE_RADIUS, false)) {
			double distance = Abilities.horizontal(other.position(), point) / BLOCKS_PER_UNIT;
			double share = distance <= 4 ? 1 : distance <= 6 ? 0.5 : 0.25;
			double damage = NUKE_DAMAGE + (other instanceof StarUnit star && star.kind.structure() ? NUKE_STRUCTURE : 0);
			other.hurt(unit.attackSource(), (float) (damage * share * SCALE));
		}
		if (level.getGameRules().getBoolean(StarMine.NUKE_GRIEFING))
			level.explode(unit, point.x, point.y + 0.5, point.z, 6f, Level.ExplosionInteraction.TNT);
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
	static void repair(StarUnit unit, @Nullable LivingEntity target) {
		if (target == null) return;
		unit.command(StarUnit.Order.NONE, null);
		unit.abilities.repairTarget = target.getUUID();
		unit.abilities.repairDebt = 0;
	}

	/**
	 * Repair, each tick: within reach the target gets back its health at the SC2 pace (its whole health in
	 * its build time), each point owing {@link #REPAIR_SHARE} of its cost; whole minerals are paid as they
	 * are owed. It stops when the target is whole or gone, or the base runs out of minerals.
	 */
	static void repairTick(StarUnit unit, ServerLevel level) {
		AbilityState state = unit.abilities;
		Entity found = level.getEntity(state.repairTarget);
		Hive base = Abilities.base(unit);
		if (!(found instanceof StarUnit target) || !target.isAlive() || target.getHealth() >= target.getMaxHealth() || base == null
			|| unit.order() != StarUnit.Order.NONE || unit.getTarget() != null) {
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
