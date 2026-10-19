package dev.starmine.gear;

import dev.starmine.StarMine;
import net.minecraft.core.Direction;
import net.minecraft.core.Position;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * The races' rounds (owner 19.10), shot by dispensers and by the turrets ({@link Turrets}):
 * - bone needles (a bone and a zerg shard: eight) and toxic needles (eight needles, a spider eye, vespene: eight — they
 *   poison a while);
 * - gauss rounds ({@link Guns#GAUSS_AMMO}: also an iron nugget, gunpowder and a mineral shard: eight);
 * - energy charges (a mineral shard, vespene and a protoss shard: eight).
 * A round flies straight and fast ({@link Shot}) and strikes for its damage.
 */
public final class Ammo {
	public static final EntityType<Shot> SHOT = Registry.register(BuiltInRegistries.ENTITY_TYPE, StarMine.id("ammo_shot"),
		EntityType.Builder.<Shot>of(Shot::new, MobCategory.MISC).sized(0.25f, 0.25f).clientTrackingRange(4).updateInterval(10).build("ammo_shot"));
	public static final Round BONE_NEEDLE = round("bone_needle", 4, false);
	public static final Round TOXIC_NEEDLE = round("toxic_needle", 3, true);
	public static final Round ENERGY_CHARGE = round("energy_charge", 5, false);

	private Ammo() {
	}

	private static Round round(String name, float damage, boolean toxic) {
		return Registry.register(BuiltInRegistries.ITEM, StarMine.id(name), new Round(new Item.Properties(), damage, toxic));
	}

	public static void load() {
		for (Item round : new Item[] {BONE_NEEDLE, TOXIC_NEEDLE, ENERGY_CHARGE, Guns.GAUSS_AMMO}) DispenserBlock.registerProjectileBehavior(round);
		net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents.modifyEntriesEvent(net.minecraft.world.item.CreativeModeTabs.COMBAT).register(entries -> {
			entries.accept(BONE_NEEDLE);
			entries.accept(TOXIC_NEEDLE);
			entries.accept(ENERGY_CHARGE);
		});
	}

	/** How hard a round strikes. */
	public static float damage(ItemStack round) {
		if (round.getItem() instanceof Round r) return r.damage;
		return 2;
	}

	/** Whether this is a round a dispenser or a turret fires. */
	public static boolean isRound(ItemStack stack) {
		return stack.getItem() instanceof Round;
	}

	/** A round fired from `from` toward `way` by `owner` (a turret, a player; null for a dispenser). */
	public static Shot fire(Level level, Position from, net.minecraft.world.phys.Vec3 way, ItemStack round, @org.jetbrains.annotations.Nullable LivingEntity owner) {
		Shot shot = new Shot(level, from.x(), from.y(), from.z());
		shot.setItem(round.copyWithCount(1));
		if (owner != null) shot.setOwner(owner);
		shot.shoot(way.x, way.y, way.z, 2.5f, 1.0f);
		level.addFreshEntity(shot);
		return shot;
	}

	/** A round's item: a dispenser fires it as a {@link Shot}. */
	public static class Round extends Item implements ProjectileItem {
		final float damage;
		final boolean toxic;

		Round(Properties properties, float damage, boolean toxic) {
			super(properties);
			this.damage = damage;
			this.toxic = toxic;
		}

		@Override
		public Projectile asProjectile(Level level, Position position, ItemStack stack, Direction direction) {
			Shot shot = new Shot(level, position.x(), position.y(), position.z());
			shot.setItem(stack.copyWithCount(1));
			return shot;
		}

		@Override
		public DispenseConfig createDispenseConfig() {
			return DispenseConfig.builder().power(2.5f).uncertainty(2.0f).build();
		}
	}

	/** A round in flight: straight and fast, it strikes the first creature it meets for its round's damage. */
	public static class Shot extends ThrowableItemProjectile {
		/** Damage beyond its round's (a turret's weapons upgrades). */
		float extra;

		public Shot(EntityType<? extends Shot> type, Level level) {
			super(type, level);
		}

		Shot(Level level, double x, double y, double z) {
			super(SHOT, x, y, z, level);
		}

		@Override
		protected Item getDefaultItem() {
			return BONE_NEEDLE;
		}

		@Override
		protected double getDefaultGravity() {
			return 0.005;
		}

		@Override
		public void tick() {
			super.tick();
			if (tickCount > 100) discard();
		}

		@Override
		protected void onHitEntity(EntityHitResult hit) {
			super.onHitEntity(hit);
			ItemStack round = getItem();
			hit.getEntity().hurt(damageSources().thrown(this, getOwner()), damage(round) + extra);
			if (round.getItem() instanceof Round r && r.toxic && hit.getEntity() instanceof LivingEntity victim)
				victim.addEffect(new MobEffectInstance(MobEffects.POISON, 80, 0), getOwner());
		}

		@Override
		protected void onHit(HitResult hit) {
			super.onHit(hit);
			if (level() instanceof ServerLevel server) {
				server.sendParticles(getItem().is(ENERGY_CHARGE) ? ParticleTypes.ELECTRIC_SPARK : ParticleTypes.CRIT, getX(), getY(), getZ(), 6, 0.1, 0.1, 0.1, 0.05);
				discard();
			}
		}
	}
}
