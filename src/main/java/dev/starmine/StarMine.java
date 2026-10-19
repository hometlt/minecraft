package dev.starmine;

import dev.starmine.block.StarMineBlocks;
import dev.starmine.entity.UnitKind;
import dev.starmine.entity.StarUnit;
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
 * StarMine: StarCraft II zerg and terrans as Minecraft mobs; terran bases are commanded by players.
 *
 * Common side: one entity type, spawn egg and four sound events (attack, death, ambient, hurt) per
 * {@link UnitKind}, plus the zerg missile. Models, textures and sound files are not part of the mod;
 * Modkit imports them from the player's own StarCraft II installation (see README).
 */
public final class StarMine implements ModInitializer {
	public static final String MOD_ID = "starmine";

	/**
	 * The sound events of a kind; their files come from SC2 through Modkit: its weapon's shot or blow (`fire`,
	 * owner 16.10), its attack line, death, idle lines and pain.
	 */
	public record KindSounds(SoundEvent fire, SoundEvent attack, SoundEvent death, SoundEvent ambient, SoundEvent hurt) {
	}

	/** An ability's sound when it is cast (owner 16.10; from SC2 through Modkit: `ability.<id>`). */
	private static final Map<dev.starmine.ability.Ability, SoundEvent> ABILITY_SOUNDS = new java.util.EnumMap<>(dev.starmine.ability.Ability.class);

	public static SoundEvent abilitySound(dev.starmine.ability.Ability ability) {
		return ABILITY_SOUNDS.get(ability);
	}

	private static final Map<UnitKind, EntityType<StarUnit>> TYPES = new EnumMap<>(UnitKind.class);
	private static final Map<EntityType<?>, UnitKind> KINDS = new IdentityHashMap<>();
	private static final Map<UnitKind, KindSounds> SOUNDS = new EnumMap<>(UnitKind.class);
	private static final Map<UnitKind, Item> EGGS = new EnumMap<>(UnitKind.class);

	static {
		for (UnitKind kind : UnitKind.values()) {
			EntityType<StarUnit> type = Registry.register(BuiltInRegistries.ENTITY_TYPE, id(kind.id),
				EntityType.Builder.<StarUnit>of(StarUnit::new, MobCategory.MONSTER)
					.sized(kind.width, kind.height)
					.clientTrackingRange(kind.flying ? 12 : 10)
					.build(kind.id));
			TYPES.put(kind, type);
			KINDS.put(type, kind);
			SOUNDS.put(kind, new KindSounds(sound(kind.id + ".fire"), sound(kind.id + ".attack"), sound(kind.id + ".death"), sound(kind.id + ".ambient"), sound(kind.id + ".hurt")));
			EGGS.put(kind, Registry.register(BuiltInRegistries.ITEM, id(kind.id + "_spawn_egg"),
				new StarSpawnEggItem(type, kind.eggBase, kind.eggSpots, new Item.Properties())));
		}
		for (dev.starmine.ability.Ability ability : dev.starmine.ability.Ability.values())
			ABILITY_SOUNDS.put(ability, sound("ability." + ability.id));
	}

	/** Creep grows from hatcheries and tumors (on by default). */
	/**
	 * How hard the wild races press the players (owner 16.10): 0 — they never come for them, 1 — rarely and
	 * few, 2 — the default, 3 — often and many ({@link dev.starmine.ai.Territory#drops}).
	 */
	public static final GameRules.Key<GameRules.IntegerValue> AI_PRESSURE =
		GameRuleRegistry.register("starmineAiPressure", GameRules.Category.MOBS, GameRuleFactory.createIntRule(2, 0, 3));
	/** Owner 17.10: the Minecraft days a world plays before Amun invades it (unless he is dead by then). */
	public static final GameRules.Key<GameRules.IntegerValue> AMUN_DAYS =
		GameRuleRegistry.register("starmineAmunDays", GameRules.Category.MOBS, GameRuleFactory.createIntRule(30, 1, 100000));
	public static final GameRules.Key<GameRules.BooleanValue> CREEP_SPREAD =
		GameRuleRegistry.register("starmineCreepSpread", GameRules.Category.MOBS, GameRuleFactory.createBooleanRule(true));

	/** Drones chop trees and dig (owner: on by default); off, they only explore. */
	public static final GameRules.Key<GameRules.BooleanValue> HIVE_GRIEFING =
		GameRuleRegistry.register("starmineHiveGriefing", GameRules.Category.MOBS, GameRuleFactory.createBooleanRule(true));

	/** A hive is generated near the world spawn (owner); off: none is. */
	public static final GameRules.Key<GameRules.BooleanValue> SPAWN_HIVE =
		GameRuleRegistry.register("starmineSpawnHive", GameRules.Category.SPAWNING, GameRuleFactory.createBooleanRule(true));

	/** Terran and protoss bases (with their dungeons and sky ships) and a leviathan over the lair are generated (owner 14.10); off: none are. */
	public static final GameRules.Key<GameRules.BooleanValue> SPAWN_BASES =
		GameRuleRegistry.register("starmineSpawnBases", GameRules.Category.SPAWNING, GameRuleFactory.createBooleanRule(true));

