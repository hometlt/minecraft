package dev.starmine.gear;

import java.util.List;
import java.util.function.Supplier;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A terran gun for players (owner 16.10): the gauss rifle and the canister (sniper) rifle. Use fires at
 * what the crosshair is on, a round of its own ammunition a shot (none needed in creative), at its own
 * pace; a hit hurts the first creature on the line, blocks stop it. Wears out like a tool.
 */
public final class GunItem extends Item {
	public final Supplier<Item> ammo;
	public final float damage;
	public final double range;
	public final int cooldown;
	/** Shots a use fires (the reaper's pistols: two) and SC2 bonus damage against light targets (the ghost's rifle). */
	public final int shots;
	public final float lightBonus;

	public GunItem(Properties properties, Supplier<Item> ammo, float damage, double range, int cooldown) {
		this(properties, ammo, damage, range, cooldown, 1, 0);
	}

	public GunItem(Properties properties, Supplier<Item> ammo, float damage, double range, int cooldown, int shots, float lightBonus) {
		super(properties);
		this.ammo = ammo;
		this.damage = damage;
		this.range = range;
		this.cooldown = cooldown;
		this.shots = shots;
		this.lightBonus = lightBonus;
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		ItemStack gun = player.getItemInHand(hand);
		if (level.isClientSide) return InteractionResultHolder.success(gun);
		if (!player.getAbilities().instabuild && !takeRound(player)) {
			player.displayClientMessage(Component.translatable("message.starmine.no_ammo", Component.translatable(ammo.get().getDescriptionId())), true);
			level.playSound(null, player.blockPosition(), SoundEvents.DISPENSER_FAIL, SoundSource.PLAYERS, 0.6f, 1.4f);
			return InteractionResultHolder.fail(gun);
		}
		for (int i = 0; i < shots; i++) { // the pistols: a shot from each hand, a little apart
			net.minecraft.world.phys.Vec3 eye = player.getEyePosition();
			if (shots > 1) eye = eye.add(player.getLookAngle().cross(new net.minecraft.world.phys.Vec3(0, 1, 0)).normalize().scale(i == 0 ? -0.3 : 0.3));
			shoot((ServerLevel) level, player, eye, player.getLookAngle(), damage, range, lightBonus);
		}
		player.getCooldowns().addCooldown(this, cooldown);
		gun.hurtAndBreak(1, player, hand == InteractionHand.MAIN_HAND ? net.minecraft.world.entity.EquipmentSlot.MAINHAND
			: net.minecraft.world.entity.EquipmentSlot.OFFHAND);
		return InteractionResultHolder.consume(gun);
	}

	private boolean takeRound(Player player) {
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			ItemStack stack = player.getInventory().getItem(i);
			if (stack.is(ammo.get())) {
				stack.shrink(1);
				return true;
			}
		}
		return false;
	}

	/** A shot from `eye` along `look`: the first creature on the line within `range` (not behind a block) is hurt. */
	public static void shoot(ServerLevel level, LivingEntity shooter, Vec3 eye, Vec3 look, float damage, double range) {
		shoot(level, shooter, eye, look, damage, range, 0);
	}

	/** {@link #shoot}, with SC2 bonus damage against light targets. */
	public static void shoot(ServerLevel level, LivingEntity shooter, Vec3 eye, Vec3 look, float damage, double range, float lightBonus) {
		Vec3 end = eye.add(look.scale(range));
		HitResult block = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, shooter));
		Vec3 stop = block.getType() == HitResult.Type.MISS ? end : block.getLocation();
		EntityHitResult hit = ProjectileUtil.getEntityHitResult(level, shooter, eye, stop, new AABB(eye, stop).inflate(1),
			entity -> entity instanceof LivingEntity living && living.isAlive() && entity != shooter && !entity.isSpectator(), 0.3f);
		if (hit != null) {
			stop = hit.getLocation();
			Entity target = hit.getEntity();
			float bonus = target instanceof net.minecraft.world.entity.LivingEntity living && dev.starmine.entity.Trait.has(living, dev.starmine.entity.Trait.LIGHT) ? lightBonus : 0;
			target.hurt(shooter instanceof Player player ? level.damageSources().playerAttack(player) : level.damageSources().mobAttack(shooter), damage + bonus);
		}
		int steps = (int) Math.min(40, eye.distanceTo(stop) * 2);
		for (int i = 2; i <= steps; i++) {
			Vec3 at = eye.lerp(stop, i / (double) steps).add(0, -0.15, 0);
			level.sendParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z, 1, 0, 0, 0, 0);
		}
		level.sendParticles(ParticleTypes.SMOKE, stop.x, stop.y, stop.z, 3, 0.05, 0.05, 0.05, 0.01);
		level.playSound(null, shooter.getX(), shooter.getY(), shooter.getZ(), dev.starmine.StarMine.sounds(dev.starmine.entity.UnitKind.MARINE).fire(),
			SoundSource.PLAYERS, 0.8f, damage > 10 ? 0.7f : 1.1f);
		level.playSound(null, shooter.getX(), shooter.getY(), shooter.getZ(), SoundEvents.CROSSBOW_SHOOT, SoundSource.PLAYERS, 0.5f, damage > 10 ? 0.6f : 1.6f);
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
		lines.add(Component.translatable("item.starmine.gun.tip", Math.round(damage), Math.round(range),
			Component.translatable(ammo.get().getDescriptionId())).withColor(0xFFB0B0B0));
	}
}
