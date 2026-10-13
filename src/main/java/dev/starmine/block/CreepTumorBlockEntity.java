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
 * A creep tumor's life (owner): it grows creep (radius {@link CreepSources#TUMOR_RADIUS}) and once, 5 to
 * 10 minutes after it appears, spawns one new tumor at the edge of its creep — only where creep still
 * meets bare ground, and never within 8 blocks of another tumor — with a trail of slime running over
 * the creep to it. Then it is exhausted and only feeds its creep.
 */
public class CreepTumorBlockEntity extends BlockEntity {
	private int age, spreadAt;
	private boolean spawned;
	/** Game time it was planted (synced to clients: it turns invisible 5 s later). */
	private long plantedAt = -1;

	public CreepTumorBlockEntity(BlockPos pos, BlockState state) {
		super(StarMineBlocks.CREEP_TUMOR_ENTITY, pos, state);
	}

	public static void serverTick(Level level, BlockPos pos, BlockState state, CreepTumorBlockEntity tumor) {
		if (!(level instanceof ServerLevel server)) return;
		if (tumor.plantedAt < 0) {
			tumor.plantedAt = level.getGameTime();
			tumor.setChanged();
			level.sendBlockUpdated(pos, state, state, 3);
		}
		CreepGrowth.grow(server, pos, CreepSources.TUMOR_RADIUS);
		tumor.age++;
		if (tumor.spreadAt == 0) tumor.spreadAt = 6000 + level.random.nextInt(6001); // 5-10 minutes
		if (!tumor.spawned && tumor.age >= tumor.spreadAt && tumor.age % 100 == 0) {
			if (CreepGrowth.spawnTumor(server, pos, CreepSources.TUMOR_SPACING, CreepSources.TUMOR_RADIUS)) {
				tumor.spawned = true; // exhausted: it only feeds its creep from now on
				tumor.setChanged();
			}
		}
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries);
		tag.putInt("age", age);
		tag.putInt("spreadAt", spreadAt);
		tag.putBoolean("spawned", spawned);
		tag.putLong("plantedAt", plantedAt);
	}

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.loadAdditional(tag, registries);
		age = tag.getInt("age");
		spreadAt = tag.getInt("spreadAt");
		spawned = tag.getBoolean("spawned");
		plantedAt = tag.contains("plantedAt") ? tag.getLong("plantedAt") : -1;
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
		return saveWithoutMetadata(registries);
	}

	@Override
	public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getUpdatePacket() {
		return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
	}
}