	/** A ghost's nuke breaks blocks (owner 14.10, docs/ABILITIES.md: off by default; the blast hurts units either way). */
	public static final GameRules.Key<GameRules.BooleanValue> NUKE_GRIEFING =
		GameRuleRegistry.register("starmineNukeGriefing", GameRules.Category.MOBS, GameRuleFactory.createBooleanRule(true));

	public static final EntityType<dev.starmine.ability.NukeMissile> NUKE_MISSILE = Registry.register(BuiltInRegistries.ENTITY_TYPE, id("nuke_missile"),
		EntityType.Builder.<dev.starmine.ability.NukeMissile>of(dev.starmine.ability.NukeMissile::new, MobCategory.MISC)
			.sized(0.6f, 1.6f)
			.clientTrackingRange(16)
			.updateInterval(1)
			.build("nuke_missile"));

	public static final EntityType<ZergSpit> SPIT = Registry.register(BuiltInRegistries.ENTITY_TYPE, id("zerg_spit"),
		EntityType.Builder.<ZergSpit>of(ZergSpit::new, MobCategory.MISC)
			.sized(0.3f, 0.3f)
			.clientTrackingRange(6)
			.updateInterval(10)
			.build("zerg_spit"));

	public static EntityType<StarUnit> type(UnitKind kind) {
		return TYPES.get(kind);
	}

	public static UnitKind kindOf(EntityType<?> type) {
		UnitKind kind = KINDS.get(type);
		if (kind == null) throw new IllegalStateException("not a StarMine zerg: " + type);
		return kind;
	}

	/** The spawn egg of a kind (also the kind's icon in the command view). */
	public static Item egg(UnitKind kind) {
		return EGGS.get(kind);
	}

	public static KindSounds sounds(UnitKind kind) {
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
		dev.starmine.block.ArmorBlocks.load(); // owner 19.10: armour walls, doors, weapons wearing blocks
		dev.starmine.gear.TraitPotions.load();
		dev.starmine.world.VoidGate.load();
		dev.starmine.world.AmunInvasion.register();
		dev.starmine.vespene.Vespene.load();
		dev.starmine.colony.CommandPanel.load();
		dev.starmine.colony.PlatformBlock.load();
		dev.starmine.world.Teleporter.load();
		dev.starmine.world.Artifact.load();
		dev.starmine.world.HeroLiberation.load();
		dev.starmine.world.VoidIslands.load();
		dev.starmine.world.VoidForces.load();
		dev.starmine.world.DungeonHeart.load();
		dev.starmine.world.SmallDungeons.load();
		dev.starmine.world.Nydus.load();
		dev.starmine.mineral.Minerals.load();
		dev.starmine.world.MapOverlay.load();
		dev.starmine.world.WildPacks.load();
		dev.starmine.gear.Guns.load();
		dev.starmine.world.SheriffHouses.load();
		dev.starmine.colony.Pedestal.load(); // owner 19.10: the founding pedestal of mineral blocks // owner 19.10: a sheriff's stone house in large villages
		dev.starmine.guide.Goals.load();
		dev.starmine.guide.Guide.load();
		for (UnitKind kind : UnitKind.values()) FabricDefaultAttributeRegistry.register(TYPES.get(kind), StarUnit.attributes(kind));
		ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new HiveResources());
		ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new dev.starmine.colony.Costs());
		// owner: hostile mobs (creepers, zombies, skeletons...) attack zerg as zerg attack them
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof Monster monster && !(entity instanceof StarUnit))
				((MobAccessor) monster).starmine$targetSelector().addGoal(3, new NearestAttackableTargetGoal<>(monster, StarUnit.class, true));
		});
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(dev.starmine.hive.HiveStructure::tick);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(dev.starmine.world.RaceBases::tick);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(dev.starmine.world.DimensionBases::tick); // owner 17.10: the Nether's and the End's bases
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(dev.starmine.ai.Territory::serverTick);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(dev.starmine.world.Painter::serverTick);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(dev.starmine.ability.Delayed::tick);
		dev.starmine.colony.ColonyNetwork.register();
		dev.starmine.entity.Fx.register();
		dev.starmine.colony.CommanderEye.register();
		dev.starmine.world.Stratosphere.register();
		dev.starmine.world.VoidWorld.register();
		dev.starmine.world.HeroicTransport.register();
		dev.starmine.world.SpacePlatforms.register();
		dev.starmine.possess.Possession.register();
		dev.starmine.suit.Suits.register();
		net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
			dev.starmine.colony.BaseLogic.onDamage(entity, source));
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(dev.starmine.colony.BaseLogic::serverTick);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(dev.starmine.colony.Engineering::serverTick);
		// owner: a player may bring resources to a command center of their base
		dev.starmine.colony.Storage.load(); // owner 17.10: storage buildings open as a chest of the base's resources
		CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> HiveCommands.register(dispatcher));
		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.SPAWN_EGGS).register(entries -> {
			for (UnitKind kind : UnitKind.values()) entries.accept(EGGS.get(kind));
		});
	}
}
