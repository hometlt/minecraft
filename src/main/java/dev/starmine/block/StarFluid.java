package dev.starmine.block;

import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;

/**
 * A StarMine liquid (owner 16.10): vespene and liquid creep. Thick like lava — it flows slowly and not far
 * ({@link Kind}); it never makes new sources. A pair of fluids, still and flowing, share one {@link Kind}.
 */
public abstract class StarFluid extends FlowingFluid {
	/** What a liquid is: its pair of fluids, bucket and block, how slow and how far it flows. */
	public record Kind(Supplier<FlowingFluid> source, Supplier<FlowingFluid> flowing, Supplier<Item> bucket, Supplier<Block> block,
		int tickDelay, int slopeFind, int dropOff) {
	}

	protected final Kind kind;

	protected StarFluid(Kind kind) {
		this.kind = kind;
	}

	@Override
	public Fluid getFlowing() {
		return kind.flowing().get();
	}

	@Override
	public Fluid getSource() {
		return kind.source().get();
	}

	@Override
	public Item getBucket() {
		return kind.bucket().get();
	}

	@Override
	public boolean isSame(Fluid fluid) {
		return fluid == getSource() || fluid == getFlowing();
	}

	@Override
	protected boolean canConvertToSource(Level level) {
		return false;
	}

	@Override
	protected void beforeDestroyingBlock(LevelAccessor level, BlockPos pos, BlockState state) {
		Block.dropResources(state, level, pos, state.hasBlockEntity() ? level.getBlockEntity(pos) : null);
	}

	@Override
	protected int getSlopeFindDistance(LevelReader level) {
		return kind.slopeFind();
	}

	@Override
	protected int getDropOff(LevelReader level) {
		return kind.dropOff();
	}

	@Override
	public int getTickDelay(LevelReader level) {
		return kind.tickDelay();
	}

	@Override
	protected float getExplosionResistance() {
		return 100f;
	}

	@Override
	protected boolean canBeReplacedWith(FluidState state, BlockGetter level, BlockPos pos, Fluid fluid, Direction direction) {
		return false;
	}

	@Override
	protected BlockState createLegacyBlock(FluidState state) {
		return kind.block().get().defaultBlockState().setValue(LiquidBlock.LEVEL, getLegacyLevel(state));
	}

	/** The still fluid: a source block. */
	public static class Source extends StarFluid {
		public Source(Kind kind) {
			super(kind);
		}

		@Override
		public boolean isSource(FluidState state) {
			return true;
		}

		@Override
		public int getAmount(FluidState state) {
			return 8;
		}
	}

	/** The flowing fluid. */
	public static class Flowing extends StarFluid {
		public Flowing(Kind kind) {
			super(kind);
		}

		@Override
		protected void createFluidStateDefinition(StateDefinition.Builder<Fluid, FluidState> builder) {
			super.createFluidStateDefinition(builder);
			builder.add(LEVEL);
		}

		@Override
		public boolean isSource(FluidState state) {
			return false;
		}

		@Override
		public int getAmount(FluidState state) {
			return state.getValue(LEVEL);
		}
	}
}
