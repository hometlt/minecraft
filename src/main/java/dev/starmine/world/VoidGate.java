package dev.starmine.world;

import dev.starmine.StarMine;
import dev.starmine.entity.StarUnit;
import java.util.List;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * A way to the Void of one's own (owner 17.10). Void shards — dropped by the shards Amun sets in the world when he
 * invades ({@link AmunInvasion}) and by those of the Void, and found in the Void's crystals — make a void gate (eight
 * round an ender eye). Used, the gate takes its player, and the player's units within {@link #ESCORT} blocks, into the
 * Void; a gate in the Void takes them out to the overworld. The Void is small: a block there is {@link #SCALE} of the
 * overworld's (the Nether's 8 count as one overworld block first). A gate stands where they come out, to go back.
 */
public final class VoidGate {
	public static final int SCALE = 16, ESCORT = 8;
	public static final Item SHARD = Registry.register(BuiltInRegistries.ITEM, StarMine.id("void_shard"), new Item(new Item.Properties().rarity(Rarity.UNCOMMON)));
	public static final Block GATE = Registry.register(BuiltInRegistries.BLOCK, StarMine.id("void_gate"), new Gate(BlockBehaviour.Properties.of()
		.strength(5.0f, 1200.0f).lightLevel(state -> 11).sound(SoundType.AMETHYST)));

	static {
		Registry.register(BuiltInRegistries.ITEM, StarMine.id("void_gate"), new BlockItem(GATE, new Item.Properties().rarity(Rarity.RARE)));
	}

	private VoidGate() {
	}

	public static void load() {
		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register(entries -> {
			entries.accept(GATE);
			entries.accept(SHARD);
		});
	}

	static final class Gate extends Block {
		Gate(Properties properties) {
			super(properties);
		}

		@Override
		protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
			if (level instanceof ServerLevel server && player instanceof ServerPlayer traveller) travel(server, pos, traveller);
			return InteractionResult.sidedSuccess(level.isClientSide);
		}
	}

	/** The column a gate in `from` at `pos` leads to, in the other world. */
	public static BlockPos target(Level from, BlockPos pos) {
		if (VoidWorld.is(from)) return new BlockPos(pos.getX() * SCALE, 0, pos.getZ() * SCALE);
		int scale = from.dimension() == Level.NETHER ? 8 : 1;
		return new BlockPos(Math.floorDiv(pos.getX() * scale, SCALE), 0, Math.floorDiv(pos.getZ() * scale, SCALE));
	}

	/** Through the gate at `pos`: the player and their units near it, into the Void or out of it. */
	public static boolean travel(ServerLevel level, BlockPos pos, ServerPlayer player) {
		ServerLevel to = VoidWorld.is(level) ? level.getServer().overworld() : VoidWorld.level(level.getServer());
		if (to == null) {
			player.displayClientMessage(Component.translatable("message.starmine.no_void"), true);
			return false;
		}
		BlockPos column = target(level, pos);
		to.getChunk(column.getX() >> 4, column.getZ() >> 4);
		Vec3 at = landing(to, column);
		List<StarUnit> escort = level.getEntitiesOfClass(StarUnit.class, new AABB(pos).inflate(ESCORT), unit -> unit.isAlive()
			&& unit.owner().filter(player.getUUID()::equals).isPresent() && !unit.kind.structure() && !unit.isPassenger());
		level.sendParticles(ParticleTypes.REVERSE_PORTAL, pos.getX() + 0.5, pos.getY() + 1.5, pos.getZ() + 0.5, 60, 0.8, 1, 0.8, 0.2);
		level.playSound(null, pos, SoundEvents.PORTAL_TRAVEL, SoundSource.BLOCKS, 0.5f, 1.3f);
		int i = 0;
		for (StarUnit unit : escort) {
			double angle = i++ * 2.4, spread = 2 + unit.getBbWidth() + i * 0.4;
			Vec3 spot = at.add(Math.cos(angle) * spread, unit.kind.flying ? unit.kind.hoverHeight() : 0, Math.sin(angle) * spread);
			unit.command(StarUnit.Order.NONE, null);
			unit.changeDimension(new DimensionTransition(to, spot, Vec3.ZERO, unit.getYRot(), unit.getXRot(), DimensionTransition.DO_NOTHING));
		}
		player.teleportTo(to, at.x, at.y, at.z, java.util.Set.of(), player.getYRot(), player.getXRot());
		to.playSound(null, BlockPos.containing(at), SoundEvents.ENDERMAN_TELEPORT, SoundSource.BLOCKS, 1.0f, 0.7f);
		player.displayClientMessage(Component.translatable(VoidWorld.is(to) ? "message.starmine.void_in" : "message.starmine.void_out"), true);
		if (VoidWorld.is(to)) VoidArmy.arrive(to, player);
		return true;
	}

	/** Where one comes out over `column` in `level`: on its ground, a ledge of void stone where there is none; a gate beside, to go back. */
	static Vec3 landing(ServerLevel level, BlockPos column) {
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ());
		if (y <= level.getMinBuildHeight() + 1) { // nothing there (the Void between its islands): a ledge
			y = VoidWorld.RETURN.getY();
			for (int dx = -3; dx <= 3; dx++)
				for (int dz = -3; dz <= 3; dz++) level.setBlock(new BlockPos(column.getX() + dx, y - 1, column.getZ() + dz),
					VoidIslands.BRICKS.defaultBlockState(), Block.UPDATE_ALL);
		}
		BlockPos feet = new BlockPos(column.getX(), y, column.getZ());
		if (nearestGate(level, feet) == null) {
			BlockPos gate = feet.offset(2, 0, 0);
			if (level.getBlockState(gate.below()).isAir()) level.setBlock(gate.below(), VoidIslands.BRICKS.defaultBlockState(), Block.UPDATE_ALL);
			level.setBlock(gate, GATE.defaultBlockState(), Block.UPDATE_ALL);
			level.setBlock(gate.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		}
		return new Vec3(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
	}

	private static @Nullable BlockPos nearestGate(ServerLevel level, BlockPos near) {
		for (BlockPos pos : BlockPos.betweenClosed(near.offset(-5, -3, -5), near.offset(5, 3, 5)))
			if (level.getBlockState(pos).is(GATE)) return pos.immutable();
		return null;
	}

	/** Whether an entity is in the Void, where nothing dies with loot or experience for anyone (owner 17.10). */
	public static boolean inVoid(Entity entity) {
		return VoidWorld.is(entity.level());
	}
}
