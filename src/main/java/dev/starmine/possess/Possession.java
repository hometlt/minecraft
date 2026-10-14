package dev.starmine.possess;

import dev.starmine.StarMine;
import dev.starmine.ability.Abilities;
import dev.starmine.ability.Ability;
import dev.starmine.ability.Modes;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.faction.Faction;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveSavedData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Playing as a unit (owner 14.10): from the command view a player takes control of one of their combat
 * units and fights as it.
 *
 * - The unit leaves the world (still counted in its base) and its data stays with the player
 *   ({@link StarUnit#pilot}); the player's body goes to its place and becomes it: its size
 *   ({@link Pilots#dimensions}), health, shields (as absorption, coming back after
 *   {@link StarUnit#SHIELD_DELAY}), speed, melee damage and reach, step height; flying units fly.
 *   The player is drawn as the unit (client).
 * - The inventory is the unit's: its modules ({@link ModuleItem}) — weapon, abilities, upgrades —
 *   built in: they cannot be dropped, stored or taken out; containers and trades are closed to a unit.
 *   What the player was (inventory, place, health, food, game mode, attributes) is kept in
 *   {@link PossessionData} and given back at the end; what the unit picked up goes to the player.
 * - Energy, cooldowns, channels and modes are the unit's own ({@link Abilities} ticks it here).
 * - It ends with the leave key (the unit comes back to the world where the player stands, with the
 *   health and shields it has) or with the unit's death (the player does not die: the unit is lost to
 *   its base and the player is back where they were). Either way the client goes back to the command
 *   view. Logging out keeps it: the player comes back still in the unit.
 */
public final class Possession {
	/** The attributes a unit's body sets on the player (their own values are kept to give back). */
	private static final List<Holder<Attribute>> BODY = List.of(Attributes.MAX_HEALTH, Attributes.MOVEMENT_SPEED, Attributes.ATTACK_DAMAGE,
		Attributes.ATTACK_SPEED, Attributes.ARMOR, Attributes.ENTITY_INTERACTION_RANGE, Attributes.STEP_HEIGHT, Attributes.JUMP_STRENGTH,
		Attributes.MAX_ABSORPTION, Attributes.SAFE_FALL_DISTANCE, Attributes.KNOCKBACK_RESISTANCE);
	private static final ResourceLocation UPGRADE = StarMine.id("pilot_upgrade");
	/** Player speed per mob speed: a marine (0.274) walks like a player (0.1). */
	private static final double SPEED_FACTOR = 0.1 / (3.15 * UnitKind.SPEED_SCALE);
	private static final Map<UUID, StarUnit> PUPPETS = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> LAST_HURT = new ConcurrentHashMap<>();

	private Possession() {
	}

	public static boolean piloting(ServerPlayer player) {
		return PUPPETS.containsKey(player.getUUID());
	}

	/** The unit a player plays as (out of the world), or null. */
	public static @Nullable StarUnit puppet(ServerPlayer player) {
		return PUPPETS.get(player.getUUID());
	}

	/** The unit with this UUID a player plays as now, or null (an interceptor looks for its carrier). */
	public static @Nullable StarUnit puppetById(MinecraftServer server, UUID unit) {
		for (StarUnit puppet : PUPPETS.values()) if (puppet.getUUID().equals(unit)) return puppet;
		return null;
	}

	// ---- start and end ----

	/** Why `player` cannot take `unit` (a lang key), or null. */
	public static @Nullable String refusal(ServerPlayer player, StarUnit unit) {
		if (piloting(player)) return "message.starmine.already_piloting";
		if (!unit.isAlive() || unit.kind.structure() || unit.kind == UnitKind.LARVA || unit.kind == UnitKind.EGG || unit.pilot() != null
			|| unit.level() != player.level() || !player.getUUID().equals(unit.owner().orElse(null))) return "message.starmine.cannot_pilot";
		return null;
	}

	public static boolean start(ServerPlayer player, StarUnit unit) {
		String refusal = refusal(player, unit);
		if (refusal != null) {
			player.displayClientMessage(Component.translatable(refusal), true);
			return false;
		}
		ServerLevel level = player.serverLevel();
		CompoundTag session = new CompoundTag();
		if (unit.isPassenger()) unit.stopRiding();
		unit.ejectPassengers();
		session.put("Unit", tagOf(unit));
		session.put("Inventory", player.getInventory().save(new ListTag()));
		session.putDouble("X", player.getX());
		session.putDouble("Y", player.getY());
		session.putDouble("Z", player.getZ());
		session.putFloat("Yaw", player.getYRot());
		session.putFloat("Pitch", player.getXRot());
		session.putString("Dim", level.dimension().location().toString());
		session.putFloat("Health", player.getHealth());
		session.putInt("Food", player.getFoodData().getFoodLevel());
		session.putFloat("Saturation", player.getFoodData().getSaturationLevel());
		session.putInt("GameMode", player.gameMode.getGameModeForPlayer().getId());
		CompoundTag attributes = new CompoundTag();
		for (Holder<Attribute> attribute : BODY) {
			AttributeInstance instance = player.getAttribute(attribute);
			if (instance != null) attributes.putDouble(attribute.unwrapKey().orElseThrow().location().toString(), instance.getBaseValue());
		}
		session.put("Attributes", attributes);
		PossessionData data = PossessionData.get(level.getServer());
		data.sessions.put(player.getUUID(), session);
		data.setDirty();

		Vec3 at = unit.position();
		float yaw = unit.getYRot();
		unit.remove(Entity.RemovalReason.UNLOADED_WITH_PLAYER); // out of the world, still a member of its base
		StarUnit puppet = puppetFrom(session.getCompound("Unit"), level);
		if (puppet == null) { // should not happen: undo
			data.sessions.remove(player.getUUID());
			return false;
		}
		puppet.setPilot(player);
		PUPPETS.put(player.getUUID(), puppet);
		if (player.isPassenger()) player.stopRiding();
		GameType mode = player.gameMode.getGameModeForPlayer();
		if (mode != GameType.SURVIVAL && mode != GameType.ADVENTURE) player.setGameMode(GameType.SURVIVAL);
		player.teleportTo(level, at.x, at.y, at.z, yaw, player.getXRot());
		becomeUnit(player, puppet);
		Pilots.set(false, player.getUUID(), puppet.kind);
		player.refreshDimensions();
		PossessNetwork.sendState(player);
		return true;
	}

	/** Ends playing as the unit: it comes back where the player stands (left) or is lost (died); the player is themselves again. */
	public static void end(ServerPlayer player, boolean died) {
		MinecraftServer server = player.getServer();
		if (server == null) return;
		PossessionData data = PossessionData.get(server);
		CompoundTag session = data.sessions.remove(player.getUUID());
		StarUnit puppet = PUPPETS.remove(player.getUUID());
		if (session == null) return;
		ServerLevel here = player.serverLevel();
		Vec3 at = player.position();
		if (puppet == null || puppet.level() != here) puppet = puppetFrom(puppet != null ? tagOf(puppet) : session.getCompound("Unit"), here);
		if (puppet != null) {
			puppet.setPilot(null);
			if (died) {
				Hive hive = puppet.hiveId() == null ? null : HiveSavedData.get(server).get(puppet.hiveId());
				if (hive != null) {
					hive.leave(puppet.kind);
					HiveSavedData.get(server).setDirty();
				}
				here.playSound(null, at.x, at.y, at.z, StarMine.sounds(puppet.kind).death(), SoundSource.HOSTILE, 1f, 1f);
				here.sendParticles(ParticleTypes.POOF, at.x, at.y + 1, at.z, 30, 0.6, 0.6, 0.6, 0.05);
			} else {
				puppet.moveTo(at.x, at.y, at.z, player.getYRot(), 0);
				puppet.setHealth(Math.max(1, Math.min(player.getHealth(), puppet.getMaxHealth())));
				puppet.setShield(player.getAbsorptionAmount());
				puppet.setDeltaMovement(Vec3.ZERO);
				puppet.command(StarUnit.Order.NONE, null);
				here.addFreshEntity(puppet);
			}
		}
		// the player again: what the unit picked up is theirs, then their own things, place and state
		List<ItemStack> picked = new ArrayList<>();
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			ItemStack stack = player.getInventory().getItem(i);
			if (!stack.isEmpty() && !ModuleItem.locked(stack)) picked.add(stack.copy());
		}
		player.getInventory().clearContent();
		player.getInventory().load(session.getList("Inventory", Tag.TAG_COMPOUND));
		for (ItemStack stack : picked) if (!player.getInventory().add(stack)) player.drop(stack, false);
		CompoundTag attributes = session.getCompound("Attributes");
		for (Holder<Attribute> attribute : BODY) {
			AttributeInstance instance = player.getAttribute(attribute);
			String id = attribute.unwrapKey().orElseThrow().location().toString();
			if (instance == null) continue;
			instance.removeModifier(UPGRADE);
			if (attributes.contains(id)) instance.setBaseValue(attributes.getDouble(id));
		}
		player.setAbsorptionAmount(0);
		player.removeAllEffects();
		player.getAbilities().setFlyingSpeed(0.05f);
		player.getAbilities().setWalkingSpeed(0.1f);
		GameType mode = GameType.byId(session.getInt("GameMode"));
		if (player.gameMode.getGameModeForPlayer() != mode) player.setGameMode(mode);
		else {
			mode.updatePlayerAbilities(player.getAbilities());
			player.onUpdateAbilities();
		}
		player.setHealth(Math.max(1, session.getFloat("Health")));
		player.getFoodData().setFoodLevel(session.getInt("Food"));
		player.getFoodData().setSaturation(session.getFloat("Saturation"));
		Pilots.set(false, player.getUUID(), null);
		LAST_HURT.remove(player.getUUID());
		player.refreshDimensions();
		ServerLevel home = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(session.getString("Dim"))));
		player.teleportTo(home != null ? home : here, session.getDouble("X"), session.getDouble("Y"), session.getDouble("Z"),
			session.getFloat("Yaw"), session.getFloat("Pitch"));
		player.fallDistance = 0;
		player.clearFire();
		data.setDirty();
		PossessNetwork.sendState(player);
		ServerPlayNetworking.send(player, new PossessNetwork.PilotEnded(died));
		if (died) player.displayClientMessage(Component.translatable("message.starmine.pilot_died"), true);
	}

	// ---- the body ----

	/** The player's body becomes the unit's: attributes, health and shields, modules, flight. */
	private static void becomeUnit(ServerPlayer player, StarUnit puppet) {
		UnitKind kind = puppet.kind;
		base(player, Attributes.MAX_HEALTH, kind.health);
		player.setHealth((float) Math.min(puppet.getHealth(), kind.health));
		base(player, Attributes.ATTACK_DAMAGE, Math.max(0.5, kind.damage));
		base(player, Attributes.ATTACK_SPEED, 20.0 / kind.interval);
		base(player, Attributes.ARMOR, 0);
		base(player, Attributes.ENTITY_INTERACTION_RANGE, Math.max(3, kind.width / 2 + 2.5));
		base(player, Attributes.STEP_HEIGHT, puppet.getAttributeValue(Attributes.STEP_HEIGHT));
		base(player, Attributes.JUMP_STRENGTH, puppet.getAttributeValue(Attributes.JUMP_STRENGTH));
		base(player, Attributes.MAX_ABSORPTION, kind.shields);
		base(player, Attributes.SAFE_FALL_DISTANCE, 3 + kind.height);
		base(player, Attributes.KNOCKBACK_RESISTANCE, puppet.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE));
		updateSpeed(player, puppet);
		player.setAbsorptionAmount(puppet.shield());
		Hive hive = baseOf(player, puppet);
		int weapons = hive == null ? 0 : hive.attackLevel, armor = hive == null ? 0 : hive.armorLevel;
		modifier(player, Attributes.ATTACK_DAMAGE, weapons * 0.5);
		modifier(player, Attributes.ARMOR, armor * 2.0);
		player.getInventory().clearContent();
		for (ItemStack module : modules(puppet, weapons, armor)) player.getInventory().add(module);
		player.getInventory().selected = 0;
		if (puppet.flying()) {
			player.getAbilities().mayfly = true;
			player.getAbilities().flying = true;
			player.onUpdateAbilities();
		}
		player.getFoodData().setFoodLevel(20);
	}

	/** The modules built into a unit: its weapon, its abilities, its upgrades (with their levels). */
	static List<ItemStack> modules(StarUnit puppet, int weapons, int armor) {
		List<ItemStack> modules = new ArrayList<>();
		if (puppet.kind.attack != UnitKind.Attack.NONE) modules.add(ModuleItem.builtIn(ModuleItem.WEAPON, puppet.kind, 0));
		for (Ability ability : Ability.of(puppet.kind)) modules.add(ModuleItem.builtIn(ModuleItem.of(ability), puppet.kind, 0));
		if (weapons > 0) modules.add(ModuleItem.builtIn(ModuleItem.WEAPON_UPGRADE, puppet.kind, weapons));
		if (armor > 0) modules.add(ModuleItem.builtIn(ModuleItem.ARMOR_UPGRADE, puppet.kind, armor));
		return modules;
	}

	private static void base(ServerPlayer player, Holder<Attribute> attribute, double value) {
		AttributeInstance instance = player.getAttribute(attribute);
		if (instance != null && instance.getBaseValue() != value) instance.setBaseValue(value);
	}

	private static void modifier(ServerPlayer player, Holder<Attribute> attribute, double amount) {
		AttributeInstance instance = player.getAttribute(attribute);
		if (instance == null) return;
		instance.removeModifier(UPGRADE);
		if (amount != 0) instance.addTransientModifier(new AttributeModifier(UPGRADE, amount, AttributeModifier.Operation.ADD_VALUE));
	}

	/** Walking and flying speed from the unit's own (a sieged tank, a channel: none). */
	private static void updateSpeed(ServerPlayer player, StarUnit puppet) {
		boolean still = puppet.abilities.channelling() || puppet.abilities.changingMode();
		double speed = still ? 0 : puppet.getAttributeValue(Attributes.MOVEMENT_SPEED) * SPEED_FACTOR;
		base(player, Attributes.MOVEMENT_SPEED, Math.max(0, speed));
		if (puppet.flying()) {
			float flying = (float) (still ? 0 : puppet.getAttributeValue(Attributes.MOVEMENT_SPEED) * SPEED_FACTOR * 0.5);
			if (Math.abs(player.getAbilities().getFlyingSpeed() - flying) > 1e-4) {
				player.getAbilities().setFlyingSpeed(flying);
				player.onUpdateAbilities();
			}
		}
	}

	private static @Nullable Hive baseOf(ServerPlayer player, StarUnit puppet) {
		return puppet.hiveId() == null ? null : HiveSavedData.get(player.getServer()).get(puppet.hiveId());
	}

	// ---- every tick ----

	private static void tick(MinecraftServer server) {
		for (Map.Entry<UUID, StarUnit> entry : PUPPETS.entrySet()) {
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player == null || !player.isAlive()) continue;
			tick(player, entry.getValue());
		}
	}

	private static void tick(ServerPlayer player, StarUnit puppet) {
		ServerLevel level = player.serverLevel();
		puppet.tickCount++;
		puppet.setPos(player.getX(), player.getY(), player.getZ());
		puppet.setYRot(player.getYRot());
		puppet.setYHeadRot(player.getYHeadRot());
		puppet.setHealth(Math.min(player.getHealth(), puppet.getMaxHealth()));
		boolean cloaked = puppet.cloaked(), revealed = puppet.revealed();
		Abilities.tick(puppet, level);
		updateSpeed(player, puppet);
		if (puppet.kind.shields > 0 && puppet.tickCount % 10 == 0
			&& level.getGameTime() - LAST_HURT.getOrDefault(player.getUUID(), 0L) >= StarUnit.SHIELD_DELAY)
			player.setAbsorptionAmount((float) Math.min(puppet.kind.shields, player.getAbsorptionAmount() + UnitKind.SCALE));
		player.getFoodData().setFoodLevel(20);
		if (puppet.flying()) {
			player.fallDistance = 0;
			if (!player.getAbilities().mayfly) {
				player.getAbilities().mayfly = true;
				player.getAbilities().flying = true;
				player.onUpdateAbilities();
			}
		} else if (puppet.kind.flying && player.getAbilities().mayfly) { // a landed viking walks
			player.getAbilities().mayfly = false;
			player.getAbilities().flying = false;
			player.onUpdateAbilities();
		}
		if (player.containerMenu != player.inventoryMenu) player.closeContainer();
		if (puppet.tickCount % 20 == 0) resyncModules(player, puppet);
		if (puppet.tickCount % 5 == 0)
			ServerPlayNetworking.send(player, new PossessNetwork.PilotHud(puppet.energy(), puppet.maxEnergy(), puppet.mode(),
				puppet.abilities.channelling() || puppet.abilities.changingMode()));
		if (cloaked != puppet.cloaked() || revealed != puppet.revealed()) PossessNetwork.sendState(player);
		if (puppet.tickCount % 200 == 0) {
			PossessionData data = PossessionData.get(level.getServer());
			CompoundTag session = data.sessions.get(player.getUUID());
			if (session != null) {
				session.put("Unit", tagOf(puppet));
				data.setDirty();
			}
		}
	}

	/** Built-in modules that went missing (thrown, dragged off) come back. */
	private static void resyncModules(ServerPlayer player, StarUnit puppet) {
		Hive hive = baseOf(player, puppet);
		for (ItemStack module : modules(puppet, hive == null ? 0 : hive.attackLevel, hive == null ? 0 : hive.armorLevel)) {
			boolean has = false;
			for (int i = 0; i < player.getInventory().getContainerSize() && !has; i++)
				has = player.getInventory().getItem(i).is(module.getItem()) && ModuleItem.locked(player.getInventory().getItem(i));
			if (!has && player.containerMenu.getCarried().is(module.getItem())) has = true;
			if (!has) player.getInventory().add(module);
		}
	}

	// ---- modules ----

	/** A module used in a hand: the weapon fires, an ability is cast. */
	public static boolean useModule(ServerPlayer player, ModuleItem item, InteractionHand hand) {
		StarUnit puppet = puppet(player);
		if (puppet == null || player.getCooldowns().isOnCooldown(item)) return false;
		boolean done = item.type == ModuleItem.Type.WEAPON ? fire(player, puppet) : item.ability != null && cast(player, puppet, item.ability);
		if (done) player.swing(hand, true);
		return done;
	}

	/** The unit's weapon at what the crosshair is on (its reach, its pace). */
	private static boolean fire(ServerPlayer player, StarUnit puppet) {
		UnitKind kind = puppet.kind;
		Modes.Weapon weapon = puppet.weapon();
		ServerLevel level = player.serverLevel();
		switch (kind.attack) {
			case NONE -> {
				return false;
			}
			case EXPLODE -> { // the baneling bursts: everything around is hit and the unit is gone
				for (LivingEntity other : level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(2.5),
					other -> other != player && Faction.hostile(puppet, other)))
					other.hurt(puppet.attackSource(), (float) kind.damage);
				level.sendParticles(ParticleTypes.ITEM_SLIME, player.getX(), player.getY() + 0.5, player.getZ(), 40, 1.2, 0.5, 1.2, 0.1);
				end(player, true);
				return true;
			}
			case MELEE, SPLASH -> {
				if (weapon.range() <= 0) return false; // burrowed, lifted…
				LivingEntity target = aimed(player, player.entityInteractionRange(), false);
				if (target == null) return false;
				target.hurt(puppet.attackSource(), (float) (player.getAttributeValue(Attributes.ATTACK_DAMAGE) + dev.starmine.ability.Passives.bonus(puppet, target)));
				dev.starmine.ability.Passives.onHit(puppet, target);
				if (kind.attack == UnitKind.Attack.SPLASH)
					for (LivingEntity other : level.getEntitiesOfClass(LivingEntity.class, target.getBoundingBox().inflate(2),
						other -> other != target && other != player && Faction.hostile(puppet, other)))
						other.hurt(puppet.attackSource(), (float) kind.damage * 0.5f);
			}
			default -> {
				if (weapon.range() <= 0) return false;
				LivingEntity target = aimed(player, weapon.range(), false);
				if (target == null) {
					player.displayClientMessage(Component.translatable("message.starmine.no_target"), true);
					return false;
				}
				puppet.performRangedAttack(target, 1f);
			}
		}
		player.getCooldowns().addCooldown(ModuleItem.WEAPON, Math.max(4, weapon.interval()));
		return true;
	}

	/** An ability of the unit, aimed with the crosshair (a unit, a point on the ground). */
	private static boolean cast(ServerPlayer player, StarUnit puppet, Ability ability) {
		LivingEntity target = null;
		Vec3 point = null;
		double reach = ability.range > 0 ? ability.range : 96;
		switch (ability.target) {
			case UNIT -> {
				target = aimed(player, Math.max(reach, 3), true);
				if (target == null) {
					player.displayClientMessage(Component.translatable("message.starmine.no_target"), true);
					return false;
				}
			}
			case POINT -> point = aimedPoint(player, reach);
			default -> {
			}
		}
		String refusal = Abilities.refusal(puppet, ability);
		if (refusal == null && Abilities.cast(puppet, player.serverLevel(), ability, target, point)) {
			if (ability.cooldownTicks > 0) player.getCooldowns().addCooldown(ModuleItem.of(ability), ability.cooldownTicks);
			return true;
		}
		player.displayClientMessage(Component.translatable(refusal != null ? refusal : "message.starmine.out_of_reach"), true);
		return false;
	}

	/** The living thing under the crosshair within `range` (foes only, or anyone), not behind a block. */
	private static @Nullable LivingEntity aimed(ServerPlayer player, double range, boolean anyone) {
		Vec3 eye = player.getEyePosition(), look = player.getViewVector(1);
		BlockHitResult block = player.level().clip(new ClipContext(eye, eye.add(look.scale(range)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		double reach = block.getType() == HitResult.Type.MISS ? range : block.getLocation().distanceTo(eye);
		Vec3 end = eye.add(look.scale(reach));
		AABB box = player.getBoundingBox().expandTowards(look.scale(reach)).inflate(1.5);
		EntityHitResult hit = ProjectileUtil.getEntityHitResult(player, eye, end, box,
			entity -> entity instanceof LivingEntity living && living.isAlive() && !entity.isSpectator() && entity != player
				&& (anyone || !Faction.allied(player, entity)), reach * reach);
		return hit != null && hit.getEntity() instanceof LivingEntity living ? living : null;
	}

	/** The point on the ground under the crosshair within `range` (or as far as that, in the air). */
	private static Vec3 aimedPoint(ServerPlayer player, double range) {
		Vec3 eye = player.getEyePosition(), look = player.getViewVector(1);
		BlockHitResult block = player.level().clip(new ClipContext(eye, eye.add(look.scale(range)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		return block.getType() == HitResult.Type.MISS ? eye.add(look.scale(range)) : Vec3.atBottomCenterOf(block.getBlockPos().above());
	}

	// ---- unit data ----

	private static CompoundTag tagOf(StarUnit unit) {
		CompoundTag tag = new CompoundTag();
		unit.saveWithoutId(tag);
		tag.putString("id", BuiltInRegistries.ENTITY_TYPE.getKey(unit.getType()).toString());
		return tag;
	}

	private static @Nullable StarUnit puppetFrom(CompoundTag tag, ServerLevel level) {
		return EntityType.create(tag, level).filter(StarUnit.class::isInstance).map(StarUnit.class::cast).orElse(null);
	}

	/** A player who comes back while playing as a unit takes it up again. */
	private static void resume(ServerPlayer player) {
		CompoundTag session = PossessionData.get(player.getServer()).sessions.get(player.getUUID());
		if (session == null) {
			stripModules(player);
			return;
		}
		StarUnit puppet = puppetFrom(session.getCompound("Unit"), player.serverLevel());
		if (puppet == null) return;
		puppet.setPilot(player);
		PUPPETS.put(player.getUUID(), puppet);
		Pilots.set(false, player.getUUID(), puppet.kind);
		player.refreshDimensions();
		PossessNetwork.sendState(player);
	}

	/** Modules left in a player's inventory without a unit (an old save): taken away. */
	private static void stripModules(ServerPlayer player) {
		for (int i = 0; i < player.getInventory().getContainerSize(); i++)
			if (ModuleItem.locked(player.getInventory().getItem(i))) player.getInventory().setItem(i, ItemStack.EMPTY);
	}

	public static void register() {
		ModuleItem.load();
		PossessNetwork.register();
		ServerTickEvents.END_SERVER_TICK.register(Possession::tick);
		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
			if (entity instanceof ServerPlayer player && piloting(player)) {
				end(player, true);
				return false;
			}
			return true;
		});
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			if (entity instanceof ServerPlayer player && piloting(player)) LAST_HURT.put(player.getUUID(), player.level().getGameTime());
			return true;
		});
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> player instanceof ServerPlayer server && piloting(server)
			&& level.getBlockState(hit.getBlockPos()).getMenuProvider(level, hit.getBlockPos()) != null ? InteractionResult.FAIL : InteractionResult.PASS);
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
			player instanceof ServerPlayer server && piloting(server) && !(entity instanceof LivingEntity) ? InteractionResult.FAIL : InteractionResult.PASS);
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof ItemEntity item && ModuleItem.locked(item.getItem())) entity.discard();
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> resume(handler.getPlayer()));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			ServerPlayer player = handler.getPlayer();
			StarUnit puppet = PUPPETS.remove(player.getUUID());
			if (puppet == null) return;
			PossessionData data = PossessionData.get(server);
			CompoundTag session = data.sessions.get(player.getUUID());
			if (session != null) {
				session.put("Unit", tagOf(puppet));
				data.setDirty();
			}
			Pilots.set(false, player.getUUID(), null);
		});
		EntityTrackingEvents.START_TRACKING.register((tracked, player) -> {
			if (tracked instanceof ServerPlayer pilot && piloting(pilot)) ServerPlayNetworking.send(player, PossessNetwork.stateOf(pilot));
		});
	}
}
