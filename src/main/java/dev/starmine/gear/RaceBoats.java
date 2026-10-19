package dev.starmine.gear;

import dev.starmine.StarMine;
import dev.starmine.entity.UnitKind;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The races' boats (owner 19.10), of five of the race's armour shards; none breaks on a fall or a knock:
 * - the zerg boat: living, it heals its hurts ({@link #ZERG_HEAL} a second); with the jump key it dives for
 *   {@link #MANEUVER_TICKS} ({@link #DIVE_DEPTH} under the surface), closed over its riders, who breathe and are
 *   spared what strikes them while it lasts — the boat takes it;
 * - the terran boat: armoured, it takes half the harm; a cabin: its riders are spared while it stands — it takes the
 *   harm meant for them;
 * - the protoss boat: a little faster ({@link #PROTOSS_PUSH}), and a shield takes its first {@link #SHIELD} of harm,
 *   coming back {@link #SHIELD_TICKS} after the last; with the jump key it rises over the water ({@link #HOVER_HEIGHT})
 *   for {@link #MANEUVER_TICKS}.
 * After a dive or a flight the boat rests {@link #MANEUVER_REST} before the next.
 */
public final class RaceBoats {
	/** Zerg healing a second and the protoss shield, in vanilla's boat units (a hit counts tenfold; over 40 it breaks). */
	public static final float ZERG_HEAL = 2, SHIELD = 3;
	public static final double PROTOSS_PUSH = 0.008;
	public static final int SHIELD_TICKS = 200, MANEUVER_TICKS = 160, MANEUVER_REST = 200;
	public static final double DIVE_DEPTH = 2.5, HOVER_HEIGHT = 2.5;
	/** A boat's maneuver: none, rising over the water (protoss), under it (zerg). */
	public static final int NONE = 0, HOVER = 1, DIVE = 2;
	public static final Map<UnitKind.Race, EntityType<RaceBoat>> TYPES = new EnumMap<>(UnitKind.Race.class);
	public static final Map<UnitKind.Race, Item> ITEMS = new EnumMap<>(UnitKind.Race.class);

	static {
		for (UnitKind.Race race : UnitKind.Race.values()) {
			String name = race.name().toLowerCase(Locale.ROOT) + "_boat";
			TYPES.put(race, Registry.register(BuiltInRegistries.ENTITY_TYPE, StarMine.id(name),
				EntityType.Builder.<RaceBoat>of((type, level) -> new RaceBoat(type, level, race), MobCategory.MISC)
					.sized(1.375f, 0.5625f).eyeHeight(0.5625f).clientTrackingRange(10).build(name)));
			ITEMS.put(race, Registry.register(BuiltInRegistries.ITEM, StarMine.id(name), new BoatItem(race, new Item.Properties().stacksTo(1))));
		}
	}

	private RaceBoats() {
	}

	public static void load() {
		// the terran cabin, a diving zerg boat: what strikes a rider strikes the boat
		net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DAMAGE.register((victim, source, amount) -> {
			if (!(victim.getVehicle() instanceof RaceBoat boat) || !boat.shelters() || source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)) return true;
			if (source.getEntity() != boat && source.getDirectEntity() != boat) boat.hurt(source, amount);
			return false;
		});
		net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents.modifyEntriesEvent(net.minecraft.world.item.CreativeModeTabs.TOOLS_AND_UTILITIES)
			.register(entries -> ITEMS.values().forEach(entries::accept));
	}

	/** A race's boat on the water. */
	public static class RaceBoat extends Boat implements net.minecraft.world.entity.PlayerRideableJumping {
		private static final net.minecraft.network.syncher.EntityDataAccessor<Integer> MANEUVER = net.minecraft.network.syncher.SynchedEntityData.defineId(
			RaceBoat.class, net.minecraft.network.syncher.EntityDataSerializers.INT);
		public final UnitKind.Race race;
		private float shield = SHIELD;
		private long lastHurt;
		private int maneuverLeft, rest;
		/** The water's surface where the maneuver began. */
		private double surface;

		public RaceBoat(EntityType<? extends Boat> type, Level level, UnitKind.Race race) {
			super(type, level);
			this.race = race;
		}

		@Override
		protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
			super.defineSynchedData(builder);
			builder.define(MANEUVER, NONE);
		}

		/** Its maneuver now: {@link #NONE}, {@link #HOVER}, {@link #DIVE}. */
		public int maneuver() {
			return entityData.get(MANEUVER);
		}

		/** Whether its riders are spared what strikes them: a terran cabin; a zerg boat under water. */
		public boolean shelters() {
			return isAlive() && (race == UnitKind.Race.TERRAN || race == UnitKind.Race.ZERG && maneuver() == DIVE);
		}

		/** The jump key (vanilla's riding jump): the protoss boat rises, the zerg one dives. */
		public void startManeuver() {
			if (race == UnitKind.Race.TERRAN || maneuver() != NONE || rest > 0) return;
			surface = getY();
			maneuverLeft = MANEUVER_TICKS;
			entityData.set(MANEUVER, race == UnitKind.Race.PROTOSS ? HOVER : DIVE);
			level().playSound(null, blockPosition(), race == UnitKind.Race.PROTOSS ? net.minecraft.sounds.SoundEvents.BEACON_ACTIVATE
				: net.minecraft.sounds.SoundEvents.PLAYER_SPLASH_HIGH_SPEED, net.minecraft.sounds.SoundSource.NEUTRAL, 1.0f, 1.2f);
		}

		@Override
		public void onPlayerJump(int power) {
		}

		@Override
		public boolean canJump() {
			return race != UnitKind.Race.TERRAN && maneuver() == NONE;
		}

		@Override
		public void handleStartJump(int power) {
			startManeuver();
		}

		@Override
		public void handleStopJump() {
		}

		/** A diving boat keeps its riders (vanilla throws them out of a sunk boat). */
		@Override
		public void ejectPassengers() {
			if (maneuver() == DIVE && isAlive() && !isRemoved() && getDamage() < 40) return;
			super.ejectPassengers();
		}

		@Override
		public Item getDropItem() {
			return ITEMS.get(race);
		}

		@Override
		public void tick() {
			super.tick();
			if (race == UnitKind.Race.PROTOSS && getControllingPassenger() != null && (getPaddleState(0) || getPaddleState(1)) && isInWater()) {
				float yaw = getYRot() * ((float) Math.PI / 180f); // a push on: a little faster than wood
				setDeltaMovement(getDeltaMovement().add(-Math.sin(yaw) * PROTOSS_PUSH, 0, Math.cos(yaw) * PROTOSS_PUSH));
			}
			int maneuver = maneuver();
			if (maneuver != NONE) { // both sides: the steering player's client moves the boat
				if (surface == 0) surface = getY();
				double target = maneuver == HOVER ? surface + HOVER_HEIGHT : surface - DIVE_DEPTH;
				Vec3 v = getDeltaMovement();
				setDeltaMovement(v.x, Math.max(-0.2, Math.min(0.2, (target - getY()) * 0.2)), v.z);
				if (level().isClientSide && tickCount % 3 == 0)
					level().addParticle(maneuver == HOVER ? ParticleTypes.END_ROD : ParticleTypes.BUBBLE, getRandomX(0.8), getY(), getRandomZ(0.8), 0, maneuver == HOVER ? -0.05 : 0.05, 0);
			} else surface = 0; // the next maneuver starts from where the boat is then
			if (!(level() instanceof ServerLevel server)) return;
			if (maneuver != NONE && --maneuverLeft <= 0) {
				entityData.set(MANEUVER, NONE);
				rest = MANEUVER_REST;
				surface = 0;
			} else if (maneuver == NONE && rest > 0) rest--;
			if (maneuver == DIVE) for (net.minecraft.world.entity.Entity rider : getPassengers()) // they breathe inside
				if (rider instanceof net.minecraft.world.entity.LivingEntity living)
					living.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.WATER_BREATHING, 40, 0, true, false));
			if (tickCount % 20 != 0) return;
			if (race == UnitKind.Race.ZERG && getDamage() > 0) setDamage(Math.max(0, getDamage() - ZERG_HEAL));
			if (race == UnitKind.Race.PROTOSS && shield < SHIELD && server.getGameTime() - lastHurt >= SHIELD_TICKS) shield = SHIELD;
		}

		@Override
		public boolean hurt(DamageSource source, float amount) {
			if (level().isClientSide || isRemoved() || isInvulnerableTo(source)) return super.hurt(source, amount);
			lastHurt = level().getGameTime();
			if (race == UnitKind.Race.TERRAN) amount /= 2;
			if (race == UnitKind.Race.PROTOSS && shield > 0 && !(source.getEntity() instanceof Player p && p.getAbilities().instabuild)) {
				float taken = Math.min(shield, amount);
				shield -= taken;
				amount -= taken;
				((ServerLevel) level()).sendParticles(ParticleTypes.ELECTRIC_SPARK, getX(), getY() + 0.5, getZ(), 8, 0.5, 0.3, 0.5, 0.05);
				if (amount <= 0) return true;
			}
			return super.hurt(source, amount);
		}

		/** No race's boat breaks on a fall: it lands. */
		@Override
		protected void checkFallDamage(double y, boolean onGround, BlockState state, BlockPos pos) {
			if (onGround) resetFallDistance();
		}
	}

	/** A race's boat's item: set on water (or ground) where one looks. */
	static final class BoatItem extends Item {
		private final UnitKind.Race race;

		BoatItem(UnitKind.Race race, Properties properties) {
			super(properties);
			this.race = race;
		}

		@Override
		public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
			ItemStack stack = player.getItemInHand(hand);
			BlockHitResult hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.ANY);
			if (hit.getType() != HitResult.Type.BLOCK) return InteractionResultHolder.pass(stack);
			Vec3 at = hit.getLocation();
			RaceBoat boat = TYPES.get(race).create(level);
			if (boat == null) return InteractionResultHolder.fail(stack);
			boat.moveTo(at.x, at.y, at.z, player.getYRot(), 0);
			if (!level.noCollision(boat, boat.getBoundingBox())) return InteractionResultHolder.fail(stack);
			if (!level.isClientSide) {
				level.addFreshEntity(boat);
				stack.consume(1, player);
			}
			return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
		}
	}
}
