package dev.starmine.gear;

import dev.starmine.StarMine;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The races' turrets (owner 19.10), set down by a player: each shoots its rounds at the foes within {@link #RANGE}
 * blocks it sees — monsters and the wild — while it has rounds, loaded by hand (right-click with rounds; empty-handed it
 * tells how many it has; sneaking, its owner takes it up with its rounds). A turret is a creature: monsters go for it.
 * - the terran gauss turret (a dispenser, a terran armour slab, redstone, iron) fires gauss rounds;
 * - the zerg needle turret (a dispenser, a zerg shard, bone meal, zerg flesh) fires needles, bone or toxic, and heals on
 *   creep;
 * - the protoss photon turret (a dispenser, a protoss shard, mineral shards, vespene) fires energy charges, and its
 *   shield — its health — comes back when it is left alone.
 * Its owner's base upgrades it (owner 19.10): each weapons level a strike more ({@link Turret#damageBonus}); a race's
 * research, {@link #RANGE_BONUS} blocks of reach ({@link #rangeResearch}: the terrans' Hi-Sec Auto Tracking, the zerg's
 * grooved spines, the protoss' extended thermal lance). In the command view its owner sees with it, finds it on the
 * minimap, selects it and orders it to fire at a foe ({@link Turret#focus}).
 */
public final class Turrets {
	public static final double RANGE = 12;
	public static final int MAX_ROUNDS = 256;
	public static final double RANGE_BONUS = 4;

	/** The research that lengthens a race's turrets' reach. */
	public static dev.starmine.race.Research rangeResearch(UnitKind.Race race) {
		return switch (race) {
			case TERRAN -> dev.starmine.race.Research.HISEC_AUTO_TRACKING;
			case ZERG -> dev.starmine.race.Research.GROOVED_SPINES;
			case PROTOSS -> dev.starmine.race.Research.EXTENDED_THERMAL_LANCE;
		};
	}

	public enum Kind {
		GAUSS(UnitKind.Race.TERRAN, 40, 10), NEEDLE(UnitKind.Race.ZERG, 30, 14), PHOTON(UnitKind.Race.PROTOSS, 30, 16);

		public final UnitKind.Race race;
		public final float health;
		public final int interval;

		Kind(UnitKind.Race race, float health, int interval) {
			this.race = race;
			this.health = health;
			this.interval = interval;
		}

		public String id() {
			return name().toLowerCase(Locale.ROOT) + "_turret";
		}

		/** Whether a turret of this kind fires that round. */
		public boolean takes(ItemStack round) {
			return switch (this) {
				case GAUSS -> round.is(Guns.GAUSS_AMMO);
				case NEEDLE -> round.is(Ammo.BONE_NEEDLE) || round.is(Ammo.TOXIC_NEEDLE);
				case PHOTON -> round.is(Ammo.ENERGY_CHARGE);
			};
		}
	}

	public static final Map<Kind, EntityType<Turret>> TYPES = new EnumMap<>(Kind.class);
	public static final Map<Kind, Item> ITEMS = new EnumMap<>(Kind.class);

	static {
		for (Kind kind : Kind.values()) {
			EntityType<Turret> type = Registry.register(BuiltInRegistries.ENTITY_TYPE, StarMine.id(kind.id()),
				EntityType.Builder.<Turret>of((t, level) -> new Turret(t, level, kind), MobCategory.MISC).sized(0.9f, 1.4f).clientTrackingRange(10).build(kind.id()));
			TYPES.put(kind, type);
			ITEMS.put(kind, Registry.register(BuiltInRegistries.ITEM, StarMine.id(kind.id()), new Placer(kind, new Item.Properties())));
		}
	}

	private Turrets() {
	}

	public static void load() {
		for (Kind kind : Kind.values())
			FabricDefaultAttributeRegistry.register(TYPES.get(kind), Mob.createMobAttributes().add(Attributes.MAX_HEALTH, kind.health)
				.add(Attributes.MOVEMENT_SPEED, 0).add(Attributes.KNOCKBACK_RESISTANCE, 1).add(Attributes.FOLLOW_RANGE, RANGE + 4));
		net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents.modifyEntriesEvent(net.minecraft.world.item.CreativeModeTabs.COMBAT)
			.register(entries -> ITEMS.values().forEach(entries::accept));
	}

	/** A turret's item: used on a block, the turret stands on it, its owner the user. */
	static final class Placer extends Item {
		private final Kind kind;

		Placer(Kind kind, Properties properties) {
			super(properties);
			this.kind = kind;
		}

		@Override
		public InteractionResult useOn(UseOnContext context) {
			if (!(context.getLevel() instanceof ServerLevel level)) return InteractionResult.SUCCESS;
			BlockPos at = context.getClickedPos().relative(context.getClickedFace());
			Turret turret = TYPES.get(kind).create(level);
			if (turret == null) return InteractionResult.FAIL;
			turret.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, context.getRotation(), 0);
			if (!level.noCollision(turret)) return InteractionResult.FAIL;
			if (context.getPlayer() != null) turret.setOwner(context.getPlayer().getUUID());
			level.addFreshEntity(turret);
			level.playSound(null, at, SoundEvents.IRON_GOLEM_REPAIR, SoundSource.BLOCKS, 0.8f, 1.2f);
			context.getItemInHand().shrink(1);
			return InteractionResult.CONSUME;
		}
	}

	/** A turret standing: it turns to its foe and fires. */
	public static class Turret extends Mob {
		private static final EntityDataAccessor<Integer> ROUNDS = SynchedEntityData.defineId(Turret.class, EntityDataSerializers.INT);
		private static final EntityDataAccessor<java.util.Optional<UUID>> OWNER = SynchedEntityData.defineId(Turret.class, EntityDataSerializers.OPTIONAL_UUID);
		public final Kind kind;
		@Nullable UUID owner;
		/** A foe its owner ordered it to fire at (its entity id; -1 none). */
		private int focus = -1;
		/** Its rounds: what it was loaded with (kind of round) and how many. */
		ItemStack round = ItemStack.EMPTY;
		private int cooldown, calm;

		public Turret(EntityType<? extends Turret> type, Level level, Kind kind) {
			super(type, level);
			this.kind = kind;
			setPersistenceRequired();
		}

		@Override
		protected void defineSynchedData(SynchedEntityData.Builder builder) {
			super.defineSynchedData(builder);
			builder.define(ROUNDS, 0);
			builder.define(OWNER, java.util.Optional.empty());
		}

		public int rounds() {
			return entityData.get(ROUNDS);
		}

		void setRounds(int count) {
			entityData.set(ROUNDS, count);
			if (count == 0) round = ItemStack.EMPTY;
		}

		public @Nullable UUID owner() {
			return level().isClientSide ? entityData.get(OWNER).orElse(null) : owner;
		}

		void setOwner(@Nullable UUID owner) {
			this.owner = owner;
			entityData.set(OWNER, java.util.Optional.ofNullable(owner));
		}

		/** Its owner's base here, or null. */
		@Nullable dev.starmine.hive.Hive base() {
			return owner == null || !(level() instanceof ServerLevel server) ? null
				: dev.starmine.hive.HiveSavedData.get(server.getServer()).baseOrHome(owner, server.dimension().location().toString());
		}

		/** How far it reaches: its race's range research adds {@link #RANGE_BONUS}. */
		public double range() {
			dev.starmine.hive.Hive base = base();
			return RANGE + (base != null && base.researched.contains(rangeResearch(kind.race).id()) ? RANGE_BONUS : 0);
		}

		/** A strike more for each weapons level of its owner's base. */
		public float damageBonus() {
			dev.starmine.hive.Hive base = base();
			return base == null ? 0 : dev.starmine.race.Research.level(base, dev.starmine.race.Research.WEAPONS);
		}

		/** Its owner's order: fire at this foe while it is in reach (null: choose its own foes). */
		public void focus(@Nullable LivingEntity foe) {
			focus = foe == null ? -1 : foe.getId();
		}

		@Override
		public void tick() {
			super.tick();
			if (!(level() instanceof ServerLevel level)) return;
			calm++;
			if (tickCount % 20 == 0) heal(level);
			if (--cooldown > 0 || rounds() <= 0) return;
			LivingEntity foe = foe(level);
			if (foe == null) return;
			Vec3 eye = getEyePosition(), at = foe.position().add(0, foe.getBbHeight() * 0.5, 0);
			getLookControl().setLookAt(at.x, at.y, at.z);
			setYRot((float) Math.toDegrees(Math.atan2(at.z - eye.z, at.x - eye.x)) - 90);
			setYHeadRot(getYRot());
			Ammo.Shot shot = Ammo.fire(level, eye.add(at.subtract(eye).normalize().scale(0.8)), at.subtract(eye), round, this);
			shot.extra = damageBonus();
			setRounds(rounds() - 1);
			cooldown = kind.interval;
			level.playSound(null, blockPosition(), kind == Kind.PHOTON ? SoundEvents.BEACON_POWER_SELECT : kind == Kind.NEEDLE ? SoundEvents.LLAMA_SPIT
				: SoundEvents.CROSSBOW_SHOOT, SoundSource.BLOCKS, 0.6f, 1.4f);
		}

		/** Its foe: the nearest monster or wild creature it sees within reach — never a player, never its side. */
		@Nullable LivingEntity foe(ServerLevel level) {
			double range = range();
			if (focus >= 0) { // its owner's order first
				if (level.getEntity(focus) instanceof LivingEntity ordered && ordered.isAlive() && distanceToSqr(ordered) <= range * range && hasLineOfSight(ordered))
					return ordered;
				if (!(level.getEntity(focus) instanceof LivingEntity alive) || !alive.isAlive()) focus = -1;
			}
			LivingEntity best = null;
			double bestDistance = range * range;
			for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(range), e -> e.isAlive() && e != this)) {
				if (e instanceof Player || e instanceof Turret || !(e instanceof Enemy) && !dev.starmine.faction.Faction.amun(e)) continue;
				if (e instanceof StarUnit unit && owner != null && unit.owner().filter(owner::equals).isPresent()) continue;
				double d = distanceToSqr(e);
				if (d < bestDistance && hasLineOfSight(e)) {
					best = e;
					bestDistance = d;
				}
			}
			return best;
		}

		/** The needle turret heals on creep; the photon turret's shield comes back when left alone 10 s. */
		void heal(ServerLevel level) {
			if (getHealth() >= getMaxHealth()) return;
			if (kind == Kind.NEEDLE && dev.starmine.block.StarMineBlocks.isCreep(level.getBlockState(blockPosition().below()))) heal(1);
			if (kind == Kind.NEEDLE && dev.starmine.block.StarMineBlocks.isCreep(level.getBlockState(blockPosition()))) heal(1);
			if (kind == Kind.PHOTON && calm >= 200) {
				heal(2);
				level.sendParticles(ParticleTypes.END_ROD, getX(), getY() + 1, getZ(), 3, 0.3, 0.4, 0.3, 0.01);
			}
		}

		@Override
		public boolean hurt(DamageSource source, float amount) {
			calm = 0;
			return super.hurt(source, amount);
		}

		@Override
		protected InteractionResult mobInteract(Player player, InteractionHand hand) {
			if (level().isClientSide) return InteractionResult.SUCCESS;
			ItemStack held = player.getItemInHand(hand);
			if (kind.takes(held)) { // loaded: rounds of one kind at a time
				if (rounds() > 0 && !ItemStack.isSameItem(round, held)) {
					player.displayClientMessage(Component.translatable("message.starmine.turret_other_rounds"), true);
					return InteractionResult.CONSUME;
				}
				int take = Math.min(held.getCount(), MAX_ROUNDS - rounds());
				if (take > 0) {
					round = held.copyWithCount(1);
					setRounds(rounds() + take);
					if (!player.getAbilities().instabuild) held.shrink(take);
				}
			} else if (held.isEmpty() && player.isShiftKeyDown() && (owner == null || owner.equals(player.getUUID()))) { // taken up
				spawnAtLocation(new ItemStack(ITEMS.get(kind)));
				dropRounds();
				discard();
				return InteractionResult.CONSUME;
			}
			player.displayClientMessage(Component.translatable("message.starmine.turret_rounds", rounds(), MAX_ROUNDS), true);
			return InteractionResult.CONSUME;
		}

		void dropRounds() {
			int left = rounds();
			while (left > 0 && !round.isEmpty()) {
				int n = Math.min(left, round.getMaxStackSize());
				spawnAtLocation(round.copyWithCount(n));
				left -= n;
			}
			setRounds(0);
		}

		@Override
		public void die(DamageSource source) {
			if (level() instanceof ServerLevel) dropRounds();
			super.die(source);
		}

		@Override
		public boolean isPushable() {
			return false;
		}

		@Override
		protected void doPush(Entity entity) {
		}

		@Override
		public boolean removeWhenFarAway(double distance) {
			return false;
		}

		@Override
		public void addAdditionalSaveData(CompoundTag tag) {
			super.addAdditionalSaveData(tag);
			if (owner != null) tag.putUUID("StarMineOwner", owner);
			tag.putInt("StarMineRounds", rounds());
			if (!round.isEmpty()) tag.put("StarMineRound", round.save(registryAccess()));
		}

		@Override
		public void readAdditionalSaveData(CompoundTag tag) {
			super.readAdditionalSaveData(tag);
			setOwner(tag.hasUUID("StarMineOwner") ? tag.getUUID("StarMineOwner") : null);
			round = tag.contains("StarMineRound") ? ItemStack.parseOptional(registryAccess(), tag.getCompound("StarMineRound")) : ItemStack.EMPTY;
			setRounds(round.isEmpty() ? 0 : tag.getInt("StarMineRounds"));
		}
	}
}
