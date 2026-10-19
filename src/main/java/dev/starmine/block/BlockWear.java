package dev.starmine.block;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Blocks worn down by weapons (owner 19.10): every block but the unbreakable can be shot to pieces. A shot works on a
 * block as a tool does — its cracks show and grow with each hit until it breaks — and the cracks stay: a block left
 * alone heals a step every {@link #HEAL_EVERY} ticks, from {@link #HEAL_EVERY} after its last hit. How much it takes
 * goes with its hardness ({@link #PER_HARDNESS} damage a point of it); armour blocks ({@link ArmorBlocks}) take
 * {@link #ARMOURED} times more than their hardness says, more than even obsidian. Not saved: cracks go with a restart.
 */
public final class BlockWear {
	public static final int HEAL_EVERY = 400;
	public static final float PER_HARDNESS = 8, ARMOURED = 4, STEP = 0.1f;

	private record Key(ResourceKey<Level> dimension, long pos) {
	}

	private static final class Wear {
		float progress;
		long lastHit;
		BlockState state;
	}

	private static final Map<Key, Wear> WEAR = new HashMap<>();

	private BlockWear() {
	}

	public static void load() {
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(BlockWear::tick);
	}

	/** How much damage a block takes to break; 0 for one that cannot be broken (or is not a block: air, a fluid). */
	public static float durability(ServerLevel level, BlockPos pos, BlockState state) {
		if (state.isAir() || !state.getFluidState().isEmpty() && state.getCollisionShape(level, pos).isEmpty()) return 0;
		float hardness = state.getDestroySpeed(level, pos);
		if (hardness < 0) return 0;
		return Math.max(1, hardness * PER_HARDNESS) * (ArmorBlocks.armoured(state) ? ARMOURED : 1);
	}

	/** A weapon's hit of `damage` on a block: its cracks grow; true when it broke. */
	public static boolean hit(ServerLevel level, BlockPos pos, float damage, @Nullable Entity by) {
		BlockState state = level.getBlockState(pos);
		float durability = durability(level, pos, state);
		if (durability <= 0 || damage <= 0) return false;
		Key key = new Key(level.dimension(), pos.asLong());
		Wear wear = WEAR.computeIfAbsent(key, k -> new Wear());
		if (wear.state != state) {
			wear.state = state;
			wear.progress = 0;
		}
		wear.progress += damage / durability;
		wear.lastHit = level.getGameTime();
		if (wear.progress >= 1) {
			WEAR.remove(key);
			level.destroyBlockProgress(id(pos), pos, -1);
			level.destroyBlock(pos, true, by);
			return true;
		}
		show(level, pos, wear);
		return false;
	}

	/** How worn a block is, 0..1 (tests). */
	public static float progress(ServerLevel level, BlockPos pos) {
		Wear wear = WEAR.get(new Key(level.dimension(), pos.asLong()));
		return wear == null ? 0 : wear.progress;
	}

	private static int id(BlockPos pos) {
		return pos.hashCode() ^ 0x5717_0000;
	}

	private static void show(ServerLevel level, BlockPos pos, Wear wear) {
		level.destroyBlockProgress(id(pos), pos, Math.min(9, (int) (wear.progress * 10)));
	}

	/** Every second: blocks gone or changed are forgotten; those left alone long enough heal a step. */
	static void tick(MinecraftServer server) {
		if (server.getTickCount() % 20 != 0 || WEAR.isEmpty()) return;
		for (Iterator<Map.Entry<Key, Wear>> it = WEAR.entrySet().iterator(); it.hasNext(); ) {
			var entry = it.next();
			ServerLevel level = server.getLevel(entry.getKey().dimension());
			BlockPos pos = BlockPos.of(entry.getKey().pos());
			Wear wear = entry.getValue();
			if (level == null || !level.isLoaded(pos) || level.getBlockState(pos) != wear.state) {
				it.remove();
				if (level != null) level.destroyBlockProgress(id(pos), pos, -1);
				continue;
			}
			long since = level.getGameTime() - wear.lastHit;
			if (since < HEAL_EVERY || since % HEAL_EVERY >= 20) {
				if (level.getGameTime() % 200 == 0) show(level, pos, wear); // shown again now and then (to players come since)
				continue;
			}
			wear.progress -= STEP;
			if (wear.progress <= 0.001f) {
				it.remove();
				level.destroyBlockProgress(id(pos), pos, -1);
			} else show(level, pos, wear);
		}
	}

	/**
	 * A blast or beam on a block (the guardians' weapons): a block that is not armour nor harder than obsidian breaks at
	 * once; armour and obsidian take `damage` of wear. True when it broke.
	 */
	public static boolean strike(ServerLevel level, BlockPos pos, float damage, @Nullable Entity by) {
		BlockState state = level.getBlockState(pos);
		if (state.isAir()) return false;
		float hardness = state.getDestroySpeed(level, pos);
		if (hardness < 0 || level.getBlockEntity(pos) != null && !ArmorBlocks.armoured(state)) return false;
		if (hardness < 50 && !ArmorBlocks.armoured(state)) {
			level.destroyBlock(pos, false, by);
			return true;
		}
		return hit(level, pos, damage, by);
	}
}
