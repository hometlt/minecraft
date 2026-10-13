package dev.starmine.block;

import com.mojang.serialization.MapCodec;
import dev.starmine.creep.CreepSources;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.MultifaceSpreader;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Creep: a thin layer on any of a block cell's six faces (floor, walls, ceiling), like sculk veins,
 * so a cave can be covered on every side. It grows from hatcheries and creep tumors
 * ({@link dev.starmine.creep.CreepGrowth}) and recedes, a cell at a time on random ticks, wherever
 * no hatchery or tumor reaches it any more.
 */
public class CreepBlock extends MultifaceBlock {
	public static final MapCodec<CreepBlock> CODEC = simpleCodec(CreepBlock::new);
	private final MultifaceSpreader spreader = new MultifaceSpreader(this);

	public CreepBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected MapCodec<CreepBlock> codec() {
		return CODEC;
	}

	@Override
	public MultifaceSpreader getSpreader() {
		return spreader;
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
