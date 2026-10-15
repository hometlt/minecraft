package dev.starmine.suit;

import dev.starmine.StarMine;
import dev.starmine.entity.UnitKind;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
import org.jetbrains.annotations.Nullable;

/**
 * The race suits' items (owner 15.10): armor shards that units leave when they die ({@link Suits#drops})
 * and one suit of four pieces per race crafted from its shards like vanilla armor (24 shards a suit).
 *
 * Armor (helmet, chestplate, leggings, boots): the terran suit is the heaviest, as tough as diamond
 * with some knockback resistance; the zerg and protoss suits are as strong as iron and have their
 * race's gift instead when worn whole ({@link Suits}): zerg regeneration, protoss shields.
 */
public final class SuitItems {
	public static final Map<UnitKind.Race, Item> SHARDS = new EnumMap<>(UnitKind.Race.class);
	private static final Map<UnitKind.Race, Holder<ArmorMaterial>> MATERIALS = new EnumMap<>(UnitKind.Race.class);
	private static final Map<UnitKind.Race, Map<ArmorItem.Type, ArmorItem>> PIECES = new EnumMap<>(UnitKind.Race.class);
	/** Pieces in the creative tab's order. */
	public static final List<ArmorItem.Type> TYPES = List.of(ArmorItem.Type.HELMET, ArmorItem.Type.CHESTPLATE, ArmorItem.Type.LEGGINGS, ArmorItem.Type.BOOTS);

	static {
		for (UnitKind.Race race : UnitKind.Race.values()) {
			String name = race.name().toLowerCase(java.util.Locale.ROOT);
			Item shard = Registry.register(BuiltInRegistries.ITEM, StarMine.id(name + "_armor_shard"), new Item(new Item.Properties()));
			SHARDS.put(race, shard);
			boolean heavy = race == UnitKind.Race.TERRAN;
			Map<ArmorItem.Type, Integer> defense = new EnumMap<>(ArmorItem.Type.class);
			defense.put(ArmorItem.Type.HELMET, heavy ? 3 : 2);
			defense.put(ArmorItem.Type.CHESTPLATE, heavy ? 8 : 6);
			defense.put(ArmorItem.Type.LEGGINGS, heavy ? 6 : 5);
			defense.put(ArmorItem.Type.BOOTS, heavy ? 3 : 2);
			defense.put(ArmorItem.Type.BODY, heavy ? 11 : 5);
			Holder<ArmorMaterial> material = Registry.registerForHolder(BuiltInRegistries.ARMOR_MATERIAL, StarMine.id(name + "_suit"),
				new ArmorMaterial(defense, 12, SoundEvents.ARMOR_EQUIP_IRON, () -> Ingredient.of(shard),
					List.of(new ArmorMaterial.Layer(StarMine.id(name + "_suit"))), heavy ? 2.0f : 0.0f, heavy ? 0.1f : 0.0f));
			MATERIALS.put(race, material);
			Map<ArmorItem.Type, ArmorItem> pieces = new EnumMap<>(ArmorItem.Type.class);
			for (ArmorItem.Type type : TYPES) {
				String piece = name + "_" + type.getName();
				pieces.put(type, Registry.register(BuiltInRegistries.ITEM, StarMine.id(piece),
					new SuitPiece(material, type, new Item.Properties().durability(type.getDurability(heavy ? 33 : 25)), name)));
			}
			PIECES.put(race, pieces);
		}
	}

	private SuitItems() {
	}

	/** A suit piece: its tooltip says what the whole suit gives. */
	private static final class SuitPiece extends ArmorItem {
		private final String race;

		SuitPiece(Holder<ArmorMaterial> material, ArmorItem.Type type, Item.Properties properties, String race) {
			super(material, type, properties);
			this.race = race;
		}

		@Override
		public void appendHoverText(net.minecraft.world.item.ItemStack stack, TooltipContext context, List<net.minecraft.network.chat.Component> lines,
				net.minecraft.world.item.TooltipFlag flag) {
			lines.add(net.minecraft.network.chat.Component.translatable("item.starmine.suit.whole").withColor(0xFFC0C0C0));
			lines.add(net.minecraft.network.chat.Component.translatable("item.starmine.suit." + race + ".gift").withColor(0xFF80C0FF));
			lines.add(net.minecraft.network.chat.Component.translatable("item.starmine.suit.modules").withColor(0xFF80C0FF));
		}
	}

	public static ArmorItem piece(UnitKind.Race race, ArmorItem.Type type) {
		return PIECES.get(race).get(type);
	}

	/** The race of a suit piece, or null for anything else. */
	public static @Nullable UnitKind.Race raceOf(Item item) {
		if (!(item instanceof ArmorItem armor)) return null;
		for (Map.Entry<UnitKind.Race, Holder<ArmorMaterial>> entry : MATERIALS.entrySet())
			if (armor.getMaterial().equals(entry.getValue())) return entry.getKey();
		return null;
	}

	/** Registers the items (loading the class) and puts them in the creative tabs. */
	public static void register() {
		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.INGREDIENTS).register(entries -> SHARDS.values().forEach(entries::accept));
		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.COMBAT).register(entries -> {
			for (UnitKind.Race race : UnitKind.Race.values()) for (ArmorItem.Type type : TYPES) entries.accept(piece(race, type));
			for (dev.starmine.ability.Ability ability : dev.starmine.ability.Ability.values()) // the modules a suit casts
				if (Suits.casterKind(ability) != null) entries.accept(dev.starmine.possess.ModuleItem.of(ability));
		});
	}
}
