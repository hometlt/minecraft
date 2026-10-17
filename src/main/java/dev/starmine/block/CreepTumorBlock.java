package dev.starmine.block;

import com.mojang.serialization.MapCodec;
import dev.starmine.creep.CreepSources;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Creep tumor (SC2 model): stands on any face of a block (floor, wall, ceiling) — FACING points away
 * from the block it grows on. It is a creep source of radius 10 and, like in SC2, spawns one more
 * tumor at the edge of its creep once ({@link CreepTumorBlockEntity}). Owner: it does not block walking,
 * can be hit and broken, lies on creep, and dies when the block it grows on is broken; owner 17.10: burrowed after
 * its first seconds, seen only by its side and detectors, worn down by shots ({@link dev.starmine.creep.CreepTumors}).
 */
public class CreepTumorBlock extends BaseEntityBlock {
	public static final MapCodec<CreepTumorBlock> CODEC = simpleCodec(CreepTumorBlock::new);
	public static final DirectionProperty FACING = BlockStateProperties.FACING;
	/** A flat outline (4 px) against the supporting face, for aiming at it; nothing to bump into. */
	private static final VoxelShape[] SHAPES = new VoxelShape[6];

	static {
		for (Direction d : Direction.values()) {
			double x0 = 3, y0 = 3, z0 = 3, x1 = 13, y1 = 13, z1 = 13;
			switch (d) {
				case UP -> { y0 = 0; y1 = 4; }
				case DOWN -> { y0 = 12; y1 = 16; }
				case NORTH -> { z0 = 12; z1 = 16; }
				case SOUTH -> { z0 = 0; z1 = 4; }
				case WEST -> { x0 = 12; x1 = 16; }
				case EAST -> { x0 = 0; x1 = 4; }
			}
			SHAPES[d.ordinal()] = Block.box(x0, y0, z0, x1, y1, z1);
		}
	}

	public CreepTumorBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.UP));
	}

	@Override
	protected MapCodec<CreepTumorBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	/** The block model is the creep under the tumor; the tumor itself is drawn by its block entity. */
	@Override
	protected RenderShape getRenderShape(BlockState state) {
		return RenderShape.MODEL;
	}

	/** It can be hit and broken (owner): a low outline on the face it grows from. */
	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPES[state.getValue(FACING).ordinal()];
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return Shapes.empty();
	}

	@Override
	protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
		Direction facing = state.getValue(FACING);
		BlockPos support = pos.relative(facing.getOpposite());
		return level.getBlockState(support).isFaceSturdy(level, support, facing);
	}

	@Override
	protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor, LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
		return canSurvive(state, level, pos) ? state : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new CreepTumorBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
		return level.isClientSide ? null : createTickerHelper(type, StarMineBlocks.CREEP_TUMOR_ENTITY, CreepTumorBlockEntity::serverTick);
	}

	@Override
	protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moved) {
		super.onPlace(state, level, pos, old, moved);
		if (!level.isClientSide && !old.is(this)) CreepSources.tumors(level).add(pos);
	}

	@Override
	protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moved) {
		if (!level.isClientSide && !replacement.is(this)) CreepSources.tumors(level).remove(pos);
		super.onRemove(state, level, pos, replacement, moved);
	}
}
