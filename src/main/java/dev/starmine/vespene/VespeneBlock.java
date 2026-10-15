package dev.starmine.vespene;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Liquid vespene (owner 14.10; a real liquid since 16.10): a thick glowing gas that fills vespene veins.
 * Poured out it flows slowly and not far, like lava, and never makes new sources. It is taken with a
 * bucket (or by a worker) and comes back over time from its vein ({@link VespeneRootBlockEntity}).
 * {@link #OUTLET}: the end of a vein, where taking it leaves an empty vent instead of air.
 */
public final class VespeneBlock extends LiquidBlock {
	public static final BooleanProperty OUTLET = BooleanProperty.create("outlet");

	public VespeneBlock(FlowingFluid fluid, Properties properties) {
		super(fluid, properties);
		registerDefaultState(stateDefinition.any().setValue(LEVEL, 0).setValue(OUTLET, false));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder);
		builder.add(OUTLET);
	}

	/** A full cell of vespene (not a flowing one). */
	public static boolean isSource(BlockState state) {
		return state.is(Vespene.VESPENE) && state.getValue(LEVEL) == 0;
	}

	/** Takes the vespene from a block: an outlet becomes an empty vent, a vein cell air. */
	public static void take(LevelAccessor level, BlockPos pos, BlockState state) {
		level.setBlock(pos, state.getValue(OUTLET) ? Vespene.VENT.defaultBlockState() : Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL_IMMEDIATE);
	}

	@Override
	public ItemStack pickupBlock(@Nullable Player player, LevelAccessor level, BlockPos pos, BlockState state) {
		if (state.getValue(LEVEL) != 0) return ItemStack.EMPTY;
		take(level, pos, state);
		return new ItemStack(Vespene.BUCKET);
	}

	/** Thick: whoever wades in it is slowed. */
	@Override
	protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
		entity.makeStuckInBlock(state, new Vec3(0.7, 0.6, 0.7));
	}
}
