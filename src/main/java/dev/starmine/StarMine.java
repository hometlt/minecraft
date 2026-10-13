package dev.starmine;

import dev.starmine.block.StarMineBlocks;
import dev.starmine.entity.ZergKind;
import dev.starmine.entity.ZergMob;
import dev.starmine.entity.ZergSpit;
import dev.starmine.hive.HiveCommands;
import dev.starmine.mixin.MobAccessor;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import dev.starmine.hive.HiveResources;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleFactory;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleRegistry;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.level.GameRules;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Map;
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
 * Common side: one entity type, spawn egg and four sound events (attack, death, ambient, hurt) per
 * {@link ZergKind}, plus the zerg missile. Models, textures and sound files are not part of the mod;
 * Modkit imports them from the player's own StarCraft II installation (see README).
 */
public final class StarMine implements ModInitializer {
	public static final String MOD_ID = "starmine";

	/** The four sound events of a kind; their files come from SC2 through Modkit. */
	public record KindSounds(SoundEvent attack, SoundEvent death, SoundEvent ambient, SoundEvent hurt) {
	}

	private static final Map<ZergKind, EntityType<ZergMob>> TYPES = new EnumMap<>(ZergKind.class);
	private static final Map<EntityType<?>, ZergKind> KINDS = new IdentityHashMap<>();
	private static final Map<ZergKind, KindSounds> SOUNDS = new EnumMap<>(ZergKind.class);
	private static final Map<ZergKind, Item> EGGS = new EnumMap<>(ZergKind.class);

	static {
		for (ZergKind kind : ZergKind.values()) {
			EntityType<ZergMob> type = Registry.register(BuiltInRegistries.ENTITY_TYPE, id(kind.id),
				EntityType.Builder.<ZergMob>of(ZergMob::new, MobCategory.MONSTER)
					.sized(kind.width, kind.height)
					.clientTrackingRange(kind.flying ? 12 : 10)
					.build(kind.id));
			TYPES.put(kind, type);
			KINDS.put(type, kind);
			SOUNDS.put(kind, new KindSounds(sound(kind.id + ".attack"), sound(kind.id + ".death"), sound(kind.id + ".ambient"), sound(kind.id + ".hurt")));
			EGGS.put(kind, Registry.register(BuiltInRegistries.ITEM, id(kind.id + "_spawn_egg"),
				new SpawnEggItem(type, kind.eggBase, kind.eggSpots, new Item.Properties())));
		}
	}

	/** Creep grows from hatcheries and tumors (on by default). */
	public static final GameRules.Key<GameRules.BooleanValue> CREEP_SPREAD =
		GameRuleRegistry.register("starmineCreepSpread", GameRules.Category.MOBS, GameRuleFactory.createBooleanRule(true));

	/** Drones chop trees and dig (owner: on by default); off, they only explore. */
	public static final GameRules.Key<GameRules.BooleanValue> HIVE_GRIEFING =
		GameRuleRegistry.register("starmineHiveGriefing", GameRules.Category.MOBS, GameRuleFactory.createBooleanRule(true));

	/** A hive is generated near the world spawn (owner); off: none is. */
	public static final GameRules.Key<GameRules.BooleanValue> SPAWN_HIVE =
		GameRuleRegistry.register("starmineSpawnHive", GameRules.Category.SPAWNING, GameRuleFactory.createBooleanRule(true));

	public static final EntityType<ZergSpit> SPIT = Registry.register(BuiltInRegistries.ENTITY_TYPE, id("zerg_spit"),
		EntityType.Builder.<ZergSpit>of(ZergSpit::new, MobCategory.MISC)
			.sized(0.3f, 0.3f)
			.clientTrackingRange(6)
			.updateInterval(10)
			.build("zerg_spit"));

	public static EntityType<ZergMob> type(ZergKind kind) {
		return TYPES.get(kind);
	}

	public static ZergKind kindOf(EntityType<?> type) {
		ZergKind kind = KINDS.get(type);
		if (kind == null) throw new IllegalStateException("not a StarMine zerg: " + type);
		return kind;
	}

	public static KindSounds sounds(ZergKind kind) {
		return SOUNDS.get(kind);
	}

	private static SoundEvent sound(String name) {
		ResourceLocation location = id(name);
		return Registry.register(BuiltInRegistries.SOUND_EVENT, location, SoundEvent.createVariableRangeEvent(location));
	}

	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		StarMineBlocks.load();
		for (ZergKind kind : ZergKind.values()) FabricDefaultAttributeRegistry.register(TYPES.get(kind), ZergMob.attributes(kind));
		ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new HiveResources());
		// owner: hostile mobs (creepers, zombies, skeletons...) attack zerg as zerg attack them
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof Monster monster && !(entity instanceof ZergMob))
				((MobAccessor) monster).starmine$targetSelector().addGoal(3, new NearestAttackableTargetGoal<>(monster, ZergMob.class, true));
		});
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(dev.starmine.hive.HiveStructure::tick);
		CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> HiveCommands.register(dispatcher));
		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.SPAWN_EGGS).register(entries -> {
			for (ZergKind kind : ZergKind.values()) entries.accept(EGGS.get(kind));
		});
	}
}
