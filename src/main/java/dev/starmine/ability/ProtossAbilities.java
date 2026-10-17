package dev.starmine.ability;

import dev.starmine.StarMine;
import dev.starmine.block.StarMineBlocks;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.faction.Faction;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveLogic;
import dev.starmine.hive.HiveSavedData;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import static dev.starmine.entity.UnitKind.BLOCKS_PER_UNIT;
import static dev.starmine.entity.UnitKind.SCALE;

/**
 * Protoss abilities' effects (owner 14.10, docs/ABILITIES.md), SC2 LotV numbers. The warp prism's
 * phasing, the oracle's pulsar beam and the warp gate are modes ({@link Modes}; a phasing prism powers
 * buildings like a pylon, {@link dev.starmine.race.ProtossRace#poweredAt}); the graviton beam is a
 * lasting channel ({@link Channels}); the immortal's barrier and the guardian shield are passives
 * ({@link Passives}). Here: the zealot's charge, blink and shadow stride, the adept's psionic transfer,
 * force fields, hallucinations, psionic storm, feedback, the archon warp, the purification nova,
 * revelation, the stasis ward, the tempest's disintegration, mass recall, time warp, the shield
 * battery's restore and the carrier's interceptors.
 */
public final class ProtossAbilities {
	/** SC2 charge: +8 damage on the strike. */
	public static final double CHARGE_DAMAGE = 8;
	/** SC2 force field radius 1.7 (a disc 2 blocks across each way, 3 high). */
	public static final double FORCE_FIELD_RADIUS = 1.7 * BLOCKS_PER_UNIT;
	/** SC2 guardian shield 11 s, graviton beam 7 s, prismatic alignment 14.3 s, chrono boost 20 s, battery overcharge 14 s. */
	public static final int GUARDIAN_TICKS = 11 * 20, GRAVITON_TICKS = 7 * 20, PRISMATIC_TICKS = 286, CHRONO_TICKS = 20 * 20,
		OVERCHARGE_TICKS = 14 * 20;
	/** SC2 hallucination lives 43 s. */
	public static final int HALLUCINATION_TICKS = 43 * 20;
	/** SC2 psionic storm: 80 over 2.85 s (10 every 0.36 s), radius 1.5. */
	public static final double STORM_DAMAGE = 80, STORM_RADIUS = 1.5 * BLOCKS_PER_UNIT + 0.3;
	public static final int STORM_PULSES = 8, STORM_EVERY = 7;
	/** SC2 feedback: 0.5 damage per energy drained. */
	public static final double FEEDBACK_PER_ENERGY = 0.5;
	/** How far a templar looks for another one to merge with. */
	public static final double ARCHON_REACH = 8;
	/** SC2 purification nova: 145 (+55 shields) after 2 s, radius 1.5. */
	public static final double NOVA_DAMAGE = 145, NOVA_SHIELDS = 55, NOVA_RADIUS = 1.5 * BLOCKS_PER_UNIT + 0.3;
	public static final int NOVA_TICKS = 40;
	/** SC2 revelation: radius 6, 20 s. */
	public static final double REVELATION_RADIUS = 6 * BLOCKS_PER_UNIT;
	public static final int REVELATION_TICKS = 20 * 20;
	/** SC2 stasis ward: lasts 3 min, springs on a ground foe within 1.5, freezes ground foes within 2.5 for 21 s. */
	public static final int WARD_TICKS = 180 * 20, STASIS_TICKS = 21 * 20;
	public static final double WARD_TRIGGER = 1.5 * BLOCKS_PER_UNIT, WARD_RADIUS = 2.5 * BLOCKS_PER_UNIT;
	/** The tempest's disintegration (owner's plan): 500 to buildings at the point. */
	public static final double DISINTEGRATION_DAMAGE = 500, DISINTEGRATION_RADIUS = 2;
	/** SC2 mass recall radius 6.5. */
	public static final double RECALL_RADIUS = 6.5 * BLOCKS_PER_UNIT;
	/** SC2 time warp: radius 3.5, 7.14 s. */
	public static final double TIME_WARP_RADIUS = 3.5 * BLOCKS_PER_UNIT;
	public static final int TIME_WARP_TICKS = 143;
	/** SC2 shield battery: 50.4 shields a second for 1 energy per 3, range 6. */
	public static final double BATTERY_RATE = 50.4, BATTERY_RANGE = 6 * BLOCKS_PER_UNIT + 1;
	/** SC2 carrier: up to 8 interceptors, comes with 4, 11 s and 15 minerals each; they return beyond 14. */
	public static final int INTERCEPTORS = 8, INTERCEPTORS_START = 4, INTERCEPTOR_TICKS = 11 * 20, INTERCEPTOR_COST = 15;
	public static final double INTERCEPTOR_LEASH = 14 * BLOCKS_PER_UNIT;

