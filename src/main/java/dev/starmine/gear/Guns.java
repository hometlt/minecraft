package dev.starmine.gear;

import dev.starmine.StarMine;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;

/**
 * Terran guns for players and the armed villagers who sell them (owner 16.10): the gauss rifle and its
 * rounds, the canister rifle (a ghost's sniper rifle) and its rounds ({@link GunItem}), the marshal and
 * the ghost ({@link Townsfolk}). One village villager in {@link #ODDS} gets an armed neighbour when it is
 * first loaded.
 */
public final class Guns {
	public static final Item GAUSS_AMMO = item("gauss_ammo", new Item(new Item.Properties()));
	public static final Item CANISTER_ROUND = item("canister_round", new Item(new Item.Properties()));
	public static final GunItem GAUSS_RIFLE = item("gauss_rifle", new GunItem(new Item.Properties().durability(600), () -> GAUSS_AMMO, 5f, 32, 5));
	public static final GunItem CANISTER_RIFLE = item("canister_rifle", new GunItem(new Item.Properties().durability(200), () -> CANISTER_ROUND, 16f, 64, 30, 1, 5f));
	// owner 17.10: every weapon of the light infantry (SC2 numbers scaled as the gauss rifle's: a marine's 6 is 5 here)
	public static final Item PUNISHER_GRENADE = item("punisher_grenade", new Item(new Item.Properties()));
	/** The marauder's Punisher grenade launcher: 8 (+8 against armored), slows what it hits; SC2's 1.07 s. */
	public static final LauncherItem GRENADE_LAUNCHER = item("grenade_launcher", new LauncherItem(new Item.Properties().durability(400),
		() -> Guns.PUNISHER_GRENADE, 8f, 8f, 1.6f, 21, true, false));
	/** The reaper's P-38 Scythe pistols: two shots of 3, 18 blocks; SC2's 0.79 s. */
	public static final GunItem REAPER_PISTOLS = item("reaper_pistols", new GunItem(new Item.Properties().durability(500), () -> GAUSS_AMMO, 3f, 18, 16, 2, 0));
	/** The reaper's KD8 charge, thrown: a blast of 4 that throws back (SC2: 5 damage, a knock-back). */
	public static final LauncherItem KD8_CHARGE = item("kd8_charge", new LauncherItem(new Item.Properties().stacksTo(16), () -> Guns.KD8_CHARGE, 4f, 0f, 1.0f, 20,
		false, true));
	/** The zealot's psi blades: a protoss blade (SC2: 8 x2), sharper than diamond. */
	public static final net.minecraft.world.item.SwordItem PSI_BLADE = item("psi_blade", new net.minecraft.world.item.SwordItem(PsiTier.INSTANCE,
		new Item.Properties().attributes(net.minecraft.world.item.SwordItem.createAttributes(PsiTier.INSTANCE, 4, -2.2f))));

	public static final EntityType<Townsfolk> MARSHAL = Registry.register(BuiltInRegistries.ENTITY_TYPE, StarMine.id("marshal"),
		EntityType.Builder.<Townsfolk>of((type, level) -> new Townsfolk(type, level, Townsfolk.Role.MARSHAL), MobCategory.CREATURE)
			.sized(0.6f, 1.95f).clientTrackingRange(10).build("marshal"));
	public static final EntityType<Townsfolk> GHOST_AGENT = Registry.register(BuiltInRegistries.ENTITY_TYPE, StarMine.id("ghost_agent"),
		EntityType.Builder.<Townsfolk>of((type, level) -> new Townsfolk(type, level, Townsfolk.Role.GHOST), MobCategory.CREATURE)
			.sized(0.6f, 1.95f).clientTrackingRange(10).build("ghost_agent"));

	/** One village villager in this many brings an armed neighbour. */
	public static final int ODDS = 10;
	private static final String CHECKED = "starmine_townsfolk_checked";

	private Guns() {
	}

	/** The psi blade's tier: as hard-wearing as diamond, sharper; mended with protoss armor shards. */
	enum PsiTier implements net.minecraft.world.item.Tier {
		INSTANCE;

		public int getUses() {
			return 1800;
		}

		public float getSpeed() {
			return 9f;
		}

		public float getAttackDamageBonus() {
			return 4f;
		}

		public net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block> getIncorrectBlocksForDrops() {
			return net.minecraft.tags.BlockTags.INCORRECT_FOR_DIAMOND_TOOL;
		}

		public int getEnchantmentValue() {
			return 12;
		}

		public net.minecraft.world.item.crafting.Ingredient getRepairIngredient() {
			return net.minecraft.world.item.crafting.Ingredient.of(dev.starmine.suit.SuitItems.SHARDS.get(dev.starmine.entity.UnitKind.Race.PROTOSS));
		}
	}

	private static <T extends Item> T item(String name, T item) {
		return Registry.register(BuiltInRegistries.ITEM, StarMine.id(name), item);
	}

	public static void load() {
		for (EntityType<Townsfolk> type : java.util.List.of(MARSHAL, GHOST_AGENT))
			FabricDefaultAttributeRegistry.register(type, Mob.createMobAttributes().add(Attributes.MOVEMENT_SPEED, 0.5).add(Attributes.MAX_HEALTH, 30)
				.add(Attributes.FOLLOW_RANGE, 48));
		Item marshalEgg = item("marshal_spawn_egg", new SpawnEggItem(MARSHAL, 0x4a5a6a, 0xd0b040, new Item.Properties()));
		Item ghostEgg = item("ghost_agent_spawn_egg", new SpawnEggItem(GHOST_AGENT, 0x2a2a30, 0x60a0ff, new Item.Properties()));
		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.COMBAT).register(entries -> {
			entries.accept(GAUSS_RIFLE);
			entries.accept(GAUSS_AMMO);
			entries.accept(CANISTER_RIFLE);
			entries.accept(CANISTER_ROUND);
			entries.accept(GRENADE_LAUNCHER);
			entries.accept(PUNISHER_GRENADE);
			entries.accept(REAPER_PISTOLS);
			entries.accept(KD8_CHARGE);
			entries.accept(PSI_BLADE);
		});
		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.SPAWN_EGGS).register(entries -> {
			entries.accept(marshalEgg);
			entries.accept(ghostEgg);
		});
		// armed neighbours in villages: decided once per villager, when it is first loaded
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (!(entity instanceof Villager villager) || villager.getTags().contains(CHECKED)) return;
			villager.addTag(CHECKED);
			if (level.random.nextInt(ODDS) != 0) return;
			EntityType<Townsfolk> type = level.random.nextInt(3) == 0 ? GHOST_AGENT : MARSHAL;
			Townsfolk folk = type.create(level);
			if (folk == null) return;
			folk.moveTo(villager.getX() + 1, villager.getY(), villager.getZ() + 1, level.random.nextFloat() * 360, 0);
			level.getServer().execute(() -> level.addFreshEntity(folk)); // not while the chunk's entities are loading
		});
	}
}
