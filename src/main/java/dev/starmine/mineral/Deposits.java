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
import net.minecraft.world.level.levelgen.FlatLevelSource;
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

	private Deposits() {
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
			double along = (i - (MINERALS - 1) / 2.0) * 3.0, back = -Math.abs(i - (MINERALS - 1) / 2.0) * 1.2; // bowed round the base
			int cx = Mth.floor(x + px * along + ux * back), cz = Mth.floor(z + pz * along + uz * back);
			Minerals.place(level, new BlockPos(cx, HiveStructure.ground(level, cx, cz), cz), set);
		}
		List<BlockPos> sites = new ArrayList<>();
		for (int side = -1; side <= 1; side += 2) {
			int gx = Mth.floor(x + px * side * 10.5 + ux * 3), gz = Mth.floor(z + pz * side * 10.5 + uz * 3);
			int ground = HiveStructure.ground(level, gx, gz);
			if (!dev.starmine.vespene.VespeneVeinFeature.build(level, random, gx, gz, true, Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.WORLD_SURFACE))
				continue;
			List<BlockPos> outlets = dev.starmine.vespene.Vespene.outletsNear(level, new BlockPos(gx, ground, gz), 10, 12);
			if (!outlets.isEmpty()) sites.add(outlets.get(0).above());
		}
		return sites;
	}

	/** Server tick: the first player's deposit, and a superflat world's deposits near players. */
	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % 20 != 15 || server instanceof net.minecraft.gametest.framework.GameTestServer) return;
		ServerLevel level = server.overworld();
		if (level.players().isEmpty()) return;
		HiveSavedData data = HiveSavedData.get(server);
		if (!data.structuresDone.contains("DEPOSIT:SPAWN")) {
			data.structuresDone.add("DEPOSIT:SPAWN");
			data.setDirty();
			var player = level.players().stream().filter(p -> !p.isSpectator()).findFirst().orElse(null);
			BlockPos home = player != null ? player.blockPosition() : level.getSharedSpawnPos();
			double angle = RandomSource.create(level.getSeed()).nextDouble() * Math.PI * 2;
			int x = home.getX() + (int) Math.round(Math.cos(angle) * NEAR_PLAYER), z = home.getZ() + (int) Math.round(Math.sin(angle) * NEAR_PLAYER);
			build(level, RandomSource.create(level.getSeed() ^ 0xDE9051L), x, z, home.getX(), home.getZ());
			return;
		}
		if (!(level.getChunkSource().getGenerator() instanceof FlatLevelSource)) return; // elsewhere the world generates them
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
					if (!taken) build(level, random, x, z, x + random.nextDouble() - 0.5, z + random.nextDouble() - 0.5);
					return; // one a tick: it loads chunks
				}
		}
	}
}
