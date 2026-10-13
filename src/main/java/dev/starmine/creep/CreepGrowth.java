package dev.starmine.creep;

import dev.starmine.StarMine;
import dev.starmine.block.CreepBlock;
import dev.starmine.block.CreepTumorBlock;
import dev.starmine.block.StarMineBlocks;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.MultifaceSpreader;
import net.minecraft.world.level.block.state.BlockState;

/**
 * How creep grows (stage 3 of docs/HIVE_DESIGN.md). Every source (a hatchery, a creep tumor) keeps a
 * list of its creep cells and, each tick, spreads a random one toward a random neighbouring face with
 * the game's multiface spreader — onto the same block's other faces, the next block, around corners —
 * so floors, walls and ceilings are covered alike. Faces beyond the source's radius are undone.
 *
 * Creep kills vegetation (owner): plants next to it go (tag `starmine:creep_destroys`), natural leaves
 * fall, and grass-like ground under it turns to dirt (tag `starmine:creep_withers`). The list is
 * transient: after a reload a source re-seeds around itself and walks back over its old creep.
 */
public final class CreepGrowth {
	public static final TagKey<Block> DESTROYS = TagKey.create(Registries.BLOCK, StarMine.id("creep_destroys"));
	public static final TagKey<Block> WITHERS = TagKey.create(Registries.BLOCK, StarMine.id("creep_withers"));
	private static final int MAX_CELLS = 6000, STEPS_PER_TICK = 2;
	private static final Direction[] SEED_FACES = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};

	private record Key(ResourceKey<Level> dimension, long pos) {
	}

	private static final class Source {
		final List<BlockPos> cells = new ArrayList<>();
		final Set<BlockPos> known = new HashSet<>();
	}

	private static final Map<Key, Source> SOURCES = new HashMap<>();

	private CreepGrowth() {
	}

	/** One tick of a source's growth; `seed` is how far around the source new creep may start. */
	public static void grow(ServerLevel level, BlockPos source, int radius) {
		grow(level, source, radius, 1);
	}

	public static void grow(ServerLevel level, BlockPos source, int radius, int seed) {
		if (!level.getGameRules().getBoolean(StarMine.CREEP_SPREAD)) return;
		Source s = SOURCES.computeIfAbsent(new Key(level.dimension(), source.asLong()), key -> new Source());
		if (s.cells.isEmpty() || level.random.nextInt(400) == 0) seed(level, source, seed, s);
		CreepBlock creep = StarMineBlocks.CREEP;
		MultifaceSpreader spreader = creep.getSpreader();
		for (int step = 0; step < STEPS_PER_TICK && !s.cells.isEmpty(); step++) {
			int index = level.random.nextInt(s.cells.size());
			BlockPos cell = s.cells.get(index);
			BlockState state = level.getBlockState(cell);
			if (!state.is(creep)) {
				drop(s, index);
				continue;
			}
			clearAround(level, cell);
			Optional<MultifaceSpreader.SpreadPos> spread = spreader.spreadFromRandomFaceTowardRandomDirection(state, level, cell, level.random);
			if (spread.isEmpty()) continue;
			BlockPos grown = spread.get().pos();
			if (grown.distSqr(source) > (radius + 0.5) * (radius + 0.5)) {
				undo(level, grown, spread.get().face());
				continue;
			}
			wither(level, grown);
			if (decorate(level, grown)) continue;
			if (s.known.add(grown)) s.cells.add(grown);
			if (s.cells.size() > MAX_CELLS) drop(s, level.random.nextInt(s.cells.size()));
		}
	}

	/** Creep starts on every free face around the source (the ring of a hatchery's foundation). */
	private static void seed(ServerLevel level, BlockPos source, int range, Source s) {
		for (BlockPos pos : BlockPos.betweenClosed(source.offset(-range, -1, -range), source.offset(range, 1, range))) {
			BlockState state = level.getBlockState(pos);
			if (state.is(DESTROYS)) {
				level.destroyBlock(pos, false);
				state = level.getBlockState(pos);
			}
			if (state.is(StarMineBlocks.CREEP)) {
				if (s.known.add(pos.immutable())) s.cells.add(pos.immutable());
				continue;
			}
			if (!state.isAir()) continue;
			for (Direction face : SEED_FACES) {
				BlockState placed = StarMineBlocks.CREEP.getStateForPlacement(state, level, pos, face);
				if (placed == null) continue;
				level.setBlockAndUpdate(pos, placed);
				wither(level, pos);
				if (s.known.add(pos.immutable())) s.cells.add(pos.immutable());
				break;
			}
		}
	}

	private static void drop(Source s, int index) {
		BlockPos last = s.cells.remove(s.cells.size() - 1);
		if (index < s.cells.size()) {
			s.known.remove(s.cells.get(index));
			s.cells.set(index, last);
		} else {
			s.known.remove(last);
		}
	}

	private static void undo(ServerLevel level, BlockPos pos, Direction face) {
		BlockState state = level.getBlockState(pos);
		if (!state.is(StarMineBlocks.CREEP)) return;
		BlockState without = state.setValue(MultifaceBlock.getFaceProperty(face), false);
		level.setBlockAndUpdate(pos, MultifaceBlock.availableFaces(without).isEmpty() ? Blocks.AIR.defaultBlockState() : without);
	}

	/**
	 * Now and then a creep floor grows something (owner: so creep-ravaged ground is not all alike): a
	 * tendril, pustules, vessels or a two-block tentacle. Only on plain floor creep with air above.
	 */
	private static boolean decorate(ServerLevel level, BlockPos pos) {
		if (level.random.nextFloat() >= 0.06f) return false;
		return grow(level, pos, false);
	}

	/**
	 * A growth on a creep floor cell, unless another growth is within 2 blocks (not too dense);
	 * `tentacle` forces a two-block tentacle (where a tree stood). True when something grew.
	 */
	private static boolean grow(ServerLevel level, BlockPos pos, boolean tentacle) {
		if (!tentacle)
			for (BlockPos near : BlockPos.betweenClosed(pos.offset(-2, -1, -2), pos.offset(2, 2, 2)))
				if (isGrowth(level.getBlockState(near))) return false;
		BlockState state = level.getBlockState(pos);
		if (state.isAir() && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP))
			state = StarMineBlocks.CREEP.getStateForPlacement(state, level, pos, Direction.DOWN);
		if (state == null) return false;
		if (!state.is(StarMineBlocks.CREEP) || !MultifaceBlock.hasFace(state, Direction.DOWN) || MultifaceBlock.availableFaces(state).size() > 1) return false;
		BlockPos below = pos.below();
		if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP) || !level.getBlockState(pos.above()).isAir()) return false;
		float pick = tentacle ? 0 : level.random.nextFloat();
		if (pick < 0.12f && level.getBlockState(pos.above()).isAir()) {
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
			net.minecraft.world.level.block.DoublePlantBlock.placeAt(level, StarMineBlocks.CREEP_TENTACLE.defaultBlockState(), pos, 3);
			return true;
		}
		Block growth = pick < 0.5f ? StarMineBlocks.CREEP_TENDRIL : pick < 0.75f ? StarMineBlocks.CREEP_PUSTULE : StarMineBlocks.CREEP_VESSELS;
		level.setBlockAndUpdate(pos, growth.defaultBlockState());
		return true;
	}

	private static boolean isGrowth(BlockState state) {
		return state.is(StarMineBlocks.CREEP_TENDRIL) || state.is(StarMineBlocks.CREEP_PUSTULE)
			|| state.is(StarMineBlocks.CREEP_VESSELS) || state.is(StarMineBlocks.CREEP_TENTACLE);
	}

	/**
	 * What creep touches dies (owner): plants go — and sometimes a growth takes their place; natural
	 * leaves fall; a natural tree it reaches is consumed whole and a tentacle grows where it stood.
	 */
	private static void clearAround(ServerLevel level, BlockPos cell) {
		for (Direction d : Direction.values()) {
			BlockPos near = cell.relative(d);
			BlockState state = level.getBlockState(near);
			if (state.is(DESTROYS)) {
				level.destroyBlock(near, false);
				if (level.random.nextFloat() < 0.25f) grow(level, near, false);
			} else if (state.is(BlockTags.LEAVES) && state.hasProperty(LeavesBlock.PERSISTENT) && !state.getValue(LeavesBlock.PERSISTENT)) {
				level.destroyBlock(near, false);
			} else if (state.is(BlockTags.LOGS)) {
				consumeTree(level, near);
			}
		}
	}

	/**
	 * A natural tree (its logs touch non-persistent leaves; log houses are spared) is consumed: logs and
	 * leaves vanish and a creep tentacle grows at the foot of the trunk.
	 */
	private static void consumeTree(ServerLevel level, BlockPos seed) {
		List<BlockPos> logs = new ArrayList<>();
		Set<BlockPos> seen = new HashSet<>();
		java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>(List.of(seed));
		boolean natural = false;
		while (!queue.isEmpty() && logs.size() < 200) {
			BlockPos pos = queue.poll();
			if (!seen.add(pos)) continue;
			BlockState state = level.getBlockState(pos);
			if (state.is(BlockTags.LOGS)) {
				logs.add(pos);
				for (BlockPos near : BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, 1, 1))) queue.add(near.immutable());
			} else if (state.is(BlockTags.LEAVES) && state.hasProperty(LeavesBlock.PERSISTENT) && !state.getValue(LeavesBlock.PERSISTENT)) {
				natural = true;
			}
		}
		if (!natural || logs.isEmpty()) return;
		BlockPos foot = logs.stream().min(java.util.Comparator.comparingInt(BlockPos::getY)).get();
		for (BlockPos log : logs)
			for (BlockPos near : BlockPos.betweenClosed(log.offset(-3, -1, -3), log.offset(3, 3, 3))) {
				BlockState state = level.getBlockState(near);
				if (state.is(BlockTags.LEAVES) && state.hasProperty(LeavesBlock.PERSISTENT) && !state.getValue(LeavesBlock.PERSISTENT))
					level.setBlock(near, Blocks.AIR.defaultBlockState(), 2);
			}
		for (BlockPos log : logs) level.setBlock(log, Blocks.AIR.defaultBlockState(), 3);
		grow(level, foot, true);
	}

	/** Grass, podzol, mycelium and paths under creep turn to dirt. */
	private static void wither(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (!state.is(StarMineBlocks.CREEP)) return;
		for (Direction face : MultifaceBlock.availableFaces(state)) {
			BlockPos ground = pos.relative(face);
			if (level.getBlockState(ground).is(WITHERS)) level.setBlockAndUpdate(ground, Blocks.DIRT.defaultBlockState());
		}
	}

	/**
	 * A new tumor on the creep of `from`: a creep cell between `min` and `max` blocks away and at least
	 * `min` from every other source, standing on the face its creep lies on.
	 */
	public static boolean spawnTumor(ServerLevel level, BlockPos from, int min, int max) {
		if (CreepSources.tumors(level).size() >= CreepSources.MAX_TUMORS) return false;
		Source s = SOURCES.get(new Key(level.dimension(), from.asLong()));
		if (s == null || s.cells.isEmpty()) return false;
		for (int attempt = 0; attempt < 24; attempt++) {
			BlockPos cell = s.cells.get(level.random.nextInt(s.cells.size()));
			double distance = Math.sqrt(cell.distSqr(from));
			if (distance < min || distance > max) continue;
			if (placeTumor(level, cell, min)) return true;
		}
		return false;
	}

	/** Puts a tumor on a creep cell (any face); false when it cannot stand there. */
	public static boolean placeTumor(ServerLevel level, BlockPos cell, int spacing) {
		BlockState state = level.getBlockState(cell);
		if (!state.is(StarMineBlocks.CREEP) || CreepSources.nearestSource(level, cell) < spacing) return false;
		Set<Direction> faces = MultifaceBlock.availableFaces(state);
		Direction face = faces.contains(Direction.DOWN) ? Direction.DOWN : faces.stream().findFirst().orElse(null);
		if (face == null) return false;
		BlockState tumor = StarMineBlocks.CREEP_TUMOR.defaultBlockState().setValue(CreepTumorBlock.FACING, face.getOpposite());
		if (!tumor.canSurvive(level, cell)) return false;
		level.setBlockAndUpdate(cell, tumor);
		return true;
	}

	/**
	 * A block that carried creep was dug out: the creep is not lost but lines the faces of the cavity
	 * it leaves (the walls, floor and ceiling around the empty cell).
	 */
	public static void lineCavity(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (!state.isAir()) return;
		BlockState lined = null;
		for (Direction face : Direction.values()) {
			BlockState next = StarMineBlocks.CREEP.getStateForPlacement(lined == null ? state : lined, level, pos, face);
			if (next != null) lined = next;
		}
		if (lined != null) level.setBlockAndUpdate(pos, lined);
	}

	/** Forgets a source that is gone (its creep then recedes on its own). */
	public static void forget(ServerLevel level, BlockPos source) {
		SOURCES.remove(new Key(level.dimension(), source.asLong()));
	}
}
