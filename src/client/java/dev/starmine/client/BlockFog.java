package dev.starmine.client;

import dev.starmine.entity.StarUnit;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Fog of war for blocks in the command view (CLOUD_TASK_2 stage 2): the player sees only what their
 * units, buildings and body have seen. On the surface each one opens a whole disc of land round it, its
 * sight wide, hills and hollows alike ({@link #disc}); rays go out from each one's eyes to its sight
 * ({@link dev.starmine.entity.UnitKind#sight}) and stop at the first block that hides what is behind
 * it (glass, leaves, water let them through): ground and walls hide what lies beyond. Every block a ray
 * met is explored; blocks never explored are not drawn at all
 * ({@link dev.starmine.mixin.client.HideUnseenBlocksMixin}: the chunk mesher reads them as air; with Sodium,
 * and Iris with a shader pack, which draws terrain through Sodium, {@link dev.starmine.mixin.sodium.SodiumHideUnseenBlocksMixin}).
 * Explored blocks stay drawn, dimmed by the ground fog of {@link CommandView} where nobody sees them
 * now (the agent's choice: the map is remembered, its creatures are not). What was explored is kept per
 * dimension for the session.
 *
 * A unit is scanned again when it has moved a block or every {@link #RESCAN} ticks, within
 * {@link #BUDGET} block reads a tick: entering the view shows the base around its units within a few
 * ticks, a moving army opens the map as it goes.
 */
public final class BlockFog {
	/** Block reads a tick for the rays. */
	static final int BUDGET = 300_000;
	static final int RESCAN = 60;
	private static final Map<ResourceKey<Level>, ConcurrentHashMap<Long, long[]>> EXPLORED = new HashMap<>();
	/** The explored bits of the shown dimension per section (4096 bits); read by chunk building threads. */
	private static volatile ConcurrentHashMap<Long, long[]> explored = new ConcurrentHashMap<>();
	private static volatile boolean hiding;
	private record Scan(double x, double y, double z, long tick) {
	}

	private record Eye(int id, double x, double y, double z, double radius) {
	}

	private static final Map<Integer, Scan> SCANS = new HashMap<>();
	private static final Map<Integer, float[]> DIRECTIONS = new HashMap<>();
	private static int cursor;

	private BlockFog() {
	}

	/** Whether the chunk mesher leaves this block out (called on chunk building threads). */
	public static boolean hidden(BlockPos pos) {
		return hidden(pos.getX(), pos.getY(), pos.getZ());
	}

	/** {@link #hidden(BlockPos)} by coordinates (Sodium's mesher reads blocks so). */
	public static boolean hidden(int x, int y, int z) {
		if (!hiding) return false;
		long[] bits = explored.get(SectionPos.asLong(x >> 4, y >> 4, z >> 4));
		if (bits == null) return true;
		int i = (y & 15) << 8 | (z & 15) << 4 | x & 15;
		return (bits[i >> 6] & 1L << (i & 63)) == 0;
	}

	/** The view opens: unexplored blocks go, everything is meshed again. */
	static void start(Minecraft mc) {
		if (mc.level == null) return;
		explored = EXPLORED.computeIfAbsent(mc.level.dimension(), key -> new ConcurrentHashMap<>());
		SCANS.clear();
		hiding = true;
		mc.levelRenderer.allChanged();
	}

	/** The view closes: the world is whole again. */
	static void stop(Minecraft mc) {
		if (!hiding) return;
		hiding = false;
		mc.levelRenderer.allChanged();
	}

	/** Client tick in the view: rays from the eyes due for a scan; the sections that gained blocks are meshed again. */
	static void tick(Minecraft mc) {
		if (!hiding || mc.level == null || mc.player == null) return;
		List<Eye> eyes = new ArrayList<>();
		eyes.add(new Eye(mc.player.getId(), mc.player.getX(), mc.player.getEyeY(), mc.player.getZ(), CommandView.PLAYER_SIGHT));
		for (Entity entity : mc.level.entitiesForRendering())
			if (entity instanceof StarUnit unit && unit.isAlive() && CommandView.own(unit))
				eyes.add(new Eye(unit.getId(), unit.getX(), unit.getY() + Math.max(1, unit.getBbHeight() * 0.85), unit.getZ(), unit.kind.sight()));
		long now = mc.level.getGameTime();
		LongOpenHashSet dirty = new LongOpenHashSet();
		int spent = 0;
		for (int n = 0; n < eyes.size() && spent < BUDGET; n++) {
			Eye eye = eyes.get((cursor + n) % eyes.size());
			Scan last = SCANS.get(eye.id);
			if (last != null && now - last.tick < RESCAN && Mth.lengthSquared(eye.x - last.x, eye.y - last.y, eye.z - last.z) < 1) continue;
			spent += scan(mc.level, eye, dirty);
			SCANS.put(eye.id, new Scan(eye.x, eye.y, eye.z, now));
		}
		cursor++;
		for (long section : dirty) mc.levelRenderer.setSectionDirty(SectionPos.x(section), SectionPos.y(section), SectionPos.z(section));
	}

	/** Rays in every direction from an eye; returns the block reads spent. */
	private static int scan(ClientLevel level, Eye eye, LongOpenHashSet dirty) {
		float[] directions = directions(eye.radius);
		int spent = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int i = 0; i < directions.length; i += 3) spent += ray(level, eye, directions[i], directions[i + 1], directions[i + 2], pos, dirty);
		return spent + disc(level, eye, dirty);
	}

	/** Blocks of the surface skin opened in every column of an eye's sight disc. */
	static final int SKIN = 6;

	/**
	 * The surface round an eye on the surface (owner 16.10: the rays left the sight ragged — hollows behind
	 * a rise and hills behind a hollow stayed dark): every column within its sight, measured flat, has its
	 * top {@link #SKIN} blocks opened, so a unit sees a whole round piece of land, as in SC2. An eye deep
	 * underground opens no surface (its rays show its cave).
	 */
	private static int disc(ClientLevel level, Eye eye, LongOpenHashSet dirty) {
		int ex = Mth.floor(eye.x), ez = Mth.floor(eye.z);
		if (eye.y < level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, ex, ez) - SKIN) return 0;
		int r = (int) Math.ceil(eye.radius), reads = 0;
		for (int dx = -r; dx <= r; dx++)
			for (int dz = -r; dz <= r; dz++) {
				if (dx * dx + dz * dz > eye.radius * eye.radius) continue;
				int x = ex + dx, z = ez + dz;
				int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z);
				for (int y = top - 1; y >= Math.max(level.getMinBuildHeight(), top - SKIN); y--) {
					reads++;
					if (mark(x, y, z)) dirty.add(SectionPos.asLong(x >> 4, y >> 4, z >> 4));
				}
			}
		return reads;
	}

	/** Even directions over a sphere (a Fibonacci lattice), dense enough for a ray a block apart at the sight's end. */
	private static float[] directions(double radius) {
		int count = Mth.clamp((int) (4 * Math.PI * radius * radius), 400, 12_000);
		return DIRECTIONS.computeIfAbsent(count, n -> {
			float[] out = new float[n * 3];
			double golden = Math.PI * (3 - Math.sqrt(5));
			for (int i = 0; i < n; i++) {
				double y = 1 - 2 * (i + 0.5) / n, r = Math.sqrt(1 - y * y), a = golden * i;
				out[i * 3] = (float) (Math.cos(a) * r);
				out[i * 3 + 1] = (float) y;
				out[i * 3 + 2] = (float) (Math.sin(a) * r);
			}
			return out;
		});
	}

	/** One ray through the block grid (Amanatides-Woo); it marks what it meets and stops at a block that hides what is behind it. */
	private static int ray(ClientLevel level, Eye eye, double dx, double dy, double dz, BlockPos.MutableBlockPos pos, LongOpenHashSet dirty) {
		int x = Mth.floor(eye.x), y = Mth.floor(eye.y), z = Mth.floor(eye.z);
		int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
		double tx = dx == 0 ? Double.MAX_VALUE : ((dx > 0 ? x + 1 - eye.x : eye.x - x) / Math.abs(dx));
		double ty = dy == 0 ? Double.MAX_VALUE : ((dy > 0 ? y + 1 - eye.y : eye.y - y) / Math.abs(dy));
		double tz = dz == 0 ? Double.MAX_VALUE : ((dz > 0 ? z + 1 - eye.z : eye.z - z) / Math.abs(dz));
		double ddx = dx == 0 ? Double.MAX_VALUE : 1 / Math.abs(dx), ddy = dy == 0 ? Double.MAX_VALUE : 1 / Math.abs(dy), ddz = dz == 0 ? Double.MAX_VALUE : 1 / Math.abs(dz);
		int reads = 0;
		while (true) {
			if (y < level.getMinBuildHeight() || y >= level.getMaxBuildHeight()) return reads;
			BlockState state = level.getBlockState(pos.set(x, y, z));
			reads++;
			if (!state.isAir() || !state.getFluidState().isEmpty()) {
				if (mark(x, y, z)) dirty.add(SectionPos.asLong(x >> 4, y >> 4, z >> 4));
				if (state.canOcclude()) return reads;
			}
			double t = Math.min(tx, Math.min(ty, tz));
			if (t > eye.radius) return reads;
			if (t == tx) {
				x += sx;
				tx += ddx;
			} else if (t == ty) {
				y += sy;
				ty += ddy;
			} else {
				z += sz;
				tz += ddz;
			}
		}
	}

	/** Marks a block explored; true when it was not. */
	private static boolean mark(int x, int y, int z) {
		long[] bits = explored.computeIfAbsent(SectionPos.asLong(x >> 4, y >> 4, z >> 4), key -> new long[64]);
		int i = (y & 15) << 8 | (z & 15) << 4 | x & 15;
		long bit = 1L << (i & 63);
		if ((bits[i >> 6] & bit) != 0) return false;
		bits[i >> 6] |= bit;
		return true;
	}
}
