package dev.starmine.block;

import dev.starmine.ability.Status;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import net.minecraft.core.BlockPos;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.phys.Vec3;

/**
 * Liquid creep (owner 16.10), the fill of the nydus tunnels. A zerg in it rushes ({@link Status#CREEP_RUSH}:
 * four times as fast), heals, climbs out of shafts, and passes through other creatures
 * ({@link StarUnit#inLiquidCreep}); anyone else is poisoned.
 */
public final class LiquidCreepBlock extends LiquidBlock {
	/** Ticks the rush lasts after a zerg leaves the creep; a zerg heals this share of its health a second in it. */
	public static final int RUSH_TICKS = 10;
	public static final float HEAL_SHARE = 0.05f;

	public LiquidCreepBlock(FlowingFluid fluid, Properties properties) {
		super(fluid, properties);
	}

	@Override
	protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
		if (level.isClientSide || !(entity instanceof LivingEntity living) || !living.isAlive()) return;
		if (zerg(living)) {
			StarUnit unit = (StarUnit) living;
			Status.apply(unit, Status.CREEP_RUSH, RUSH_TICKS);
			unit.markInLiquidCreep();
			if (unit.tickCount % 20 == 0 && unit.getHealth() < unit.getMaxHealth()) unit.heal(unit.getMaxHealth() * HEAL_SHARE);
			if (unit.horizontalCollision) { // up the shafts
				Vec3 motion = unit.getDeltaMovement();
				unit.setDeltaMovement(motion.x, Math.max(motion.y, 0.3), motion.z);
			}
			return;
		}
		if (living instanceof Player player && (player.isCreative() || player.isSpectator())) return;
		if (!living.hasEffect(MobEffects.POISON)) living.addEffect(new MobEffectInstance(MobEffects.POISON, 80, 1));
	}

	/** A zerg unit that is not a building. */
	public static boolean zerg(Entity entity) {
		return entity instanceof StarUnit unit && unit.kind.race == UnitKind.Race.ZERG && !unit.kind.structure();
	}
}
