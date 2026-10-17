package dev.starmine.world;

import dev.starmine.StarMine;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;

/**
 * The Void's islands (owner 17.10: like the End — islands in the dark joined by paths, few resources). Made once,
 * the first time anyone comes in ({@link VoidWorld#arrival}), over the next ticks ({@link Painter#deferred}):
 *
 * - the first island with the portal back at the middle ({@link VoidWorld#RETURN});
 * - {@link #ISLANDS} more round it farther and farther out, rough masses of void stone with glowing crystals,
 *   amethyst and crying obsidian; on a few a mineral crystal or a chest — little else;
 * - Amun's island, the largest, {@link #AMUN_DISTANCE} out, ringed by obsidian spires with void shards on them;
 *   more shards stand on spires on the other islands; Amun circles over it;
 * - paths of void bricks, three wide, from every island to the nearest one before it (Amun's to the two nearest),
 *   with glowing posts along them.
 */
public final class VoidIslands {
	public static final int ISLANDS = 10, AMUN_DISTANCE = 260, AMUN_RADIUS = 30, SHARDS_ON_AMUN = 6, SHARDS_ELSEWHERE = 4;
	public static final Block STONE, BRICKS, CRYSTAL;

	static {
		STONE = block("void_stone", new Block(BlockBehaviour.Properties.of().strength(3.0f, 9.0f).requiresCorrectToolForDrops().sound(SoundType.DEEPSLATE)));
		BRICKS = block("void_stone_bricks", new Block(BlockBehaviour.Properties.of().strength(3.5f, 9.0f).requiresCorrectToolForDrops().sound(SoundType.DEEPSLATE_BRICKS)));
		CRYSTAL = block("void_crystal", new Block(BlockBehaviour.Properties.of().strength(1.5f).lightLevel(state -> 12).sound(SoundType.AMETHYST)));
	}

	/** One island: its middle (the top of its floor) and its radius. */
	public record Island(BlockPos centre, int radius) {
	}

	private VoidIslands() {
	}

	private static Block block(String name, Block block) {
		Registry.register(BuiltInRegistries.BLOCK, StarMine.id(name), block);
		Registry.register(BuiltInRegistries.ITEM, StarMine.id(name), new BlockItem(block, new Item.Properties()));
		return block;
	}

