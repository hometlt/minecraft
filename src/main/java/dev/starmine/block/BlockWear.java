package dev.starmine.block;

import dev.starmine.StarMine;
import dev.starmine.entity.UnitKind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

/**
 * Blocks keep their damage (owner 19.10). Every block but the unbreakable can be shot to pieces, and a block a player
 * began to mine and left keeps what was done ({@code ServerMiningMixin}, {@code ClientMiningMixin}): the cracks stay —
 * saved with the world, they do not pass by themselves as vanilla's do. How much a block takes goes with its hardness
 * ({@link #PER_HARDNESS} damage a point of it); armour blocks ({@link ArmorBlocks}) take {@link #ARMOURED} times more.
 *
 * The races' blocks have their ways:
 * - zerg: living, they heal a {@link #STEP} every {@link #ZERG_HEAL} ticks once left alone that long;
 * - terran: tough — half again as much to break ({@link #TERRAN_TOUGH}), blasts wear them half as much;
 * - protoss: a shield takes their first {@link #SHIELD_HITS} hits; it comes back {@link #SHIELD_TICKS} after the last.
 * The races' repair tools ({@code gear/RepairTools}) mend them: the terran's what is mechanical, the zerg's what lives,
 * the protoss' the shields.
 */
public final class BlockWear {
	public static final int ZERG_HEAL = 200, SHIELD_HITS = 5, SHIELD_TICKS = 600;
	public static final float PER_HARDNESS = 8, ARMOURED = 4, STEP = 0.1f, TERRAN_TOUGH = 1.5f;

	/** A block's damage: how worn (0..1), when last hit, the block it was (by id), its shield's hits taken. */
	static final class Wear {
		float progress;
		long lastHit;
		int block;
		int shieldTaken;
	}

	/** The worn blocks of a world, saved with it. */
	public static final class Data extends SavedData {
		private static final Factory<Data> FACTORY = new Factory<>(Data::new, Data::load, null);
		final Map<Long, Wear> wear = new HashMap<>();

		static Data get(ServerLevel level) {
			return level.getDataStorage().computeIfAbsent(FACTORY, "starmine_block_wear");
		}

		static Data load(CompoundTag tag, HolderLookup.Provider registries) {
			Data data = new Data();
			for (Tag t : tag.getList("Wear", Tag.TAG_COMPOUND)) {
				CompoundTag entry = (CompoundTag) t;
				Wear w = new Wear();
				w.progress = entry.getFloat("P");
				w.lastHit = entry.getLong("T");
				w.block = entry.getInt("B");
				w.shieldTaken = entry.getInt("S");
				data.wear.put(entry.getLong("At"), w);
			}
			return data;
		}

