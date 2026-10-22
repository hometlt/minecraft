package dev.starmine.ability;

import dev.starmine.entity.StarUnit;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

/** A blind strike or digging at occupied ground can hurt a burrowed unit without revealing it. */
public final class BurrowDamage {
	private BurrowDamage() {}

	public static boolean strike(LivingEntity attacker, BlockPos ground) {
		if (!(attacker.level() instanceof ServerLevel level)) return false;
		boolean hit = false;
		for (StarUnit unit : level.getEntitiesOfClass(StarUnit.class, new AABB(ground).inflate(0, 1, 0),
			u -> u.isAlive() && u.mode() == Modes.BURROWED && !dev.starmine.faction.Faction.allied(attacker, u))) {
			float damage = (float) (attacker instanceof StarUnit star ? star.weapon().damage() + star.attackBonus()
				: attacker.getAttributeValue(Attributes.ATTACK_DAMAGE));
			if (damage <= 0) continue;
			if (attacker instanceof Player player) unit.hurt(level.damageSources().playerAttack(player), damage);
			else unit.hurt(level.damageSources().mobAttack(attacker), damage);
			hit = true;
		}
		return hit;
	}

	public static void load() {
		AttackBlockCallback.EVENT.register((player, level, hand, pos, face) -> {
			if (!player.isSpectator() && player.getAttackStrengthScale(0) >= .9f && strike(player, pos)) player.resetAttackStrengthTicker();
			return InteractionResult.PASS;
		});
	}
}
