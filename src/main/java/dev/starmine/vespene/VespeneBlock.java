package dev.starmine.vespene;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import org.jetbrains.annotations.Nullable;

/**
 * Liquid vespene (owner 14.10): a thick glowing gas that fills vespene veins. It does not flow; it is
 * taken with a bucket (or by a worker) and comes back over time from its vein ({@link VespeneRootBlockEntity}).
 * {@link #OUTLET}: the end of a vein, where taking it leaves an empty vent instead of air.
 */
public final class VespeneBlock extends Block implements BucketPickup {
	public static final BooleanProperty OUTLET = BooleanProperty.create("outlet");

	public VespeneBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(OUTLET, false));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(OUTLET);
	}

	/** Takes the vespene from a block: an outlet becomes an empty vent, a vein cell air. */
	public static void take(LevelAccessor level, BlockPos pos, BlockState state) {
		level.setBlock(pos, state.getValue(OUTLET) ? Vespene.VENT.defaultBlockState() : Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL_IMMEDIATE);
	}

	@Override
	public ItemStack pickupBlock(@Nullable Player player, LevelAccessor level, BlockPos pos, BlockState state) {
		take(level, pos, state);
		return new ItemStack(Vespene.BUCKET);
	}

	@Override
	public Optional<SoundEvent> getPickupSound() {
		return Optional.of(SoundEvents.BUCKET_FILL);
	}
}
