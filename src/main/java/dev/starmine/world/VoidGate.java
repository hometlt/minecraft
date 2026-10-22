package dev.starmine.world;

import dev.starmine.StarMine;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Six crystal arms around a world's stone become a paired, destructible spherical gate between worlds. */
public final class VoidGate {
	/** Owner 20.10: sixteen blocks in the Void equal one in any outside world (no extra Nether multiplier). */
	public static final int SCALE = 16;
	public static final Item SHARD = Registry.register(BuiltInRegistries.ITEM, StarMine.id("void_shard"), new Item(new Item.Properties().rarity(Rarity.UNCOMMON)));
	/** The old block is retained for saved worlds; it no longer bypasses the six-arm construction. */
	public static final Block GATE = Registry.register(BuiltInRegistries.BLOCK, StarMine.id("void_gate"), new Block(BlockBehaviour.Properties.of().strength(5, 1200)));
	static { Registry.register(BuiltInRegistries.ITEM, StarMine.id("void_gate"), new BlockItem(GATE, new Item.Properties())); }
	private VoidGate() { }

	public static void load() {
		net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.INGREDIENTS).register(entries -> entries.accept(SHARD));
		net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			ItemStack held = player.getItemInHand(hand);
			if (!held.is(Items.FLINT_AND_STEEL) || !level.getBlockState(hit.getBlockPos()).is(VoidCraft.BLOCK)) return InteractionResult.PASS;
			if (!player.mayBuild()) return InteractionResult.FAIL;
			if (level.isClientSide) return InteractionResult.SUCCESS;
			if (!(level instanceof ServerLevel server) || !(player instanceof ServerPlayer traveller)) return InteractionResult.PASS;
			if (!activate(server, hit.getBlockPos(), traveller)) {
				player.displayClientMessage(Component.translatable("message.starmine.gate_pattern"), true);
				return InteractionResult.FAIL;
			}
			held.hurtAndBreak(1, player, hand == net.minecraft.world.InteractionHand.MAIN_HAND ? net.minecraft.world.entity.EquipmentSlot.MAINHAND : net.minecraft.world.entity.EquipmentSlot.OFFHAND);
			return InteractionResult.CONSUME;
		});
	}

	/** The centre's stone selects the destination, independently of the source world. */
	public static @Nullable ResourceKey<Level> world(Block block) {
		if (block == Blocks.STONE) return Level.OVERWORLD;
		if (block == Blocks.NETHERRACK) return Level.NETHER;
		if (block == Blocks.END_STONE) return Level.END;
		if (block == VoidIslands.STONE) return VoidWorld.KEY;
		if (block == Blocks.QUARTZ_BLOCK) return Stratosphere.KEY;
		return null;
	}

	/** Radius 1..4 when all six arms have the same length, otherwise zero. No blocks are consumed on failure. */
	public static int radius(ServerLevel level, BlockPos centre) {
		int radius = 0;
		for (Direction dir : Direction.values()) {
			int length = 0;
			while (length < 5 && level.getBlockState(centre.relative(dir, length + 1)).is(VoidCraft.BLOCK)) length++;
			if (length < 1 || length > 4 || radius != 0 && length != radius) return 0;
			radius = length;
		}
		return radius;
	}

	/** Activates a centre or one of its arms: validates both destinations before removing the construction. */
	public static boolean activate(ServerLevel level, BlockPos clicked, ServerPlayer player) {
		List<BlockPos> candidates = new ArrayList<>();
		candidates.add(clicked);
		for (Direction dir : Direction.values()) for (int n = 1; n <= 4; n++) candidates.add(clicked.relative(dir, n));
		for (BlockPos centre : candidates) {
			ResourceKey<Level> selected = world(level.getBlockState(centre).getBlock());
			if (selected == null || selected == level.dimension()) continue;
			int radius = radius(level, centre);
			if (radius == 0) continue;
			boolean allowed = level.mayInteract(player, centre);
			for (Direction dir : Direction.values()) for (int n = 1; n <= radius; n++) allowed &= level.mayInteract(player, centre.relative(dir, n));
			if (!allowed) return false;
			ServerLevel to = level.getServer().getLevel(selected);
			if (to == null) return false;
			if (VoidWorld.is(to)) VoidWorld.prepare(to);
			BlockPos column = target(level, to, centre);
			Vec3 other = landing(to, column, radius * 2 + 1);
			if (other == null) return false;
			Vec3 here = Vec3.atCenterOf(centre).add(0, -radius - 0.5, 0);
			// The six arms must stand in open space; do not leave the new sphere inside unrelated building blocks.
			for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-radius, -radius, -radius), centre.offset(radius, radius, radius))) {
				boolean arm = (pos.getX() == centre.getX() ? 1 : 0) + (pos.getY() == centre.getY() ? 1 : 0) + (pos.getZ() == centre.getZ() ? 1 : 0) >= 2;
				if (!arm && !level.getBlockState(pos).canBeReplaced()) return false;
			}
			if (pair(level, here, to, other, radius * 2 + 1, player.getUUID()) == null) return false;
			level.setBlock(centre, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			for (Direction dir : Direction.values()) for (int n = 1; n <= radius; n++) level.setBlock(centre.relative(dir, n), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			platform(level, here, radius);
			platform(to, other, radius);
			return true;
		}
		return false;
	}

	/** Maps horizontal coordinates only; vertical placement is found safely in the destination. */
	public static BlockPos target(Level from, BlockPos pos) {
		return VoidWorld.is(from) ? new BlockPos(Math.floorDiv(pos.getX(), SCALE), 0, Math.floorDiv(pos.getZ(), SCALE))
			: new BlockPos(pos.getX() * SCALE, 0, pos.getZ() * SCALE);
	}

	public static BlockPos target(Level from, Level to, BlockPos pos) {
		if (VoidWorld.is(from)) return new BlockPos(Math.floorDiv(pos.getX(), SCALE), 0, Math.floorDiv(pos.getZ(), SCALE));
		if (VoidWorld.is(to)) return new BlockPos(pos.getX() * SCALE, 0, pos.getZ() * SCALE);
		double scale = from.dimensionType().coordinateScale() / to.dimensionType().coordinateScale();
		return new BlockPos(net.minecraft.util.Mth.floor(pos.getX() * scale), 0, net.minecraft.util.Mth.floor(pos.getZ() * scale));
	}

	/** An empty volume near the mapped column. Never clears a player's buildings, a Nether roof or a fluid. */
	static @Nullable Vec3 landing(ServerLevel level, BlockPos column, int size) {
		if (!level.getWorldBorder().isWithinBounds(column.offset(-size, 0, -size)) || !level.getWorldBorder().isWithinBounds(column.offset(size, 0, size))) return null;
		level.getChunkAt(column);
		int start = level.dimension() == Level.NETHER ? 64 : Math.max(VoidWorld.is(level) ? 80 : 64,
			level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ()));
		int top = Math.min(level.getMaxBuildHeight() - size - 1, level.dimension() == Level.NETHER ? 119 - size : level.getMaxBuildHeight() - size - 1);
		int r = size / 2;
		for (int step = 0; step < 256; step++) {
			int y = start + (step % 2 == 0 ? step / 2 : -(step + 1) / 2);
			if (y < level.getMinBuildHeight() + 2 || y > top) continue;
			boolean clear = true;
			for (BlockPos at : BlockPos.betweenClosed(column.offset(-r, y, -r), column.offset(r, y + size - 1, r))) {
				if (!level.getBlockState(at).isAir()) { clear = false; break; }
			}
			if (clear) return new Vec3(column.getX() + 0.5, y, column.getZ() + 0.5);
		}
		return null;
	}

	private static void platform(ServerLevel level, Vec3 feet, int radius) {
		BlockPos floor = BlockPos.containing(feet).below();
		for (BlockPos pos : BlockPos.betweenClosed(floor.offset(-radius - 1, 0, -radius - 1), floor.offset(radius + 1, 0, radius + 1)))
			if (level.getBlockState(pos).canBeReplaced()) level.setBlock(pos, VoidIslands.BRICKS.defaultBlockState(), Block.UPDATE_ALL);
	}

	/** Creates both entities with one durable pair id; callers may provide an already prepared landing. */
	public static @Nullable VoidRift pair(ServerLevel from, Vec3 here, ServerLevel to, Vec3 there, int size, UUID owner) {
		if (from == to || size < 3 || size > 9 || size % 2 == 0) return null;
		from.getChunkAt(BlockPos.containing(here));
		to.getChunkAt(BlockPos.containing(there));
		VoidRift a = VoidForces.RIFT.create(from), b = VoidForces.RIFT.create(to);
		if (a == null || b == null) return null;
		UUID id = UUID.randomUUID();
		a.configure(owner, id, to.dimension(), there, size);
		b.configure(owner, id, from.dimension(), here, size);
		a.moveTo(here.x, here.y, here.z, 0, 0);
		b.moveTo(there.x, there.y, there.z, 0, 0);
		if (!from.addFreshEntity(a)) return null;
		if (!to.addFreshEntity(b)) { a.discard(); return null; }
		return a;
	}

	/** Destroyed pair ids survive restarts so an unloaded counterpart cannot reappear later. */
	public static final class Broken extends SavedData {
		private final Set<UUID> ids = new HashSet<>();
		private static final Factory<Broken> FACTORY = new Factory<>(Broken::new, (tag, registries) -> {
			Broken data = new Broken();
			for (Tag id : tag.getList("pairs", Tag.TAG_INT_ARRAY)) data.ids.add(NbtUtils.loadUUID(id));
			return data;
		}, null);
		public static Broken get(MinecraftServer server) { return server.overworld().getDataStorage().computeIfAbsent(FACTORY, "starmine_broken_gates"); }
		public boolean contains(UUID id) { return ids.contains(id); }
		public void add(UUID id) { if (ids.add(id)) setDirty(); }
		@Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
			ListTag list = new ListTag();
			ids.forEach(id -> list.add(NbtUtils.createUUID(id)));
			tag.put("pairs", list);
			return tag;
		}
	}

	public static boolean inVoid(Entity entity) { return VoidWorld.is(entity.level()); }
}
