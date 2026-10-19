package dev.starmine.gear;

import dev.starmine.StarMine;
import dev.starmine.block.StarMineBlocks;
import dev.starmine.entity.StarUnit;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * The races' traps (owner 19.10), set by a player, sprung by a foe — a monster, one of the wild, a player not their
 * setter (the setter's own units never):
 * - the protoss stasis trap (a dispenser, a protoss armour slab, an ender pearl, minerals): a foe within
 *   {@link #STASIS_REACH} blocks is shut in a shell of stasis for {@link #STASIS_TICKS}; the trap rests
 *   {@link #STASIS_REST} before it springs again;
 * - Abathur's toxic nest (a zerg shard, gunpowder, a spider eye, zerg flesh): lying flat like the ground's skin, it bursts
 *   under a foe's foot — poison round it, no blocks broken — and throws creep two cells in eight ways.
 */
public final class Traps {
	public static final double STASIS_REACH = 2.5;
	public static final int STASIS_TICKS = 100, STASIS_REST = 600;
	public static final float NEST_DAMAGE = 6;

	public static final Block STASIS_TRAP = block("stasis_trap", new StasisTrap(BlockBehaviour.Properties.of().strength(3.0f, 1200)
		.sound(SoundType.AMETHYST).noOcclusion().lightLevel(state -> 6).pushReaction(PushReaction.BLOCK)), true);
	public static final Block STASIS_FIELD = block("stasis_field", new StasisField(BlockBehaviour.Properties.of().strength(-1.0f, 3600000.0f)
		.noLootTable().noOcclusion().lightLevel(state -> 10).sound(SoundType.GLASS).pushReaction(PushReaction.BLOCK)
		.isViewBlocking((s, l, p) -> false).isSuffocating((s, l, p) -> false)), false);
	public static final Block TOXIC_NEST = block("toxic_nest", new ToxicNest(BlockBehaviour.Properties.of().strength(0.5f)
		.sound(SoundType.SLIME_BLOCK).noOcclusion().noCollission().pushReaction(PushReaction.DESTROY)), true);
	public static final BlockEntityType<Setter> SETTER = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, StarMine.id("trap"),
		BlockEntityType.Builder.of(Setter::new, STASIS_TRAP, TOXIC_NEST).build(null));

	private Traps() {
	}

	private static Block block(String name, Block block, boolean item) {
		Registry.register(BuiltInRegistries.BLOCK, StarMine.id(name), block);
		if (item) Registry.register(BuiltInRegistries.ITEM, StarMine.id(name), new BlockItem(block, new Item.Properties()));
		return block;
	}

	public static void load() {
		net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents.modifyEntriesEvent(net.minecraft.world.item.CreativeModeTabs.COMBAT).register(entries -> {
			entries.accept(STASIS_TRAP);
			entries.accept(TOXIC_NEST);
		});
	}

	/** Whether a trap of `setter` springs for this creature. */
	static boolean foe(@Nullable UUID setter, Entity entity) {
		if (!(entity instanceof LivingEntity living) || !living.isAlive() || living.isSpectator()) return false;
		if (entity instanceof Player player) return setter != null && !setter.equals(player.getUUID()) && !player.isCreative();
		if (entity instanceof StarUnit unit) return unit.owner().isEmpty() || setter == null || !unit.owner().get().equals(setter);
		return entity instanceof Enemy;
	}

	/** Who set a trap, and when it may spring again. */
	public static final class Setter extends BlockEntity {
		@Nullable UUID owner;
		long restUntil;

		public Setter(BlockPos pos, BlockState state) {
			super(SETTER, pos, state);
		}

		@Override
		protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
			super.saveAdditional(tag, registries);
			if (owner != null) tag.putUUID("Owner", owner);
			tag.putLong("RestUntil", restUntil);
		}

		@Override
		protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
			super.loadAdditional(tag, registries);
			owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
			restUntil = tag.getLong("RestUntil");
		}
	}

	/** Remembers who set a trap. */
	static void setBy(Level level, BlockPos pos, @Nullable LivingEntity placer) {
		if (placer instanceof Player player && level.getBlockEntity(pos) instanceof Setter setter) {
			setter.owner = player.getUUID();
			setter.setChanged();
		}
	}

	private static final VoxelShape PLATE = Block.box(1, 0, 1, 15, 2, 15), FILM = Block.box(0, 0, 0, 16, 1, 16);

	/** The stasis trap: a plate of khaydarin that shuts a foe near in stasis. */
	static final class StasisTrap extends BaseEntityBlock {
		StasisTrap(Properties properties) {
			super(properties);
		}

		@Override
		protected com.mojang.serialization.MapCodec<? extends BaseEntityBlock> codec() {
			return simpleCodec(StasisTrap::new);
		}

		@Override
		protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
			return PLATE;
		}

		@Override
		protected RenderShape getRenderShape(BlockState state) {
			return RenderShape.MODEL;
		}

		@Override
		public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
			return new Setter(pos, state);
		}

		@Override
		public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
			setBy(level, pos, placer);
		}

		@Override
		public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
			return level.isClientSide ? null : createTickerHelper(type, SETTER, (l, pos, s, setter) -> {
				if (l.getGameTime() % 5 != 0 || l.getGameTime() < setter.restUntil) return;
				AABB reach = new AABB(pos).inflate(STASIS_REACH);
				LivingEntity foe = l.getEntitiesOfClass(LivingEntity.class, reach, e -> foe(setter.owner, e)).stream().findFirst().orElse(null);
				if (foe == null) return;
				encase((ServerLevel) l, foe);
				setter.restUntil = l.getGameTime() + STASIS_REST;
				setter.setChanged();
			});
		}
	}

	/** A shell of stasis round a creature: every free cell next to its body, gone after {@link #STASIS_TICKS}. */
	public static void encase(ServerLevel level, LivingEntity foe) {
		AABB body = foe.getBoundingBox();
		BlockPos low = BlockPos.containing(body.minX, body.minY, body.minZ).offset(-1, 0, -1), high = BlockPos.containing(body.maxX, body.maxY, body.maxZ).offset(1, 1, 1);
		for (BlockPos at : BlockPos.betweenClosed(low, high)) {
			boolean inside = at.getX() > low.getX() && at.getX() < high.getX() && at.getZ() > low.getZ() && at.getZ() < high.getZ() && at.getY() < high.getY();
			if (!inside && level.getBlockState(at).canBeReplaced()) level.setBlock(at, STASIS_FIELD.defaultBlockState(), Block.UPDATE_ALL);
		}
		foe.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
		if (foe instanceof StarUnit unit) dev.starmine.ability.Status.apply(unit, dev.starmine.ability.Status.STASIS, STASIS_TICKS);
		else foe.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, STASIS_TICKS, 9));
		level.sendParticles(ParticleTypes.END_ROD, foe.getX(), foe.getY() + foe.getBbHeight() / 2, foe.getZ(), 30, 0.6, 0.8, 0.6, 0.05);
		level.playSound(null, foe.blockPosition(), SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 1.0f, 1.6f);
	}

	/** Stasis: unbreakable while it lasts; it takes itself away by a scheduled tick (saved with its chunk). */
	static final class StasisField extends Block {
		StasisField(Properties properties) {
			super(properties);
		}

		@Override
		protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moved) {
			super.onPlace(state, level, pos, old, moved);
			if (!level.isClientSide) level.scheduleTick(pos, this, STASIS_TICKS);
		}

		@Override
		protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		}
	}

	/** Abathur's toxic nest: a film on the ground that bursts under a foe. */
	static final class ToxicNest extends BaseEntityBlock {
		ToxicNest(Properties properties) {
			super(properties);
		}

		@Override
		protected com.mojang.serialization.MapCodec<? extends BaseEntityBlock> codec() {
			return simpleCodec(ToxicNest::new);
		}

		@Override
		protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
			return FILM;
		}

		@Override
		protected RenderShape getRenderShape(BlockState state) {
			return RenderShape.MODEL;
		}

		@Override
		public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
			return new Setter(pos, state);
		}

		@Override
		public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
			setBy(level, pos, placer);
		}

		@Override
		protected boolean canSurvive(BlockState state, net.minecraft.world.level.LevelReader level, BlockPos pos) {
			return level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP);
		}

		@Override
		protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
			if (!(level instanceof ServerLevel server) || !(level.getBlockEntity(pos) instanceof Setter setter) || !foe(setter.owner, entity)) return;
			burst(server, pos, setter.owner);
		}

		/** A foe standing still on it springs it too. */
		@Override
		public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
			return level.isClientSide ? null : createTickerHelper(type, SETTER, (l, pos, s, setter) -> {
				if (l.getGameTime() % 5 == 0 && !l.getEntitiesOfClass(LivingEntity.class, new AABB(pos), e -> foe(setter.owner, e)).isEmpty())
					burst((ServerLevel) l, pos, setter.owner);
			});
		}
	}

	/** The nest bursts: poison round it (not its setter), no block broken; creep thrown two cells in eight ways. */
	public static void burst(ServerLevel level, BlockPos pos, @Nullable UUID setter) {
		level.removeBlock(pos, false);
		level.sendParticles(ParticleTypes.ITEM_SLIME, pos.getX() + 0.5, pos.getY() + 0.3, pos.getZ() + 0.5, 40, 1.2, 0.4, 1.2, 0.1);
		level.sendParticles(ParticleTypes.EXPLOSION, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 1, 0, 0, 0, 0);
		level.playSound(null, pos, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 1.0f, 1.4f);
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(pos).inflate(3), e -> foe(setter, e) || e instanceof Player p && setter == null)) {
			e.hurt(level.damageSources().magic(), NEST_DAMAGE);
			e.addEffect(new MobEffectInstance(MobEffects.POISON, 100, 1));
		}
		BlockState creep = StarMineBlocks.CREEP.defaultBlockState().setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), true);
		for (int dx = -1; dx <= 1; dx++)
			for (int dz = -1; dz <= 1; dz++) {
				if (dx == 0 && dz == 0) continue;
				for (int step = 1; step <= 2; step++)
					for (int dy = 1; dy >= -2; dy--) { // the ground near the nest's height: the first free cell over a floor
						BlockPos cell = pos.offset(dx * step, dy, dz * step);
						if (!level.getBlockState(cell).isAir() || !level.getBlockState(cell.below()).isFaceSturdy(level, cell.below(), Direction.UP)) continue;
						level.setBlock(cell, creep, Block.UPDATE_ALL);
						break;
					}
			}
	}
}
