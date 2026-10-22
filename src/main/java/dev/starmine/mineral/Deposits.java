package dev.starmine.mineral;

import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveSavedData;
import dev.starmine.hive.HiveStructure;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * A base's resources, SC2's way (owner 18.10): {@link #MINERALS} crystals in a line bowed round the base and
 * {@link #GEYSERS} vespene geysers at its ends. Every generated base has one by its main building, with its
 * race's gas buildings on the geysers ({@link dev.starmine.world.RaceBases}, {@link HiveStructure}); one lies
 * near the first player's place ({@link #tick}); and in a superflat world, where the game generates no
 * features at all, deposits lie here and there ({@link #REGION}, {@link #CHANCE}), built when a player comes near.
 */
public final class Deposits {
	public static final int MINERALS = 5, GEYSERS = 2;
	/** Superflat: one deposit at most in a region this many blocks a side, by this chance; built within {@link #TRIGGER}. */
	public static final int REGION = 384, TRIGGER = 96;
	public static final double CHANCE = 0.35;
	/** The first player's deposit lies this far from their place. */
	public static final int NEAR_PLAYER = 30;
	private static final java.util.Map<MinecraftServer, Integer> NEXT_START_SEARCH = new java.util.WeakHashMap<>();

	private Deposits() {
	}

	/** A dry valley with room for a main, its resources and the mandatory clearance. */
	static boolean flat(ServerLevel level, int x, int z) {
		int y = HiveStructure.ground(level, x, z);
		for (int dx = -24; dx <= 24; dx += 4) for (int dz = -24; dz <= 24; dz += 4) {
			int ground = HiveStructure.ground(level, x + dx, z + dz);
			BlockPos at = new BlockPos(x + dx, ground - 1, z + dz);
			if (Math.abs(ground - y) > 1 || !level.getFluidState(at).isEmpty() || !level.getFluidState(at.above()).isEmpty()
				|| !level.getBlockState(at).isFaceSturdy(level, at, net.minecraft.core.Direction.UP)) return false;
		}
		return true;
	}

	static BlockPos nearestValley(ServerLevel level, BlockPos home) {
		for (int radius = NEAR_PLAYER; radius <= 190; radius += 16)
			for (int i = 0; i < 16; i++) {
				double a = i * Math.PI / 8;
				int x = home.getX() + (int) Math.round(Math.cos(a) * radius), z = home.getZ() + (int) Math.round(Math.sin(a) * radius);
				if (!dev.starmine.world.MapOverlay.covers(x, z) && flat(level, x, z)) return new BlockPos(x, HiveStructure.ground(level, x, z), z);
			}
		return null;
	}

	/**
	 * Lays a deposit whose middle is (x, z), its crystals bowed away from `towardX, towardZ` (where the base stands
	 * or will) and the geysers at its ends. Returns the sites of gas buildings over the geysers' outlets.
	 */
	public static List<BlockPos> build(ServerLevel level, RandomSource random, int x, int z, double towardX, double towardZ) {
		double ux = towardX - x, uz = towardZ - z, length = Math.sqrt(ux * ux + uz * uz);
		if (length < 1e-6) {
			ux = 1;
			uz = 0;
		} else {
			ux /= length;
			uz /= length;
		}
		double px = -uz, pz = ux; // along the line
		Minerals.Setter set = (pos, state) -> level.setBlock(pos, state, Block.UPDATE_CLIENTS);
		for (int i = 0; i < MINERALS; i++) {
			double along = (i - (MINERALS - 1) / 2.0) * 5.0, back = -Math.abs(i - (MINERALS - 1) / 2.0) * 1.2;
			int cx = Mth.floor(x + px * along + ux * back), cz = Mth.floor(z + pz * along + uz * back);
			// owner 22.10: the start's geysers came without their crystals (a tree or a bush stood where they go): the place is
			// cleared first, and a step aside is tried when it still does not fit
			boolean placed = false;
			for (int[] off : new int[][] {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {2, 0}, {-2, 0}, {0, 2}, {0, -2}}) {
				int ox = cx + off[0], oz = cz + off[1];
				BlockPos core = new BlockPos(ox, HiveStructure.ground(level, ox, oz), oz);
				clear(level, core);
				if (Minerals.place(level, core, 3, set) && level.getBlockEntity(core) instanceof Minerals.Core crystal) {
					crystal.setSize(3);
					crystal.setAmount(i < 3 ? 1300 : 800);
					placed = true;
					break;
				}
			}
			if (!placed) org.slf4j.LoggerFactory.getLogger("StarMine/deposits").warn("No room for crystal {} of the deposit at {} {}", i, x, z);
		}
		List<BlockPos> sites = new ArrayList<>();
		for (int side = -1; side <= 1; side += 2) {
			int gx = Mth.floor(x + px * side * 22 + ux * 3), gz = Mth.floor(z + pz * side * 22 + uz * 3);
			int ground = HiveStructure.ground(level, gx, gz);
			if (!dev.starmine.vespene.VespeneVeinFeature.build(level, random, gx, gz, true, Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.WORLD_SURFACE))
				continue;
			List<BlockPos> outlets = dev.starmine.vespene.Vespene.outletsNear(level, new BlockPos(gx, ground, gz), 10, 12);
			if (!outlets.isEmpty()) sites.add(outlets.get(0).above());
		}
		return sites;
	}

	/** What stands where a crystal goes (3 x 3, up to 8 high: trees, bushes, snow, flowers) is taken away; fluids and block entities stay. */
	static void clear(ServerLevel level, BlockPos core) {
		for (BlockPos at : BlockPos.betweenClosed(core.offset(-1, 0, -1), core.offset(3, 8, 3))) {
			var state = level.getBlockState(at);
			if (state.isAir() || !state.getFluidState().isEmpty() || level.getBlockEntity(at) != null || Minerals.isCrystal(state)) continue;
			if (state.is(net.minecraft.tags.BlockTags.LOGS) || state.is(net.minecraft.tags.BlockTags.LEAVES) || state.canBeReplaced()
				|| state.getCollisionShape(level, at).isEmpty())
				level.setBlock(at, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
		}
	}

	/** Server tick: the first player's deposit, and a superflat world's deposits near players. */
	public static void tick(MinecraftServer server) {
		if (dev.starmine.world.SkirmishMap.nativeWorld()) return;
		if (server.getTickCount() % 20 != 15 || server instanceof net.minecraft.gametest.framework.GameTestServer) return;
		ServerLevel level = server.overworld();
		if (level.players().isEmpty()) return;
		HiveSavedData data = HiveSavedData.get(server);
		if (!data.structuresDone.contains("DEPOSIT:SPAWN")) {
			if (server.getTickCount() < NEXT_START_SEARCH.getOrDefault(server, 0)) return;
			NEXT_START_SEARCH.put(server, server.getTickCount() + 600);
			var player = level.players().stream().filter(p -> !p.isSpectator()).findFirst().orElse(null);
			BlockPos home = player != null ? player.blockPosition() : level.getSharedSpawnPos();
			BlockPos valley = nearestValley(level, home);
			if (valley != null) {
				build(level, RandomSource.create(level.getSeed() ^ 0xDE9051L), valley.getX(), valley.getZ(), home.getX(), home.getZ());
				data.structuresDone.add("DEPOSIT:SPAWN"); data.setDirty();
			}
			return;
		}
		for (var player : level.players()) {
			if (player.isSpectator()) continue;
			net.minecraft.world.phys.Vec3 eye = dev.starmine.colony.CommanderEye.eye(player); // round the command view's camera, if it is open
			int prx = Math.floorDiv(eye != null ? (int) eye.x : player.getBlockX(), REGION), prz = Math.floorDiv(eye != null ? (int) eye.z : player.getBlockZ(), REGION);
			for (int rx = prx - 1; rx <= prx + 1; rx++)
				for (int rz = prz - 1; rz <= prz + 1; rz++) {
					String key = "DEPOSIT:" + rx + ":" + rz;
					if (data.structuresDone.contains(key)) continue;
					RandomSource random = RandomSource.create(level.getSeed() ^ (rx * 341873128712L + rz * 132897987541L) ^ 0xDE9051L);
					if (random.nextDouble() >= CHANCE) {
						data.structuresDone.add(key);
						data.setDirty();
						continue;
					}
					int x = rx * REGION + 48 + random.nextInt(REGION - 96), z = rz * REGION + 48 + random.nextInt(REGION - 96);
					if (!dev.starmine.world.RaceBases.scouted(level, x, z, TRIGGER)) continue;
					data.structuresDone.add(key);
					data.setDirty();
					boolean taken = false; // not on a base: those have their own
					for (Hive hive : data.all()) taken |= hive.hatchery.distSqr(new BlockPos(x, hive.hatchery.getY(), z)) < 64 * 64;
					if (!taken && !dev.starmine.world.MapOverlay.covers(x, z) && flat(level, x, z))
						build(level, random, x, z, x + random.nextDouble() - 0.5, z + random.nextDouble() - 0.5);
					return; // one a tick: it loads chunks
				}
		}
	}
}