	private ProtossAbilities() {
	}

	/** Ticks a charge lasts at most (it ends on the strike). */
	public static final int CHARGE_TICKS = 40;

	/**
	 * Charge (owner 16.10: not a jump): the zealot runs at its foe three times as fast ({@link Status#CHARGING})
	 * and strikes it for 8 more when it gets there; walls stop it like any runner.
	 */
	static void charge(StarUnit unit, ServerLevel level, @Nullable LivingEntity target) {
		if (target == null) return;
		Entity body = unit.body();
		unit.setTarget(target);
		Status.apply(unit, Status.CHARGING, CHARGE_TICKS);
		if (body != unit && body instanceof LivingEntity pilot) Status.apply(pilot, Status.CHARGING, CHARGE_TICKS);
		Vec3 to = target.position().subtract(body.position()).multiply(1, 0, 1);
		if (to.lengthSqr() > 1e-4) { // a first push toward the foe
			Vec3 push = to.normalize().scale(0.6);
			body.setDeltaMovement(push.x, body.getDeltaMovement().y, push.z);
			body.hurtMarked = true;
		}
		level.sendParticles(ParticleTypes.END_ROD, body.getX(), body.getY() + 0.8, body.getZ(), 10, 0.2, 0.4, 0.2, 0.05);
		chargeTick(unit, level);
	}

	/** Every tick of a charge: a trail, the run on, and the strike once the foe is in reach. */
	static void chargeTick(StarUnit unit, ServerLevel level) {
		Entity body = unit.body();
		LivingEntity target = unit.getTarget();
		if (target == null || !target.isAlive()) {
			Status.end(unit, Status.CHARGING);
			return;
		}
		if (unit.tickCount % 2 == 0)
			level.sendParticles(ParticleTypes.END_ROD, body.getX(), body.getY() + 0.6, body.getZ(), 2, 0.2, 0.3, 0.2, 0.01);
		if (body == unit && unit.getNavigation().isDone()) unit.getNavigation().moveTo(target, 1.0);
		if (body.distanceTo(target) <= (target.getBbWidth() + body.getBbWidth()) / 2 + 1.5) {
			target.hurt(unit.attackSource(), (float) (CHARGE_DAMAGE * SCALE));
			unit.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
			Status.end(unit, Status.CHARGING);
		}
	}

	/** Blink, shadow stride, psionic transfer: to the point at once, through walls, onto the nearest place to stand there. */
	static void blink(StarUnit unit, ServerLevel level, @Nullable Vec3 point) {
		if (point == null) return;
		Vec3 at = Abilities.standable(unit, level, point);
		Entity body = unit.body();
		if (at == null) return;
		level.sendParticles(ParticleTypes.REVERSE_PORTAL, body.getX(), body.getY() + 1, body.getZ(), 30, 0.4, 0.8, 0.4, 0.05);
		Abilities.teleport(unit, at);
		level.sendParticles(ParticleTypes.PORTAL, at.x, at.y + 1, at.z, 30, 0.4, 0.8, 0.4, 0.3);
		level.playSound(null, at.x, at.y, at.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 0.7f, 1.4f);
	}

	/** An adept's look while its shade is out, and the shade's (synced: the card offers to call it off, the shade is drawn see-through). */
	public static final String SHADING = "shading", SHADE = "shade";
	/** SC2 psionic transfer: the shade lasts 7 s. */
	public static final int SHADE_TICKS = 7 * 20;

