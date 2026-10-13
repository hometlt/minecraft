package dev.starmine.block;

import dev.starmine.creep.CreepGrowth;
import dev.starmine.creep.CreepSources;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A creep tumor's life: it grows creep (radius {@link CreepSources#TUMOR_RADIUS}) and, 11 s after it
 * appears (SC2), spawns one new tumor at the edge of its creep; then it only feeds its creep.
 */
public class CreepTumorBlockEntity extends BlockEntity {
	private int age;
	private boolean spawned;

	public CreepTumorBlockEntity(BlockPos pos, BlockState state) {
		super(StarMineBlocks.CREEP_TUMOR_ENTITY, pos, state);
	}

	public static void serverTick(Level level, BlockPos pos, BlockState state, CreepTumorBlockEntity tumor) {
		if (!(level instanceof ServerLevel server)) return;
		CreepGrowth.grow(server, pos, CreepSources.TUMOR_RADIUS);
		tumor.age++;
		if (!tumor.spawned && tumor.age >= 220 && tumor.age % 40 == 0) {
			if (CreepGrowth.spawnTumor(server, pos, 6, CreepSources.TUMOR_RADIUS)) {
				tumor.spawned = true;
				tumor.setChanged();
			}
		}
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries);
		tag.putInt("age", age);
		tag.putBoolean("spawned", spawned);
	}

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.loadAdditional(tag, registries);
		age = tag.getInt("age");
		spawned = tag.getBoolean("spawned");
	}
}
