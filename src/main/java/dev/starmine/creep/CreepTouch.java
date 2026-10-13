package dev.starmine.creep;

import dev.starmine.block.StarMineBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;

/** Whether something stands on creep: a creep layer at its feet, or thick creep under them. */
public final class CreepTouch {
	private CreepTouch() {
	}

	public static boolean onCreep(Entity entity) {
		BlockPos feet = entity.blockPosition();
		BlockState here = entity.level().getBlockState(feet);
		if (StarMineBlocks.isCreep(here)) return true;
		return entity.level().getBlockState(feet.below()).is(StarMineBlocks.THICK_CREEP);
	}
}
