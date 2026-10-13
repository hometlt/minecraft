package dev.starmine.block;

import com.mojang.serialization.MapCodec;
import dev.starmine.creep.CreepSources;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * A two-block creep tentacle (StarMine's own art) on a creep floor; both halves go together and it
 * recedes with the creep.
 */
public class CreepTentacleBlock extends DoublePlantBlock {
	public static final MapCodec<CreepTentacleBlock> CODEC = simpleCodec(CreepTentacleBlock::new);

	public CreepTentacleBlock(Properties properties) {
		super(properties);
	}

	@Override
	public MapCodec<? extends DoublePlantBlock> codec() {
		return CODEC;
	}

	@Override
	protected boolean mayPlaceOn(BlockState state, BlockGetter level, BlockPos pos) {
		return state.isFaceSturdy(level, pos, Direction.UP);
	}

	@Override
	protected boolean isRandomlyTicking(BlockState state) {
		return state.getValue(HALF) == DoubleBlockHalf.LOWER;
	}

	@Override
	protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
		if (CreepSources.covered(level, pos)) return;
		level.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), 3);
		level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
	}
}
