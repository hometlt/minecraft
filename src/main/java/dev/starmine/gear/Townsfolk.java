package dev.starmine.gear;

import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import java.util.EnumSet;
import java.util.List;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Armed villagers (owner 16.10), met in villages: the marshal with a gauss rifle and the ghost in a ghost
 * suit with a canister rifle, who goes invisible while a foe is near. They shoot monsters and the wild
 * zerg, and trade like wandering traders, for emeralds: ammunition, guns, terran armor shards and
 * pieces, a stimpack module; they never leave. The sheriff (owner 19.10) keeps a stone house in a large
 * village ({@link dev.starmine.world.SheriffHouses}) and stays by it: a gauss rifle too, and a few wares
 * of his own picked at random ({@link #SHERIFF_WARES}) for minerals and vespene.
 */
public class Townsfolk extends WanderingTrader {
	public enum Role { MARSHAL, GHOST, SHERIFF }

	/** How far from his house the sheriff walks. */
	public static final int SHERIFF_BEAT = 14;
	/** How many of {@link #SHERIFF_WARES} a sheriff sells. */
	public static final int SHERIFF_OFFERS = 6;

	public final Role role;

	public Townsfolk(EntityType<? extends WanderingTrader> type, Level level, Role role) {
		super(type, level);
		this.role = role;
		this.setDespawnDelay(0); // stays in its village
		this.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(role == Role.GHOST ? Guns.CANISTER_RIFLE : Guns.GAUSS_RIFLE));
		this.setDropChance(EquipmentSlot.MAINHAND, 0);
	}

	@Override
	protected void registerGoals() {
		super.registerGoals();
		this.goalSelector.addGoal(1, new Marksman(this));
		if (role == Role.SHERIFF) this.goalSelector.addGoal(3, new net.minecraft.world.entity.ai.goal.MoveTowardsRestrictionGoal(this, 0.5));
	}

	/** The sheriff's house: he keeps within {@link #SHERIFF_BEAT} of it. */
	public void keepHome(net.minecraft.core.BlockPos home) {
		this.restrictTo(home, SHERIFF_BEAT);
		this.setPersistenceRequired();
	}

	@Override
	public boolean removeWhenFarAway(double distance) {
		return false;
	}

	/** A sheriff's possible wares: what he sells, for how many mineral shards, and whether a bucket of vespene too. */
	public record Ware(java.util.function.Supplier<ItemStack> goods, int minerals, boolean vespene) {
	}

	public static final List<Ware> SHERIFF_WARES = List.of(
		new Ware(() -> new ItemStack(Guns.GAUSS_AMMO, 16), 4, false),
		new Ware(() -> new ItemStack(Guns.CANISTER_ROUND, 8), 6, true),
		new Ware(() -> new ItemStack(Guns.PUNISHER_GRENADE, 8), 8, true),
		new Ware(() -> new ItemStack(Guns.KD8_CHARGE, 4), 6, false),
		new Ware(() -> new ItemStack(dev.starmine.suit.SuitItems.SHARDS.get(UnitKind.Race.TERRAN), 2), 6, false),
		new Ware(() -> new ItemStack(dev.starmine.suit.SuitItems.piece(UnitKind.Race.TERRAN, net.minecraft.world.item.ArmorItem.Type.HELMET)), 20, false),
		new Ware(() -> new ItemStack(dev.starmine.suit.SuitItems.piece(UnitKind.Race.TERRAN, net.minecraft.world.item.ArmorItem.Type.CHESTPLATE)), 40, true),
		new Ware(() -> new ItemStack(dev.starmine.suit.SuitItems.piece(UnitKind.Race.TERRAN, net.minecraft.world.item.ArmorItem.Type.LEGGINGS)), 30, true),
		new Ware(() -> new ItemStack(dev.starmine.suit.SuitItems.piece(UnitKind.Race.TERRAN, net.minecraft.world.item.ArmorItem.Type.BOOTS)), 18, false),
		new Ware(() -> new ItemStack(dev.starmine.possess.ModuleItem.of(dev.starmine.ability.Ability.STIMPACK)), 25, true),
		new Ware(() -> new ItemStack(Guns.GAUSS_RIFLE), 30, false),
		new Ware(() -> new ItemStack(Guns.GRENADE_LAUNCHER), 30, true),
		new Ware(() -> new ItemStack(Guns.REAPER_PISTOLS), 25, true));

	@Override
	protected void updateTrades() {
		MerchantOffers offers = this.getOffers();
		if (role == Role.SHERIFF) { // a few of his wares, his own pick (the trader's random: another sheriff, other wares)
			List<Ware> wares = new java.util.ArrayList<>(SHERIFF_WARES);
			net.minecraft.Util.shuffle(wares, this.random);
			for (Ware ware : wares.subList(0, SHERIFF_OFFERS))
				offers.add(new MerchantOffer(new ItemCost(dev.starmine.mineral.Minerals.SHARD, ware.minerals()),
					ware.vespene() ? java.util.Optional.of(new ItemCost(dev.starmine.vespene.Vespene.BUCKET, 1)) : java.util.Optional.empty(),
					ware.goods().get(), 8, 2, 0.05f));
			return;
		}
		offers.add(offer(1, new ItemStack(Guns.GAUSS_AMMO, 16)));
		offers.add(offer(8, new ItemStack(Guns.GAUSS_RIFLE)));
		offers.add(offer(3, new ItemStack(dev.starmine.suit.SuitItems.SHARDS.get(UnitKind.Race.TERRAN), 2)));
		offers.add(offer(12, new ItemStack(dev.starmine.possess.ModuleItem.of(dev.starmine.ability.Ability.STIMPACK))));
		if (role == Role.GHOST) {
			offers.add(offer(2, new ItemStack(Guns.CANISTER_ROUND, 8)));
			offers.add(offer(18, new ItemStack(Guns.CANISTER_RIFLE)));
			offers.add(offer(10, new ItemStack(dev.starmine.possess.ModuleItem.of(dev.starmine.ability.Ability.CLOAK))));
		} else {
			offers.add(offer(14, new ItemStack(Items.IRON_CHESTPLATE)));
			offers.add(offer(2, new ItemStack(Guns.PUNISHER_GRENADE, 8)));
			offers.add(offer(14, new ItemStack(Guns.GRENADE_LAUNCHER)));
			offers.add(offer(10, new ItemStack(Guns.REAPER_PISTOLS)));
			offers.add(offer(3, new ItemStack(Guns.KD8_CHARGE, 4)));
		}
	}

	private static MerchantOffer offer(int emeralds, ItemStack result) {
		return new MerchantOffer(new ItemCost(Items.EMERALD, emeralds), result, 12, 2, 0.05f);
	}

	@Override
	public void addAdditionalSaveData(CompoundTag tag) {
		super.addAdditionalSaveData(tag);
		tag.putString("StarMineRole", role.name());
		if (this.hasRestriction()) tag.putLong("StarMineHome", this.getRestrictCenter().asLong());
	}

	@Override
	public void readAdditionalSaveData(CompoundTag tag) {
		super.readAdditionalSaveData(tag);
		if (tag.contains("StarMineHome")) keepHome(net.minecraft.core.BlockPos.of(tag.getLong("StarMineHome")));
	}

	/** Shoots monsters and the wild zerg near it; the ghost cloaks while a foe is near. */
	static final class Marksman extends Goal {
		private final Townsfolk folk;
		private @Nullable LivingEntity target;
		private int reload;

		Marksman(Townsfolk folk) {
			this.folk = folk;
			this.setFlags(EnumSet.of(Flag.LOOK));
		}

		@Override
		public boolean canUse() {
			if (folk.tickCount % 10 != 0 && target == null) return false;
			target = foe();
			if (target == null && folk.role == Role.GHOST && folk.isInvisible()) folk.setInvisible(false);
			return target != null;
		}

		@Override
		public boolean canContinueToUse() {
			return target != null && target.isAlive() && folk.distanceToSqr(target) < range() * range();
		}

		@Override
		public void stop() {
			target = null;
			if (folk.role == Role.GHOST) folk.setInvisible(false);
		}

		@Override
		public boolean requiresUpdateEveryTick() {
			return true;
		}

		private double range() {
			return folk.role == Role.GHOST ? 40 : 24; // the marshal and the sheriff: a gauss rifle
		}

		private @Nullable LivingEntity foe() {
			double r = range();
			List<LivingEntity> foes = folk.level().getEntitiesOfClass(LivingEntity.class, new AABB(folk.blockPosition()).inflate(r),
				other -> other.isAlive() && (other instanceof Enemy && !(other instanceof StarUnit)
					|| other instanceof StarUnit unit && unit.owner().isEmpty() && unit.kind.race == UnitKind.Race.ZERG && !unit.kind.structure())
					&& folk.hasLineOfSight(other));
			return foes.stream().min(java.util.Comparator.comparingDouble(folk::distanceToSqr)).orElse(null);
		}

		@Override
		public void tick() {
			if (target == null || !(folk.level() instanceof ServerLevel level)) return;
			folk.getLookControl().setLookAt(target, 30, 30);
			if (folk.role == Role.GHOST && !folk.isInvisible()) {
				folk.setInvisible(true);
				level.sendParticles(ParticleTypes.SMOKE, folk.getX(), folk.getY() + 1, folk.getZ(), 10, 0.3, 0.6, 0.3, 0.02);
			}
			if (--reload > 0) return;
			boolean ghost = folk.role == Role.GHOST;
			reload = ghost ? 40 : 14;
			Vec3 eye = folk.getEyePosition(), aim = target.position().add(0, target.getBbHeight() * 0.6, 0).subtract(eye).normalize();
			GunItem.shoot(level, folk, eye, aim, ghost ? 14 : 4, range());
		}
	}
}