	public static void load() {
		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.BUILDING_BLOCKS).register(entries -> {
			entries.accept(STONE);
			entries.accept(BRICKS);
			entries.accept(CRYSTAL);
		});
	}

	/** Where the islands are (the same for a world's seed): the first, the others, Amun's last. */
	public static List<Island> layout(long seed) {
		RandomSource random = RandomSource.create(seed ^ 0x5A17_A3C1L);
		List<Island> islands = new ArrayList<>();
		islands.add(new Island(VoidWorld.RETURN.below(), 13));
		double heading = random.nextDouble() * Math.PI * 2;
		for (int i = 1; i <= ISLANDS; i++) {
			heading += 2.39996 + (random.nextDouble() - 0.5) * 0.5; // the golden angle: spread all round
			double distance = 40 + i * 19 + random.nextInt(14);
			int y = VoidWorld.RETURN.getY() - 1 + random.nextInt(25) - 10;
			islands.add(new Island(new BlockPos(Mth.floor(Math.cos(heading) * distance), y, Mth.floor(Math.sin(heading) * distance)), 8 + random.nextInt(10)));
		}
		double away = random.nextDouble() * Math.PI * 2;
		islands.add(new Island(new BlockPos(Mth.floor(Math.cos(away) * AMUN_DISTANCE), VoidWorld.RETURN.getY() + 4,
			Mth.floor(Math.sin(away) * AMUN_DISTANCE)), AMUN_RADIUS));
		return islands;
	}

	/** Makes the islands, their paths, the shards and Amun, over the next ticks. */
	public static void build(ServerLevel level) {
		RandomSource random = RandomSource.create(level.getSeed() ^ 0x1D_A11DL);
		List<Island> islands = layout(level.getSeed());
		Painter painter = Painter.deferred(level, random);
		Island amun = islands.get(islands.size() - 1);
		for (int i = 1; i < islands.size(); i++) island(painter, islands.get(i), random, i == islands.size() - 1);
		for (int i = 1; i < islands.size(); i++) { // each to the nearest before it; Amun's to the two nearest
			Island from = islands.get(i);
			List<Island> before = new ArrayList<>(islands.subList(0, i));
			before.sort(java.util.Comparator.comparingDouble(other -> other.centre().distSqr(from.centre())));
			for (int link = 0; link < (from == amun ? 2 : 1) && link < before.size(); link++) path(painter, from, before.get(link));
		}
		List<BlockPos> spires = new ArrayList<>();
		for (int i = 0; i < SHARDS_ON_AMUN; i++) { // a ring of spires round Amun's island
			double a = Math.PI * 2 * i / SHARDS_ON_AMUN;
			spires.add(amun.centre().offset(Mth.floor(Math.cos(a) * (amun.radius() - 6)), 0, Mth.floor(Math.sin(a) * (amun.radius() - 6))));
		}
		for (int i = 0; i < SHARDS_ELSEWHERE; i++) { // and on islands far from the first
			Island on = islands.get(islands.size() - 2 - i * 2);
			spires.add(on.centre().offset(2, 0, -2));
		}
		for (BlockPos spire : spires) spire(painter, spire, 8 + random.nextInt(8));
		for (int i = 2; i < islands.size() - 1; i += 3) { // a little to take: a mineral crystal here and there, a chest
			BlockPos core = islands.get(i).centre().offset(-3, 1, 3);
			painter.then(() -> dev.starmine.mineral.Minerals.place(level, core, (pos, state) -> level.setBlock(pos, state, 2)));
			if (i % 2 == 0) painter.chest(islands.get(i).centre().offset(3, 1, -4), BuiltInLootTables.END_CITY_TREASURE);
		}
		painter.then(() -> {
			VoidWorld.State state = VoidWorld.State.get(level.getServer());
			state.shards += spires.size();
			state.setDirty();
			for (BlockPos spire : spires) {
				BlockPos top = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, spire);
				VoidShard shard = VoidForces.SHARD.create(level);
				if (shard == null) continue;
				shard.moveTo(spire.getX() + 0.5, top.getY() + 1, spire.getZ() + 0.5, 0, 0);
				level.addFreshEntity(shard);
			}
			if (VoidWorld.State.get(level.getServer()).amunDead) return;
			Amun boss = VoidForces.AMUN.create(level);
			if (boss == null) return;
			boss.home(amun.centre());
			boss.moveTo(amun.centre().getX() + 0.5, amun.centre().getY() + 24, amun.centre().getZ() + 0.5, 0, 0);
			level.addFreshEntity(boss);
		});
		painter.start();
	}

	/** A rough mass of void stone under a flat top with crystals and a little amethyst and crying obsidian on it. */
	static void island(Painter painter, Island island, RandomSource random, boolean amun) {
		BlockPos c = island.centre();
		int r = island.radius();
		double[] wobble = new double[8];
		for (int i = 0; i < wobble.length; i++) wobble[i] = random.nextDouble() * Math.PI * 2;
		for (int dx = -r - 3; dx <= r + 3; dx++)
			for (int dz = -r - 3; dz <= r + 3; dz++) {
				double a = Math.atan2(dz, dx);
				double edge = r * (1 + 0.18 * Math.sin(a * 3 + wobble[0]) + 0.1 * Math.sin(a * 5 + wobble[1]));
				double d = Math.hypot(dx, dz);
				if (d > edge) continue;
				double k = 1 - d / edge;
				int depth = 1 + (int) Math.round(k * r * 0.9 + Math.sin(dx * 0.7 + wobble[2]) * 1.5 + Math.cos(dz * 0.6 + wobble[3]) * 1.5);
				int top = (int) Math.round(Math.sin(dx * 0.25 + wobble[4]) * 0.7 + Math.cos(dz * 0.3 + wobble[5]) * 0.7);
				for (int dy = top; dy > top - depth; dy--) {
					BlockState state = dy == top ? (random.nextInt(9) == 0 ? Blocks.CRYING_OBSIDIAN : STONE).defaultBlockState()
						: dy > top - 3 ? STONE.defaultBlockState() : random.nextInt(5) == 0 ? Blocks.OBSIDIAN.defaultBlockState() : Blocks.BLACKSTONE.defaultBlockState();
					painter.set(c.offset(dx, dy, dz), state);
				}
				for (int up = 1; up <= 6; up++) painter.set(c.offset(dx, top + up, dz), Blocks.AIR.defaultBlockState());
				if (d < edge - 2 && random.nextInt(amun ? 140 : 90) == 0) // a glowing crystal spike
					for (int up = 1; up <= 1 + random.nextInt(3); up++) painter.set(c.offset(dx, top + up, dz), CRYSTAL.defaultBlockState());
				else if (d < edge - 1 && random.nextInt(70) == 0)
					painter.set(c.offset(dx, top + 1, dz), Blocks.AMETHYST_CLUSTER.defaultBlockState().setValue(AmethystClusterBlock.FACING, net.minecraft.core.Direction.UP));
			}
		if (amun) { // his throne: a dais of void bricks with a crystal at each corner
			painter.box(c.offset(-5, 1, -5), c.offset(5, 1, 5), Painter.of(BRICKS.defaultBlockState()));
			for (int[] corner : new int[][] {{-5, -5}, {5, -5}, {-5, 5}, {5, 5}})
				for (int up = 2; up <= 4; up++) painter.set(c.offset(corner[0], up, corner[1]), CRYSTAL.defaultBlockState());
		}
	}

	/** A path of void bricks three wide from one island's middle to another's, its height straight between them. */
	static void path(Painter painter, Island from, Island to) {
		BlockPos a = from.centre(), b = to.centre();
		double length = Math.sqrt(a.distSqr(b));
		double dx = (b.getX() - a.getX()) / length, dz = (b.getZ() - a.getZ()) / length;
		for (int step = 0; step <= (int) length; step++) {
			double t = step / length;
			int x = Mth.floor(a.getX() + (b.getX() - a.getX()) * t), z = Mth.floor(a.getZ() + (b.getZ() - a.getZ()) * t);
			int y = Mth.floor(a.getY() + (b.getY() - a.getY()) * t);
			for (int side = -1; side <= 1; side++) {
				BlockPos at = new BlockPos(x + Mth.floor(-dz * side + 0.5), y, z + Mth.floor(dx * side + 0.5));
				if (painter.stateAt(at).isAir() || !painter.stateAt(at).is(STONE)) painter.set(at, BRICKS.defaultBlockState());
				for (int up = 1; up <= 3; up++) if (!painter.stateAt(at.above(up)).is(CRYSTAL)) painter.set(at.above(up), Blocks.AIR.defaultBlockState());
			}
			if (step % 9 == 4) { // posts along it, a crystal on each, and a stone beneath
				BlockPos post = new BlockPos(x + Mth.floor(-dz * 2 + 0.5), y, z + Mth.floor(dx * 2 + 0.5));
				painter.set(post, BRICKS.defaultBlockState());
				painter.set(post.above(), Blocks.POLISHED_BLACKSTONE_WALL.defaultBlockState());
				painter.set(post.above(2), CRYSTAL.defaultBlockState());
				painter.set(new BlockPos(x, y - 1, z), STONE.defaultBlockState());
			}
		}
	}

	/** An obsidian spire a void shard stands on. */
	static void spire(Painter painter, BlockPos base, int height) {
		painter.cylinder(base.above(), 2.4, height, Painter.of(Blocks.OBSIDIAN.defaultBlockState()));
		painter.cylinder(base.above(height + 1), 1.2, 1, Painter.of(BRICKS.defaultBlockState()));
	}
}
