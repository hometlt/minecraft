package dev.starmine.world;

import dev.starmine.StarMine;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import java.util.EnumMap;
import java.util.Map;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * The xel'naga artifact (owner 17.10): each hero of the sky ships carries a part of its key — Kerrigan the
 * zerg part, Raynor the terran, Artanis the protoss ({@link dev.starmine.entity.Heroes#reward}); owner 19.10: every hero
 * carries its world's part of its race ({@link World}, {@link #KEYS}), which also opens that world's
 * {@link XelNagaTemple}. The three
 * parts stacked on a pedestal, one on another like a beacon on its base, make the artifact: the pedestal
 * lights, a beam goes up, and high in the sky over it a portal to the Void opens ({@link #portalCentre}) —
 * a ring of frame round a pool of portal, flown into from below. Neither can be broken; taking the artifact
 * apart (any part, or the pedestal) closes the portal and takes it away. Units of a player sent onto the
 * portal or the artifact (a move order there) are carried through by its beam ({@link VoidWorld#send}).
 */
public final class Artifact {
	public static final BooleanProperty ACTIVE = BooleanProperty.create("active");
	public static final Block PEDESTAL;
	/** The overworld's parts (the sky ships' heroes' and the guardians'). */
	public static final Map<UnitKind.Race, Block> PARTS = new EnumMap<>(UnitKind.Race.class);
	/** Owner 19.10: each world's own three parts, carried by its heroes; its xel'naga temple opens with them ({@link XelNagaTemple}). */
	public static final Map<World, Map<UnitKind.Race, Block>> KEYS = new EnumMap<>(World.class);

	/** The worlds with heroes, each with its own key. */
	public enum World {
		OVERWORLD(""), NETHER("nether_"), END("end_");

		public final String prefix;

		World(String prefix) {
			this.prefix = prefix;
		}

		/** The world a level belongs to (the stratosphere is the overworld's sky), or null (the Void). */
		public static @Nullable World of(Level level) {
			if (level.dimension() == Level.NETHER) return NETHER;
			if (level.dimension() == Level.END) return END;
			return VoidWorld.is(level) ? null : OVERWORLD;
		}

		/** The world whose heroes include `hero` (null: not a hero with a part). */
		public static @Nullable World ofHero(UnitKind hero) {
			return switch (hero) {
				case KERRIGAN, RAYNOR, ARTANIS, ABATHUR, SWANN, KARAX -> OVERWORLD;
				case TOSH, ZERATUL, ZAGARA -> NETHER;
				case HORNER, STUKOV, SELENDIS -> END;
				default -> null;
			};
		}

		public net.minecraft.resources.ResourceKey<Level> dimension() {
			return switch (this) {
				case OVERWORLD -> Level.OVERWORLD;
				case NETHER -> Level.NETHER;
				case END -> Level.END;
			};
		}
	}
	public static final Block PORTAL, FRAME;
	public static final BlockEntityType<Core> CORE;
	/** Half the side of the portal's pool, and how high over the artifact it opens (at least {@link #PORTAL_MIN_Y}). */
	public static final int PORTAL_RADIUS = 2, PORTAL_RISE = 48, PORTAL_MIN_Y = 200;

	static {
		PEDESTAL = block("artifact_pedestal", new Pedestal(BlockBehaviour.Properties.of().strength(6.0f, 1200.0f).sound(SoundType.AMETHYST)
			.requiresCorrectToolForDrops().lightLevel(state -> state.getValue(ACTIVE) ? 15 : 4)), Rarity.RARE);
		for (World world : World.values()) {
			Map<UnitKind.Race, Block> parts = new EnumMap<>(UnitKind.Race.class);
			for (UnitKind.Race race : UnitKind.Race.values()) {
				String name = world.prefix + race.name().toLowerCase(java.util.Locale.ROOT) + "_key_part";
				Block block = Registry.register(BuiltInRegistries.BLOCK, StarMine.id(name), new Part(race, world, BlockBehaviour.Properties.of()
					.strength(4.0f, 1200.0f).lightLevel(state -> 10).noOcclusion().pushReaction(PushReaction.BLOCK)
					.sound(race == UnitKind.Race.ZERG ? SoundType.SLIME_BLOCK : race == UnitKind.Race.TERRAN ? SoundType.METAL : SoundType.AMETHYST)));
				Registry.register(BuiltInRegistries.ITEM, StarMine.id(name), new PartItem(block, new Item.Properties().rarity(Rarity.EPIC).stacksTo(1)));
				parts.put(race, block);
			}
			KEYS.put(world, parts);
		}
		PARTS.putAll(KEYS.get(World.OVERWORLD));
		PORTAL = Registry.register(BuiltInRegistries.BLOCK, StarMine.id("void_portal"), new VoidPortal(BlockBehaviour.Properties.of()
			.strength(-1.0f, 3600000.0f).noCollission().noLootTable().lightLevel(state -> 13).pushReaction(PushReaction.BLOCK).noOcclusion()));
		FRAME = Registry.register(BuiltInRegistries.BLOCK, StarMine.id("void_portal_frame"), new Block(BlockBehaviour.Properties.of()
			.strength(-1.0f, 3600000.0f).noLootTable().lightLevel(state -> 6).pushReaction(PushReaction.BLOCK).sound(SoundType.DEEPSLATE)));
		Registry.register(BuiltInRegistries.ITEM, StarMine.id("void_portal_frame"), new BlockItem(FRAME, new Item.Properties()));
		CORE = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, StarMine.id("artifact"),
			BlockEntityType.Builder.of(Core::new, PEDESTAL).build(null));
	}

	private Artifact() {
	}

	private static Block block(String name, Block block, Rarity rarity) {
		Registry.register(BuiltInRegistries.BLOCK, StarMine.id(name), block);
		Registry.register(BuiltInRegistries.ITEM, StarMine.id(name), new BlockItem(block, new Item.Properties().rarity(rarity).stacksTo(1)));
		return block;
	}

	public static void load() {
		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register(entries -> {
			entries.accept(PEDESTAL);
			KEYS.values().forEach(parts -> parts.values().forEach(entries::accept));
		});
	}

	/** The overworld's part of a race. */
	public static Item part(UnitKind.Race race) {
		return PARTS.get(race).asItem();
	}

	/** A world's part of a race. */
	public static Item part(World world, UnitKind.Race race) {
		return KEYS.get(world).get(race).asItem();
	}

	/** The part a hero carries: its world's, of its race (null: none). */
	public static @Nullable Item partOf(UnitKind hero) {
		World world = World.ofHero(hero);
		return world == null ? null : part(world, hero.race);
	}

	/** The middle of the portal an artifact on the pedestal at `pedestal` opens. */
	public static BlockPos portalCentre(Level level, BlockPos pedestal) {
		int y = Math.max(pedestal.getY() + PORTAL_RISE, Math.min(PORTAL_MIN_Y, level.getMaxBuildHeight() - 8));
		return new BlockPos(pedestal.getX(), Math.min(y, level.getMaxBuildHeight() - 4), pedestal.getZ());
	}

	/** Whether the three parts of three races stand one on another on the pedestal at `pedestal`. */
	public static boolean whole(BlockGetter level, BlockPos pedestal) {
		if (!level.getBlockState(pedestal).is(PEDESTAL)) return false;
		java.util.Set<UnitKind.Race> races = java.util.EnumSet.noneOf(UnitKind.Race.class);
		for (int up = 1; up <= 3; up++)
			if (level.getBlockState(pedestal.above(up)).getBlock() instanceof Part part) races.add(part.race);
			else return false;
		return races.size() == 3;
	}

	/** The pedestal under a part (or the pedestal itself) at `pos`, or null. */
	static @Nullable BlockPos pedestalAt(BlockGetter level, BlockPos pos) {
		for (int down = 0; down <= 3; down++) {
			BlockState state = level.getBlockState(pos.below(down));
			if (state.is(PEDESTAL)) return pos.below(down);
			if (down > 0 && !(state.getBlock() instanceof Part)) return null;
		}
		return null;
	}

	/** Something changed at `pos` (a part or the pedestal put or taken): the artifact wakes or falls asleep. */
	static void check(ServerLevel level, BlockPos pos, boolean pedestalGone) {
		BlockPos pedestal = pedestalGone ? pos : pedestalAt(level, pos);
		if (pedestal == null) return;
		boolean whole = !pedestalGone && whole(level, pedestal);
		BlockState state = level.getBlockState(pedestal);
		boolean active = !pedestalGone && state.is(PEDESTAL) && state.getValue(ACTIVE);
		if (whole && !active) open(level, pedestal); // owner 17.10: in any world (the parts of the overworld's, the Nether's or the End's heroes)
		else if (!whole && (active || pedestalGone)) close(level, pedestal);
	}

	/** The artifact is whole: the pedestal lights and the portal opens over it. */
	static void open(ServerLevel level, BlockPos pedestal) {
		level.setBlock(pedestal, level.getBlockState(pedestal).setValue(ACTIVE, true), Block.UPDATE_ALL);
		BlockPos centre = portalCentre(level, pedestal);
		int r = PORTAL_RADIUS;
		for (int dx = -r - 1; dx <= r + 1; dx++)
			for (int dz = -r - 1; dz <= r + 1; dz++) {
				boolean rim = Math.abs(dx) == r + 1 || Math.abs(dz) == r + 1;
				level.setBlock(centre.offset(dx, 0, dz), rim ? FRAME.defaultBlockState() : PORTAL.defaultBlockState(), Block.UPDATE_ALL);
				if (rim && (Math.abs(dx) == r + 1) == (Math.abs(dz) == r + 1)) // the corners stand up as spires
					for (int up = 1; up <= 2; up++) level.setBlock(centre.offset(dx, up, dz), FRAME.defaultBlockState(), Block.UPDATE_ALL);
			}
		VoidWorld.State.get(level.getServer()).opened(level, pedestal);
		level.playSound(null, pedestal, SoundEvents.END_PORTAL_SPAWN, SoundSource.BLOCKS, 1.0f, 1.0f);
		level.playSound(null, centre, SoundEvents.END_PORTAL_SPAWN, SoundSource.BLOCKS, 4.0f, 0.8f);
		for (ServerPlayer player : level.players())
			if (player.blockPosition().closerThan(pedestal, 96))
				player.displayClientMessage(Component.translatable("message.starmine.artifact_opened"), false);
		// owner 17.10: the heroes come, and the player's army goes up the beam to the Void
		ServerPlayer maker = level.players().stream().filter(p -> !p.isSpectator() && p.blockPosition().closerThan(pedestal, 32))
			.min(java.util.Comparator.comparingDouble(p -> p.distanceToSqr(Vec3.atCenterOf(pedestal)))).orElse(null);
		if (maker != null) VoidArmy.assembled(level, pedestal, maker);
	}

	/** The artifact is broken: its portal goes. */
	static void close(ServerLevel level, BlockPos pedestal) {
		BlockState state = level.getBlockState(pedestal);
		if (state.is(PEDESTAL) && state.getValue(ACTIVE)) level.setBlock(pedestal, state.setValue(ACTIVE, false), Block.UPDATE_ALL);
		BlockPos centre = portalCentre(level, pedestal);
		int r = PORTAL_RADIUS;
		for (int dx = -r - 1; dx <= r + 1; dx++)
			for (int dz = -r - 1; dz <= r + 1; dz++)
				for (int up = 0; up <= 2; up++) {
					BlockPos at = centre.offset(dx, up, dz);
					BlockState there = level.getBlockState(at);
					if (there.is(PORTAL) || there.is(FRAME)) level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
				}
		VoidWorld.State.get(level.getServer()).closed(level, pedestal);
		level.playSound(null, pedestal, SoundEvents.BEACON_DEACTIVATE, SoundSource.BLOCKS, 1.0f, 0.6f);
		for (ServerPlayer player : level.players())
			if (player.blockPosition().closerThan(pedestal, 96))
				player.displayClientMessage(Component.translatable("message.starmine.artifact_closed"), true);
	}

	/** Units of a player sent onto the artifact or its portal go through (every 10 ticks of a whole artifact). */
	static void carry(ServerLevel level, BlockPos pedestal) {
		BlockPos centre = portalCentre(level, pedestal);
		AABB base = new AABB(pedestal).inflate(4, 3, 4), sky = new AABB(centre).inflate(PORTAL_RADIUS + 3, 4, PORTAL_RADIUS + 3);
		AABB order = new AABB(pedestal.getX() - 6, pedestal.getY() - 4, pedestal.getZ() - 6, pedestal.getX() + 7, centre.getY() + 6, pedestal.getZ() + 7);
		for (AABB box : new AABB[] {base, sky})
			for (StarUnit unit : level.getEntitiesOfClass(StarUnit.class, box, unit -> unit.isAlive() && unit.owner().isPresent()
				&& !unit.kind.structure() && !unit.isPassenger() && unit.order() == StarUnit.Order.MOVE && unit.orderPos() != null
				&& order.contains(unit.orderPos())))
				VoidWorld.send(level, unit);
	}

	/** The pedestal: lit while the artifact on it is whole; it shows the beam ({@link Core}). */
	static final class Pedestal extends Block implements EntityBlock {
		Pedestal(Properties properties) {
			super(properties);
			registerDefaultState(stateDefinition.any().setValue(ACTIVE, false));
		}

		@Override
		protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
			builder.add(ACTIVE);
		}

		@Override
		public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
			return new Core(pos, state);
		}

		@Override
		public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
			return level.isClientSide || type != CORE ? null : (world, pos, at, entity) -> {
				if (at.getValue(ACTIVE) && (world.getGameTime() + pos.hashCode()) % 10 == 0 && world instanceof ServerLevel server) {
					if (!whole(server, pos)) check(server, pos, false); // a part went some way the block did not tell
					else carry(server, pos);
				}
			};
		}

		@Override
		protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moved) {
			super.onPlace(state, level, pos, old, moved);
			if (level instanceof ServerLevel server && !old.is(this)) check(server, pos, false);
		}

		@Override
		protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState now, boolean moved) {
			if (level instanceof ServerLevel server && !now.is(this) && state.getValue(ACTIVE)) check(server, pos, true);
			super.onRemove(state, level, pos, now, moved);
		}
	}

	/** A part of the artifact's key: a race's, of a world. */
	public static final class Part extends Block {
		private static final VoxelShape SHAPE = Block.box(2, 0, 2, 14, 16, 14);
		public final UnitKind.Race race;
		public final World world;

		Part(UnitKind.Race race, World world, Properties properties) {
			super(properties);
			this.race = race;
			this.world = world;
		}

		@Override
		protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
			return SHAPE;
		}

		@Override
		protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moved) {
			super.onPlace(state, level, pos, old, moved);
			if (level instanceof ServerLevel server && !old.is(this)) {
				check(server, pos, false);
				XelNagaTemple.socketChanged(server, pos);
			}
		}

		@Override
		protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState now, boolean moved) {
			super.onRemove(state, level, pos, now, moved);
			if (level instanceof ServerLevel server && !now.is(this)) {
				check(server, pos, false);
				XelNagaTemple.socketChanged(server, pos);
			}
		}

		@Override
		public void animateTick(BlockState state, Level level, BlockPos pos, net.minecraft.util.RandomSource random) {
			if (random.nextInt(3) == 0)
				level.addParticle(ParticleTypes.END_ROD, pos.getX() + random.nextDouble(), pos.getY() + random.nextDouble(), pos.getZ() + random.nextDouble(), 0, 0.02, 0);
		}
	}

	/** A part in hand: used in the air it tells where its world's xel'naga temple is (owner 19.10: the key shows the way). */
	static final class PartItem extends BlockItem {
		PartItem(Block block, Properties properties) {
			super(block, properties);
		}

		@Override
		public net.minecraft.world.InteractionResultHolder<net.minecraft.world.item.ItemStack> use(Level level, net.minecraft.world.entity.player.Player player,
				net.minecraft.world.InteractionHand hand) {
			net.minecraft.world.item.ItemStack stack = player.getItemInHand(hand);
			if (level instanceof ServerLevel server && player instanceof ServerPlayer traveller && getBlock() instanceof Part part)
				XelNagaTemple.pointTo(server, traveller, part.world);
			return net.minecraft.world.InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
		}
	}

	/** The pedestal's block entity: only for the beam's renderer. */
	public static final class Core extends BlockEntity {
		public Core(BlockPos pos, BlockState state) {
			super(CORE, pos, state);
		}

		/** How tall the beam is: from the artifact's top up to its portal. */
		public int beam() {
			return level == null ? 0 : portalCentre(level, worldPosition).getY() - worldPosition.getY() - 4;
		}
	}

	/** The portal's pool: whatever enters goes to the Void, or back from it. */
	static final class VoidPortal extends Block {
		private static final VoxelShape SHAPE = Block.box(0, 6, 0, 16, 10, 16);

		VoidPortal(Properties properties) {
			super(properties);
		}

		@Override
		protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
			return SHAPE;
		}

		@Override
		protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
			return Shapes.empty();
		}

		@Override
		protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
			if (level instanceof ServerLevel server && entity.canUsePortal(false)) VoidWorld.pass(server, entity.getRootVehicle());
		}

		@Override
		public void animateTick(BlockState state, Level level, BlockPos pos, net.minecraft.util.RandomSource random) {
			for (int i = 0; i < 2; i++)
				level.addParticle(random.nextBoolean() ? ParticleTypes.PORTAL : ParticleTypes.REVERSE_PORTAL, pos.getX() + random.nextDouble(),
					pos.getY() + 0.5, pos.getZ() + random.nextDouble(), 0, random.nextGaussian() * 0.2, 0);
		}
	}
}
