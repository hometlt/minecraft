package dev.starmine.possess;

import dev.starmine.StarMine;
import dev.starmine.ability.Ability;
import dev.starmine.entity.UnitKind;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * A unit's module (owner 14.10): what a player who plays as a unit fights and casts with. Every
 * combat unit has its weapon module, a module for each of its abilities and its upgrade modules
 * (weapons and armor levels of its base). They are built into the unit: they stay in the player's
 * inventory while they play as it (they cannot be dropped or stored, {@link Possession}) and vanish
 * when they leave it. Put in the main hand or the off hand (drag and drop in the inventory) and used
 * with the use key: the weapon fires at what the crosshair is on, an ability is cast like the
 * unit's own (cooldown shown on the item, energy and health paid). Upgrades work by being there.
 */
public final class ModuleItem extends Item {
	public enum Type { WEAPON, ABILITY, UPGRADE }

	public static final String LOCKED = "StarMineLocked", KIND = "StarMineKind", LEVEL = "StarMineLevel";
	private static final Map<Ability, ModuleItem> ABILITIES = new EnumMap<>(Ability.class);
	public static final ModuleItem WEAPON = register("module_weapon", new ModuleItem(Type.WEAPON, null, null));
	public static final ModuleItem WEAPON_UPGRADE = register("module_weapons_upgrade", new ModuleItem(Type.UPGRADE, null, "weapons"));
	public static final ModuleItem ARMOR_UPGRADE = register("module_armor_upgrade", new ModuleItem(Type.UPGRADE, null, "armor"));

	static {
		for (Ability ability : Ability.values()) ABILITIES.put(ability, register("module_" + ability.id, new ModuleItem(Type.ABILITY, ability, null)));
	}

	public final Type type;
	public final @Nullable Ability ability;
	public final @Nullable String upgrade;

	private ModuleItem(Type type, @Nullable Ability ability, @Nullable String upgrade) {
		super(new Item.Properties().stacksTo(1).rarity(type == Type.WEAPON ? Rarity.UNCOMMON : Rarity.RARE));
		this.type = type;
		this.ability = ability;
		this.upgrade = upgrade;
	}

	private static ModuleItem register(String id, ModuleItem item) {
		return Registry.register(BuiltInRegistries.ITEM, StarMine.id(id), item);
	}

	public static ModuleItem of(Ability ability) {
		return ABILITIES.get(ability);
	}

	/** Loads the class (registers the items). */
	public static void load() {
	}

	/** A module built into a unit of `kind` (an upgrade with its level). */
	public static ItemStack builtIn(ModuleItem item, UnitKind kind, int level) {
		ItemStack stack = new ItemStack(item);
		CompoundTag tag = new CompoundTag();
		tag.putBoolean(LOCKED, true);
		tag.putString(KIND, kind.name());
		if (level > 0) tag.putInt(LEVEL, level);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		return stack;
	}

	/** Whether a stack is a module built into a unit (it may not leave its player's inventory). */
	public static boolean locked(ItemStack stack) {
		if (!(stack.getItem() instanceof ModuleItem)) return false;
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data != null && data.copyTag().getBoolean(LOCKED);
	}

	public static @Nullable UnitKind kindOf(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) return null;
		try {
			return UnitKind.valueOf(data.copyTag().getString(KIND));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	@Override
	public Component getName(ItemStack stack) {
		return switch (type) {
			case WEAPON -> {
				UnitKind kind = kindOf(stack);
				yield kind == null ? Component.translatable("item.starmine.module_weapon")
					: Component.translatable("item.starmine.module_weapon.of", Component.translatable("entity.starmine." + kind.id));
			}
			case ABILITY -> Component.translatable("item.starmine.module", Component.translatable("ability.starmine." + ability.id));
			case UPGRADE -> {
				CustomData data = stack.get(DataComponents.CUSTOM_DATA);
				int level = data == null ? 0 : data.copyTag().getInt(LEVEL);
				yield Component.translatable("item.starmine.module_" + upgrade + "_upgrade", level);
			}
		};
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (type == Type.UPGRADE || Pilots.kindOf(player) == null) return InteractionResultHolder.pass(stack);
		if (level.isClientSide) return InteractionResultHolder.success(stack);
		boolean done = Possession.useModule((ServerPlayer) player, this, hand);
		return done ? InteractionResultHolder.success(stack) : InteractionResultHolder.fail(stack);
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
		switch (type) {
			case WEAPON -> lines.add(Component.translatable("item.starmine.module_weapon.tip").withColor(0xFFC0C0C0));
			case ABILITY -> {
				lines.add(Component.translatable("ability.starmine." + ability.id + ".tip").withColor(0xFFC0C0C0));
				if (ability.energy > 0) lines.add(Component.translatable("command.starmine.energy_cost", ability.energy).withColor(0xFFC080FF));
				if (ability.cooldownTicks > 0) lines.add(Component.translatable("command.starmine.cooldown", ability.cooldownTicks / 20));
				UnitKind kind = kindOf(stack);
				double health = kind == null ? 0 : ability.healthCost(kind);
				if (health > 0) lines.add(Component.translatable("command.starmine.health_cost", (int) Math.round(health * UnitKind.SCALE)).withColor(0xFFFF6060));
			}
			case UPGRADE -> lines.add(Component.translatable("item.starmine.module_" + upgrade + "_upgrade.tip").withColor(0xFFC0C0C0));
		}
		if (locked(stack)) lines.add(Component.translatable("item.starmine.module.built_in").withColor(0xFF808080));
	}
}
