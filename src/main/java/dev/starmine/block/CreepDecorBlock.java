package dev.starmine.block;

import com.mojang.serialization.MapCodec;
import dev.starmine.creep.CreepSources;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Small growths on creep floors (owner: so creep-ravaged ground is not all alike): tendrils,
 * pustules, vessels — StarMine's own Minecraft-style art with a creep layer under them. They count as
 * creep, have no collision, and recede with the creep when no source reaches them.
 */
public class CreepDecorBlock extends Block {
	public static final MapCodec<CreepDecorBlock> CODEC = simpleCodec(CreepDecorBlock::new);
	private static final VoxelShape SHAPE = Block.box(2, 0, 2, 14, 10, 14);

	public CreepDecorBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected MapCodec<? extends Block> codec() {
		return CODEC;
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	@Override
	protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
		return level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP);
	}

	@Override
	protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor, LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
		return canSurvive(state, level, pos) ? state : Blocks.AIR.defaultBlockState();
	}

	@Override
	protected boolean isRandomlyTicking(BlockState state) {
		return true;
	}

	@Override
	protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
		if (!CreepSources.covered(level, pos)) level.removeBlock(pos, false);
	}
}
