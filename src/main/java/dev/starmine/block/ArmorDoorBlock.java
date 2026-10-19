package dev.starmine.block;

import com.mojang.serialization.MapCodec;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * A race's door (owner 19.10): a zerg sphincter that opens like a valve, a terran airlock, a protoss gate whose shut
 * face is a force field. Door blocks side by side are one door — a 2 x 2 or 3 x 3 (up to {@link #MOST} blocks) that
 * opens and shuts whole; break any block of it and all of it breaks. It opens by itself while a friendly player or unit
 * is within {@link #NEAR} blocks: its placer and their units; a door no one placed (a ship's, a base's) opens for any
 * player or unit, and the race's own. Open, it can be walked through.
 */
public class ArmorDoorBlock extends BaseEntityBlock {
	public static final BooleanProperty OPEN = BooleanProperty.create("open");
	public static final int MOST = 64, CHECK = 8;
	public static final double NEAR = 2.5;
	public final UnitKind.Race race;

	public ArmorDoorBlock(UnitKind.Race race, Properties properties) {
		super(properties);
		this.race = race;
		registerDefaultState(stateDefinition.any().setValue(OPEN, false));
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return simpleCodec(properties -> new ArmorDoorBlock(race, properties));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
		builder.add(OPEN);
	}

	@Override
	protected RenderShape getRenderShape(BlockState state) {
		return RenderShape.MODEL;
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return state.getValue(OPEN) ? Shapes.empty() : Shapes.block();
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return state.getValue(OPEN) ? Shapes.box(0, 0, 0, 1, 0.0625, 1) : Shapes.block();
	}

	@Override
	public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new Owner(pos, state);
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		if (placer instanceof Player player && level.getBlockEntity(pos) instanceof Owner owner) owner.owner = player.getUUID();
	}

	@Override
	protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moved) {
		super.onPlace(state, level, pos, old, moved);
		if (!level.isClientSide && !old.is(this)) level.scheduleTick(pos, this, CHECK);
	}

	/** Every few ticks: the whole door opens if a friend is near any block of it, shuts when none is. */
	@Override
	protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
		level.scheduleTick(pos, this, CHECK);
		Set<BlockPos> door = door(level, pos);
		if (!pos.equals(door.stream().min(BlockPos::compareTo).orElse(pos))) return; // one block decides for the whole door
		UUID owner = null;
		for (BlockPos part : door) if (level.getBlockEntity(part) instanceof Owner o && o.owner != null) owner = o.owner;
		AABB box = null;
		for (BlockPos part : door) box = box == null ? new AABB(part) : box.minmax(new AABB(part));
		final UUID placer = owner;
		boolean friend = !level.getEntitiesOfClass(LivingEntity.class, box.inflate(NEAR), e -> e.isAlive() && friendly(e, placer)).isEmpty();
		if (friend == state.getValue(OPEN)) return;
		for (BlockPos part : door) level.setBlock(part, level.getBlockState(part).setValue(OPEN, friend), 3);
		level.playSound(null, pos, sound(friend), SoundSource.BLOCKS, 1f, race == UnitKind.Race.ZERG ? 0.6f : 1f);
	}

	private SoundEvent sound(boolean open) {
		return switch (race) {
			case ZERG -> open ? SoundEvents.SLIME_SQUISH : SoundEvents.HONEY_BLOCK_SLIDE;
			case TERRAN -> open ? SoundEvents.PISTON_EXTEND : SoundEvents.PISTON_CONTRACT;
			case PROTOSS -> open ? SoundEvents.BEACON_DEACTIVATE : SoundEvents.BEACON_ACTIVATE;
		};
	}

	/** Who a door opens for: its placer and their units; one no one placed opens for any player or unit (spectators aside). */
	boolean friendly(LivingEntity entity, @Nullable UUID owner) {
		if (entity instanceof Player player) return !player.isSpectator() && (owner == null || owner.equals(player.getUUID()));
		if (!(entity instanceof StarUnit unit)) return false;
		if (owner == null) return true;
		return owner.equals(unit.owner().orElse(null));
	}

	/** The door a block belongs to: the door blocks of its race joined to it face to face (at most {@link #MOST}). */
	public Set<BlockPos> door(Level level, BlockPos from) {
		Set<BlockPos> seen = new HashSet<>(List.of(from.immutable()));
		ArrayDeque<BlockPos> queue = new ArrayDeque<>(seen);
		while (!queue.isEmpty() && seen.size() < MOST) {
			BlockPos at = queue.poll();
			for (Direction direction : Direction.values()) {
				BlockPos next = at.relative(direction);
				if (!seen.contains(next) && level.getBlockState(next).is(this)) {
					seen.add(next.immutable());
					queue.add(next.immutable());
				}
			}
		}
		return seen;
	}

	/** One block of a door broken (by hand, a weapon, a blast): the rest of it goes too. */
	@Override
	protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState now, boolean moved) {
		super.onRemove(state, level, pos, now, moved);
		if (level.isClientSide || now.is(this) || moved) return;
		for (Direction direction : Direction.values()) {
			BlockPos next = pos.relative(direction);
			if (level.getBlockState(next).is(this)) level.destroyBlock(next, true);
		}
	}

	/** Who placed a door block (none for generated doors). */
	public static class Owner extends BlockEntity {
		@Nullable UUID owner;

		public Owner(BlockPos pos, BlockState state) {
			super(ArmorBlocks.DOOR_ENTITY, pos, state);
		}

		@Override
		protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
			super.saveAdditional(tag, registries);
			if (owner != null) tag.putUUID("Owner", owner);
		}

		@Override
		protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
			super.loadAdditional(tag, registries);
			owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
		}
	}

	/** Whether an entity is in a door block's way when it shuts (it opens again then). */
	static boolean blocked(Level level, BlockPos pos) {
		return !level.getEntitiesOfClass(Entity.class, new AABB(pos)).isEmpty();
	}
}
