package dev.starmine.world;

import com.mojang.serialization.Codec;
import dev.starmine.StarMine;
import dev.starmine.block.ArmorBlocks;
import dev.starmine.block.StarMineBlocks;
import dev.starmine.entity.UnitKind;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The story dungeons (owner 19.10), rarer than the small ones ({@code worldgen/placed_feature/story_dungeon.json}), each
 * built in its chunk and those next to it while the world generates, its dwellers waiting in a {@link DungeonHeart}:
 * - the infested mine ({@link #mine}): a headframe over a shaft with a ladder; below, four galleries with rails, timber
 *   props, lanterns and vents, overgrown with flesh and creep; infested terrans and two infestors in the far room;
 * - the Xel'Naga vault ({@link #vault}): a hall of unbreakable xel'naga metal with a beam puzzle — an emitter, prisms
 *   that turn when used, a receiver; when the beam reaches it the seal of the inner room opens ({@link Lock}), where the
 *   Xel'Naga relic lies ({@link DungeonBosses#RELIC});
 * - the sunken scout ({@link #sunkenScout}): on a deep sea's floor a crashed protoss scout under a shield bubble of glass
 *   full of air, its hull of protoss armour, the relic and protoss loot inside, two stalkers on guard;
 * - the brood nest ({@link #broodNest}): a great womb of flesh with ribs, creep, dormant eggs and the brood mother, who
 *   lays egg after egg while a foe is near ({@link DungeonBosses});
 * - Moebius's hybrid laboratory ({@link #hybridLab}): a corridor of cells — captured zerg behind glass, laboratories
 *   with the Moebius Foundation's notes on lecterns — marines and a ghost on guard, and at its end the broken stasis
 *   chamber of the hybrid ({@link DungeonBosses}).
 */
public final class StoryDungeons extends Feature<NoneFeatureConfiguration> {
	public static final Feature<NoneFeatureConfiguration> FEATURE = Registry.register(BuiltInRegistries.FEATURE, StarMine.id("story_dungeon"),
		new StoryDungeons(NoneFeatureConfiguration.CODEC));

	public enum Kind { INFESTED_MINE, XELNAGA_VAULT, SUNKEN_SCOUT, BROOD_NEST, HYBRID_LAB }

	public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
	public static final BooleanProperty LIT = BlockStateProperties.LIT;
	public static final Block EMITTER, PRISM, RECEIVER, SEAL, LOCK;
	public static final BlockEntityType<Lock> LOCK_ENTITY;
	/** The vault's beam: how far it goes, its colour. */
	public static final int BEAM_REACH = 48, BEAM_COLOR = 0x60ffd0;

	static {
		EMITTER = block("xelnaga_beam_emitter", new Facing(unbreakable().lightLevel(state -> 12)));
		PRISM = block("xelnaga_prism", new Prism(unbreakable().lightLevel(state -> 8).noOcclusion()));
		RECEIVER = block("xelnaga_beam_receiver", new Receiver(unbreakable().lightLevel(state -> state.getValue(LIT) ? 15 : 3)));
		SEAL = block("xelnaga_seal", new Block(unbreakable().lightLevel(state -> 6)));
		LOCK = block("xelnaga_vault_lock", new LockBlock(unbreakable().lightLevel(state -> 10)));
		LOCK_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, StarMine.id("xelnaga_vault_lock"),
			BlockEntityType.Builder.of(Lock::new, LOCK).build(null));
	}

	private static BlockBehaviour.Properties unbreakable() {
		return BlockBehaviour.Properties.of().strength(-1.0f, 3600000.0f).noLootTable().pushReaction(PushReaction.BLOCK).sound(SoundType.AMETHYST);
	}

	private static Block block(String name, Block block) {
		Registry.register(BuiltInRegistries.BLOCK, StarMine.id(name), block);
		Registry.register(BuiltInRegistries.ITEM, StarMine.id(name), new BlockItem(block, new Item.Properties().rarity(Rarity.RARE)));
		return block;
	}

	private StoryDungeons(Codec<NoneFeatureConfiguration> codec) {
		super(codec);
	}

	public static void load() {
		BiomeModifications.addFeature(BiomeSelectors.foundInOverworld(), GenerationStep.Decoration.SURFACE_STRUCTURES,
			ResourceKey.create(Registries.PLACED_FEATURE, StarMine.id("story_dungeon")));
		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register(entries -> {
			for (Block block : List.of(EMITTER, PRISM, RECEIVER, SEAL, LOCK)) entries.accept(block);
		});
	}

	@Override
	public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
		WorldGenLevel level = context.level();
		RandomSource random = context.random();
		int x = (context.origin().getX() & ~15) + 8, z = (context.origin().getZ() & ~15) + 8;
		if (MapOverlay.covers(x, z)) return false;
		int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z), floor = level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z);
		if (surface > floor) return surface - floor >= 8 && build(level, random, Kind.SUNKEN_SCOUT, x, z) != null; // the sea: only the scout
		Kind[] land = {Kind.INFESTED_MINE, Kind.XELNAGA_VAULT, Kind.BROOD_NEST, Kind.HYBRID_LAB};
		return build(level, random, land[random.nextInt(land.length)], x, z) != null;
	}

	/** Builds a story dungeon of `kind` about the column (x, z); its heart, or null when the place does not suit. */
	public static @Nullable BlockPos build(WorldGenLevel level, RandomSource random, Kind kind, int x, int z) {
		if (kind == Kind.SUNKEN_SCOUT) return sunkenScout(level, random, new BlockPos(x, level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z), z));
		int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
		return surface <= level.getMinBuildHeight() + 40 ? null : build(level, random, kind, x, z, surface);
	}

	/** {@link #build} under ground whose top is at `surface` (for the scout: its sea's floor). */
	public static @Nullable BlockPos build(WorldGenLevel level, RandomSource random, Kind kind, int x, int z, int surface) {
		if (kind == Kind.SUNKEN_SCOUT) return sunkenScout(level, random, new BlockPos(x, surface, z));
		int deep = level.getMinBuildHeight() + 12;
		return switch (kind) {
			case INFESTED_MINE -> mine(level, random, new BlockPos(x, Math.max(deep, surface - 24), z), surface);
			case XELNAGA_VAULT -> vault(level, random, new BlockPos(x, Math.max(deep, surface - 18), z), surface);
			case BROOD_NEST -> broodNest(level, random, new BlockPos(x, Math.max(deep + 8, surface - 26), z), surface);
			case HYBRID_LAB -> hybridLab(level, random, new BlockPos(x, Math.max(deep, surface - 20), z), surface);
			case SUNKEN_SCOUT -> null;
		};
	}

	// ---- shared ----

	private static void set(WorldGenLevel level, BlockPos pos, BlockState state) {
		SmallDungeons.set(level, pos, state);
	}

	private static void box(WorldGenLevel level, BlockPos a, BlockPos b, BlockState state) {
		for (BlockPos pos : BlockPos.betweenClosed(a, b)) set(level, pos.immutable(), state);
	}

	/** A hollow box: `shell` round its outside (fluids and caves sealed), air inside. */
	private static void room(WorldGenLevel level, BlockPos a, BlockPos b, BlockState shell) {
		for (BlockPos pos : BlockPos.betweenClosed(a.offset(-1, -1, -1), b.offset(1, 1, 1))) {
			boolean inside = pos.getX() >= a.getX() && pos.getX() <= b.getX() && pos.getY() >= a.getY() && pos.getY() <= b.getY()
				&& pos.getZ() >= a.getZ() && pos.getZ() <= b.getZ();
			set(level, pos.immutable(), inside ? Blocks.AIR.defaultBlockState() : shell);
		}
	}

	private static BlockState creep() {
		return StarMineBlocks.CREEP.defaultBlockState().setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), true);
	}

	/** A written note of the Moebius Foundation (its pages translated where it is read), on a lectern. */
	static void lectern(WorldGenLevel level, BlockPos pos, Direction facing, int note) {
		set(level, pos, Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING, facing).setValue(LecternBlock.HAS_BOOK, true));
		if (level.getBlockEntity(pos) instanceof LecternBlockEntity lectern) lectern.setBook(note(note));
	}

	/** The Moebius Foundation's note `n` (1-4). */
	public static ItemStack note(int n) {
		ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
		List<Filterable<Component>> pages = new ArrayList<>();
		for (int page = 1; page <= 3; page++) pages.add(Filterable.passThrough(Component.translatable("book.starmine.moebius." + n + ".page" + page)));
		book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough("Moebius " + n), "Dr. Narud", 0, pages, true));
		book.set(DataComponents.CUSTOM_NAME, Component.translatable("book.starmine.moebius." + n + ".title"));
		return book;
	}

	/** A chest that holds only the Xel'Naga relic. */
	private static void relicChest(WorldGenLevel level, BlockPos pos) {
		set(level, pos, Blocks.CHEST.defaultBlockState());
		if (level.getBlockEntity(pos) instanceof ChestBlockEntity chest) chest.setItem(13, new ItemStack(DungeonBosses.RELIC));
	}

	// ---- the infested mine ----

	/**
	 * Owner 19.10: an infested terran mine. A timber headframe over a ladder shaft down to `base`; four galleries out of
	 * the shaft's foot, three wide, with rails, timber props every five blocks, hanging lanterns and vent ducts behind
	 * bars, flesh and creep growing over them; at the end of the east gallery a room with two infestors, at the north's a
	 * store room with chests. Infested terrans in every gallery.
	 */
	static @Nullable BlockPos mine(WorldGenLevel level, RandomSource random, BlockPos base, int surface) {
		BlockState stone = Blocks.STONE_BRICKS.defaultBlockState(), log = Blocks.OAK_LOG.defaultBlockState(), plank = Blocks.SPRUCE_PLANKS.defaultBlockState(),
			air = Blocks.AIR.defaultBlockState(), flesh = Blocks.NETHER_WART_BLOCK.defaultBlockState(), bars = Blocks.IRON_BARS.defaultBlockState();
		int arm = 14; // the far rooms stay within the chunks next to the dungeon's
		for (Direction way : Direction.Plane.HORIZONTAL) { // the galleries
			Direction side = way.getClockWise();
			for (int d = 2; d <= arm; d++)
				for (int w = -2; w <= 2; w++)
					for (int y = -1; y <= 4; y++) {
						BlockPos pos = base.relative(way, d).relative(side, w).above(y);
						boolean edge = Math.abs(w) == 2 || y == -1 || y == 4;
						if (!edge) set(level, pos, air);
						else if (d % 5 == 0 && y >= 0 && y <= 3 && Math.abs(w) == 2) set(level, pos, log); // the props
						else if (d % 5 == 0 && y == 4) set(level, pos, plank);
						else set(level, pos, random.nextInt(9) == 0 ? flesh : stone);
					}
			for (int d = 2; d <= arm; d++) {
				BlockPos floor = base.relative(way, d);
				set(level, floor, Blocks.RAIL.defaultBlockState());
				if (random.nextInt(3) > 0) set(level, floor.relative(side, random.nextBoolean() ? 1 : -1), creep());
				if (d % 10 == 3) set(level, floor.above(3), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
				if (d % 8 == 6) { // a vent: bars in the wall, a duct behind them
					BlockPos vent = floor.relative(side, 2).above(2);
					set(level, vent, bars);
					set(level, vent.above(), bars);
					for (int k = 1; k <= 4; k++) {
						set(level, vent.relative(side, k), air);
						set(level, vent.relative(side, k).above(), air);
					}
				}
			}
		}
		box(level, base.offset(-1, 0, -1), base.offset(1, 3, 1), air); // the shaft's foot
		BlockPos far = base.relative(Direction.EAST, arm + 4); // the infestors' room
		room(level, far.offset(-3, 0, -4), far.offset(3, 5, 4), flesh);
		box(level, far.offset(-3, -1, -4), far.offset(3, -1, 4), Blocks.CRIMSON_NYLIUM.defaultBlockState());
		for (int i = 0; i < 6; i++) set(level, far.offset(random.nextInt(7) - 3, 0, random.nextInt(9) - 4), creep());
		BlockPos store = base.relative(Direction.NORTH, arm + 3);
		room(level, store.offset(-3, 0, -2), store.offset(3, 3, 2), stone);
		SmallDungeons.chest(level, random, store.offset(-2, 0, -1), FactionLoot.TERRAN);
		SmallDungeons.chest(level, random, store.offset(2, 0, -1), BuiltInLootTables.ABANDONED_MINESHAFT);
		for (int y = base.getY(); y <= surface + 1; y++) // the shaft and its ladder
			for (int dx = -2; dx <= 2; dx++)
				for (int dz = -2; dz <= 2; dz++) {
					BlockPos pos = new BlockPos(base.getX() + dx, y, base.getZ() + dz);
					boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
					if (edge) {
						if (y < surface) set(level, pos, stone);
					} else set(level, pos, air);
				}
		for (int y = base.getY(); y < surface + 1; y++)
			set(level, new BlockPos(base.getX(), y, base.getZ() - 1), Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH));
		BlockPos top = new BlockPos(base.getX(), surface, base.getZ()); // the headframe
		for (int[] c : new int[][] {{-2, -2}, {2, -2}, {-2, 2}, {2, 2}}) box(level, top.offset(c[0], 0, c[1]), top.offset(c[0], 7, c[1]), log);
		box(level, top.offset(-2, 8, -2), top.offset(2, 8, 2), plank);
		box(level, top.offset(-1, 8, -1), top.offset(1, 8, 1), air);
		box(level, top.offset(0, 9, -2), top.offset(0, 10, 2), log); // the wheel's axle and the wheel
		box(level, top.offset(0, 11, -1), top.offset(0, 11, 1), Blocks.SPRUCE_FENCE.defaultBlockState());
		DungeonHeart.Heart heart = SmallDungeons.heart(level, base.below(), Blocks.STONE_BRICKS);
		if (heart == null) return null;
		for (Direction way : Direction.Plane.HORIZONTAL)
			for (int i = 0; i < 2; i++) heart.add(UnitKind.INFESTED_TERRAN, base.relative(way, 6 + i * 7), null, 0);
		heart.add(UnitKind.INFESTOR, far.offset(-1, 0, -2), null, 0);
		heart.add(UnitKind.INFESTOR, far.offset(1, 0, 2), null, 0);
		heart.add(UnitKind.INFESTED_TERRAN, far, null, 0);
		return heart.getBlockPos();
	}

	// ---- the Xel'Naga vault ----

	/**
	 * Owner 19.10: a vault of xel'naga metal. Its hall (21 across) holds a beam puzzle: the emitter on the west wall, three
	 * prisms on the beam's way turned wrong and four more to mislead, the receiver by the north wall. Each prism turns a
	 * quarter when used and sends the beam on the way it faces. When the beam reaches the receiver the seal of the east
	 * room opens ({@link Lock}): there wait the Xel'Naga relic, protoss loot and a sentinel. A spiral stair comes down to
	 * the west of the hall.
	 */
	static @Nullable BlockPos vault(WorldGenLevel level, RandomSource random, BlockPos floor, int surface) {
		BlockState metal = XelNagaTemple.METAL.defaultBlockState(), glyph = XelNagaTemple.GLYPH.defaultBlockState();
		room(level, floor.offset(-10, 0, -10), floor.offset(10, 6, 10), metal);
		for (int[] c : new int[][] {{-10, -10}, {10, -10}, {-10, 10}, {10, 10}}) box(level, floor.offset(c[0], 0, c[1]), floor.offset(c[0], 6, c[1]), glyph);
		room(level, floor.offset(12, 0, -3), floor.offset(17, 4, 3), metal); // the inner room, behind the seal
		box(level, floor.offset(11, 0, -1), floor.offset(11, 2, 1), SEAL.defaultBlockState());
		relicChest(level, floor.offset(16, 0, 0));
		SmallDungeons.chest(level, random, floor.offset(16, 0, 2), FactionLoot.PROTOSS);
		set(level, floor.offset(16, 3, 0), glyph);
		// the puzzle: emitter (-10, -6) east -> A (ax, -6) south -> B (ax, bz) west -> C (-5, bz) north -> receiver (-5, -9)
		int ax = random.nextInt(7), bz = 3 + random.nextInt(5);
		set(level, floor.offset(-10, 0, -6), EMITTER.defaultBlockState().setValue(FACING, Direction.EAST));
		set(level, floor.offset(-5, 0, -9), RECEIVER.defaultBlockState());
		List<long[]> solution = new ArrayList<>();
		BlockPos[] prisms = {floor.offset(ax, 0, -6), floor.offset(ax, 0, bz), floor.offset(-5, 0, bz)};
		Direction[] right = {Direction.SOUTH, Direction.WEST, Direction.NORTH};
		for (int i = 0; i < 3; i++) {
			Direction wrong = right[i].getClockWise(Direction.Axis.Y);
			if (random.nextBoolean()) wrong = wrong.getClockWise(Direction.Axis.Y);
			set(level, prisms[i], PRISM.defaultBlockState().setValue(FACING, wrong));
			solution.add(new long[] {prisms[i].asLong(), right[i].get2DDataValue()});
		}
		for (int i = 0, tries = 0; i < 4 && tries < 60; tries++) { // prisms to mislead, off the beam's way
			int dx = random.nextInt(19) - 9, dz = random.nextInt(19) - 9;
			boolean onWay = dz == -6 && dx <= ax || dx == ax && dz >= -6 && dz <= bz || dz == bz && dx >= -5 && dx <= ax || dx == -5 && dz >= -9 && dz <= bz
				|| Math.abs(dx) <= 1 && Math.abs(dz) <= 1;
			if (onWay) continue;
			set(level, floor.offset(dx, 0, dz), PRISM.defaultBlockState().setValue(FACING, Direction.from2DDataValue(random.nextInt(4))));
			i++;
		}
		set(level, floor.below(), LOCK.defaultBlockState());
		if (level.getBlockEntity(floor.below()) instanceof Lock lock) lock.solution.addAll(solution);
		BlockPos shaft = floor.offset(-14, 0, 0); // the way down
		box(level, floor.offset(-12, 0, -1), floor.offset(-11, 2, 1), Blocks.AIR.defaultBlockState());
		SmallDungeons.spiral(level, shaft, surface, Blocks.DEEPSLATE_TILES.defaultBlockState(), Blocks.POLISHED_DEEPSLATE.defaultBlockState());
		DungeonHeart.Heart heart = SmallDungeons.heart(level, floor.offset(8, -1, 8), XelNagaTemple.METAL); // in the floor, off the beam's way
		if (heart == null) return null;
		heart.add(UnitKind.XELNAGA_CONSTRUCT, floor.offset(-6, 0, 6), null, 0);
		heart.add(UnitKind.XELNAGA_CONSTRUCT, floor.offset(6, 0, -8), null, 0);
		heart.add(UnitKind.XELNAGA_SENTINEL, floor.offset(14, 0, 0), null, 0);
		return heart.getBlockPos();
	}

	/** A block that faces one of the four ways (the emitter). */
	static class Facing extends Block {
		Facing(Properties properties) {
			super(properties);
			registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
		}

		@Override
		protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
			builder.add(FACING);
		}

		@Override
		public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext context) {
			return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
		}
	}

	/** A prism: it sends a beam on the way it faces; used, it turns a quarter. */
	static final class Prism extends Facing {
		Prism(Properties properties) {
			super(properties);
		}

		@Override
		protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
			if (!level.isClientSide) {
				level.setBlock(pos, state.setValue(FACING, state.getValue(FACING).getClockWise()), Block.UPDATE_ALL);
				level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 1.0f, 1.4f);
			}
			return InteractionResult.sidedSuccess(level.isClientSide);
		}
	}

	/** The receiver: lit while the beam reaches it. */
	static final class Receiver extends Block {
		Receiver(Properties properties) {
			super(properties);
			registerDefaultState(stateDefinition.any().setValue(LIT, false));
		}

		@Override
		protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
			builder.add(LIT);
		}
	}

	/** The vault's lock under its floor: it casts the beams and opens the seal ({@link Lock}). */
	static final class LockBlock extends Block implements EntityBlock {
		LockBlock(Properties properties) {
			super(properties);
		}

		@Override
		public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
			return new Lock(pos, state);
		}

		@Override
		public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
			return level.isClientSide || type != LOCK_ENTITY ? null : (world, pos, at, entity) -> {
				if (world.getGameTime() % 10 == 0 && world instanceof ServerLevel server && entity instanceof Lock lock) lock.tick(server);
			};
		}
	}

	/**
	 * The vault's lock: every half second, while a player is near, it traces the beam of each emitter in its vault
	 * ({@link #trace}) — straight on through air, turned by a prism the way it faces, stopped by anything else — draws it,
	 * lights the receivers it reaches and, when all are lit, opens the seals for good. It keeps the prisms' right ways
	 * (`solution`).
	 */
	public static final class Lock extends BlockEntity {
		public static final int REACH = 18;
		boolean done;
		final List<long[]> solution = new ArrayList<>();

		public Lock(BlockPos pos, BlockState state) {
			super(LOCK_ENTITY, pos, state);
		}

		public boolean done() {
			return done;
		}

		/** The prisms' right ways (position, way). */
		public List<long[]> solution() {
			return solution;
		}

		void tick(ServerLevel level) {
			if (done || level.getNearestPlayer(worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(), 40, false) == null) return;
			trace(level);
		}

		/** Traces the vault's beams now; true when the seal opened. */
		public boolean trace(ServerLevel level) {
			if (done) return true;
			List<BlockPos> emitters = new ArrayList<>(), receivers = new ArrayList<>();
			for (BlockPos pos : BlockPos.betweenClosed(worldPosition.offset(-REACH, 0, -REACH), worldPosition.offset(REACH, 6, REACH))) {
				BlockState state = level.getBlockState(pos);
				if (state.is(EMITTER)) emitters.add(pos.immutable());
				else if (state.is(RECEIVER)) receivers.add(pos.immutable());
			}
			java.util.Set<BlockPos> lit = new java.util.HashSet<>();
			for (BlockPos emitter : emitters) {
				List<Vec3> points = new ArrayList<>(List.of(Vec3.atCenterOf(emitter)));
				Direction way = level.getBlockState(emitter).getValue(FACING);
				BlockPos at = emitter;
				for (int step = 0; step < BEAM_REACH; step++) {
					at = at.relative(way);
					BlockState state = level.getBlockState(at);
					if (state.is(PRISM)) {
						points.add(Vec3.atCenterOf(at));
						way = state.getValue(FACING);
					} else if (state.is(RECEIVER)) {
						points.add(Vec3.atCenterOf(at));
						lit.add(at);
						break;
					} else if (!state.isAir() && !state.getCollisionShape(level, at).isEmpty()) {
						points.add(Vec3.atCenterOf(at).subtract(Vec3.atLowerCornerOf(way.getNormal()).scale(0.5)));
						break;
					} else if (step == BEAM_REACH - 1) points.add(Vec3.atCenterOf(at));
				}
				for (int i = 1; i < points.size(); i++)
					dev.starmine.entity.Fx.beam(level, dev.starmine.entity.Fx.Style.BEAM, points.get(i - 1), points.get(i), BEAM_COLOR, 12, 0.15f);
			}
			for (BlockPos receiver : receivers) {
				BlockState state = level.getBlockState(receiver);
				boolean on = lit.contains(receiver);
				if (state.getValue(LIT) != on) level.setBlock(receiver, state.setValue(LIT, on), Block.UPDATE_ALL);
			}
			if (receivers.isEmpty() || !lit.containsAll(receivers)) return false;
			for (BlockPos pos : BlockPos.betweenClosed(worldPosition.offset(-REACH, 0, -REACH), worldPosition.offset(REACH, 6, REACH)))
				if (level.getBlockState(pos).is(SEAL)) {
					level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
					level.sendParticles(ParticleTypes.END_ROD, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 4, 0.3, 0.3, 0.3, 0.05);
				}
			level.playSound(null, worldPosition, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 3.0f, 0.7f);
			for (ServerPlayer player : level.players())
				if (player.blockPosition().closerThan(worldPosition, 40)) player.displayClientMessage(Component.translatable("message.starmine.vault_opened"), true);
			done = true;
			setChanged();
			return true;
		}

		@Override
		protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
			super.saveAdditional(tag, registries);
			tag.putBoolean("done", done);
			long[] flat = new long[solution.size() * 2];
			for (int i = 0; i < solution.size(); i++) {
				flat[i * 2] = solution.get(i)[0];
				flat[i * 2 + 1] = solution.get(i)[1];
			}
			tag.putLongArray("solution", flat);
		}

		@Override
		protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
			super.loadAdditional(tag, registries);
			done = tag.getBoolean("done");
			solution.clear();
			long[] flat = tag.getLongArray("solution");
			for (int i = 0; i + 1 < flat.length; i += 2) solution.add(new long[] {flat[i], flat[i + 1]});
		}
	}

	// ---- the sunken scout ----

	/**
	 * Owner 19.10: a protoss scout sunk to a deep sea's floor, under a shield bubble: a dome of glass nine blocks round,
	 * full of air, over a floor of prismarine; in it the broken hull of protoss armour, the relic and protoss loot, sea
	 * lanterns, two stalkers that still keep it.
	 */
	static @Nullable BlockPos sunkenScout(WorldGenLevel level, RandomSource random, BlockPos floor) {
		if (floor.getY() <= level.getMinBuildHeight() + 6) return null;
		BlockState glass = Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
		int r = 9;
		for (int dx = -r; dx <= r; dx++)
			for (int dz = -r; dz <= r; dz++)
				for (int dy = -2; dy <= r; dy++) {
					double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
					BlockPos pos = floor.offset(dx, dy, dz);
					if (dy < 0) {
						if (Math.sqrt(dx * dx + dz * dz) <= r) set(level, pos, dy == -1 ? Blocks.PRISMARINE_BRICKS.defaultBlockState() : Blocks.SMOOTH_SANDSTONE.defaultBlockState());
					} else if (d <= r - 1) set(level, pos, air);
					else if (d <= r) set(level, pos, glass);
				}
		BlockState hull = ArmorBlocks.wall(UnitKind.Race.PROTOSS, 0), trim = ArmorBlocks.wall(UnitKind.Race.PROTOSS, 2);
		for (int dx = -6; dx <= 6; dx++) // the hull: a long oval, a breach in its side
			for (int dz = -3; dz <= 3; dz++)
				for (int dy = 0; dy <= 4; dy++) {
					double e = (dx * dx) / 36.0 + (dz * dz) / 9.0 + ((dy - 1.5) * (dy - 1.5)) / 6.25;
					if (e > 1.0) continue;
					BlockPos pos = floor.offset(dx, dy, dz);
					boolean shell = e > 0.55 || dy == 0;
					boolean breach = dz >= 2 && dx >= -1 && dx <= 2 && dy >= 1 && dy <= 3;
					set(level, pos, shell && !breach ? (dy == 2 ? trim : hull) : air);
				}
		set(level, floor.offset(-4, 1, 0), Blocks.SEA_LANTERN.defaultBlockState());
		set(level, floor.offset(4, 1, 0), Blocks.SEA_LANTERN.defaultBlockState());
		for (int[] c : new int[][] {{7, 0}, {-7, 0}, {0, 7}, {0, -7}}) set(level, floor.offset(c[0], 0, c[1]), Blocks.SEA_LANTERN.defaultBlockState());
		relicChest(level, floor.offset(-2, 1, 0));
		SmallDungeons.chest(level, random, floor.offset(2, 1, -1), FactionLoot.PROTOSS);
		DungeonHeart.Heart heart = SmallDungeons.heart(level, floor.offset(0, -1, 5), Blocks.PRISMARINE_BRICKS);
		if (heart == null) return null;
		heart.add(UnitKind.STALKER, floor.offset(-4, 0, 6), null, 0);
		heart.add(UnitKind.STALKER, floor.offset(4, 0, 6), null, 0);
		return heart.getBlockPos();
	}

	// ---- the brood nest ----

	/**
	 * Owner 19.10: the brood mother's womb: a cave of flesh 38 across and 16 high, ribs of flesh from floor to roof, a
	 * floor of nylium under creep, dormant eggs round it and the brood mother in its middle; zerg caches by the walls, a
	 * spiral of flesh up to the surface.
	 */
	static @Nullable BlockPos broodNest(WorldGenLevel level, RandomSource random, BlockPos centre, int surface) {
		BlockState flesh = Blocks.NETHER_WART_BLOCK.defaultBlockState(), floor = Blocks.CRIMSON_NYLIUM.defaultBlockState(),
			air = Blocks.AIR.defaultBlockState(), glow = Blocks.SHROOMLIGHT.defaultBlockState();
		double rx = 19, ry = 10, rz = 19;
		int ground = -6;
		for (int dx = -20; dx <= 20; dx++)
			for (int dy = -11; dy <= 11; dy++)
				for (int dz = -20; dz <= 20; dz++) {
					double d = Painter.sq(dx / rx) + Painter.sq(dy / ry) + Painter.sq(dz / rz);
					if (d > 1.0) continue;
					BlockPos pos = centre.offset(dx, dy, dz);
					if (dy < ground) set(level, pos, flesh);
					else if (dy == ground) set(level, pos, d <= 0.85 ? floor : flesh);
					else if (d <= 0.85) set(level, pos, air);
					else set(level, pos, random.nextInt(16) == 0 ? glow : flesh);
				}
		for (int i = 0; i < 6; i++) { // the ribs
			double a = Math.PI * 2 * i / 6 + 0.3;
			BlockPos rib = centre.offset((int) Math.round(Math.cos(a) * 12), ground + 1, (int) Math.round(Math.sin(a) * 12));
			for (int up = 0; up < 15; up++) {
				set(level, rib.above(up), flesh);
				if (up % 5 == 2) set(level, rib.above(up).east(), glow);
			}
		}
		for (int i = 0; i < 70; i++) set(level, centre.offset(random.nextInt(31) - 15, ground + 1, random.nextInt(31) - 15), creep());
		BlockPos floorCentre = centre.above(ground + 1);
		SmallDungeons.chest(level, random, floorCentre.offset(-14, 0, 4), FactionLoot.ZERG);
		SmallDungeons.chest(level, random, floorCentre.offset(13, 0, -6), FactionLoot.ZERG);
		SmallDungeons.spiral(level, floorCentre.offset(15, 0, 0), surface, flesh, flesh);
		DungeonHeart.Heart heart = SmallDungeons.heart(level, floorCentre.below(), Blocks.CRIMSON_NYLIUM);
		if (heart == null) return null;
		heart.add(UnitKind.BROOD_MOTHER, floorCentre.offset(0, 0, 0), null, 0);
		for (int i = 0; i < 10; i++) {
			double a = Math.PI * 2 * i / 10;
			int d = 7 + random.nextInt(7);
			UnitKind brood = random.nextInt(3) == 0 ? UnitKind.ROACH : UnitKind.ZERGLING;
			heart.add(UnitKind.EGG, floorCentre.offset((int) Math.round(Math.cos(a) * d), 0, (int) Math.round(Math.sin(a) * d)), brood, brood == UnitKind.ZERGLING ? 2 : 1);
		}
		for (int i = 0; i < 4; i++) heart.add(UnitKind.ZERGLING, floorCentre.offset(i * 3 - 5, 0, 5), null, 0);
		return heart.getBlockPos();
	}

	// ---- the hybrid laboratory ----

	/**
	 * Owner 19.10: a laboratory of the Moebius Foundation where hybrids were made. A corridor of terran plating from the
	 * stair in the west to the stasis chamber in the east; three cells each side: two hold captured zerg behind glass, the
	 * others are laboratories — brewing stands, cauldrons, chests and the Foundation's notes on lecterns (four notes in
	 * all, the story of the experiment); marines and a ghost on guard; in the chamber, among broken glass, the hybrid.
	 */
	static @Nullable BlockPos hybridLab(WorldGenLevel level, RandomSource random, BlockPos floor, int surface) {
		BlockState wall = ArmorBlocks.wall(UnitKind.Race.TERRAN, 0), panel = ArmorBlocks.wall(UnitKind.Race.TERRAN, 1),
			glass = Blocks.GLASS.defaultBlockState(), light = Blocks.SEA_LANTERN.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
		room(level, floor.offset(-19, 0, -2), floor.offset(12, 4, 2), wall); // the corridor
		for (int x = -18; x <= 12; x += 5) set(level, floor.offset(x, 5, 0), light);
		int[] cells = {-14, -6, 2};
		int note = 1;
		for (int c = 0; c < cells.length; c++)
			for (int side = -1; side <= 1; side += 2) {
				int cx = cells[c];
				BlockPos near = floor.offset(cx - 3, 0, side > 0 ? 4 : -9), farCorner = floor.offset(cx + 3, 4, side > 0 ? 9 : -4);
				room(level, near, farCorner, panel);
				set(level, floor.offset(cx, 5, side * 6), light);
				int front = side * 3;
				boolean specimen = c == 0 || c == 1 && side < 0;
				for (int dx = -3; dx <= 3; dx++)
					for (int y = 0; y <= 3; y++) // the cell's front: glass for a specimen, a doorway for a laboratory
						set(level, floor.offset(cx + dx, y, front), specimen ? glass : Math.abs(dx) <= 1 && y <= 2 ? air : panel);
				BlockPos inside = floor.offset(cx, 0, side * 7);
				if (specimen) continue;
				set(level, inside.offset(-2, 0, 0), Blocks.BREWING_STAND.defaultBlockState());
				set(level, inside.offset(2, 0, 0), Blocks.CAULDRON.defaultBlockState());
				SmallDungeons.chest(level, random, inside.offset(2, 0, side), BuiltInLootTables.SIMPLE_DUNGEON);
				if (note <= 3) lectern(level, inside.offset(-1, 0, side), side > 0 ? Direction.NORTH : Direction.SOUTH, note++);
			}
		BlockPos chamber = floor.offset(18, 0, 0); // the stasis chamber
		room(level, chamber.offset(-4, 0, -5), chamber.offset(4, 6, 5), wall);
		box(level, floor.offset(13, 0, -1), floor.offset(13, 3, 1), air);
		for (int dx = -2; dx <= 2; dx++) // its broken stasis tube: a ring of glass with gaps, glyphs under it
			for (int dz = -2; dz <= 2; dz++) {
				set(level, chamber.offset(dx, -1, dz), XelNagaTemple.GLYPH.defaultBlockState());
				if ((Math.abs(dx) == 2 || Math.abs(dz) == 2) && random.nextInt(3) > 0) set(level, chamber.offset(dx, random.nextInt(3), dz), glass);
			}
		set(level, chamber.offset(0, 7, 0), light);
		lectern(level, chamber.offset(-3, 0, 4), Direction.SOUTH, 4);
		SmallDungeons.chest(level, random, chamber.offset(3, 0, -4), FactionLoot.TERRAN);
		box(level, floor.offset(-20, 0, -1), floor.offset(-20, 2, 1), air);
		SmallDungeons.spiral(level, floor.offset(-22, 0, 0), surface, wall, panel);
		DungeonHeart.Heart heart = SmallDungeons.heart(level, floor.offset(-4, -1, 0), Blocks.SMOOTH_STONE);
		if (heart == null) return null;
		heart.add(UnitKind.HYBRID, chamber, null, 0);
		heart.add(UnitKind.MARINE, floor.offset(-12, 0, 0), null, 0);
		heart.add(UnitKind.MARINE, floor.offset(-2, 0, 1), null, 0);
		heart.add(UnitKind.MARINE, floor.offset(6, 0, -1), null, 0);
		heart.add(UnitKind.GHOST, floor.offset(9, 0, 0), null, 0);
		heart.add(UnitKind.HYDRALISK, floor.offset(cells[0], 0, 7), null, 0);
		heart.add(UnitKind.INFESTOR, floor.offset(cells[0], 0, -7), null, 0);
		heart.add(UnitKind.ROACH, floor.offset(cells[1], 0, -7), null, 0);
		return heart.getBlockPos();
	}
}
