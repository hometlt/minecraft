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
	public static final GunItem CANISTER_RIFLE = item("canister_rifle", new GunItem(new Item.Properties().durability(200), () -> CANISTER_ROUND, 16f, 64, 30));

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
