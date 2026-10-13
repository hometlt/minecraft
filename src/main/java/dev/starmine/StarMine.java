package dev.starmine;

import dev.starmine.entity.Zergling;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;

/**
 * StarMine: StarCraft II zerg as Minecraft mobs.
 *
 * Common side: entity types, their attributes, spawn eggs and sound events. Models, textures and
 * sound files are not part of the mod; Modkit imports them from the player's own StarCraft II
 * installation (see README). Health and damage are scaled to Minecraft: 1 SC2 hit point is
 * {@link #HEALTH_PER_SC2_HP} Minecraft health, so a zergling (35) has half a player's health and a
 * hydralisk (90) more than a player.
 */
public final class StarMine implements ModInitializer {
	public static final String MOD_ID = "starmine";
	public static final double HEALTH_PER_SC2_HP = 10.0 / 35.0;

	public static final EntityType<Zergling> ZERGLING = Registry.register(
		BuiltInRegistries.ENTITY_TYPE,
		id("zergling"),
		EntityType.Builder.of(Zergling::new, MobCategory.MONSTER)
			.sized(0.8f, 0.9f)
			.clientTrackingRange(10)
			.build("zergling"));

	public static final Item ZERGLING_SPAWN_EGG = Registry.register(
		BuiltInRegistries.ITEM,
		id("zergling_spawn_egg"),
		new SpawnEggItem(ZERGLING, 0x4b1f4f, 0xc79a52, new Item.Properties()));

	/** Sound events; their files come from SC2 through Modkit (assets/starmine/sounds/sc2/...). */
	public static final class Sounds {
		public static final SoundEvent ZERGLING_ATTACK = sound("zergling.attack");
		public static final SoundEvent ZERGLING_DEATH = sound("zergling.death");
		public static final SoundEvent ZERGLING_AMBIENT = sound("zergling.ambient");
		public static final SoundEvent ZERGLING_HURT = sound("zergling.hurt");

		private static SoundEvent sound(String name) {
			ResourceLocation location = id(name);
			return Registry.register(BuiltInRegistries.SOUND_EVENT, location, SoundEvent.createVariableRangeEvent(location));
		}

		static void load() {
		}
	}

	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		Sounds.load();
		FabricDefaultAttributeRegistry.register(ZERGLING, Zergling.createAttributes());
		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.SPAWN_EGGS).register(entries -> entries.accept(ZERGLING_SPAWN_EGG));
	}
}
