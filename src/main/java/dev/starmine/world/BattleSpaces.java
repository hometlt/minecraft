package dev.starmine.world;

import dev.starmine.entity.UnitKind;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;

/** Headroom for the largest current unit; small side passages may keep their original size. */
public final class BattleSpaces {
	public static final int PASSAGE_WIDTH = 15, HEADROOM = 20, BASE_RADIUS = 32;

	private BattleSpaces() {}

	/** Open natural terrain around an underground player base, leaving built structures and block entities alone. */
	public static void clearBase(ServerLevel level, BlockPos feet) {
		if (level.canSeeSky(feet)) return;
		for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-BASE_RADIUS, 0, -BASE_RADIUS), feet.offset(BASE_RADIUS, HEADROOM - 1, BASE_RADIUS))) {
			var state = level.getBlockState(pos);
			if (level.getBlockEntity(pos) != null || !state.getFluidState().isEmpty()) continue;
			if (state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.BASE_STONE_NETHER) || state.is(BlockTags.DIRT)
				|| state.is(Blocks.END_STONE) || state.is(Blocks.SCULK) || state.is(Blocks.GRAVEL))
				level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
		}
	}

	public static boolean fits(UnitKind kind) {
		return kind.width + 2 <= PASSAGE_WIDTH && kind.height + 2 <= HEADROOM;
	}
}
