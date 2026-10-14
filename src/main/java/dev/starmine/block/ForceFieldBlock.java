package dev.starmine.block;

import com.mojang.serialization.MapCodec;
import dev.starmine.entity.StarUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A force field (owner 14.10, the sentry's, docs/ABILITIES.md): a see-through wall that cannot be broken
 * by hand and goes away by itself after SC2 11 s — through a scheduled block tick, so it goes away even
 * after the world is saved and loaded meanwhile. Massive units (an ultralisk, a thor, a colossus…) walk
 * through it and shatter it (SC2); a ravager's bile melts it. It drops nothing.
 */
public class ForceFieldBlock extends Block {
	public static final MapCodec<ForceFieldBlock> CODEC = simpleCodec(ForceFieldBlock::new);
	/** SC2 force field: 11 s. */
	public static final int TICKS = 11 * 20;

	public ForceFieldBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected MapCodec<ForceFieldBlock> codec() {
		return CODEC;
	}

	@Override
	protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moved) {
		super.onPlace(state, level, pos, old, moved);
		if (!level.isClientSide && !old.is(this)) level.scheduleTick(pos, this, TICKS);
	}

	@Override
	protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
		level.removeBlock(pos, false);
	}

	/** Massive units pass (and break it, {@link #entityInside}); everyone else is stopped. */
	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		if (context instanceof EntityCollisionContext entity && entity.getEntity() instanceof StarUnit unit && unit.kind.massive()) return Shapes.empty();
		return Shapes.block();
	}

	@Override
	protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
		if (!level.isClientSide && entity instanceof StarUnit unit && unit.kind.massive()) level.removeBlock(pos, false);
	}

	@Override
	protected boolean skipRendering(BlockState state, BlockState neighbour, Direction direction) {
		return neighbour.is(this) || super.skipRendering(state, neighbour, direction);
	}

	@Override
	protected float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
		return 1.0f;
	}

	@Override
	protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
		return true;
	}
}
