package dev.starmine.vespene;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Vespene canister (owner 14.10): holds up to {@link #CAPACITY} buckets of vespene; one per stack.
 * Right click with a vespene bucket pours it in, with an empty bucket draws one out. Broken, it keeps
 * its fill (loot table `copy_state`); a full one handed in at a command center gives its vespene.
 */
public final class VespeneCanisterBlock extends Block {
	public static final int CAPACITY = 4;
	public static final IntegerProperty FILL = IntegerProperty.create("fill", 0, CAPACITY);
	private static final VoxelShape SHAPE = Block.box(3, 0, 3, 13, 16, 13);

	public VespeneCanisterBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(FILL, 0));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FILL);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	@Override
	protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
			InteractionHand hand, BlockHitResult hit) {
		int fill = state.getValue(FILL);
		boolean pour = stack.is(Vespene.BUCKET) && fill < CAPACITY, draw = stack.is(Items.BUCKET) && fill > 0;
		if (!pour && !draw) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
		if (!level.isClientSide) {
			level.setBlockAndUpdate(pos, state.setValue(FILL, fill + (pour ? 1 : -1)));
			player.setItemInHand(hand, ItemUtils.createFilledResult(stack, player, new ItemStack(pour ? Items.BUCKET : Vespene.BUCKET)));
			level.playSound(null, pos, pour ? SoundEvents.BUCKET_EMPTY : SoundEvents.BUCKET_FILL, SoundSource.BLOCKS, 1.0f, 1.0f);
		}
		return ItemInteractionResult.sidedSuccess(level.isClientSide);
	}

	/** The fill of a canister item (its block state component), 0 when it has none. */
	public static int fill(ItemStack stack) {
		var props = stack.get(net.minecraft.core.component.DataComponents.BLOCK_STATE);
		Integer fill = props == null ? null : props.get(FILL);
		return fill == null ? 0 : fill;
	}

	public static void setFill(ItemStack stack, int fill) {
		stack.set(net.minecraft.core.component.DataComponents.BLOCK_STATE,
			net.minecraft.world.item.component.BlockItemStateProperties.EMPTY.with(FILL, fill));
	}
}
