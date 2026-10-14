package dev.starmine.ability;

import dev.starmine.entity.StarUnit;
import dev.starmine.faction.Faction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * How wild units use their abilities (owner 14.10), once a second, by simple rules: cloak when a foe is
 * near and there is energy, uncloak after 10 calm seconds; siege up when foes are within the siege reach
 * but not too close, unsiege after 10 calm seconds; Yamato on a strong target; a badly hurt
 * battlecruiser jumps home. A player's units cast only on their player's command.
 */
final class AbilityAi {
	private static final int CALM = 200;

	private AbilityAi() {
	}

	static void think(StarUnit unit, ServerLevel level) {
		AbilityState state = unit.abilities;
		if (state.changingMode() || state.channelling() || Ability.of(unit.kind).isEmpty()) return;
		LivingEntity target = unit.getTarget();
		state.calmTicks = target == null ? state.calmTicks + 20 : 0;
		for (Ability ability : Ability.of(unit.kind)) {
			if (Abilities.refusal(unit, ability) != null) continue;
			boolean on = Abilities.on(unit, ability);
			switch (ability) {
				case CLOAK -> {
					if (!on && target != null && unit.distanceTo(target) < 15 && unit.energy() >= 50) Abilities.cast(unit, level, ability, null, null);
					else if (on && state.calmTicks >= CALM) Abilities.cast(unit, level, ability, null, null);
				}
				case SIEGE_MODE -> {
					Modes.Weapon siege = Modes.weapon(unit.kind, Modes.SIEGED);
					if (!on && foeWithin(unit, siege.range() - 1) && !foeWithin(unit, siege.minRange() + 1)) Abilities.cast(unit, level, ability, null, null);
					else if (on && state.calmTicks >= CALM && !foeWithin(unit, siege.range())) Abilities.cast(unit, level, ability, null, null);
				}
				case YAMATO -> {
					if (target != null && target.getMaxHealth() >= 30) Abilities.cast(unit, level, ability, target, null);
				}
				case TACTICAL_JUMP -> {
					if (unit.getHealth() < unit.getMaxHealth() * 0.3f && unit.home() != null && unit.home().distSqr(unit.blockPosition()) > 64 * 64)
						Abilities.cast(unit, level, ability, null, Vec3.atBottomCenterOf(unit.home()));
				}
				default -> {
				}
			}
		}
	}

	private static boolean foeWithin(StarUnit unit, double range) {
		return !unit.level().getEntitiesOfClass(LivingEntity.class, unit.getBoundingBox().inflate(range),
			other -> Faction.hostile(unit, other) && other.distanceTo(unit) <= range
				&& !(other instanceof StarUnit cloaked && !Cloak.seen(cloaked, unit))).isEmpty();
	}
}