	/**
	 * Psionic transfer (owner 17.10, SC2): the adept stays and fights on; its shade — fast, untouchable, harmless — runs
	 * to the point and can be ordered about. When the 7 s are up the adept is where its shade is; called off, the shade goes.
	 */
	static void shade(StarUnit unit, ServerLevel level, @Nullable Vec3 point) {
		if (point == null) return;
		cancelShade(unit, level);
		StarUnit shade = StarMine.type(UnitKind.ADEPT).create(level);
		if (shade == null) return;
		Entity body = unit.body();
		shade.moveTo(body.getX(), body.getY(), body.getZ(), body.getYRot(), 0);
		ZergAbilities.summon(unit, shade, level);
		shade.setDisguise(SHADE);
		shade.abilities.shadeOf = unit.getUUID();
		Status.apply(shade, Status.SHADE, SHADE_TICKS);
		level.addFreshEntity(shade);
		shade.command(StarUnit.Order.MOVE, point);
		unit.abilities.shade = shade.getUUID();
		unit.setDisguise(SHADING);
		level.sendParticles(ParticleTypes.SOUL, body.getX(), body.getY() + 0.8, body.getZ(), 20, 0.3, 0.6, 0.3, 0.01);
	}

	/** The adept calls its shade off: the shade goes, the adept stays. */
	static void cancelShade(StarUnit unit, ServerLevel level) {
		java.util.UUID id = unit.abilities.shade;
		unit.abilities.shade = null;
		if (SHADING.equals(unit.disguise())) unit.setDisguise("");
		if (id != null && level.getEntity(id) instanceof StarUnit shade) {
			shade.abilities.shadeOf = null; // no transfer when it goes
			level.sendParticles(ParticleTypes.SOUL, shade.getX(), shade.getY() + 0.8, shade.getZ(), 15, 0.3, 0.6, 0.3, 0.01);
			shade.discard();
		}
	}

	/** The shade's time is up: its adept comes to where it is. */
	static void shadeDone(StarUnit shade) {
		if (!(shade.level() instanceof ServerLevel level)) return;
		java.util.UUID id = shade.abilities.shadeOf;
		Vec3 at = shade.position();
		shade.discard();
		if (id == null || !(level.getEntity(id) instanceof StarUnit adept) || !adept.isAlive()) return;
		adept.abilities.shade = null;
		if (SHADING.equals(adept.disguise())) adept.setDisguise("");
		blink(adept, level, at);
		adept.command(StarUnit.Order.NONE, null);
	}

