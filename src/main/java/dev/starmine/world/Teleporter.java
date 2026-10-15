package dev.starmine.world;

import dev.starmine.StarMine;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.HiveSavedData;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * A race's teleporter (CLOUD_TASK_2 stage 7), instead of the scaffolding up to the sky ships: a pad by the
 * race's base below and one on its ship above (the zerg's a bubble of flesh by the lair, the terrans' a
 * landing pad with a beacon light, the protoss' a gate of gold and amethyst). A player who steps on one
 * is carried to its pair — only once the pad's guards are dead: no wild ground unit within
 * {@link #GUARD_RADIUS} of it, and none still waiting to appear there ({@link HiveSavedData#pending}).
 * The pads below and above have guards of their own.
 */
public final class Teleporter extends Block implements EntityBlock {
	public static final Map<UnitKind.Race, Teleporter> BLOCKS = new EnumMap<>(UnitKind.Race.class);
	public static final BlockEntityType<Pad> ENTITY;
	public static final double GUARD_RADIUS = 14, GUARD_HEIGHT = 8;
	/** Ticks a player who arrived is not sent back. */
	static final int REST = 60;
	private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 4, 16);
	private static final Map<UUID, Long> RESTING = new HashMap<>();

	static {
		for (UnitKind.Race race : UnitKind.Race.values()) {
			String name = race.name().toLowerCase() + "_teleporter";
			Teleporter block = Registry.register(BuiltInRegistries.BLOCK, StarMine.id(name), new Teleporter(race,
				BlockBehaviour.Properties.of().strength(-1.0f, 3600000.0f).lightLevel(state -> 12)
					.sound(race == UnitKind.Race.ZERG ? SoundType.SLIME_BLOCK : SoundType.METAL).noOcclusion()));
			Registry.register(BuiltInRegistries.ITEM, StarMine.id(name), new BlockItem(block, new Item.Properties()));
			BLOCKS.put(race, block);
		}
		ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, StarMine.id("teleporter"),
			BlockEntityType.Builder.of(Pad::new, BLOCKS.values().toArray(new Block[0])).build(null));
	}

	public final UnitKind.Race race;

	private Teleporter(UnitKind.Race race, Properties properties) {
		super(properties);
		this.race = race;
	}

	public static void load() {
	}

	/** Where a pad leads. */
	public static class Pad extends BlockEntity {
		@Nullable BlockPos pair;

		public Pad(BlockPos pos, BlockState state) {
			super(ENTITY, pos, state);
		}

		public @Nullable BlockPos pair() {
			return pair;
		}

		@Override
		protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
			super.saveAdditional(tag, registries);
			if (pair != null) tag.put("pair", NbtUtils.writeBlockPos(pair));
		}

		@Override
		protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
			super.loadAdditional(tag, registries);
			pair = NbtUtils.readBlockPos(tag, "pair").orElse(null);
		}
	}

	@Override
	public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new Pad(pos, state);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return SHAPE;
	}

	@Override
	public void stepOn(Level level, BlockPos pos, BlockState state, Entity entity) {
		if (level instanceof ServerLevel server && entity instanceof Player) use(server, pos, entity);
		super.stepOn(level, pos, state, entity);
	}

	/** A pad of `race` at `pos` on a floor of `rim` 5 across (a ship's or a base's). */
	public static void pad(Painter painter, BlockPos pos, UnitKind.Race race, BlockState rim) {
		for (int dx = -2; dx <= 2; dx++)
			for (int dz = -2; dz <= 2; dz++) {
				painter.set(pos.offset(dx, -1, dz), Math.abs(dx) == 2 || Math.abs(dz) == 2 ? rim : light(race));
				for (int up = 0; up <= 3; up++) painter.set(pos.offset(dx, up, dz), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
			}
		painter.set(pos, BLOCKS.get(race).defaultBlockState());
	}

	private static BlockState light(UnitKind.Race race) {
		return switch (race) {
			case ZERG -> net.minecraft.world.level.block.Blocks.SHROOMLIGHT.defaultBlockState();
			case TERRAN -> net.minecraft.world.level.block.Blocks.REDSTONE_LAMP.defaultBlockState().setValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT, true);
			case PROTOSS -> net.minecraft.world.level.block.Blocks.AMETHYST_BLOCK.defaultBlockState();
		};
	}

	/** Pairs two pads (both must stand already). */
	public static void link(ServerLevel level, BlockPos a, BlockPos b) {
		if (level.getBlockEntity(a) instanceof Pad one && level.getBlockEntity(b) instanceof Pad two) {
			one.pair = b.immutable();
			two.pair = a.immutable();
			one.setChanged();
			two.setChanged();
		}
	}

	/** Guards of a pad still alive or yet to appear. */
	public static int guards(ServerLevel level, BlockPos pos) {
		AABB area = new AABB(pos).inflate(GUARD_RADIUS, GUARD_HEIGHT, GUARD_RADIUS);
		int alive = level.getEntitiesOfClass(StarUnit.class, area, unit -> unit.isAlive() && unit.owner().isEmpty() && !unit.kind.flying
			&& !unit.kind.structure()).size();
		int waiting = (int) HiveSavedData.get(level.getServer()).pending.stream().filter(p -> !p.kind().flying && area.contains(p.pos().getCenter())).count();
		return alive + waiting;
	}

	/** An entity on a pad: carried to its pair when the guards are dead (true), else told how many are left. */
	public static boolean use(ServerLevel level, BlockPos pos, Entity entity) {
		long now = level.getGameTime();
		Long resting = RESTING.get(entity.getUUID());
		if (resting != null && now < resting) return false;
		if (!(level.getBlockEntity(pos) instanceof Pad pad) || pad.pair == null) return false;
		int guards = guards(level, pos);
		if (guards > 0) {
			RESTING.put(entity.getUUID(), now + 40); // told once in a while, not every step
			if (entity instanceof Player player)
				player.displayClientMessage(Component.translatable("message.starmine.teleporter_guarded", guards), true);
			return false;
		}
		BlockPos to = pad.pair;
		level.getChunk(to.getX() >> 4, to.getZ() >> 4);
		RESTING.put(entity.getUUID(), now + REST);
		entity.teleportTo(to.getX() + 0.5, to.getY() + 0.3, to.getZ() + 0.5);
		level.playSound(null, to, SoundEvents.ENDERMAN_TELEPORT, SoundSource.BLOCKS, 1.0f, 1.0f);
		level.playSound(null, pos, SoundEvents.ENDERMAN_TELEPORT, SoundSource.BLOCKS, 1.0f, 1.0f);
		if (RESTING.size() > 256) RESTING.values().removeIf(until -> until < now);
		return true;
	}
}