		@Override
		public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
			ListTag list = new ListTag();
			wear.forEach((at, w) -> {
				CompoundTag entry = new CompoundTag();
				entry.putLong("At", at);
				entry.putFloat("P", w.progress);
				entry.putLong("T", w.lastHit);
				entry.putInt("B", w.block);
				entry.putInt("S", w.shieldTaken);
				list.add(entry);
			});
			tag.put("Wear", list);
			return tag;
		}
	}

	/** To the clients near: how worn blocks are, so their mining starts from it. */
	public record Sync(List<Long> positions, List<Float> progress) implements CustomPacketPayload {
		public static final Type<Sync> TYPE = new Type<>(StarMine.id("block_wear"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Sync> CODEC = StreamCodec.of((buf, value) -> {
			buf.writeVarInt(value.positions.size());
			for (int i = 0; i < value.positions.size(); i++) {
				buf.writeLong(value.positions.get(i));
				buf.writeFloat(value.progress.get(i));
			}
		}, buf -> {
			int n = buf.readVarInt();
			List<Long> positions = new ArrayList<>(n);
			List<Float> progress = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				positions.add(buf.readLong());
				progress.add(buf.readFloat());
			}
			return new Sync(positions, progress);
		});

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	private BlockWear() {
	}

	public static void load() {
		PayloadTypeRegistry.playS2C().register(Sync.TYPE, Sync.CODEC);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(BlockWear::tick);
	}

	/** The race a block is of (its armour), or null. */
	public static @Nullable UnitKind.Race race(BlockState state) {
		return ArmorBlocks.raceOf(state.getBlock());
	}

	/** How much damage a block takes to break; 0 for one that cannot be broken (or is not a block: air, a fluid). */
	public static float durability(ServerLevel level, BlockPos pos, BlockState state) {
		if (state.isAir() || !state.getFluidState().isEmpty() && state.getCollisionShape(level, pos).isEmpty()) return 0;
		float hardness = state.getDestroySpeed(level, pos);
		if (hardness < 0) return 0;
		float tough = race(state) == UnitKind.Race.TERRAN ? TERRAN_TOUGH : 1;
		return Math.max(1, hardness * PER_HARDNESS) * (ArmorBlocks.armoured(state) ? ARMOURED : 1) * tough;
	}

	/** The block's damage record, made fresh when there is none or the block there is another now. */
	private static Wear wear(ServerLevel level, BlockPos pos, BlockState state, boolean make) {
		Data data = Data.get(level);
		Wear wear = data.wear.get(pos.asLong());
		int id = Block.getId(state);
		if (wear != null && wear.block != id) {
			data.wear.remove(pos.asLong());
			wear = null;
		}
		if (wear == null && make) {
			wear = new Wear();
			wear.block = id;
			data.wear.put(pos.asLong(), wear);
		}
		return wear;
	}

	/** A weapon's hit of `damage` on a block: its cracks grow (a protoss block's shield takes it first); true when it broke. */
	public static boolean hit(ServerLevel level, BlockPos pos, float damage, @Nullable Entity by) {
		BlockState state = level.getBlockState(pos);
		float durability = durability(level, pos, state);
		if (durability <= 0 || damage <= 0) return false;
		Wear wear = wear(level, pos, state, true);
		wear.lastHit = level.getGameTime();
		Data.get(level).setDirty();
		if (race(state) == UnitKind.Race.PROTOSS && wear.shieldTaken < SHIELD_HITS) { // the shield takes it
			wear.shieldTaken++;
			level.sendParticles(ParticleTypes.ELECTRIC_SPARK, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 12, 0.4, 0.4, 0.4, 0.1);
			level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_HIT, SoundSource.BLOCKS, 0.8f, 1.6f);
			return false;
		}
		wear.progress += damage / durability;
		if (wear.progress >= 1) {
			Data.get(level).wear.remove(pos.asLong());
			level.destroyBlockProgress(id(pos), pos, -1);
			level.destroyBlock(pos, true, by);
			sync(level, pos, 0);
			return true;
		}
		show(level, pos, wear);
		return false;
	}

	/** A player left a block half mined: the work done stays on it (the most of it and what was there). */
	public static void mined(ServerLevel level, BlockPos pos, float progress) {
		BlockState state = level.getBlockState(pos);
		if (progress <= 0 || durability(level, pos, state) <= 0) return;
		Wear wear = wear(level, pos, state, true);
		wear.progress = Math.max(wear.progress, Math.min(0.95f, progress));
		wear.lastHit = level.getGameTime();
		Data.get(level).setDirty();
		show(level, pos, wear);
	}

	/** A repair tool's work: `amount` of the damage mended. True when there was something to mend. */
	public static boolean mend(ServerLevel level, BlockPos pos, float amount) {
		Wear wear = wear(level, pos, level.getBlockState(pos), false);
		if (wear == null || wear.progress <= 0) return false;
		wear.progress -= amount;
		Data.get(level).setDirty();
		if (wear.progress <= 0.001f) forget(level, pos);
		else show(level, pos, wear);
		return true;
	}

	/** A protoss repair tool: the block's shield whole again. True when it was down. */
	public static boolean recharge(ServerLevel level, BlockPos pos) {
		Wear wear = wear(level, pos, level.getBlockState(pos), false);
		if (wear == null || wear.shieldTaken == 0) return false;
		wear.shieldTaken = 0;
		Data.get(level).setDirty();
		return true;
	}

	/** How worn a block is, 0..1. */
	public static float progress(ServerLevel level, BlockPos pos) {
		Wear wear = wear(level, pos, level.getBlockState(pos), false);
		return wear == null ? 0 : wear.progress;
	}

	/** How many hits a protoss block's shield has left. */
	public static int shield(ServerLevel level, BlockPos pos) {
		Wear wear = wear(level, pos, level.getBlockState(pos), false);
		return SHIELD_HITS - (wear == null ? 0 : wear.shieldTaken);
	}

	private static void forget(ServerLevel level, BlockPos pos) {
		Data.get(level).wear.remove(pos.asLong());
		Data.get(level).setDirty();
		level.destroyBlockProgress(id(pos), pos, -1);
		sync(level, pos, 0);
	}

	private static int id(BlockPos pos) {
		return pos.hashCode() ^ 0x5717_0000;
	}

	private static void show(ServerLevel level, BlockPos pos, Wear wear) {
		level.destroyBlockProgress(id(pos), pos, Math.min(9, (int) (wear.progress * 10)));
		sync(level, pos, wear.progress);
	}

	private static void sync(ServerLevel level, BlockPos pos, float progress) {
		Sync sync = new Sync(List.of(pos.asLong()), List.of(progress));
		for (ServerPlayer player : PlayerLookup.tracking(level, pos)) if (ServerPlayNetworking.canSend(player, Sync.TYPE)) ServerPlayNetworking.send(player, sync);
	}

	/**
	 * Every second, in each world's loaded part: blocks gone or changed are forgotten; zerg blocks left alone heal a step;
	 * protoss shields come back; cracks are shown again now and then (to players come since).
	 */
	static void tick(MinecraftServer server) {
		if (server.getTickCount() % 20 != 0) return;
		for (ServerLevel level : server.getAllLevels()) {
			Data data = Data.get(level);
			if (data.wear.isEmpty()) continue;
			long now = level.getGameTime();
			boolean again = now % 200 < 20;
			for (Iterator<Map.Entry<Long, Wear>> it = data.wear.entrySet().iterator(); it.hasNext(); ) {
				var entry = it.next();
				BlockPos pos = BlockPos.of(entry.getKey());
				if (!level.isLoaded(pos)) continue;
				Wear wear = entry.getValue();
				BlockState state = level.getBlockState(pos);
				if (Block.getId(state) != wear.block) {
					it.remove();
					data.setDirty();
					level.destroyBlockProgress(id(pos), pos, -1);
					continue;
				}
				UnitKind.Race race = race(state);
				long since = now - wear.lastHit;
				if (race == UnitKind.Race.PROTOSS && wear.shieldTaken > 0 && since >= SHIELD_TICKS) {
					wear.shieldTaken = 0;
					data.setDirty();
				}
				if (race == UnitKind.Race.ZERG && wear.progress > 0 && since >= ZERG_HEAL && since % ZERG_HEAL < 20) {
					wear.progress -= STEP;
					data.setDirty();
					if (wear.progress <= 0.001f && wear.shieldTaken == 0) {
						it.remove();
						level.destroyBlockProgress(id(pos), pos, -1);
						sync(level, pos, 0);
						continue;
					}
					show(level, pos, wear);
				} else if (again && wear.progress > 0) show(level, pos, wear);
			}
		}
	}

	/**
	 * A blast or beam on a block (the guardians' weapons): a block that is not armour nor harder than obsidian breaks at
	 * once; armour and obsidian take `damage` of wear (terran armour half of it). True when it broke.
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
		return hit(level, pos, race(state) == UnitKind.Race.TERRAN ? damage / 2 : damage, by);
	}
}
