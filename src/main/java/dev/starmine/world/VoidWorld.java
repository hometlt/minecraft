package dev.starmine.world;

import dev.starmine.StarMine;
import dev.starmine.entity.StarUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The Void (owner 17.10): Amun's dimension, reached only through the portal a whole xel'naga artifact opens
 * ({@link Artifact}). One arrives on its first island, by the portal back ({@link #RETURN}): a pool in a ring
 * of frame in the island's floor, which leads to the artifact that opened the way last. Players step or fly
 * into a portal; units of a player sent onto it with a move order go through themselves.
 */
public final class VoidWorld {
	public static final ResourceKey<Level> KEY = ResourceKey.create(Registries.DIMENSION, StarMine.id("void"));
	/** The middle of the portal back, on the first island. */
	public static final BlockPos RETURN = new BlockPos(0, 64, 0);
	/** Ticks one who passed is not carried again. */
	static final int REST = 80;
	private static final Map<UUID, Long> RESTING = new HashMap<>();

	private VoidWorld() {
	}

	public static @Nullable ServerLevel level(MinecraftServer server) {
		return server.getLevel(KEY);
	}

	public static boolean is(Level level) {
		return level.dimension() == KEY;
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 10 != 0) return;
			ServerLevel there = level(server);
			if (there == null || there.players().isEmpty() && there.getForcedChunks().isEmpty()) return;
			AABB box = new AABB(RETURN).inflate(Artifact.PORTAL_RADIUS + 3, 3, Artifact.PORTAL_RADIUS + 3);
			for (StarUnit unit : there.getEntitiesOfClass(StarUnit.class, box, unit -> unit.isAlive() && unit.owner().isPresent()
				&& !unit.kind.structure() && !unit.isPassenger() && unit.order() == StarUnit.Order.MOVE && unit.orderPos() != null
				&& box.contains(unit.orderPos())))
				send(there, unit);
		});
	}

	/** An entity in a portal's pool in `level`: into the Void, or out of it. */
	static void pass(ServerLevel level, Entity entity) {
		ServerLevel to = is(level) ? back(level.getServer()) : level(level.getServer());
		if (to == null) return;
		long now = level.getGameTime();
		Long resting = RESTING.get(entity.getUUID());
		if (resting != null && now < resting) return;
		Vec3 at = is(level) ? landing(to) : arrival(to);
		for (Entity rider : entity.getSelfAndPassengers().toList()) RESTING.put(rider.getUUID(), now + REST);
		level.playSound(null, entity.blockPosition(), SoundEvents.PORTAL_TRAVEL, SoundSource.BLOCKS, 0.4f, 1.4f);
		Entity moved = entity.changeDimension(new DimensionTransition(to, at, Vec3.ZERO, entity.getYRot(), entity.getXRot(), DimensionTransition.DO_NOTHING));
		if (moved == null) return;
		to.playSound(null, BlockPos.containing(at), SoundEvents.ENDERMAN_TELEPORT, SoundSource.BLOCKS, 1.0f, 0.7f);
		for (Entity arrived : moved.getSelfAndPassengers().toList()) {
			RESTING.put(arrived.getUUID(), now + REST);
			if (arrived instanceof ServerPlayer player)
				player.displayClientMessage(Component.translatable(is(to) ? "message.starmine.void_in" : "message.starmine.void_out"), true);
		}
		if (RESTING.size() > 256) RESTING.values().removeIf(until -> until < now);
	}

	/** A unit of a player sent onto a portal goes through it, and waits there for its next order. */
	static void send(ServerLevel level, StarUnit unit) {
		unit.command(StarUnit.Order.NONE, null);
		pass(level, unit);
	}

	/** Where one comes out of the Void: by the artifact that opened the way last (its world's spawn when there is none). */
	static @Nullable ServerLevel back(MinecraftServer server) {
		State state = State.get(server);
		ServerLevel level = state.open.isEmpty() ? null : server.getLevel(state.open.get(state.open.size() - 1).dimension());
		return level != null ? level : server.overworld();
	}

	/** The ground next to the artifact that opened the way last. */
	static Vec3 landing(ServerLevel level) {
		State state = State.get(level.getServer());
		BlockPos pedestal = null;
		for (int i = state.open.size() - 1; i >= 0 && pedestal == null; i--)
			if (state.open.get(i).dimension() == level.dimension()) pedestal = state.open.get(i).pos();
		BlockPos near = pedestal != null ? pedestal.offset(3, 0, 0) : level.getSharedSpawnPos();
		level.getChunk(near.getX() >> 4, near.getZ() >> 4);
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, near.getX(), near.getZ());
		return new Vec3(near.getX() + 0.5, Math.max(y, level.getMinBuildHeight() + 1), near.getZ() + 0.5);
	}

	/** Where one comes into the Void: on its first island, beside the portal back (built the first time). */
	static Vec3 arrival(ServerLevel level) {
		level.getChunk(RETURN.getX() >> 4, RETURN.getZ() >> 4);
		if (!level.getBlockState(RETURN.below()).is(Artifact.FRAME)) firstIsland(level);
		return new Vec3(RETURN.getX() + 0.5, RETURN.getY() + 1, RETURN.getZ() + Artifact.PORTAL_RADIUS + 4.5);
	}

	/** The first island: a shelf of stone round the portal back, sunk in its floor. */
	static void firstIsland(ServerLevel level) {
		int r = Artifact.PORTAL_RADIUS;
		for (int dx = -14; dx <= 14; dx++)
			for (int dz = -14; dz <= 14; dz++) {
				double d = Math.hypot(dx, dz);
				if (d > 13.5) continue;
				int depth = (int) Math.round(6 * (1 - d / 14) + 1 + ((dx * 31 + dz * 17) & 1));
				for (int down = 1; down <= depth; down++)
					level.setBlock(RETURN.offset(dx, -down, dz), (down == 1 ? Blocks.POLISHED_BLACKSTONE : down < 3 ? Blocks.BLACKSTONE : Blocks.OBSIDIAN)
						.defaultBlockState(), Block.UPDATE_CLIENTS);
				for (int up = 0; up <= 4; up++) level.setBlock(RETURN.offset(dx, up, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
			}
		for (int dx = -r - 1; dx <= r + 1; dx++)
			for (int dz = -r - 1; dz <= r + 1; dz++) {
				boolean rim = Math.abs(dx) == r + 1 || Math.abs(dz) == r + 1;
				level.setBlock(RETURN.offset(dx, -1, dz), Artifact.FRAME.defaultBlockState(), Block.UPDATE_CLIENTS);
				level.setBlock(RETURN.offset(dx, 0, dz), (rim ? Artifact.FRAME : Artifact.PORTAL).defaultBlockState(), Block.UPDATE_CLIENTS);
				if (rim && Math.abs(dx) == r + 1 && Math.abs(dz) == r + 1)
					for (int up = 1; up <= 2; up++) level.setBlock(RETURN.offset(dx, up, dz), Artifact.FRAME.defaultBlockState(), Block.UPDATE_CLIENTS);
			}
	}

	/** What the Void remembers: the artifacts standing whole (the last one is where the portal back leads). */
	public static final class State extends SavedData {
		public record Open(ResourceKey<Level> dimension, BlockPos pos) {
		}

		final List<Open> open = new ArrayList<>();

		private static final SavedData.Factory<State> FACTORY = new SavedData.Factory<>(State::new, State::load, null);

		public static State get(MinecraftServer server) {
			return server.overworld().getDataStorage().computeIfAbsent(FACTORY, "starmine_void");
		}

		void opened(ServerLevel level, BlockPos pedestal) {
			closed(level, pedestal);
			open.add(new Open(level.dimension(), pedestal.immutable()));
			setDirty();
		}

		void closed(ServerLevel level, BlockPos pedestal) {
			if (open.removeIf(o -> o.dimension() == level.dimension() && o.pos().equals(pedestal))) setDirty();
		}

		public List<Open> open() {
			return java.util.Collections.unmodifiableList(open);
		}

		@Override
		public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
			ListTag list = new ListTag();
			for (Open o : open) {
				CompoundTag entry = new CompoundTag();
				entry.putString("dim", o.dimension().location().toString());
				entry.put("pos", NbtUtils.writeBlockPos(o.pos()));
				list.add(entry);
			}
			tag.put("open", list);
			return tag;
		}

		static State load(CompoundTag tag, HolderLookup.Provider registries) {
			State state = new State();
			for (Tag t : tag.getList("open", Tag.TAG_COMPOUND)) {
				CompoundTag entry = (CompoundTag) t;
				NbtUtils.readBlockPos(entry, "pos").ifPresent(pos -> state.open.add(new Open(
					ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(entry.getString("dim"))), pos)));
			}
			return state;
		}
	}
}