	/** A force field: a disc of force blocks at the point, 3 high, where nothing stands; it goes by itself after 11 s. */
	static void forceField(StarUnit unit, ServerLevel level, @Nullable Vec3 point) {
		if (point == null) return;
		BlockPos centre = BlockPos.containing(point);
		int r = (int) Math.ceil(FORCE_FIELD_RADIUS);
		for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-r, 0, -r), centre.offset(r, 2, r))) {
			double dx = pos.getX() + 0.5 - point.x, dz = pos.getZ() + 0.5 - point.z;
			if (dx * dx + dz * dz > FORCE_FIELD_RADIUS * FORCE_FIELD_RADIUS) continue;
			BlockState there = level.getBlockState(pos);
			if (!there.canBeReplaced() || !there.getFluidState().isEmpty()) continue;
			if (!level.getEntitiesOfClass(LivingEntity.class, new AABB(pos), LivingEntity::isAlive).isEmpty()) continue; // SC2 pushes them aside
			level.setBlockAndUpdate(pos, StarMineBlocks.FORCE_FIELD.defaultBlockState());
		}
		level.playSound(null, point.x, point.y, point.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.HOSTILE, 2f, 0.8f);
	}

	/** Hallucination: two illusory zealots of the sentry's side for 43 s (no damage dealt, double taken). */
	static void hallucination(StarUnit unit, ServerLevel level) {
		Entity body = unit.body();
		UnitKind kind = unit.abilities.hallucinate != null && Ability.HALLUCINATIONS.contains(unit.abilities.hallucinate) ? unit.abilities.hallucinate : UnitKind.ZEALOT;
		unit.abilities.hallucinate = null;
		int count = kind == UnitKind.PROBE ? 4 : kind == UnitKind.ZEALOT || kind == UnitKind.STALKER || kind == UnitKind.ADEPT ? 2 : 1;
		for (int i = 0; i < count; i++) {
			StarUnit copy = StarMine.type(kind).create(level);
			if (copy == null) return;
			double angle = level.random.nextDouble() * Math.PI * 2;
			double y = kind.flying ? body.getY() + 3 : body.getY();
			copy.moveTo(body.getX() + Math.cos(angle) * 1.5, y, body.getZ() + Math.sin(angle) * 1.5, body.getYRot(), 0);
			ZergAbilities.summon(unit, copy, level);
			Status.apply(copy, Status.HALLUCINATION, HALLUCINATION_TICKS);
			level.addFreshEntity(copy);
			level.sendParticles(ParticleTypes.END_ROD, copy.getX(), copy.getY() + 1, copy.getZ(), 15, 0.3, 0.6, 0.3, 0.02);
		}
	}

	/** Psionic storm: 2.85 s of lightning at the point; everyone inside takes 10 each 0.36 s (80 in all; SC2 hits its own side too). */
	static void storm(StarUnit unit, ServerLevel level, @Nullable Vec3 point) {
		if (point == null) return;
		level.playSound(null, point.x, point.y, point.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.HOSTILE, 0.6f, 1.8f);
		Delayed.repeat(level, STORM_EVERY, STORM_PULSES, n -> {
			for (LivingEntity other : Abilities.around(unit, point, STORM_RADIUS, false)) // the ground and the air over it (SC2), not buildings
				if (!(other instanceof StarUnit star && star.kind.structure()))
					other.hurt(unit.attackSource(), (float) (STORM_DAMAGE / STORM_PULSES * SCALE));
			// its edge drawn where it hurts: a ring at its radius, lightning inside
			Abilities.ring(level, point, STORM_RADIUS, ParticleTypes.WITCH);
			for (int k = 0; k < 24; k++) {
				double angle = level.random.nextDouble() * Math.PI * 2, r = Math.sqrt(level.random.nextDouble()) * STORM_RADIUS;
				level.sendParticles(k % 3 == 0 ? ParticleTypes.WITCH : ParticleTypes.ELECTRIC_SPARK, point.x + Math.cos(angle) * r,
					point.y + 0.5 + level.random.nextDouble() * 2.5, point.z + Math.sin(angle) * r, 1, 0, 0, 0, 0.05);
			}
		});
	}

	/** Feedback: the target loses all its energy and takes half as much damage. */
	static void feedback(StarUnit unit, ServerLevel level, @Nullable LivingEntity target) {
		if (!(target instanceof StarUnit victim)) return;
		float energy = victim.energy();
		victim.setEnergy(0);
		Channels.beam(level, unit, victim, ParticleTypes.WITCH);
		if (energy > 0) victim.hurt(unit.attackSource(), (float) (energy * FEEDBACK_PER_ENERGY * SCALE));
	}

	/** The nearest templar of its side it may merge with (high or dark, not busy), or null. */
	static @Nullable StarUnit partner(StarUnit unit) {
		if (unit.pilot() != null) return null;
		return unit.level().getEntitiesOfClass(StarUnit.class, unit.getBoundingBox().inflate(ARCHON_REACH),
				other -> other != unit && other.isAlive() && (other.kind == UnitKind.HIGH_TEMPLAR || other.kind == UnitKind.DARK_TEMPLAR)
					&& Faction.allied(unit, other) && java.util.Objects.equals(unit.hiveId(), other.hiveId()) && other.pilot() == null
					&& !Status.has(other, Status.MERGING) && !Status.has(other, Status.HALLUCINATION))
			.stream().min(Comparator.comparingDouble(other -> other.distanceToSqr(unit))).orElse(null);
	}

	/** Archon warp: the templar and its nearest partner stand and merge; an archon appears between them when the warp is done. */
	static void archonWarp(StarUnit unit, ServerLevel level) {
		StarUnit other = partner(unit);
		if (other == null) return;
		int ticks = Math.max(20, UnitKind.ARCHON.economy().trainTicks());
		Status.apply(unit, Status.MERGING, ticks + 20);
		Status.apply(other, Status.MERGING, ticks + 20);
		Vec3 middle = unit.position().add(other.position()).scale(0.5);
		Delayed.repeat(level, 10, ticks / 10, n -> level.sendParticles(ParticleTypes.ELECTRIC_SPARK, middle.x, middle.y + 1, middle.z, 6, 0.6, 0.6, 0.6, 0.1));
		Delayed.after(level, ticks, () -> {
			if (!unit.isAlive() || !other.isAlive()) {
				if (unit.isAlive()) Status.end(unit, Status.MERGING);
				if (other.isAlive()) Status.end(other, Status.MERGING);
				return;
			}
			Vec3 at = unit.position().add(other.position()).scale(0.5);
			Hive base = Abilities.base(unit);
			unit.discard();
			other.discard();
			StarUnit archon;
			if (base != null) archon = HiveLogic.spawn(level, base, UnitKind.ARCHON, at, null);
			else {
				archon = StarMine.type(UnitKind.ARCHON).create(level);
				if (archon == null) return;
				archon.moveTo(at.x, at.y, at.z, unit.getYRot(), 0);
				archon.setOwner(unit.owner().orElse(null));
				level.addFreshEntity(archon);
			}
			if (base != null) HiveSavedData.get(level.getServer()).setDirty();
			level.sendParticles(ParticleTypes.FLASH, at.x, at.y + 1, at.z, 1, 0, 0, 0, 0);
		});
	}

	/** Purification nova: a ball rolls to the point in 2 s and bursts: 145 (+55 to shields) to everyone on the ground there. */
	static void nova(StarUnit unit, ServerLevel level, @Nullable Vec3 point) {
		if (point == null) return;
		Vec3 from = unit.body().position();
		Delayed.repeat(level, 2, NOVA_TICKS / 2, n -> {
			Vec3 at = from.lerp(point, (n + 1) / (double) (NOVA_TICKS / 2));
			level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, at.x, at.y + 0.5, at.z, 4, 0.2, 0.2, 0.2, 0.01);
		});
		Delayed.after(level, NOVA_TICKS, () -> {
			level.sendParticles(ParticleTypes.EXPLOSION, point.x, point.y + 0.5, point.z, 3, NOVA_RADIUS / 2, 0.3, NOVA_RADIUS / 2, 0);
			level.playSound(null, point.x, point.y, point.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 1.5f, 1.3f);
			for (LivingEntity other : Abilities.around(unit, point, NOVA_RADIUS, false)) {
				if (other instanceof StarUnit star && star.flying()) continue;
				double damage = NOVA_DAMAGE + (other instanceof StarUnit star && star.shield() > 0 ? NOVA_SHIELDS : 0);
				other.hurt(unit.attackSource(), (float) (damage * SCALE));
			}
		});
	}

	/** Revelation: foes in the area are seen by everyone and glow for 20 s, cloaked or burrowed. */
	static void revelation(StarUnit unit, ServerLevel level, @Nullable Vec3 point) {
		if (point == null) return;
		Abilities.ring(level, point, REVELATION_RADIUS, ParticleTypes.GLOW);
		for (LivingEntity other : Abilities.around(unit, point, REVELATION_RADIUS, true)) Status.apply(other, Status.REVEALED, REVELATION_TICKS);
	}

	/** A hidden stasis ward at the point: for 3 min, the first ground foe near it springs it — ground foes around are frozen 21 s. */
	static void stasisWard(StarUnit unit, ServerLevel level, @Nullable Vec3 point) {
		if (point == null) return;
		level.sendParticles(ParticleTypes.GLOW, point.x, point.y + 0.3, point.z, 6, 0.2, 0.1, 0.2, 0);
		Delayed.whileTrue(level, 10, WARD_TICKS / 10, () -> {
			List<LivingEntity> near = Abilities.around(unit, point, WARD_TRIGGER, true).stream().filter(ProtossAbilities::onGround).toList();
			if (near.isEmpty()) return true;
			for (LivingEntity other : Abilities.around(unit, point, WARD_RADIUS, true))
				if (onGround(other)) Status.apply(other, Status.STASIS, STASIS_TICKS);
			level.sendParticles(ParticleTypes.SNOWFLAKE, point.x, point.y + 1, point.z, 60, WARD_RADIUS / 2, 0.8, WARD_RADIUS / 2, 0.02);
			return false;
		});
	}

	private static boolean onGround(LivingEntity entity) {
		return entity instanceof StarUnit star ? !star.flying() : !entity.isFallFlying() && !(entity instanceof net.minecraft.world.entity.FlyingMob);
	}

	/** The tempest's strike (after 7 s): 500 to the buildings at the point. */
	static void disintegration(StarUnit unit, ServerLevel level, @Nullable Vec3 point) {
		if (point == null) return;
		level.sendParticles(ParticleTypes.SONIC_BOOM, point.x, point.y + 1, point.z, 1, 0, 0, 0, 0);
		for (LivingEntity other : Abilities.around(unit, point, DISINTEGRATION_RADIUS + 4, true))
			if (other instanceof StarUnit building && building.kind.structure()
				&& Abilities.horizontal(building.position(), point) <= DISINTEGRATION_RADIUS + building.getBbWidth() / 2)
				building.hurt(unit.attackSource(), (float) (DISINTEGRATION_DAMAGE * SCALE));
	}

	/**
	 * Strategic recall (SC2, owner 17.10): the mothership or a nexus calls its own units in the circle at the point to
	 * itself — round the nexus, or on the ground under the mothership (flyers at their height).
	 */
	static void massRecall(StarUnit unit, ServerLevel level, @Nullable Vec3 point) {
		if (point == null) return;
		Entity self = unit.body();
		Vec3 home = unit.flying() ? new Vec3(self.getX(), unit.groundBelow(self.getX(), self.getZ()), self.getZ()) : self.position();
		List<StarUnit> army = level.getEntitiesOfClass(StarUnit.class, new AABB(point, point).inflate(RECALL_RADIUS, 16, RECALL_RADIUS),
			other -> other.isAlive() && other != unit && !other.kind.structure() && other.kind != UnitKind.MOTHERSHIP && Faction.allied(unit, other)
				&& Abilities.horizontal(other.position(), point) <= RECALL_RADIUS + other.getBbWidth() / 2);
		Abilities.ring(level, point, RECALL_RADIUS, ParticleTypes.REVERSE_PORTAL);
		int i = 0;
		double widest = army.stream().filter(other -> !other.flying()).mapToDouble(other -> other.kind.width).max().orElse(1);
		double room = unit.flying() ? 1 : unit.kind.width / 2 + 0.5;
		for (StarUnit other : army) {
			// round the nexus (or under the mothership), apart by the widest walker: a colossus or an immortal landed in it or in each other
			double angle = i * 2.4, spread = room + widest * (0.6 + 0.3 * i);
			i++;
			Vec3 spot = new Vec3(home.x + Math.cos(angle) * spread, home.y, home.z + Math.sin(angle) * spread);
			Vec3 at = other.flying() ? spot.add(0, other.kind.hoverHeight(), 0) : Abilities.standable(other, level, spot);
			if (at == null) at = spot.add(0, 1, 0);
			level.sendParticles(ParticleTypes.REVERSE_PORTAL, other.getX(), other.getY() + 1, other.getZ(), 20, 0.4, 0.6, 0.4, 0.05);
			Abilities.teleport(other, at);
			level.sendParticles(ParticleTypes.PORTAL, at.x, at.y + 1, at.z, 20, 0.4, 0.6, 0.4, 0.3);
			other.command(StarUnit.Order.NONE, null);
		}
	}

	/** Time warp: foes in the area are slowed by half for 7.14 s. */
	static void timeWarp(StarUnit unit, ServerLevel level, @Nullable Vec3 point) {
		if (point == null) return;
		Delayed.repeat(level, 5, TIME_WARP_TICKS / 5, n -> {
			for (LivingEntity other : Abilities.around(unit, point, TIME_WARP_RADIUS, true)) Status.apply(other, Status.SLOW, 10);
			if (n % 2 == 0) Abilities.ring(level, point, TIME_WARP_RADIUS, ParticleTypes.ENCHANT);
		});
	}

	/**
	 * A powered shield battery restores the shields of its side around it, the most damaged first: SC2
	 * 50.4 a second for 1 energy per 3 (overcharged: twice as fast and free).
	 */
	static void batteryTick(StarUnit battery, ServerLevel level) {
		if (battery.tickCount % 4 != 0 || battery.underConstruction() || !battery.kind.race.rules().powered(battery)) return;
		boolean overcharged = Status.has(battery, Status.OVERCHARGE);
		if (!overcharged && battery.energy() < 1) return;
		LivingEntity best = null;
		double worst = 1;
		for (LivingEntity other : level.getEntitiesOfClass(LivingEntity.class, battery.getBoundingBox().inflate(BATTERY_RANGE),
			other -> other != battery && other.isAlive() && Faction.allied(battery, other) && other.distanceTo(battery) <= BATTERY_RANGE)) {
			double full = shieldMax(other);
			if (full <= 0) continue;
			double share = shieldOf(other) / full;
			if (share < worst) {
				worst = share;
				best = other;
			}
		}
		if (best == null) return;
		double restore = BATTERY_RATE / 5 * (overcharged ? 2 : 1); // SC2 shield points this pulse
		if (!overcharged) {
			restore = Math.min(restore, battery.energy() * 3);
			battery.setEnergy((float) (battery.energy() - restore / 3));
		}
		double amount = restore * SCALE;
		if (best instanceof StarUnit unit) unit.setShield((float) (unit.shield() + amount));
		else best.setAbsorptionAmount((float) Math.min(shieldMax(best), best.getAbsorptionAmount() + amount));
		if (battery.tickCount % 8 == 0) Channels.beam(level, battery, best, ParticleTypes.GLOW);
	}

	private static double shieldMax(LivingEntity entity) {
		if (entity instanceof StarUnit unit) return unit.maxShield();
		StarUnit puppet = entity instanceof ServerPlayer player ? dev.starmine.possess.Possession.puppet(player) : null;
		return puppet == null ? 0 : puppet.kind.shields;
	}

	private static double shieldOf(LivingEntity entity) {
		return entity instanceof StarUnit unit ? unit.shield() : entity.getAbsorptionAmount();
	}

	/** A carrier builds interceptors (up to 8, 15 minerals from its base each) while it has room. */
	static void carrierTick(StarUnit carrier, ServerLevel level) {
		AbilityState state = carrier.abilities;
		if (state.interceptors < 0) state.interceptors = INTERCEPTORS_START;
		if (carrier.tickCount % 20 != 0) return;
		int out = launched(carrier, level).size();
		if (state.interceptors + out >= INTERCEPTORS) return;
		state.interceptorTicks += 20;
		if (state.interceptorTicks < INTERCEPTOR_TICKS) return;
		Hive base = Abilities.base(carrier);
		if (base != null) {
			if (base.minerals < INTERCEPTOR_COST) return;
			base.minerals -= INTERCEPTOR_COST;
			HiveSavedData.get(level.getServer()).setDirty();
		}
		state.interceptorTicks = 0;
		state.interceptors++;
	}

	/** The carrier's interceptors out now. */
	static List<StarUnit> launched(StarUnit carrier, ServerLevel level) {
		return level.getEntitiesOfClass(StarUnit.class, carrier.getBoundingBox().inflate(INTERCEPTOR_LEASH + 8),
			other -> other.kind == UnitKind.INTERCEPTOR && other.isAlive() && carrier.getUUID().equals(other.summoner()));
	}

	/** The carrier's attack: one more interceptor goes out for the target (two a shot while it has them). */
	public static void launchInterceptors(StarUnit carrier, LivingEntity target) {
		if (!(carrier.level() instanceof ServerLevel level)) return;
		AbilityState state = carrier.abilities;
		if (state.interceptors < 0) state.interceptors = INTERCEPTORS_START;
		for (StarUnit out : launched(carrier, level)) if (out.getTarget() == null) out.setTarget(target);
		for (int i = 0; i < 2 && state.interceptors > 0; i++) {
			StarUnit interceptor = StarMine.type(UnitKind.INTERCEPTOR).create(level);
			if (interceptor == null) return;
			Entity body = carrier.body();
			interceptor.moveTo(body.getX(), body.getY() + 0.5, body.getZ(), body.getYRot(), 0);
			ZergAbilities.summon(carrier, interceptor, level);
			interceptor.setTarget(target);
			level.addFreshEntity(interceptor);
			state.interceptors--;
		}
	}

	/** An interceptor: dies with its carrier; with nothing to fight or too far from it, flies back aboard. */
	static void interceptorTick(StarUnit interceptor, ServerLevel level) {
		if (interceptor.summoner() == null) return;
		StarUnit carrier = level.getEntity(interceptor.summoner()) instanceof StarUnit unit && unit.isAlive() ? unit
			: dev.starmine.possess.Possession.puppetById(level.getServer(), interceptor.summoner()); // a carrier a player plays as
		if (carrier == null) {
			if (interceptor.tickCount > 5) interceptor.kill();
			return;
		}
		Entity body = carrier.body();
		LivingEntity target = interceptor.getTarget();
		boolean far = interceptor.distanceTo(body) > INTERCEPTOR_LEASH;
		if (target != null && target.isAlive() && !far) return;
		interceptor.setTarget(null);
		interceptor.getMoveControl().setWantedPosition(body.getX(), body.getY() + 0.5, body.getZ(), 1.5);
		if (interceptor.distanceTo(body) < body.getBbWidth() / 2 + 1.5) { // aboard: at the carrier's edge (7 wide)
			carrier.abilities.interceptors = Math.min(INTERCEPTORS, Math.max(0, carrier.abilities.interceptors) + 1);
			interceptor.discard();
		}
	}
}
