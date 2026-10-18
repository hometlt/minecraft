package dev.starmine.mineral;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import dev.starmine.StarMine;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * Mineral fields (owner 16.10), SC2's blue crystals: rare, a few together, often by a vespene geyser.
 * A crystal fills 2 x 2 x 2 blocks ({@link #CRYSTAL}; its lowest north-west block is the core, which keeps
 * what is left in it, {@link #AMOUNT} at first, and draws the crystal). It cannot be broken, only gathered:
 * a worker takes {@link #PER_TRIP} a trip home, a player's hand takes a mineral shard ({@link #SHARD}, worth
 * {@link #PER_TRIP} minerals, good for nothing but a base's economy and the platform that calls the first
 * main building). Emptied, the crystal is gone. Under it and round it for 2 blocks lies mineralized earth
 * ({@link #EARTH}) that cannot be broken while the crystal stands; it turns to dirt when the crystal is gone.
 * No main building may stand within {@link #KEEP_OFF} blocks of a crystal (SC2: they stand off the field).
 * A laid-over map's crystals are 3 x 3 x 3 (owner 17.10: one SC2 mineral field, one crystal; the
 * core keeps its size), and the block over them is {@link #WARD}: nothing climbs over them.
 */
public final class Minerals {
	public static final int AMOUNT = 500, PER_TRIP = 5, KEEP_OFF = 9; // owner 18.10: 9 blocks (was 16), off geysers too
	/** The deepest gap under a crystal filled with mineralized earth; deeper, the crystal does not go there. */
	static final int MAX_FILL = 3;
	/** Ticks a worker spends on a trip's minerals (SC2: 2.786 s). */
	public static final int MINE_TICKS = 56;

	public static final BooleanProperty CORE = BooleanProperty.create("core");
	public static final Crystal CRYSTAL = Registry.register(BuiltInRegistries.BLOCK, StarMine.id("mineral_crystal"),
		new Crystal(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_BLUE).strength(-1f, 3_600_000f).noLootTable()
			.sound(SoundType.AMETHYST).lightLevel(state -> 6).noOcclusion().pushReaction(PushReaction.BLOCK)
			.isValidSpawn((state, level, pos, type) -> false)));
	/**
	 * The block over a map's crystal (owner 17.10: SC2's mineral walls hold units back, none climbs over
	 * a crystal): unseen, a fence's height to walk into, a fence to a path finder. It goes with the
	 * crystal ({@link #deplete}).
	 */
	public static final Block WARD = Registry.register(BuiltInRegistries.BLOCK, StarMine.id("mineral_ward"),
		new Ward(BlockBehaviour.Properties.of().mapColor(MapColor.NONE).strength(-1f, 3_600_000f).noLootTable().noOcclusion()
			.pushReaction(PushReaction.BLOCK).isValidSpawn((state, level, pos, type) -> false)));
	public static final Block EARTH = Registry.register(BuiltInRegistries.BLOCK, StarMine.id("mineralized_earth"),
		new Block(BlockBehaviour.Properties.of().mapColor(MapColor.DIRT).strength(-1f, 3_600_000f).noLootTable().sound(SoundType.ROOTED_DIRT)
			.pushReaction(PushReaction.BLOCK)));
	public static final BlockEntityType<Core> CORE_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, StarMine.id("mineral_crystal"),
		BlockEntityType.Builder.of(Core::new, CRYSTAL).build(null));
	public static final Item SHARD = Registry.register(BuiltInRegistries.ITEM, StarMine.id("mineral_shard"), new Item(new Item.Properties()));
	public static final Feature<NoneFeatureConfiguration> FIELD = Registry.register(BuiltInRegistries.FEATURE, StarMine.id("mineral_field"),
		new FieldFeature(NoneFeatureConfiguration.CODEC));
	/** Owner 17.10: minerals underground at any depth, in a hall with a geyser ({@link MineralHallFeature}). */
	public static final Feature<NoneFeatureConfiguration> HALL = Registry.register(BuiltInRegistries.FEATURE, StarMine.id("mineral_hall"),
		new MineralHallFeature(NoneFeatureConfiguration.CODEC));

	private Minerals() {
	}

	public static void load() {
		Registry.register(BuiltInRegistries.ITEM, StarMine.id("mineralized_earth"), new net.minecraft.world.item.BlockItem(EARTH, new Item.Properties()));
		BiomeModifications.addFeature(BiomeSelectors.foundInOverworld(), GenerationStep.Decoration.SURFACE_STRUCTURES,
			ResourceKey.create(Registries.PLACED_FEATURE, StarMine.id("mineral_field")));
		BiomeModifications.addFeature(BiomeSelectors.foundInOverworld(), GenerationStep.Decoration.UNDERGROUND_STRUCTURES,
			ResourceKey.create(Registries.PLACED_FEATURE, StarMine.id("mineral_hall")));
		// owner 17.10: the Nether has mineral halls too (each with its geyser and crystals), far rarer
		BiomeModifications.addFeature(BiomeSelectors.foundInTheNether(), GenerationStep.Decoration.UNDERGROUND_STRUCTURES,
			ResourceKey.create(Registries.PLACED_FEATURE, StarMine.id("nether_mineral_hall")));
		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.INGREDIENTS).register(entries -> entries.accept(SHARD));
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(Deposits::tick); // owner 18.10
	}

	// ---- the crystal ----

	/** A block of a crystal; the core (lowest, north-west) keeps it and draws it. */
	public static final class Crystal extends BaseEntityBlock {
		public static final MapCodec<Crystal> CODEC = simpleCodec(Crystal::new);

		Crystal(Properties properties) {
			super(properties);
			registerDefaultState(stateDefinition.any().setValue(CORE, false));
		}

		@Override
		protected MapCodec<? extends BaseEntityBlock> codec() {
			return CODEC;
		}

		@Override
		protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
			builder.add(CORE);
		}

		@Override
		protected RenderShape getRenderShape(BlockState state) {
			return RenderShape.INVISIBLE; // the core's renderer draws the whole crystal
		}

		@Override
		public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
			return state.getValue(CORE) ? new Core(pos, state) : null;
		}

		/** A player's hand takes a shard (a trip's minerals) now and then. */
		@Override
		protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
			if (level.isClientSide) return InteractionResult.SUCCESS;
			if (player.getCooldowns().isOnCooldown(SHARD)) return InteractionResult.CONSUME;
			int taken = harvest((ServerLevel) level, pos, PER_TRIP);
			if (taken <= 0) return InteractionResult.PASS;
			player.getCooldowns().addCooldown(SHARD, MINE_TICKS);
			if (!player.getInventory().add(new ItemStack(SHARD))) player.drop(new ItemStack(SHARD), false);
			return InteractionResult.CONSUME;
		}
	}

	/** {@link #WARD}: collides as a fence, shows nothing, rays and the cursor pass through it. */
	static final class Ward extends Block {
		private static final net.minecraft.world.phys.shapes.VoxelShape FENCE = Block.box(0, 0, 0, 16, 24, 16);

		Ward(Properties properties) {
			super(properties);
		}

		@Override
		protected RenderShape getRenderShape(BlockState state) {
			return RenderShape.INVISIBLE;
		}

		@Override
		protected net.minecraft.world.phys.shapes.VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
			net.minecraft.world.phys.shapes.CollisionContext context) {
			return net.minecraft.world.phys.shapes.Shapes.empty();
		}

		@Override
		protected net.minecraft.world.phys.shapes.VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
			net.minecraft.world.phys.shapes.CollisionContext context) {
			return FENCE;
		}

		@Override
		protected net.minecraft.world.phys.shapes.VoxelShape getVisualShape(BlockState state, BlockGetter level, BlockPos pos,
			net.minecraft.world.phys.shapes.CollisionContext context) {
			return net.minecraft.world.phys.shapes.Shapes.empty();
		}
	}

	/** What is left in a crystal (synced to clients: the crystal shrinks as it empties), and its size (blocks across). */
	public static final class Core extends BlockEntity {
		int amount = AMOUNT;
		/** Owner 17.10: a gold crystal of the Void — each trip's minerals are worth twice. */
		boolean rich;
		int size = 2;

		public Core(BlockPos pos, BlockState state) {
			super(CORE_ENTITY, pos, state);
		}

		public int amount() {
			return amount;
		}

		public boolean rich() {
			return rich;
		}

		public void setRich(boolean rich) {
			this.rich = rich;
			setChanged();
			if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
		}

		public int size() {
			return size;
		}

		public void setSize(int size) {
			this.size = Math.clamp(size, 2, 3);
			setChanged();
			if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
		}

		public void setAmount(int amount) {
			this.amount = amount;
			setChanged();
			if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
		}

		@Override
		protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
			super.saveAdditional(tag, registries);
			tag.putInt("Amount", amount);
			tag.putBoolean("Rich", rich);
			tag.putInt("Size", size);
		}

		@Override
		protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
			super.loadAdditional(tag, registries);
			amount = tag.contains("Amount") ? tag.getInt("Amount") : AMOUNT;
			rich = tag.getBoolean("Rich");
			size = tag.contains("Size") ? Math.clamp(tag.getInt("Size"), 2, 3) : 2;
		}

		@Override
		public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
			return saveWithoutMetadata(registries);
		}

		@Override
		public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
			return ClientboundBlockEntityDataPacket.create(this);
		}
	}

	/** The cores of the crystals with minerals left within `radius` blocks (flat) of `centre`, nearest first, from the loaded chunks. */
	public static java.util.List<BlockPos> coresNear(net.minecraft.server.level.ServerLevel level, BlockPos centre, int radius) {
		java.util.List<BlockPos> cores = new java.util.ArrayList<>();
		for (int cx = (centre.getX() - radius) >> 4; cx <= (centre.getX() + radius) >> 4; cx++)
			for (int cz = (centre.getZ() - radius) >> 4; cz <= (centre.getZ() + radius) >> 4; cz++) {
				if (!level.hasChunk(cx, cz)) continue;
				for (BlockEntity entity : level.getChunk(cx, cz).getBlockEntities().values())
					if (entity instanceof Core core && core.amount() > 0) {
						BlockPos pos = core.getBlockPos();
						double dx = pos.getX() - centre.getX(), dz = pos.getZ() - centre.getZ();
						if (dx * dx + dz * dz <= (double) radius * radius) cores.add(pos.immutable());
					}
			}
		cores.sort(java.util.Comparator.comparingDouble(pos -> pos.distSqr(centre)));
		return cores;
	}

	public static boolean isCrystal(BlockState state) {
		return state.is(CRYSTAL);
	}

	/** The core of the crystal a block belongs to (it is within two blocks below, west or north), or null. */
	public static @Nullable BlockPos core(BlockGetter level, BlockPos pos) {
		for (int dx = -2; dx <= 0; dx++)
			for (int dy = -2; dy <= 0; dy++)
				for (int dz = -2; dz <= 0; dz++) {
					BlockPos at = pos.offset(dx, dy, dz);
					BlockState state = level.getBlockState(at);
					if (state.is(CRYSTAL) && state.getValue(CORE)) return at;
				}
		return null;
	}

	/** Takes up to `want` minerals from the crystal at `pos` (any of its blocks); what it gave. An emptied crystal is gone. */
	public static int harvest(ServerLevel level, BlockPos pos, int want) {
		BlockPos core = core(level, pos);
		if (core == null || !(level.getBlockEntity(core) instanceof Core entity)) return 0;
		int taken = Math.min(want, entity.amount);
		entity.setAmount(entity.amount - taken);
		level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_HIT, SoundSource.BLOCKS, 0.8f, entity.rich ? 1.6f : 1.2f);
		boolean rich = entity.rich;
		if (entity.amount <= 0) deplete(level, core);
		return rich ? taken * 2 : taken; // a gold crystal's minerals count twice
	}

	/** An emptied crystal goes; the mineralized earth round it that no other crystal holds turns to dirt. */
	static void deplete(ServerLevel level, BlockPos core) {
		int last = level.getBlockEntity(core) instanceof Core entity ? entity.size - 1 : 1;
		for (BlockPos pos : BlockPos.betweenClosed(core, core.offset(last, last, last)))
			if (level.getBlockState(pos).is(CRYSTAL)) level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		for (BlockPos pos : BlockPos.betweenClosed(core.offset(-5, -1, -5), core.offset(last + 5, 1, last + 5)))
			if (level.getBlockState(pos).is(WARD) && !crystalWithin(level, pos, 4)) level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		level.playSound(null, core, SoundEvents.AMETHYST_BLOCK_BREAK, SoundSource.BLOCKS, 1.0f, 0.8f);
		for (BlockPos pos : BlockPos.betweenClosed(core.offset(-3, -3, -3), core.offset(4, 2, 4))) {
			if (!level.getBlockState(pos).is(EARTH) || crystalWithin(level, pos, 3)) continue;
			level.setBlock(pos, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
		}
	}

	static boolean crystalWithin(LevelAccessor level, BlockPos pos, int radius) {
		for (BlockPos at : BlockPos.betweenClosed(pos.offset(-radius, -radius, -radius), pos.offset(radius, radius, radius)))
			if (level.getBlockState(at).is(CRYSTAL)) return true;
		return false;
	}

	/** Whether a crystal stands within {@link #KEEP_OFF} blocks of a main building's site (it may not stand there). */
	public static boolean nearSite(BlockGetter level, BlockPos site, double halfWidth) {
		int r = KEEP_OFF + (int) Math.ceil(halfWidth);
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		for (int dx = -r; dx <= r; dx++)
			for (int dz = -r; dz <= r; dz++) {
				// measured from the building's edge
				double ex = Math.max(0, Math.abs(dx) - halfWidth), ez = Math.max(0, Math.abs(dz) - halfWidth);
				if (ex * ex + ez * ez > KEEP_OFF * KEEP_OFF) continue;
				for (int dy = -6; dy <= 8; dy++)
					if (level.getBlockState(at.set(site.getX() + dx, site.getY() + dy, site.getZ() + dz)).is(CRYSTAL)
						|| dev.starmine.vespene.Vespene.isOutlet(level.getBlockState(at))) return true;
			}
		return false;
	}

	// ---- world generation ----

	/**
	 * Puts a crystal whose core is at `core` (its 2 x 2 x 2 blocks free of anything solid but plants) on
	 * mineralized earth: under it and round it for 2 blocks, the top of the ground. `set` writes a block.
	 */
	public static boolean place(WorldGenLevel level, BlockPos core, Setter set) {
		return place(level, core, 2, set);
	}

	/** {@link #place(WorldGenLevel, BlockPos, Setter)} of a crystal `size` blocks across (2 or 3); its core's size is the caller's to set. */
	public static boolean place(WorldGenLevel level, BlockPos core, int size, Setter set) {
		int last = size - 1;
		for (BlockPos pos : BlockPos.betweenClosed(core, core.offset(last, last, last))) {
			BlockState there = level.getBlockState(pos);
			if (!there.isAir() && !there.canBeReplaced() || !there.getFluidState().isEmpty()) return false;
		}
		// it stands on ground (owner 16.10: crystals hung over cliffs and caves): a gap of up to MAX_FILL
		// under it is filled with mineralized earth, a deeper one refuses the place
		List<BlockPos> fill = new ArrayList<>();
		for (int dx = 0; dx <= last; dx++)
			for (int dz = 0; dz <= last; dz++) {
				int depth = 0;
				BlockPos at = core.offset(dx, -1, dz);
				for (; depth <= MAX_FILL; depth++, at = at.below()) {
					BlockState there = level.getBlockState(at);
					if (!there.getFluidState().isEmpty()) return false;
					if (there.isFaceSturdy(level, at, net.minecraft.core.Direction.UP)) break;
					fill.add(at.immutable());
				}
				if (depth > MAX_FILL) return false;
			}
		for (BlockPos at : fill) set.set(at, EARTH.defaultBlockState());
		for (int dx = -2; dx <= last + 2; dx++)
			for (int dz = -2; dz <= last + 2; dz++) {
				if ((dx == -2 || dx == last + 2) && (dz == -2 || dz == last + 2)) continue; // round corners
				for (int dy = -1; dy >= -2; dy--) {
					BlockPos at = core.offset(dx, dy, dz);
					BlockState there = level.getBlockState(at);
					boolean under = dx >= 0 && dx <= last && dz >= 0 && dz <= last;
					if (there.isAir() && dy == -1 || !there.getFluidState().isEmpty() || there.is(Blocks.BEDROCK)) continue;
					if (dy == -1 || under) set.set(at, EARTH.defaultBlockState());
				}
			}
		for (BlockPos pos : BlockPos.betweenClosed(core, core.offset(last, last, last))) set.set(pos.immutable(), CRYSTAL.defaultBlockState().setValue(CORE, pos.equals(core)));
		return true;
	}

	public interface Setter {
		void set(BlockPos pos, BlockState state);
	}

	/**
	 * A field of 3 to 6 crystals about a point (`centre`: a column), each on the ground there, a few blocks
	 * apart; only where they fit (on land, dry). `limit` bounds how far from `centre` it may write.
	 */
	public static int field(WorldGenLevel level, RandomSource random, int x, int z, int limit, Setter set) {
		int count = 3 + random.nextInt(4), made = 0;
		for (int i = 0; i < count * 3 && made < count; i++) {
			double angle = random.nextDouble() * Math.PI * 2, distance = i == 0 ? 0 : 3 + random.nextDouble() * 5;
			int cx = x + (int) Math.round(Math.cos(angle) * distance), cz = z + (int) Math.round(Math.sin(angle) * distance);
			if (Math.abs(cx - x) > limit - 4 || Math.abs(cz - z) > limit - 4) continue;
			int ground = level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, cx, cz);
			if (level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, cx, cz) > ground + 2) continue; // water or a tree
			if (Math.abs(level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, cx + 1, cz + 1) - ground) > 1) continue; // too steep
			if (!level.getFluidState(new BlockPos(cx, ground - 1, cz)).isEmpty()) continue;
			if (crystalWithin(level, new BlockPos(cx, ground, cz), 2)) continue;
			if (place(level, new BlockPos(cx, ground, cz), set)) made++;
		}
		return made;
	}

	/** A field of minerals alone (`worldgen/placed_feature/mineral_field.json`), rare. */
	static final class FieldFeature extends Feature<NoneFeatureConfiguration> {
		FieldFeature(Codec<NoneFeatureConfiguration> codec) {
			super(codec);
		}

		@Override
		public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
			WorldGenLevel level = context.level();
			int cx = (context.origin().getX() & ~15) + 8, cz = (context.origin().getZ() & ~15) + 8;
			if (dev.starmine.world.MapOverlay.covers(cx, cz)) return false; // a laid-over map has its own minerals
			return field(level, context.random(), cx, cz, 22, (pos, state) -> level.setBlock(pos, state, Block.UPDATE_CLIENTS)) > 0;
		}
	}
}
