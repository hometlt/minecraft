package dev.starmine.world;

import dev.starmine.StarMine;
import dev.starmine.ai.AiCommander;
import dev.starmine.ai.Territory;
import dev.starmine.block.LiquidCreepBlock;
import dev.starmine.block.StarMineBlocks;
import dev.starmine.entity.Spots;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.faction.Faction;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveLogic;
import dev.starmine.hive.HiveSavedData;
import dev.starmine.hive.HiveStructure;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Nydus corridors (owner 16.10): long tunnels that start at zerg bases. A nydus network
 * ({@link UnitKind#NYDUS_NETWORK}, the SC2 model) stands by the base; a nydus worm ({@link UnitKind#NYDUS_CANAL})
 * comes out {@link #MIN_LENGTH}-{@link #MAX_LENGTH} blocks away; between them runs a winding tunnel 2 x 2
 * under the land, lined with zerg flesh and full of liquid creep ({@link LiquidCreepBlock}: zerg rush through
 * it), with a shaft up beside each end.
 *
 * - A wild zerg base ({@link #decide}) with a network opens a corridor toward its nearest foe (stopping short of
 *   another race's wild, {@link RaceSeparation}), else anywhere; a spine crawler guards the worm.
 * - Every {@link #TRANSFER_TICKS} it sends idle warriors through, until {@link #GARRISON} stand by the worm;
 *   a hurt network or worm sends help through at once ({@link #underAttack}).
 * - A zerg unit at a network or a worm goes through to the other end when it is sent ({@link #bind}) or, a
 *   player's, when ordered to move onto the building. A player's drone builds the worm where it is wanted:
 *   when it is done, the corridor is dug from the base's nearest network ({@link #connect}).
 * The orifice block is kept for the free worms of older worlds (stepping on one still carries a zerg).
 */
public final class Nydus {
	public static final Orifice ORIFICE = Registry.register(BuiltInRegistries.BLOCK, StarMine.id("nydus_orifice"),
		new Orifice(BlockBehaviour.Properties.of().mapColor(MapColor.CRIMSON_NYLIUM).strength(1.5f, 6f).sound(SoundType.WART_BLOCK)
			.lightLevel(state -> 6).isValidSpawn((state, level, pos, type) -> false)));
	public static final BlockEntityType<Exit> EXIT = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, StarMine.id("nydus_orifice"),
		BlockEntityType.Builder.of(Exit::new, ORIFICE).build(null));
	/** How far a worm comes out from its network; how deep the tunnel runs under the lowest ground on its way. */
	public static final int MIN_LENGTH = 64, MAX_LENGTH = 112, DEPTH = 10, CONNECT_RANGE = 192;
	/** Ticks between squads sent through; warriors kept by a worm; the most sent at once. */
	public static final int TRANSFER_TICKS = 60 * 20, GARRISON = 6, SQUAD = 4;
	/** Ticks a zerg that came out of a worm is not taken back in. */
	static final int REST = 100;
	private static final Map<UUID, Long> RESTING = new HashMap<>();
	/** Units sent through by their base: where they go (the far end). */
	private static final Map<UUID, BlockPos> BOUND = new HashMap<>();

	private Nydus() {
	}

	public static void load() {
		Registry.register(BuiltInRegistries.ITEM, StarMine.id("nydus_orifice"), new BlockItem(ORIFICE, new Item.Properties()));
	}

	// ---- a base's corridors ----

	/** One decision of a wild zerg base: a corridor from its network, then squads through it. */
	public static void decide(StarUnit main, ServerLevel level, Hive base) {
		forgetLost(level, base);
		StarUnit network = level.getEntitiesOfClass(StarUnit.class, main.getBoundingBox().inflate(64),
				unit -> unit.kind == UnitKind.NYDUS_NETWORK && base.id.equals(unit.hiveId()) && unit.isAlive() && !unit.underConstruction())
			.stream().findFirst().orElse(null);
		if (network == null) return;
		if (base.nydus.isEmpty() && base.canAfford(UnitKind.NYDUS_CANAL, 1)) {
			BlockPos site = site(level, base, network);
			if (site != null) {
				open(level, base, network, site, level.random);
				base.pay(UnitKind.NYDUS_CANAL, 1);
			}
		}
		long now = level.getGameTime();
		if (!base.nydus.isEmpty() && now >= base.nextNydusTransfer) {
			base.nextNydusTransfer = now + TRANSFER_TICKS;
			transfer(level, base, main.position(), GARRISON);
		}
	}

	/** Where a base's worm comes out: toward its nearest foe, short of other races' wild; else anywhere about. */
	static @Nullable BlockPos site(ServerLevel level, Hive base, StarUnit network) {
		BlockPos from = network.blockPosition();
		BlockPos foe = Territory.foeClaim(level, base, from, AiCommander.WAVE_RANGE);
		if (foe == null) {
			String dimension = level.dimension().location().toString();
			foe = HiveSavedData.get(level.getServer()).all().stream()
				.filter(other -> other != base && other.dimension.equals(dimension) && (other.owner != null || other.race != base.race)
					&& other.hatchery.distSqr(from) < (double) AiCommander.WAVE_RANGE * AiCommander.WAVE_RANGE)
				.map(other -> other.hatchery).min(java.util.Comparator.comparingDouble(pos -> pos.distSqr(from))).orElse(null);
		}
		RandomSource random = level.random;
		for (int attempt = 0; attempt < 12; attempt++) {
			double angle, length = MIN_LENGTH + random.nextInt(MAX_LENGTH - MIN_LENGTH + 1);
			if (foe != null && attempt < 6) {
				angle = Math.atan2(foe.getZ() - from.getZ(), foe.getX() - from.getX()) + (random.nextDouble() - 0.5) * 0.5;
				length = Math.min(length, Math.sqrt(foe.distSqr(from)) - RaceSeparation.GAP);
				if (length < MIN_LENGTH) continue;
			} else angle = random.nextDouble() * Math.PI * 2;
			int x = Mth.floor(network.getX() + Math.cos(angle) * length), z = Mth.floor(network.getZ() + Math.sin(angle) * length);
			BlockPos ground = ground(level, x, z, from.getY());
			if (ground == null || RaceSeparation.foreignNear(level, UnitKind.Race.ZERG, ground) != null) continue;
			return ground;
		}
		return null;
	}

	/**
	 * Opens a corridor from `network` to a worm at `site`: the tunnel now, the worm (finished) and a spine
	 * crawler by it as soon as their place is loaded ({@link HiveSavedData#pending}); records it in the base.
	 * Returns the blocks it changed and what was there before (tests put them back).
	 */
	public static Map<BlockPos, BlockState> open(ServerLevel level, Hive base, StarUnit network, BlockPos site, RandomSource random) {
		Vec3 at = UnitKind.NYDUS_CANAL.gridCentre(Vec3.atBottomCenterOf(site));
		BlockPos canal = BlockPos.containing(at);
		keepLoaded(level, canal);
		HiveSavedData data = HiveSavedData.get(level.getServer());
		data.pending.add(new HiveStructure.Pending(base.id, UnitKind.NYDUS_CANAL, canal, null, null, 0));
		Map<BlockPos, BlockState> changed = dig(level, network.position(), network.kind.width, at, UnitKind.NYDUS_CANAL.width, random);
		double side = random.nextDouble() * Math.PI * 2, r = UnitKind.NYDUS_CANAL.width / 2 + UnitKind.SPINE_CRAWLER.width / 2 + 1;
		BlockPos feet = HiveLogic.groundNear(level, Mth.floor(at.x + Math.cos(side) * r), canal.getY(), Mth.floor(at.z + Math.sin(side) * r));
		if (feet != null)
			data.pending.add(new HiveStructure.Pending(base.id, UnitKind.SPINE_CRAWLER, BlockPos.containing(UnitKind.SPINE_CRAWLER.gridCentre(Vec3.atBottomCenterOf(feet))), null, null, 0));
		record(level, base, network.blockPosition(), canal);
		level.playSound(null, canal, SoundEvents.SLIME_SQUISH, SoundSource.HOSTILE, 2.0f, 0.5f);
		return changed;
	}

	/** Keeps the chunks round `pos` loaded and ticking a while (a worm coming out, a unit arriving where no player is). */
	static void keepLoaded(ServerLevel level, BlockPos pos) {
		level.getChunkSource().addRegionTicket(net.minecraft.server.level.TicketType.PORTAL, new net.minecraft.world.level.ChunkPos(pos), 2, pos);
	}

	/** A worm is done (a player's drone built it): the corridor from its base's nearest network, if there is one. */
	public static void connect(ServerLevel level, StarUnit canal) {
		Hive base = canal.hiveId() == null ? null : HiveSavedData.get(level.getServer()).get(canal.hiveId());
		if (base == null) return;
		StarUnit network = level.getEntitiesOfClass(StarUnit.class, canal.getBoundingBox().inflate(CONNECT_RANGE, 64, CONNECT_RANGE),
				unit -> unit.kind == UnitKind.NYDUS_NETWORK && base.id.equals(unit.hiveId()) && unit.isAlive() && !unit.underConstruction())
			.stream().min(java.util.Comparator.comparingDouble(unit -> unit.distanceToSqr(canal))).orElse(null);
		if (network == null) return;
		dig(level, network.position(), network.kind.width, canal.position(), canal.kind.width, level.random);
		record(level, base, network.blockPosition(), canal.blockPosition());
	}

	private static void record(ServerLevel level, Hive base, BlockPos network, BlockPos canal) {
		base.nydus.add(network.immutable());
		base.nydus.add(canal.immutable());
		HiveSavedData.get(level.getServer()).setDirty();
	}

	/** Pairs whose network or worm is gone (seen where its chunk is loaded) are forgotten. */
	private static void forgetLost(ServerLevel level, Hive base) {
		for (int i = base.nydus.size() - 2; i >= 0; i -= 2)
			if (gone(level, base, base.nydus.get(i), UnitKind.NYDUS_NETWORK) || gone(level, base, base.nydus.get(i + 1), UnitKind.NYDUS_CANAL)) {
				base.nydus.remove(i + 1);
				base.nydus.remove(i);
			}
	}

	private static boolean gone(ServerLevel level, Hive base, BlockPos pos, UnitKind kind) {
		return level.isLoaded(pos) && level.isPositionEntityTicking(pos) && building(level, base, pos, kind) == null;
	}

	private static @Nullable StarUnit building(ServerLevel level, Hive base, BlockPos pos, UnitKind kind) {
		return level.getEntitiesOfClass(StarUnit.class, new AABB(pos).inflate(2),
			unit -> unit.kind == kind && base.id.equals(unit.hiveId()) && unit.isAlive()).stream().findFirst().orElse(null);
	}

	// ---- squads through ----

	/** Sends idle warriors of the base near `home` through its corridors until each worm has `garrison` by it; how many went. */
	public static int transfer(ServerLevel level, Hive base, Vec3 home, int garrison) {
		int sent = 0;
		for (int i = 0; i + 1 < base.nydus.size(); i += 2) {
			BlockPos network = base.nydus.get(i), canal = base.nydus.get(i + 1);
			int there = level.isLoaded(canal) ? level.getEntitiesOfClass(StarUnit.class, new AABB(canal).inflate(16),
				unit -> base.id.equals(unit.hiveId()) && unit.isAlive() && warrior(unit)).size() : 0;
			int want = Math.min(SQUAD, garrison - there);
			if (want <= 0) continue;
			List<StarUnit> idle = level.getEntitiesOfClass(StarUnit.class, new AABB(BlockPos.containing(home)).inflate(48),
				unit -> base.id.equals(unit.hiveId()) && unit.isAlive() && warrior(unit) && !unit.flying() && unit.order() == StarUnit.Order.NONE
					&& unit.raidTarget() == null && unit.nest() == null && unit.getTarget() == null && !BOUND.containsKey(unit.getUUID())
					&& unit.distanceToSqr(Vec3.atCenterOf(canal)) > 24 * 24);
			for (StarUnit unit : idle.subList(0, Math.min(want, idle.size()))) {
				bind(unit, canal);
				unit.command(StarUnit.Order.MOVE, Vec3.atBottomCenterOf(network));
				sent++;
			}
		}
		return sent;
	}

	private static boolean warrior(StarUnit unit) {
		return !unit.kind.structure() && !unit.kind.worker() && unit.kind != UnitKind.LARVA && unit.kind.attack != UnitKind.Attack.NONE;
	}

	/** Sends `unit` through the corridor ending at `to` (the far end) when it reaches the near one. */
	public static void bind(StarUnit unit, BlockPos to) {
		BOUND.put(unit.getUUID(), to.immutable());
	}

	/** A network or worm of a base is hurt: help comes through the corridor at once (the base's warriors at the other end). */
	public static void underAttack(ServerLevel level, StarUnit building, LivingEntity attacker) {
		Hive base = HiveSavedData.get(level.getServer()).get(building.hiveId());
		if (base == null || base.owner != null) return;
		for (int i = 0; i + 1 < base.nydus.size(); i += 2) {
			BlockPos network = base.nydus.get(i), canal = base.nydus.get(i + 1);
			boolean atCanal = canal.distSqr(building.blockPosition()) < 9, atNetwork = network.distSqr(building.blockPosition()) < 9;
			if (!atCanal && !atNetwork) continue;
			BlockPos near = atCanal ? network : canal, far = atCanal ? canal : network;
			if (!level.isLoaded(near)) continue;
			for (StarUnit unit : level.getEntitiesOfClass(StarUnit.class, new AABB(near).inflate(48),
				u -> base.id.equals(u.hiveId()) && u.isAlive() && warrior(u) && !u.flying() && u.raidTarget() == null && u.nest() == null
					&& u.getTarget() == null && !BOUND.containsKey(u.getUUID())).stream().limit(SQUAD).toList()) {
				bind(unit, far);
				unit.command(StarUnit.Order.MOVE, Vec3.atBottomCenterOf(near));
			}
		}
	}

	/** A finished network or worm, now and then: zerg at it that are meant to go through come out by the other end. */
	public static void tick(StarUnit building, ServerLevel level) {
		if (building.hiveId() == null) return;
		Hive base = HiveSavedData.get(level.getServer()).get(building.hiveId());
		if (base == null) return;
		List<BlockPos> others = new ArrayList<>();
		for (int i = 0; i + 1 < base.nydus.size(); i += 2) {
			if (base.nydus.get(i).distSqr(building.blockPosition()) < 9) others.add(base.nydus.get(i + 1));
			else if (base.nydus.get(i + 1).distSqr(building.blockPosition()) < 9) others.add(base.nydus.get(i));
		}
		if (others.isEmpty()) return;
		double reach = building.kind.width / 2 + 3;
		AABB box = building.getBoundingBox().inflate(3, 2, 3), footprint = building.getBoundingBox().inflate(1, 3, 1);
		for (StarUnit unit : level.getEntitiesOfClass(StarUnit.class, box, unit -> unit.isAlive() && unit.kind.race == UnitKind.Race.ZERG
			&& !unit.kind.structure() && !unit.flying() && !unit.isPassenger() && Faction.allied(unit, building))) {
			if (Math.hypot(unit.getX() - building.getX(), unit.getZ() - building.getZ()) > reach + unit.kind.width / 2) continue;
			BlockPos to = BOUND.get(unit.getUUID());
			if (to != null && !others.contains(to)) continue; // bound for another corridor
			if (to == null && !(unit.owner().isPresent() && unit.order() == StarUnit.Order.MOVE && unit.orderPos() != null
				&& footprint.contains(unit.orderPos()))) continue;
			travel(level, unit, to != null ? to : others.get(others.size() - 1));
		}
	}

	/** `unit` comes out by the building at `to` and stays there (its new home, on guard). */
	static boolean travel(ServerLevel level, StarUnit unit, BlockPos to) {
		long now = level.getGameTime();
		Long resting = RESTING.get(unit.getUUID());
		if (resting != null && now < resting) return false;
		if (!level.isPositionEntityTicking(to)) { // the far end is not loaded: it will be in a moment, the unit waits
			keepLoaded(level, to);
			return false;
		}
		StarUnit end = level.getEntitiesOfClass(StarUnit.class, new AABB(to).inflate(2),
			other -> (other.kind == UnitKind.NYDUS_CANAL || other.kind == UnitKind.NYDUS_NETWORK) && other.isAlive()).stream().findFirst().orElse(null);
		double radius = (end != null ? end.kind.width / 2 : 1) + unit.kind.width / 2 + 0.5;
		Vec3 out = Spots.outside(level, unit.kind, end != null ? end.position() : Vec3.atBottomCenterOf(to), level.random.nextDouble() * Math.PI * 2, radius);
		level.playSound(null, unit.blockPosition(), SoundEvents.SLIME_SQUISH, SoundSource.HOSTILE, 1.0f, 0.6f);
		unit.teleportTo(out.x, out.y, out.z);
		unit.command(StarUnit.Order.NONE, out);
		level.playSound(null, BlockPos.containing(out), SoundEvents.SLIME_SQUISH, SoundSource.HOSTILE, 1.0f, 0.6f);
		BOUND.remove(unit.getUUID());
		RESTING.put(unit.getUUID(), now + REST);
		if (RESTING.size() > 512) RESTING.values().removeIf(until -> until < now);
		return true;
	}

	// ---- the tunnel ----

	/** The ground of the column (x, z) near `y` (a test arena under a ceiling, a cave) or by the heightmap; null on water. */
	static @Nullable BlockPos ground(ServerLevel level, int x, int z, int y) {
		level.getChunk(x >> 4, z >> 4);
		BlockPos feet = HiveLogic.groundNear(level, x, y, z);
		if (feet == null) feet = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
		return level.getFluidState(feet.below()).isEmpty() && level.getFluidState(feet).isEmpty() ? feet : null;
	}

	/**
	 * Digs the corridor between a network and a worm: a shaft beside each, a winding tunnel 2 x 2 between them
	 * {@link #DEPTH} under the lowest ground on the way, flesh round it, liquid creep in it. Returns what it changed.
	 */
	public static Map<BlockPos, BlockState> dig(ServerLevel level, Vec3 from, float fromWidth, Vec3 to, float toWidth, RandomSource random) {
		BlockPos a = mouth(level, from, fromWidth, to), b = mouth(level, to, toWidth, from);
		// the way: steps toward b, now and then aside (it winds), with the ground over each step
		List<BlockPos> way = new ArrayList<>();
		int x = a.getX(), z = a.getZ(), hint = a.getY(), lowest = Math.min(a.getY(), b.getY());
		while (x != b.getX() || z != b.getZ()) {
			int dx = b.getX() - x, dz = b.getZ() - z;
			if (random.nextInt(5) == 0 && Math.abs(dx) + Math.abs(dz) > 6) { // a bend
				if (Math.abs(dx) >= Math.abs(dz)) z += random.nextBoolean() ? 1 : -1;
				else x += random.nextBoolean() ? 1 : -1;
			} else if (Math.abs(dx) >= Math.abs(dz) ? dx != 0 : dz == 0) x += Integer.signum(dx);
			else z += Integer.signum(dz);
			BlockPos ground = HiveLogic.groundNear(level, x, hint, z);
			int surface = ground != null ? ground.getY() : level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			hint = surface;
			lowest = Math.min(lowest, surface);
			way.add(new BlockPos(x, surface, z));
		}
		int floor = Math.max(level.getMinBuildHeight() + 4, lowest - DEPTH);
		Set<BlockPos> inside = new LinkedHashSet<>();
		for (BlockPos top : new BlockPos[] {a, b})
			for (int y = floor; y < top.getY(); y++) square(inside, top.getX(), y, top.getZ());
		for (BlockPos step : way) {
			square(inside, step.getX(), floor, step.getZ());
			square(inside, step.getX(), floor + 1, step.getZ());
		}
		Map<BlockPos, BlockState> changed = new LinkedHashMap<>();
		BlockState flesh = StarMineBlocks.ZERG_FLESH.defaultBlockState(), glow = StarMineBlocks.GLOWING_ZERG_FLESH.defaultBlockState();
		for (BlockPos cell : inside)
			for (int dx = -1; dx <= 1; dx++)
				for (int dy = -1; dy <= 1; dy++)
					for (int dz = -1; dz <= 1; dz++) {
						BlockPos at = cell.offset(dx, dy, dz);
						if (inside.contains(at) || level.getBlockState(at).isAir()) continue; // the lining only in the ground
						set(level, changed, at, random.nextInt(12) == 0 ? glow : flesh);
					}
		BlockState creep = StarMineBlocks.LIQUID_CREEP.defaultBlockState();
		for (BlockPos cell : inside) set(level, changed, cell, creep);
		return changed;
	}

	/** Where a building's shaft comes up: beside it (`width` wide at `at`), toward the other end. */
	private static BlockPos mouth(ServerLevel level, Vec3 at, float width, Vec3 toward) {
		double angle = Math.atan2(toward.z - at.z, toward.x - at.x), r = width / 2 + 1.5;
		int x = Mth.floor(at.x + Math.cos(angle) * r) - 1, z = Mth.floor(at.z + Math.sin(angle) * r) - 1;
		BlockPos ground = HiveLogic.groundNear(level, x, Mth.floor(at.y), z);
		return ground != null ? ground : new BlockPos(x, Mth.floor(at.y), z);
	}

	private static void square(Set<BlockPos> cells, int x, int y, int z) {
		for (int dx = 0; dx <= 1; dx++)
			for (int dz = 0; dz <= 1; dz++) cells.add(new BlockPos(x + dx, y, z + dz));
	}

	private static void set(ServerLevel level, Map<BlockPos, BlockState> changed, BlockPos pos, BlockState state) {
		if (pos.getY() <= level.getMinBuildHeight() || pos.getY() >= level.getMaxBuildHeight()) return;
		BlockState old = level.getBlockState(pos);
		if (old.is(Blocks.BEDROCK) || old.is(Blocks.BARRIER) || level.getBlockEntity(pos) != null) return;
		changed.putIfAbsent(pos.immutable(), old);
		level.setBlock(pos, state, Block.UPDATE_CLIENTS);
	}

	// ---- the free worms of older worlds ----

	/** Where an orifice leads. */
	public static class Exit extends BlockEntity {
		@Nullable BlockPos to;

		public Exit(BlockPos pos, BlockState state) {
			super(EXIT, pos, state);
		}

		@Override
		protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
			super.saveAdditional(tag, registries);
			if (to != null) tag.put("to", NbtUtils.writeBlockPos(to));
		}

		@Override
		protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
			super.loadAdditional(tag, registries);
			to = NbtUtils.readBlockPos(tag, "to").orElse(null);
		}
	}

	/** A nydus orifice (worms of older worlds): a zerg stepping on it comes out by the other worm. */
	public static final class Orifice extends Block implements EntityBlock {
		Orifice(Properties properties) {
			super(properties);
		}

		@Override
		public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
			return new Exit(pos, state);
		}

		@Override
		public void stepOn(Level level, BlockPos pos, BlockState state, Entity entity) {
			if (level instanceof ServerLevel server && LiquidCreepBlock.zerg(entity)) orifice(server, pos, entity);
			super.stepOn(level, pos, state, entity);
		}
	}

	private static void orifice(ServerLevel level, BlockPos pos, Entity entity) {
		long now = level.getGameTime();
		Long resting = RESTING.get(entity.getUUID());
		if (resting != null && now < resting) return;
		if (!(level.getBlockEntity(pos) instanceof Exit exit) || exit.to == null) return;
		BlockPos to = exit.to;
		level.getChunk(to.getX() >> 4, to.getZ() >> 4);
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, to.getX(), to.getZ());
		RESTING.put(entity.getUUID(), now + REST);
		level.playSound(null, pos, SoundEvents.SLIME_SQUISH, SoundSource.HOSTILE, 1.0f, 0.6f);
		entity.teleportTo(to.getX() + 0.5, y, to.getZ() + 0.5);
		level.playSound(null, to, SoundEvents.SLIME_SQUISH, SoundSource.HOSTILE, 1.0f, 0.6f);
	}
}
