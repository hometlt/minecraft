package dev.starmine.ability;

import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.faction.Faction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * Lasting channels (owner 14.10): a caster holds a spell on one target for a while and can do nothing
 * else ({@link Status#CHANNELLING}) — the phoenix's graviton beam (the target floats while the beam
 * holds), the corruptor's caustic spray (damage growing over time) and the infestor's neural parasite
 * (the target fights for the infestor's side). It breaks when the target dies or gets out of reach, and
 * the hold on the target ends with it (the lift and the parasite are refreshed only while it lasts).
 */
final class Channels {
	/** SC2 caustic spray: 7 damage a second, 30 after 4.3 s. */
	static final double SPRAY_DPS = 7, SPRAY_DPS_FULL = 30;
	static final int SPRAY_RAMP = 86, SPRAY_TICKS = 30 * 20;

	private Channels() {
	}

	/** Starts holding `ability` on `target` for `ticks`. */
	static void start(StarUnit caster, Ability ability, LivingEntity target, int ticks) {
		AbilityState state = caster.abilities;
		state.channelOn = target.getUUID();
		state.channelOnAbility = ability;
		Status.apply(caster, Status.CHANNELLING, ticks);
		hold(caster, (ServerLevel) caster.level(), ability, target, 0);
	}

	static void tick(StarUnit caster, ServerLevel level) {
		AbilityState state = caster.abilities;
		if (!Status.has(caster, Status.CHANNELLING) || state.channelOnAbility == null) {
			state.channelOn = null;
			return;
		}
		Entity found = level.getEntity(state.channelOn);
		Ability ability = state.channelOnAbility;
		if (!(found instanceof LivingEntity target) || !target.isAlive()
			|| caster.body().distanceTo(target) > ability.range * 1.5 + target.getBbWidth() / 2 + 2) {
			Status.end(caster, Status.CHANNELLING);
			return;
		}
		caster.getNavigation().stop();
		caster.getLookControl().setLookAt(target);
		int elapsed = (ability == Ability.CAUSTIC_SPRAY ? SPRAY_TICKS : ability == Ability.GRAVITON_BEAM ? ProtossAbilities.GRAVITON_TICKS
			: ZergAbilities.PARASITE_TICKS) - Status.left(caster, Status.CHANNELLING);
		hold(caster, level, ability, target, elapsed);
	}

	private static void hold(StarUnit caster, ServerLevel level, Ability ability, LivingEntity target, int elapsed) {
		switch (ability) {
			case GRAVITON_BEAM -> {
				if (target instanceof StarUnit) Status.apply(target, Status.LIFTED, 3);
				else hover(level, target); // a player or a creature: held 2.5 blocks up (levitation would carry it off into the sky)
				if (caster.tickCount % 4 == 0) beam(level, caster, target, ParticleTypes.END_ROD);
			}
			case CAUSTIC_SPRAY -> {
				if (elapsed % 10 == 0) {
					double dps = elapsed < SPRAY_RAMP ? SPRAY_DPS : SPRAY_DPS_FULL;
					target.hurt(caster.attackSource(), (float) (dps / 2 * UnitKind.SCALE));
				}
				if (caster.tickCount % 3 == 0) beam(level, caster, target, ParticleTypes.ITEM_SLIME);
			}
			case NEURAL_PARASITE -> {
				if (target instanceof StarUnit unit) {
					unit.abilities.parasiteSide = Faction.of(caster);
					unit.abilities.parasiteCaster = caster.getUUID();
					Status.apply(unit, Status.PARASITE, 15);
					unit.setController(caster.owner().orElse(null)); // SC2: the infestor's player commands it meanwhile
				} else if (target instanceof Mob mob && caster.tickCount % 20 == 0) { // a creature turns on the infestor's foes
					LivingEntity foe = level.getNearestEntity(LivingEntity.class, net.minecraft.world.entity.ai.targeting.TargetingConditions.forCombat()
							.selector(other -> other != mob && Faction.hostile(caster, other)), mob, mob.getX(), mob.getY(), mob.getZ(),
						mob.getBoundingBox().inflate(16));
					if (foe != null) mob.setTarget(foe);
				}
				if (caster.tickCount % 5 == 0) beam(level, caster, target, ParticleTypes.WITCH);
			}
			default -> {
			}
		}
	}

	/** Holds a player or a creature {@link Status#LIFT_HEIGHT} over the ground under it, helpless. */
	static void hover(ServerLevel level, LivingEntity target) {
		net.minecraft.core.BlockPos.MutableBlockPos at = target.blockPosition().mutable();
		int floor = at.getY() - 8;
		while (at.getY() > floor && level.getBlockState(at.below()).getCollisionShape(level, at.below()).isEmpty()) at.move(0, -1, 0);
		double height = at.getY() + Status.LIFT_HEIGHT;
		Vec3 motion = target.getDeltaMovement();
		target.setDeltaMovement(motion.x * 0.3, target.getY() < height ? 0.12 : target.getY() > height + 0.3 ? -0.05 : 0.0, motion.z * 0.3);
		target.fallDistance = 0;
		target.hurtMarked = true; // a player's client takes the motion
		if (target instanceof Mob mob) mob.getNavigation().stop();
	}

	/** A line of particles from the caster to the target. */
	static void beam(ServerLevel level, StarUnit caster, LivingEntity target, ParticleOptions particle) {
		Vec3 from = caster.body().position().add(0, caster.getBbHeight() / 2, 0), to = target.position().add(0, target.getBbHeight() / 2, 0);
		int steps = (int) Math.min(24, Math.max(3, from.distanceTo(to) * 2));
		for (int i = 0; i <= steps; i++) {
			Vec3 point = from.lerp(to, i / (double) steps);
			level.sendParticles(particle, point.x, point.y, point.z, 1, 0, 0, 0, 0);
		}
	}
}
