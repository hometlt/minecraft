package dev.starmine.suit;

import dev.starmine.StarMine;
import dev.starmine.creep.CreepTouch;
import dev.starmine.entity.UnitKind;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import org.jetbrains.annotations.Nullable;

/**
 * The heroes' helmets (owner 17.10): each hero of the sky ships leaves its own helmet ({@link
 * dev.starmine.entity.Heroes#reward}) in place of its race's plain one. It is a piece of its race's suit (it
 * counts for the whole suit's gift and its modules) and has a perk of its own while worn:
 * - Kerrigan's crown: regeneration, as the zerg suit's, in any armor (both together heal twice as fast);
 * - Raynor's helmet: more armor — {@link #RAYNOR_ARMOR} and {@link #RAYNOR_TOUGHNESS} instead of the terran helmet's 3 and 2;
 * - Artanis's helmet: the protoss suit's shields come back {@link #ARTANIS_REGEN} times faster, and sooner after a hit.
 */
public final class HeroHelmets {
	public static final Map<UnitKind.Race, Item> HELMETS = new EnumMap<>(UnitKind.Race.class);
	public static final int RAYNOR_ARMOR = 6;
	public static final float RAYNOR_TOUGHNESS = 4;
	public static final int ARTANIS_REGEN = 3;

	static {
		for (UnitKind.Race race : UnitKind.Race.values()) {
			String name = switch (race) {
				case ZERG -> "kerrigan_crown";
				case TERRAN -> "raynor_helmet";
				case PROTOSS -> "artanis_helmet";
			};
			ArmorItem plain = SuitItems.piece(race, ArmorItem.Type.HELMET);
			HELMETS.put(race, Registry.register(BuiltInRegistries.ITEM, StarMine.id(name), new Helmet(race, plain,
				new Item.Properties().durability(ArmorItem.Type.HELMET.getDurability(40)).rarity(Rarity.EPIC).fireResistant(), name)));
		}
	}

	private HeroHelmets() {
	}

	static void load() {
	}

	/** The race whose hero's helmet this is, or null. */
	public static @Nullable UnitKind.Race heroOf(Item item) {
		return item instanceof Helmet helmet ? helmet.race : null;
	}

	/** The race of the hero's helmet a player wears, or null. */
	public static @Nullable UnitKind.Race worn(Player player) {
		return heroOf(player.getItemBySlot(EquipmentSlot.HEAD).getItem());
	}

	/** Kerrigan's crown heals as the zerg suit does (from {@link Suits}' tick, not while playing as a unit). */
	static void tick(ServerPlayer player) {
		if (worn(player) == UnitKind.Race.ZERG && player.getHealth() < player.getMaxHealth()
			&& player.tickCount % (CreepTouch.onCreep(player) ? Suits.REGEN_TICKS / 4 : Suits.REGEN_TICKS) == 0)
			player.heal(1);
	}

	/** A hero's helmet: its race's material (so it counts for the suit), its perk in its tooltip. */
	private static final class Helmet extends ArmorItem {
		final UnitKind.Race race;
		private final String name;
		private @Nullable ItemAttributeModifiers modifiers;

		Helmet(UnitKind.Race race, ArmorItem plain, Properties properties, String name) {
			super(plain.getMaterial(), ArmorItem.Type.HELMET, properties);
			this.race = race;
			this.name = name;
		}

		@Override
		public ItemAttributeModifiers getDefaultAttributeModifiers() {
			if (race != UnitKind.Race.TERRAN) return super.getDefaultAttributeModifiers();
			if (modifiers == null) {
				net.minecraft.resources.ResourceLocation id = StarMine.id("armor.raynor_helmet");
				modifiers = ItemAttributeModifiers.builder()
					.add(Attributes.ARMOR, new AttributeModifier(id, RAYNOR_ARMOR, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.HEAD)
					.add(Attributes.ARMOR_TOUGHNESS, new AttributeModifier(id, RAYNOR_TOUGHNESS, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.HEAD)
					.add(Attributes.KNOCKBACK_RESISTANCE, new AttributeModifier(id, 0.15, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.HEAD)
					.build();
			}
			return modifiers;
		}

		@Override
		public int getDefense() {
			return race == UnitKind.Race.TERRAN ? RAYNOR_ARMOR : super.getDefense();
		}

		@Override
		public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
			lines.add(Component.translatable("item.starmine." + name + ".perk").withColor(0xFFFFD060));
			lines.add(Component.translatable("item.starmine.suit.whole").withColor(0xFFC0C0C0));
			lines.add(Component.translatable("item.starmine.suit." + race.name().toLowerCase(java.util.Locale.ROOT) + ".gift").withColor(0xFF80C0FF));
		}
	}
}
