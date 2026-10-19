package dev.starmine.block;

import dev.starmine.StarMine;
import dev.starmine.entity.UnitKind;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;

/**
 * The races' armour blocks (owner 19.10). An armour wall is made of nine of a race's armour shards and is as hard as
 * obsidian; weapons and blasts wear it far slower than any natural block ({@link BlockWear}). Each race has it in
 * {@link #STYLES} looks (plain, plated, ribbed/lined), turned one into another at a stonecutter. The sky ships are built
 * of them, and generated bases stand on them.
 *
 * The races' doors ({@link ArmorDoorBlock}): a zerg sphincter of flesh and chitin, a terran airlock of steel, a
 * protoss gate whose closed face is a force field — opening by themselves for a friendly player or unit near.
 */
public final class ArmorBlocks {
	/** The looks of a race's armour wall. */
	public static final List<String> STYLES = List.of("armor_wall", "armor_plating", "armor_panel");
	public static final Map<UnitKind.Race, List<Block>> WALLS = new EnumMap<>(UnitKind.Race.class);
	public static final Map<UnitKind.Race, ArmorDoorBlock> DOORS = new EnumMap<>(UnitKind.Race.class);
	public static final BlockEntityType<ArmorDoorBlock.Owner> DOOR_ENTITY;
	/**
	 * The races' building blocks (owner 19.10), of the armour wall's stuff: slab, stairs, wall (`armor_rampart`), fence,
	 * trapdoor, porthole (a blast-proof window) and bars. Their race's ways under wear go with them ({@link BlockWear}).
	 */
	public static final List<String> BUILDING = List.of("armor_slab", "armor_stairs", "armor_rampart", "armor_fence", "armor_trapdoor", "porthole", "armor_bars");
	public static final Map<UnitKind.Race, Map<String, Block>> BUILDING_BLOCKS = new EnumMap<>(UnitKind.Race.class);
	/** Every race's block (walls, doors, building blocks) and its race: their ways under wear ({@link BlockWear}). */
	private static final Map<Block, UnitKind.Race> RACE_OF = new java.util.HashMap<>();
	/** As hard as obsidian, harder still against blasts. */
	public static final float HARDNESS = 50, RESISTANCE = 3600;

