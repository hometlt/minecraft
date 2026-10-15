package dev.starmine.suit;

import dev.starmine.StarMine;
import dev.starmine.ability.Abilities;
import dev.starmine.ability.Ability;
import dev.starmine.ability.Modes;
import dev.starmine.ability.Status;
import dev.starmine.creep.CreepTouch;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveSavedData;
import dev.starmine.possess.ModuleItem;
import dev.starmine.possess.Possession;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Race suits (owner 15.10): what a whole suit gives and what it opens.
 *
 * - Gifts of a whole suit (four pieces of one race, {@link SuitItems}): protoss shields
 *   ({@link #SHIELDS} hit points as absorption, coming back {@link #SHIELD_REGEN} a second after
 *   {@link StarUnit#SHIELD_DELAY} without damage, as plasma shields do); zerg regeneration
 *   ({@link #REGEN_TICKS}, four times faster on creep, as zerg units heal); the terran suit is the
 *   heaviest armor and has no gift besides.
 * - Ability modules ({@link ModuleItem}) work only in a whole suit of their race: a terran suit casts
 *   the stimpack or the ghost's cloak, a zerg suit corrosive bile or the infestor's spells, a protoss
 *   suit the zealot's charge or the adept's shade. The ability is cast by a unit of its kind that is
 *   not in the world and whose body is the player (the way a player playing as a unit casts,
 *   {@link Possession}): its energy, cooldowns and channels are that unit's, kept per player and kind
 *   while the player is online; health costs come off the player, states fall on the player.
 *   Forms and modes (siege, burrow, landing…), transports and buildings' abilities are a unit's own.
 * - Units leave shards of their race's armor when a player or a player's unit kills them: one for
 *   every {@link #VALUE_PER_SHARD} minerals and vespene of their cost (the rest as a chance), and now
 *   and then ({@link #MODULE_CHANCE}) a module of one of their abilities. Fights between wild bases
 *   leave nothing (they would litter the world), nor do summons, illusions and buildings.
 */
public final class Suits {
	public static final float SHIELDS = 8;
	public static final float SHIELD_REGEN = 1;
	public static final int REGEN_TICKS = 40, VALUE_PER_SHARD = 50;
	public static final float MODULE_CHANCE = 0.1f;
	private static final ResourceLocation SHIELD_MODIFIER = StarMine.id("suit_shields");
	private static final Map<UUID, Long> LAST_HURT = new ConcurrentHashMap<>();
	/** The units each player casts their modules with, by kind. */
	private static final Map<UUID, Map<UnitKind, StarUnit>> CASTERS = new ConcurrentHashMap<>();

	private Suits() {
	}

	// ---- the suit ----

	/** The race of the whole suit a player wears (all four pieces of one race), or null. */
	public static @Nullable UnitKind.Race worn(Player player) {
		UnitKind.Race race = null;
		for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
			UnitKind.Race piece = SuitItems.raceOf(player.getItemBySlot(slot).getItem());
			if (piece == null || race != null && piece != race) return null;
			race = piece;
		}
		return race;
	}

	/** Each tick of a player: the suit's gift (not while they play as a unit: the unit's body is theirs then). */
	static void tick(ServerPlayer player) {
		AttributeInstance max = player.getAttribute(Attributes.MAX_ABSORPTION);
		if (Possession.piloting(player)) {
			if (max != null) max.removeModifier(SHIELD_MODIFIER);
			return;
		}
		UnitKind.Race race = worn(player);
		boolean shields = race == UnitKind.Race.PROTOSS;
		if (max != null && shields != max.hasModifier(SHIELD_MODIFIER)) {
			if (shields) max.addTransientModifier(new AttributeModifier(SHIELD_MODIFIER, SHIELDS, AttributeModifier.Operation.ADD_VALUE));
			else {
				max.removeModifier(SHIELD_MODIFIER);
				player.setAbsorptionAmount(Math.min(player.getAbsorptionAmount(), (float) max.getValue()));
			}
		}
		long now = player.level().getGameTime();
		if (shields && player.tickCount % 20 == 0 && now - LAST_HURT.getOrDefault(player.getUUID(), Long.MIN_VALUE / 2) >= StarUnit.SHIELD_DELAY
			&& player.getAbsorptionAmount() < SHIELDS)
			player.setAbsorptionAmount(Math.min(SHIELDS, player.getAbsorptionAmount() + SHIELD_REGEN));
		if (race == UnitKind.Race.ZERG && player.getHealth() < player.getMaxHealth()
			&& player.tickCount % (CreepTouch.onCreep(player) ? REGEN_TICKS / 4 : REGEN_TICKS) == 0)
			player.heal(1);
		Map<UnitKind, StarUnit> casters = CASTERS.get(player.getUUID());
		if (casters != null) for (StarUnit caster : casters.values()) tickCaster(player, caster);
	}

	/** A module's unit keeps time with its player: energy, cooldowns, channels; its cloak is the player's invisibility. */
	private static void tickCaster(ServerPlayer player, StarUnit caster) {
		caster.tickCount++;
		caster.setPos(player.getX(), player.getY(), player.getZ());
		caster.setYRot(player.getYRot());
		Abilities.tick(caster, player.serverLevel());
		if (caster.cloaked() && caster.tickCount % 10 == 0)
			player.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 15, 0, false, false));
	}

	// ---- modules ----

	/** The race whose suit casts this ability, or null when no suit can (a form, a transport, a building's). */
	public static @Nullable UnitKind.Race raceOf(Ability ability) {
		UnitKind kind = casterKind(ability);
		return kind == null ? null : kind.race;
	}

	/** The kind that casts a module's ability for a suit: its first user that is not a building, unless it is a unit's own. */
	static @Nullable UnitKind casterKind(Ability ability) {
		if (Modes.of(ability) >= 0 || ability == Ability.LOAD || ability == Ability.UNLOAD || ability == Ability.ARCHON_WARP) return null;
		for (UnitKind kind : ability.users()) if (!kind.structure()) return kind;
		return null;
	}

	/** A player in a suit uses an ability module (the use key): refused with a message unless the suit is the ability's race. */
	public static boolean useModule(ServerPlayer player, ModuleItem item, InteractionHand hand) {
		if (item.ability == null) return false; // weapons and upgrades are a unit's own
		UnitKind kind = casterKind(item.ability);
		if (kind == null) {
			player.displayClientMessage(Component.translatable("message.starmine.module_unit_only"), true);
			return false;
		}
		if (worn(player) != kind.race) {
			player.displayClientMessage(Component.translatable("message.starmine.need_suit." + kind.race.name().toLowerCase(java.util.Locale.ROOT)), true);
			return false;
		}
		if (player.getCooldowns().isOnCooldown(item)) return false;
		boolean done = Possession.castModule(player, caster(player, kind), item.ability);
		if (done) player.swing(hand, true);
		return done;
	}

	/** The player's unit of `kind` that casts their modules (made on first use: its side and base are the player's). */
	static StarUnit caster(ServerPlayer player, UnitKind kind) {
		return CASTERS.computeIfAbsent(player.getUUID(), id -> new EnumMap<>(UnitKind.class)).computeIfAbsent(kind, k -> {
			StarUnit caster = StarMine.type(k).create(player.serverLevel());
			caster.setPos(player.getX(), player.getY(), player.getZ());
			Hive base = HiveSavedData.get(player.getServer()).baseOf(player.getUUID(), player.level().dimension().location().toString());
			if (base != null) caster.joinAsSummon(base, null);
			else caster.setOwner(player.getUUID());
			caster.setPilot(player);
			return caster;
		});
	}

	// ---- loot ----

	/** What a dying unit leaves (from {@link StarUnit}'s death loot): shards, sometimes a module. */
	public static List<ItemStack> drops(StarUnit unit, DamageSource source, boolean recentlyHit) {
		List<ItemStack> loot = new ArrayList<>();
		if (unit.kind.structure() || unit.summoned() || unit.pilot() != null || Status.has(unit, Status.HALLUCINATION)) return loot;
		boolean byPlayerSide = recentlyHit || source.getEntity() instanceof Player
			|| source.getEntity() instanceof StarUnit killer && killer.owner().isPresent();
		if (!byPlayerSide) return loot;
		UnitKind.Economy cost = unit.kind.economy();
		double shards = (cost.minerals() + cost.vespene()) / (double) VALUE_PER_SHARD;
		int count = (int) shards + (unit.getRandom().nextDouble() < shards - (int) shards ? 1 : 0);
		if (count > 0) loot.add(new ItemStack(SuitItems.SHARDS.get(unit.kind.race), count));
		List<Ability> modules = Ability.of(unit.kind).stream().filter(ability -> casterKind(ability) != null).toList();
		if (!modules.isEmpty() && unit.getRandom().nextFloat() < MODULE_CHANCE)
			loot.add(new ItemStack(ModuleItem.of(modules.get(unit.getRandom().nextInt(modules.size())))));
		return loot;
	}

	// ---- registration ----

	private static void tick(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) tick(player);
	}

	public static void register() {
		SuitItems.register();
		ServerTickEvents.END_SERVER_TICK.register(Suits::tick);
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			if (entity instanceof ServerPlayer player) LAST_HURT.put(player.getUUID(), player.level().getGameTime());
			return true;
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			CASTERS.remove(handler.getPlayer().getUUID());
			LAST_HURT.remove(handler.getPlayer().getUUID());
		});
	}
}
