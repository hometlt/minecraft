package dev.starmine.mineral;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import org.jetbrains.annotations.Nullable;

/**
 * A block of minerals (owner 19.10): nine mineral shards pressed into a blue block of crystal ({@link Minerals#BLOCK},
 * crafted), and the rich minerals, gold, found rarely deep in the stone ({@link Minerals#RICH_BLOCK}). It is mined like
 * any ore — the same cracks, by hand or tool, or by a worker — but it is not broken: each time it gives its shards
 * ({@link #shards}: 1, rich 2) and keeps the rest, {@link #CHARGES} times in all; then it is gone. A worker takes one
 * charge a trip as minerals ({@link #harvest}).
 */
public final class MineralBlock extends Block {
	public static final IntegerProperty CHARGES = IntegerProperty.create("charges", 1, 9);
	public static final int FULL = 9;
	/** Shards each charge gives (SC2's rich fields give more a trip). */
	public final int shards;

	public MineralBlock(Properties properties, int shards) {
		super(properties);
		this.shards = shards;
		registerDefaultState(stateDefinition.any().setValue(CHARGES, FULL));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(CHARGES);
	}

	/** Mined by a player: its shards drop, and the block stays with a charge less (the last one goes). */
	@Override
	public void playerDestroy(Level level, Player player, BlockPos pos, BlockState state, @Nullable BlockEntity entity, ItemStack tool) {
		super.playerDestroy(level, player, pos, state, entity, tool);
		popResource(level, pos, new ItemStack(Minerals.SHARD, shards));
		int left = state.getValue(CHARGES) - 1;
		if (left > 0) level.setBlock(pos, state.setValue(CHARGES, left), Block.UPDATE_ALL);
	}

	/** A worker's trip: one charge as minerals (its shards' worth); the block loses it. 0 when it is no mineral block. */
	public static int harvest(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof MineralBlock block)) return 0;
		int left = state.getValue(CHARGES) - 1;
		if (left > 0) level.setBlock(pos, state.setValue(CHARGES, left), Block.UPDATE_ALL);
		else level.removeBlock(pos, false);
		return block.shards * Minerals.PER_TRIP;
	}
}