	static {
		for (UnitKind.Race race : UnitKind.Race.values()) {
			String name = race.name().toLowerCase(Locale.ROOT);
			SoundType sound = race == UnitKind.Race.ZERG ? SoundType.BONE_BLOCK : race == UnitKind.Race.TERRAN ? SoundType.NETHERITE_BLOCK : SoundType.AMETHYST;
			List<Block> walls = new ArrayList<>();
			for (String style : STYLES) {
				Block wall = register(name + "_" + style, new Block(BlockBehaviour.Properties.of().strength(HARDNESS, RESISTANCE)
					.requiresCorrectToolForDrops().sound(sound).lightLevel(state -> style.equals("armor_panel") && race != UnitKind.Race.TERRAN ? 6 : 0)));
				walls.add(wall);
			}
			WALLS.put(race, walls);
			walls.forEach(wall -> RACE_OF.put(wall, race));
			ArmorDoorBlock door = register(name + "_door", new ArmorDoorBlock(race, BlockBehaviour.Properties.of().strength(HARDNESS, RESISTANCE)
				.requiresCorrectToolForDrops().sound(sound).noOcclusion().pushReaction(PushReaction.BLOCK)
				.isViewBlocking((state, level, pos) -> !state.getValue(ArmorDoorBlock.OPEN))
				.isSuffocating((state, level, pos) -> !state.getValue(ArmorDoorBlock.OPEN))
				.lightLevel(state -> race == UnitKind.Race.PROTOSS && !state.getValue(ArmorDoorBlock.OPEN) ? 7 : 0)));
			DOORS.put(race, door);
			RACE_OF.put(door, race);
			Map<String, Block> building = new java.util.LinkedHashMap<>();
			java.util.function.Supplier<BlockBehaviour.Properties> hard = () -> BlockBehaviour.Properties.of().strength(HARDNESS, RESISTANCE)
				.requiresCorrectToolForDrops().sound(sound);
			building.put("armor_slab", register(name + "_armor_slab", new net.minecraft.world.level.block.SlabBlock(hard.get())));
			building.put("armor_stairs", register(name + "_armor_stairs", new net.minecraft.world.level.block.StairBlock(walls.get(0).defaultBlockState(), hard.get()) { }));
			building.put("armor_rampart", register(name + "_armor_rampart", new net.minecraft.world.level.block.WallBlock(hard.get().forceSolidOn())));
			building.put("armor_fence", register(name + "_armor_fence", new net.minecraft.world.level.block.FenceBlock(hard.get())));
			building.put("armor_trapdoor", register(name + "_armor_trapdoor", new net.minecraft.world.level.block.TrapDoorBlock(
				net.minecraft.world.level.block.state.properties.BlockSetType.COPPER, BlockBehaviour.Properties.of().strength(HARDNESS, RESISTANCE)
				.requiresCorrectToolForDrops().sound(sound).noOcclusion().isValidSpawn((st, l, p, t) -> false)) { }));
			java.util.function.Supplier<BlockBehaviour.Properties> pane = () -> BlockBehaviour.Properties.of().strength(HARDNESS / 2, RESISTANCE)
				.requiresCorrectToolForDrops().sound(sound).noOcclusion();
			building.put("porthole", register(name + "_porthole", new net.minecraft.world.level.block.IronBarsBlock(pane.get()) { }));
			building.put("armor_bars", register(name + "_armor_bars", new net.minecraft.world.level.block.IronBarsBlock(pane.get()) { }));
			building.values().forEach(block -> RACE_OF.put(block, race));
			BUILDING_BLOCKS.put(race, building);
		}
		DOOR_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, StarMine.id("armor_door"),
			BlockEntityType.Builder.of(ArmorDoorBlock.Owner::new, DOORS.values().toArray(new Block[0])).build(null));
	}

	private ArmorBlocks() {
	}

	private static <T extends Block> T register(String name, T block) {
		Registry.register(BuiltInRegistries.BLOCK, StarMine.id(name), block);
		Registry.register(BuiltInRegistries.ITEM, StarMine.id(name), new BlockItem(block, new Item.Properties()));
		return block;
	}

	public static void load() {
		net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents.modifyEntriesEvent(net.minecraft.world.item.CreativeModeTabs.BUILDING_BLOCKS).register(entries -> {
			for (UnitKind.Race race : UnitKind.Race.values()) {
				WALLS.get(race).forEach(entries::accept);
				entries.accept(DOORS.get(race));
				BUILDING_BLOCKS.get(race).values().forEach(entries::accept);
			}
		});
		BlockWear.load();
	}

	/** Whether a block is a race's armour (walls and doors): weapons and blasts wear it slowly. */
	public static boolean armoured(BlockState state) {
		if (RACE_OF.containsKey(state.getBlock())) return true;
		for (List<Block> walls : WALLS.values()) if (walls.contains(state.getBlock())) return true;
		return false;
	}

	/** Another of a race's blocks (its chest) under its race's ways ({@link BlockWear}). */
	public static void addRaceBlock(Block block, UnitKind.Race race) {
		RACE_OF.put(block, race);
	}

	/** The race a block belongs to (its armour), or null. */
	public static @org.jetbrains.annotations.Nullable UnitKind.Race raceOf(Block block) {
		return RACE_OF.get(block);
	}

	/** A race's armour wall of a look (0 plain, 1 plated, 2 panel). */
	public static BlockState wall(UnitKind.Race race, int style) {
		return WALLS.get(race).get(Math.floorMod(style, STYLES.size())).defaultBlockState();
	}
}
