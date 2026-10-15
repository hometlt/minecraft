package dev.starmine.world;

import dev.starmine.StarMine;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.HiveLogic;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The heart of a small dungeon ({@link SmallDungeons}): the world is generated on other threads, where
 * no unit may be made, so a dungeon keeps the list of its guards here. When a player comes within
 * {@link #WAKE} blocks, the guards appear where they were planned, keeping to the dungeon (nest
 * dwellers, {@link StarUnit#makeNest}); eggs lie dormant until disturbed. Then the heart becomes the
 * dungeon's own block ({@link Heart#look}).
 */
public final class DungeonHeart extends Block implements EntityBlock {
	public static final DungeonHeart BLOCK = Registry.register(BuiltInRegistries.BLOCK, StarMine.id("dungeon_heart"),
		new DungeonHeart(BlockBehaviour.Properties.of().strength(-1.0f, 3600000.0f).lightLevel(state -> 10)));
	public static final BlockEntityType<Heart> ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, StarMine.id("dungeon_heart"),
		BlockEntityType.Builder.of(Heart::new, BLOCK).build(null));
	/** How near a player wakes the guards. */
	public static final double WAKE = 32;

	private DungeonHeart(Properties properties) {
		super(properties);
	}

	public static void load() {
	}

	/** A guard to appear: its kind, where (relative to the heart), and an egg's brood. */
	public record Guard(UnitKind kind, BlockPos offset, @Nullable UnitKind brood, int count) {
	}

	public static class Heart extends BlockEntity {
		final List<Guard> guards = new ArrayList<>();
		/** The block the heart becomes once its guards are out. */
		ResourceLocation look = BuiltInRegistries.BLOCK.getKey(Blocks.CHISELED_STONE_BRICKS);

		public Heart(BlockPos pos, BlockState state) {
			super(ENTITY, pos, state);
		}

		public List<Guard> guards() {
			return guards;
		}

		public void add(UnitKind kind, BlockPos at, @Nullable UnitKind brood, int count) {
			guards.add(new Guard(kind, at.subtract(getBlockPos()), brood, count));
			setChanged();
		}

		public void look(Block block) {
			look = BuiltInRegistries.BLOCK.getKey(block);
			setChanged();
		}

		/** Puts the guards in the world and turns into the dungeon's block; the units made. */
		public List<StarUnit> wake(ServerLevel level) {
			List<StarUnit> made = new ArrayList<>();
			BlockPos hall = getBlockPos();
			for (Guard guard : guards) {
				Vec3 at = Vec3.atBottomCenterOf(hall.offset(guard.offset()));
				if (!guard.kind().structure()) at = dev.starmine.entity.Spots.near(level, guard.kind(), at);
				StarUnit unit = HiveLogic.spawnNest(level, null, guard.kind(), at, hall);
				if (unit == null) continue;
				if (guard.brood() != null) unit.becomeCocoon(guard.brood(), guard.count(), StarUnit.DORMANT);
				made.add(unit);
			}
			guards.clear();
			level.setBlock(hall, BuiltInRegistries.BLOCK.get(look).defaultBlockState(), 3);
			return made;
		}

		@Override
		protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
			super.saveAdditional(tag, registries);
			ListTag list = new ListTag();
			for (Guard guard : guards) {
				CompoundTag one = new CompoundTag();
				one.putString("kind", guard.kind().name());
				one.putInt("x", guard.offset().getX());
				one.putInt("y", guard.offset().getY());
				one.putInt("z", guard.offset().getZ());
				if (guard.brood() != null) {
					one.putString("brood", guard.brood().name());
					one.putInt("count", guard.count());
				}
				list.add(one);
			}
			tag.put("guards", list);
			tag.putString("look", look.toString());
		}

		@Override
		protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
			super.loadAdditional(tag, registries);
			guards.clear();
			for (Tag element : tag.getList("guards", Tag.TAG_COMPOUND)) {
				CompoundTag one = (CompoundTag) element;
				guards.add(new Guard(UnitKind.valueOf(one.getString("kind")), new BlockPos(one.getInt("x"), one.getInt("y"), one.getInt("z")),
					one.contains("brood") ? UnitKind.valueOf(one.getString("brood")) : null, one.getInt("count")));
			}
			if (tag.contains("look")) look = ResourceLocation.parse(tag.getString("look"));
		}
	}

	@Override
	public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new Heart(pos, state);
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
		if (level.isClientSide || type != ENTITY) return null;
		return (world, pos, blockState, entity) -> {
			if (world.getGameTime() % 20 != 0 || !(world instanceof ServerLevel server) || !(entity instanceof Heart heart)) return;
			if (server.getNearestPlayer(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, WAKE, player -> !player.isSpectator() && !player.isCreative()) != null)
				heart.wake(server);
		};
	}
}
