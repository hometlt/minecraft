package dev.starmine.ai;

import dev.starmine.StarMine;
import dev.starmine.block.StarMineBlocks;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveLogic;
import dev.starmine.hive.HiveSavedData;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChainBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The wild races take the land (owner 16.10). A wild base, while it is loaded:
 * - sends expeditions — a worker and a few warriors — to claim places round it ({@link #MAX_CLAIMS} at
 *   most, one every {@link #CLAIM_COOLDOWN}): caves under the land as often as outposts on it. Where they
 *   arrive the race makes the place its own: the zerg cover it with creep, flesh and glowing flesh (and
 *   now and then redstone), plant a creep tumor and spine and spore crawlers; the terrans widen the way,
 *   hang lamps, run cables and put a missile turret (and a bunker on the land) — now and then an iron
 *   block; the protoss light it with sea lanterns, inlay it with yellow «gold» (now and then real gold)
 *   and raise a pylon with a photon cannon. The warriors stay to guard it; no monster spawns in a claim.
 * - Waves between wild bases go for each other's claims, never for the main bases (they stand).
 * And at any time, loaded or not, each wild base drops a squad on the nearest player's home now and then
 * ({@link #drops}): the terrans from drop pods, the protoss by warp, the zerg out of the ground; how often
 * and how many by the game rule `starmineAiPressure` (0 — never).
 */
public final class Territory {
	public static final int MAX_CLAIMS = 6, CLAIM_COOLDOWN = 6 * 60 * 20, EXPEDITION_TICKS = 4 * 60 * 20; // owner 18.10: outposts rarer (12, 3 min)
	/** Blocks round a claim where no monster spawns and the race's style is laid. */
	public static final int CLAIM_RADIUS = 20, STYLE_RADIUS = 7;
	/** Ticks between drops at pressure 1 (divided by the pressure), the first drop's delay, squads' sizes. */
	public static final int DROP_TICKS = 24 * 60 * 20, FIRST_DROP = 15 * 60 * 20, DROP_MIN = 3, DROP_MAX = 12;

	private Territory() {
	}

	// ---- expeditions and claims (a loaded base, every AI decision) ----

	/** One decision of a wild base: its expedition goes on, or a new one sets out. */
	public static void decide(StarUnit main, ServerLevel level, Hive base) {
		long now = level.getGameTime();
		if (base.expedition != null) {
			progress(level, base, now);
			return;
		}
		if (base.nextClaim == 0) base.nextClaim = now + CLAIM_COOLDOWN; // a new base settles first
		if (base.territory.size() >= MAX_CLAIMS || now < base.nextClaim) return;
		base.nextClaim = now + CLAIM_COOLDOWN;
		RandomSource random = level.random;
		boolean cave = random.nextBoolean();
		BlockPos site = null;
		for (int attempt = 0; attempt < 8 && site == null; attempt++) {
			double angle = random.nextDouble() * Math.PI * 2, distance = 40 + random.nextDouble() * 70;
			int x = Mth.floor(base.hatchery.getX() + Math.cos(angle) * distance), z = Mth.floor(base.hatchery.getZ() + Math.sin(angle) * distance);
			if (!level.hasChunk(x >> 4, z >> 4)) continue;
			BlockPos found = cave ? caveFloor(level, x, z) : surface(level, x, z);
			if (found != null && free(level, base, found)) site = found;
		}
		if (site == null) return;
		List<StarUnit> squad = new ArrayList<>();
		UnitKind worker = base.race.rules().worker();
		StarUnit builder = level.getEntitiesOfClass(StarUnit.class, main.getBoundingBox().inflate(48),
			unit -> unit.kind == worker && base.id.equals(unit.hiveId()) && unit.isAlive() && unit.buildKind() == null && unit.cargoBlocks == 0)
			.stream().findFirst().orElse(null);
		if (builder != null) squad.add(builder);
		squad.addAll(level.getEntitiesOfClass(StarUnit.class, main.getBoundingBox().inflate(48),
			unit -> base.id.equals(unit.hiveId()) && unit.isAlive() && !unit.kind.structure() && !unit.kind.worker()
				&& unit.kind.attack != UnitKind.Attack.NONE && unit.order() == StarUnit.Order.NONE && unit.raidTarget() == null
				&& unit.nest() == null && !unit.flying()).stream().limit(3).toList());
		if (squad.isEmpty()) return;
		Vec3 to = Vec3.atBottomCenterOf(site);
		for (StarUnit unit : squad) unit.command(StarUnit.Order.MOVE, to);
		base.expedition = site;
		base.expeditionCave = cave;
		base.expeditionUntil = now + EXPEDITION_TICKS;
		base.expeditionUnits.clear();
		squad.forEach(unit -> base.expeditionUnits.add(unit.getUUID()));
	}

	/** An expedition arrives (one of its units within 6 blocks of the place: it is claimed) or gives up in time. */
	private static void progress(ServerLevel level, Hive base, long now) {
		BlockPos site = base.expedition;
		boolean arrived = false, alive = false;
		for (UUID id : base.expeditionUnits)
			if (level.getEntity(id) instanceof StarUnit unit && unit.isAlive()) {
				alive = true;
				if (unit.position().distanceToSqr(Vec3.atBottomCenterOf(site)) < 36) arrived = true;
			}
		if (arrived) {
			claim(level, base, site, base.expeditionCave, level.random);
			for (UUID id : base.expeditionUnits) // the warriors stay there on guard
				if (level.getEntity(id) instanceof StarUnit unit && unit.isAlive() && !unit.kind.worker())
					unit.command(StarUnit.Order.MOVE, Vec3.atBottomCenterOf(site));
		}
		if (arrived || !alive || now > base.expeditionUntil) {
			base.expedition = null;
			base.expeditionUnits.clear();
		}
	}

	/** The race takes the place: its style laid round it, its defences on it, the place in its territory. */
	public static void claim(ServerLevel level, Hive base, BlockPos site, boolean cave, RandomSource random) {
		style(level, base.race, site, cave, random);
		List<UnitKind> guns = switch (base.race) {
			case ZERG -> List.of(UnitKind.SPINE_CRAWLER, UnitKind.SPORE_CRAWLER);
			case TERRAN -> cave ? List.of(UnitKind.MISSILE_TURRET) : List.of(UnitKind.MISSILE_TURRET, UnitKind.BUNKER);
			case PROTOSS -> List.of(UnitKind.PYLON, UnitKind.PHOTON_CANNON);
		};
		for (int i = 0; i < guns.size(); i++) {
			UnitKind kind = guns.get(i);
			double angle = i * Math.PI + random.nextDouble() * 0.5;
			double r = kind.width / 2 + 2;
			BlockPos feet = HiveLogic.groundNear(level, Mth.floor(site.getX() + Math.cos(angle) * r), site.getY(), Mth.floor(site.getZ() + Math.sin(angle) * r));
			if (feet == null) continue;
			StarUnit gun = HiveLogic.spawn(level, base, kind, kind.gridCentre(Vec3.atBottomCenterOf(feet)), null);
			if (gun != null) base.join(kind);
		}
		base.territory.add(site.immutable());
		HiveSavedData.get(level.getServer()).setDirty();
	}

	/** Lays the race's style round a place: on the faces of the blocks open to the air within {@link #STYLE_RADIUS}. */
	static void style(ServerLevel level, UnitKind.Race race, BlockPos site, boolean cave, RandomSource random) {
		int r = STYLE_RADIUS;
		if (race == UnitKind.Race.TERRAN) // neat ways for soldiers and machines: the place widened, a floor laid
			for (BlockPos pos : BlockPos.betweenClosed(site.offset(-2, 0, -2), site.offset(2, 3, 2))) {
				BlockState state = level.getBlockState(pos);
				if (!state.isAir() && state.getDestroySpeed(level, pos) >= 0 && state.getDestroySpeed(level, pos) < 5 && level.getBlockEntity(pos) == null)
					level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			}
		List<BlockPos> faces = new ArrayList<>();
		for (BlockPos pos : BlockPos.betweenClosed(site.offset(-r, -3, -r), site.offset(r, cave ? r : 3, r))) {
			if (pos.distSqr(site) > r * r) continue;
			BlockState state = level.getBlockState(pos);
			if (!state.isSolid() || state.getDestroySpeed(level, pos) < 0 || level.getBlockEntity(pos) != null) continue;
			for (Direction side : Direction.values())
				if (level.getBlockState(pos.relative(side)).isAir()) {
					faces.add(pos.immutable());
					break;
				}
		}
		for (BlockPos pos : faces) {
			int roll = random.nextInt(100);
			BlockState style = switch (race) {
				case ZERG -> roll < 30 ? StarMineBlocks.ZERG_FLESH.defaultBlockState() : roll < 37 ? StarMineBlocks.GLOWING_ZERG_FLESH.defaultBlockState()
					: roll < 38 ? Blocks.REDSTONE_BLOCK.defaultBlockState() : null;
				case TERRAN -> pos.getY() < site.getY() && roll < 50 ? Blocks.POLISHED_ANDESITE.defaultBlockState()
					: roll < 2 ? Blocks.IRON_BLOCK.defaultBlockState() : roll < 10 ? Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState() : null;
				case PROTOSS -> roll < 8 ? Blocks.YELLOW_TERRACOTTA.defaultBlockState() : roll < 10 ? Blocks.GOLD_BLOCK.defaultBlockState()
					: roll < 14 ? Blocks.SMOOTH_SANDSTONE.defaultBlockState() : null;
			};
			if (style != null) level.setBlock(pos, style, Block.UPDATE_ALL);
		}
		// lights: floor faces with air over them, every few blocks
		BlockState light = switch (race) {
			case ZERG -> StarMineBlocks.GLOWING_ZERG_FLESH.defaultBlockState();
			case TERRAN -> Blocks.LANTERN.defaultBlockState();
			case PROTOSS -> Blocks.SEA_LANTERN.defaultBlockState();
		};
		int placed = 0;
		for (BlockPos pos : faces) {
			if (placed >= 6 || random.nextInt(8) != 0 || !level.getBlockState(pos.above()).isAir()) continue;
			if (race == UnitKind.Race.TERRAN) level.setBlock(pos.above(), light, Block.UPDATE_ALL);
			else level.setBlock(pos, light, Block.UPDATE_ALL);
			placed++;
		}
		if (race == UnitKind.Race.ZERG) { // a creep tumor at the middle: creep grows over it all
			BlockPos floor = site.below();
			if (level.getBlockState(floor).isSolid() && level.getBlockState(site).isAir())
				level.setBlock(site, StarMineBlocks.CREEP_TUMOR.defaultBlockState(), Block.UPDATE_ALL);
		}
		if (race == UnitKind.Race.TERRAN) { // a cable run along the ceiling or overhead, east-west
			for (int dx = -r; dx <= r; dx++) {
				BlockPos at = site.offset(dx, cave ? 3 : 4, 0);
				if (level.getBlockState(at).isAir())
					level.setBlock(at, Blocks.CHAIN.defaultBlockState().setValue(ChainBlock.AXIS, Direction.Axis.X), Block.UPDATE_ALL);
			}
		}
	}

	/** The first free cell over dry land, or null. */
	private static @Nullable BlockPos surface(ServerLevel level, int x, int z) {
		int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		BlockPos at = new BlockPos(x, top, z);
		return level.getFluidState(at.below()).isEmpty() && level.getBlockState(at.below()).isSolid() ? at : null;
	}

	/** A cave's floor under the column: air with room over it and solid ground under it, deep under the land. */
	static @Nullable BlockPos caveFloor(ServerLevel level, int x, int z) {
		int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 10;
		for (int y = top; y > level.getMinBuildHeight() + 6; y--) {
			BlockPos at = new BlockPos(x, y, z);
			if (level.getBlockState(at).isAir() && level.getBlockState(at.above()).isAir() && level.getBlockState(at.above(2)).isAir()
				&& level.getBlockState(at.below()).isSolid() && level.getFluidState(at.below()).isEmpty()) return at;
		}
		return null;
	}

	/** A place no base claimed near, and no base's main stands near; never near another race's wild ({@link dev.starmine.world.RaceSeparation}). */
	private static boolean free(ServerLevel level, Hive base, BlockPos site) {
		if (dev.starmine.world.RaceSeparation.foreignNear(level, base.race, site) != null) return false;
		for (Hive other : HiveSavedData.get(level.getServer()).all()) {
			if (other != base && other.hatchery.distSqr(site) < 48 * 48) return false;
			for (BlockPos claim : other.territory) if (claim.distSqr(site) < 24 * 24) return false;
		}
		return true;
	}

	/** Whether a place lies in some race's territory (no monster spawns there). */
	public static boolean claimed(ServerLevel level, BlockPos pos) {
		String dimension = level.dimension().location().toString();
		for (Hive hive : HiveSavedData.get(level.getServer()).all())
			if (hive.owner == null && hive.dimension.equals(dimension))
				for (BlockPos claim : hive.territory) if (claim.distSqr(pos) < (double) CLAIM_RADIUS * CLAIM_RADIUS) return true;
		return false;
	}

	/** The nearest claim of a wild base of another race within `range` of `from` (a wave's target), or null. */
	public static @Nullable BlockPos foeClaim(ServerLevel level, Hive base, BlockPos from, int range) {
		BlockPos best = null;
		double bestDistance = (double) range * range;
		String dimension = level.dimension().location().toString();
		for (Hive other : HiveSavedData.get(level.getServer()).all()) {
			if (other == base || other.owner != null || other.race == base.race || !other.dimension.equals(dimension)) continue;
			for (BlockPos claim : other.territory) {
				double d = claim.distSqr(from);
				if (d < bestDistance) {
					bestDistance = d;
					best = claim;
				}
			}
		}
		return best;
	}

	// ---- drops (every wild base, loaded or not) ----

	/** Server tick: each wild base whose time has come drops a squad on the nearest player's home. */
	public static void serverTick(MinecraftServer server) {
		if (server.getTickCount() % 100 != 40) return;
		ServerLevel level = server.overworld();
		int pressure = level.getGameRules().getInt(StarMine.AI_PRESSURE);
		if (pressure <= 0 || level.players().isEmpty() || System.getProperty("fabric-api.gametest") != null) return; // not on the test server's mock players
		long now = level.getGameTime();
		HiveSavedData data = HiveSavedData.get(server);
		String dimension = level.dimension().location().toString();
		for (Hive base : data.all()) {
			if (base.owner != null || !base.dimension.equals(dimension) || base.race == null) continue;
			if (base.nextDrop == 0) {
				base.nextDrop = now + FIRST_DROP / pressure;
				data.setDirty();
				continue;
			}
			if (now < base.nextDrop) continue;
			base.nextDrop = now + DROP_TICKS / pressure;
			data.setDirty();
			drops(level, data, base, pressure);
		}
	}

	/** A squad of `base`'s race lands by the nearest player's home (their base, else their bed or the world's spawn). */
	public static @Nullable BlockPos drops(ServerLevel level, HiveSavedData data, Hive base, int pressure) {
		ServerPlayer player = level.players().stream().filter(p -> !p.isSpectator() && !p.isCreative())
			.min(java.util.Comparator.comparingDouble(p -> p.blockPosition().distSqr(base.hatchery))).orElse(null);
		return player == null ? null : drops(level, data, base, pressure, player);
	}

	/** {@link #drops} on `player`'s home. */
	public static BlockPos drops(ServerLevel level, HiveSavedData data, Hive base, int pressure, ServerPlayer player) {
		Hive home = data.baseOf(player.getUUID(), level.dimension().location().toString());
		BlockPos target = home != null ? home.hatchery : player.getRespawnPosition() != null && player.getRespawnDimension() == level.dimension()
			? player.getRespawnPosition() : level.getSharedSpawnPos();
		RandomSource random = level.random;
		double angle = random.nextDouble() * Math.PI * 2;
		int x = Mth.floor(target.getX() + Math.cos(angle) * 28), z = Mth.floor(target.getZ() + Math.sin(angle) * 28);
		level.getChunk(x >> 4, z >> 4);
		BlockPos landing = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
		if (!level.getFluidState(landing.below()).isEmpty()) landing = target.above();
		base.dropSize = Mth.clamp(Math.max(base.dropSize, DROP_MIN + pressure - 1) + 1, DROP_MIN, DROP_MAX);
		List<UnitKind> squad = squad(base, base.dropSize, random);
		Vec3 attack = Vec3.atBottomCenterOf(target);
		for (int i = 0; i < squad.size(); i++) {
			UnitKind kind = squad.get(i);
			double a = i * Math.PI * 2 / squad.size();
			Vec3 at = Vec3.atBottomCenterOf(landing).add(Math.cos(a) * 3, 0, Math.sin(a) * 3);
			if (base.race == UnitKind.Race.TERRAN) at = at.add(0, 18, 0); // drop pods fall in
			StarUnit unit = HiveLogic.spawn(level, base, kind, at, null);
			if (unit == null) continue;
			if (base.race == UnitKind.Race.TERRAN)
				unit.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.SLOW_FALLING, 200, 0, false, false));
			unit.command(StarUnit.Order.ATTACK_MOVE, attack);
		}
		Vec3 centre = Vec3.atBottomCenterOf(landing);
		switch (base.race) {
			case TERRAN -> level.sendParticles(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, centre.x, centre.y + 12, centre.z, 30, 2, 6, 2, 0.02);
			case PROTOSS -> level.sendParticles(ParticleTypes.PORTAL, centre.x, centre.y + 1, centre.z, 120, 3, 1, 3, 0.5);
			case ZERG -> level.sendParticles(ParticleTypes.SQUID_INK, centre.x, centre.y + 0.5, centre.z, 60, 3, 0.3, 3, 0.05);
		}
		level.playSound(null, landing, base.race == UnitKind.Race.PROTOSS ? SoundEvents.BEACON_ACTIVATE : SoundEvents.GENERIC_EXPLODE.value(),
			SoundSource.HOSTILE, 1.5f, 0.8f);
		player.sendSystemMessage(Component.translatable("message.starmine.drop_incoming",
			Component.translatable("race.starmine." + base.race.name().toLowerCase())));
		return landing;
	}

	/** What a drop brings: the race's line infantry with something heavier as it grows. */
	static List<UnitKind> squad(Hive base, int size, RandomSource random) {
		UnitKind[] kinds = switch (base.race) {
			case ZERG -> new UnitKind[] {UnitKind.ZERGLING, UnitKind.ZERGLING, UnitKind.ROACH, UnitKind.HYDRALISK, UnitKind.BANELING};
			case TERRAN -> new UnitKind[] {UnitKind.MARINE, UnitKind.MARINE, UnitKind.MARAUDER, UnitKind.MARINE, UnitKind.GHOST};
			case PROTOSS -> new UnitKind[] {UnitKind.ZEALOT, UnitKind.ZEALOT, UnitKind.STALKER, UnitKind.ADEPT, UnitKind.SENTRY};
		};
		List<UnitKind> squad = new ArrayList<>();
		for (int i = 0; i < size; i++) squad.add(kinds[i < 2 ? i : random.nextInt(kinds.length)]);
		return squad;
	}

	/** Units of `base` within `radius` of a place (tests). */
	static int unitsNear(ServerLevel level, Hive base, BlockPos at, int radius) {
		return level.getEntitiesOfClass(StarUnit.class, new AABB(at).inflate(radius), unit -> base.id.equals(unit.hiveId()) && unit.isAlive()).size();
	}
}
