package dev.starmine.ai;

import dev.starmine.StarMine;
import dev.starmine.block.StarMineBlocks;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.faction.Faction;
import dev.starmine.hive.Hive;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Workers in danger (owner 16.10), every race the same way, at each decision of a wild base: a worker away from
 * its main building ({@link #AWAY}) with a foe within {@link #DANGER} (a monster, a player, another side's unit)
 * or in the dark gets an escort — the base's nearest idle warrior goes to it — and in the dark lights the place
 * its race's way: the zerg a glowing lump of flesh, the terrans a lantern, the protoss a sea lantern (the lights
 * of their claims, {@link Territory}; with `starmineHiveGriefing` off nothing is placed).
 */
public final class Escorts {
	public static final int AWAY = 24, DANGER = 20, DARK = 7;

	private Escorts() {
	}

	public static void decide(StarUnit main, ServerLevel level, Hive base) {
		boolean griefing = level.getGameRules().getBoolean(StarMine.HIVE_GRIEFING);
		List<StarUnit> workers = level.getEntitiesOfClass(StarUnit.class, main.getBoundingBox().inflate(128, 48, 128),
			unit -> unit.kind.worker() && base.id.equals(unit.hiveId()) && unit.isAlive() && unit.distanceToSqr(main) > AWAY * AWAY);
		for (StarUnit worker : workers) {
			BlockPos at = worker.blockPosition();
			boolean dark = !level.canSeeSky(at) && level.getBrightness(LightLayer.BLOCK, at) < DARK;
			if (!dark && !threatened(level, worker)) continue;
			if (dark && griefing) light(level, base.race, at);
			if (!level.getEntitiesOfClass(StarUnit.class, worker.getBoundingBox().inflate(10),
				unit -> base.id.equals(unit.hiveId()) && unit.isAlive() && warrior(unit)).isEmpty()) continue; // it has one
			StarUnit escort = level.getEntitiesOfClass(StarUnit.class, main.getBoundingBox().inflate(64, 24, 64),
					unit -> base.id.equals(unit.hiveId()) && unit.isAlive() && warrior(unit) && unit.order() == StarUnit.Order.NONE
						&& unit.getTarget() == null && unit.raidTarget() == null && unit.nest() == null && !unit.flying())
				.stream().min(Comparator.comparingDouble(unit -> unit.distanceToSqr(worker))).orElse(null);
			if (escort != null) escort.command(StarUnit.Order.MOVE, worker.position());
		}
	}

	private static boolean warrior(StarUnit unit) {
		return !unit.kind.structure() && !unit.kind.worker() && unit.kind != UnitKind.LARVA && unit.kind.attack != UnitKind.Attack.NONE;
	}

	/** A foe near the worker: a monster, a player, a unit of another side. */
	static boolean threatened(ServerLevel level, StarUnit worker) {
		return !level.getEntitiesOfClass(LivingEntity.class, worker.getBoundingBox().inflate(DANGER),
			other -> other.isAlive() && (other instanceof Enemy && !(other instanceof StarUnit) || other instanceof Player || other instanceof StarUnit)
				&& Faction.hostile(worker, other)).isEmpty();
	}

	/** Lights a dark place its race's way, beside `at` (where the worker stands); the light placed, or null. */
	public static @Nullable BlockPos light(ServerLevel level, UnitKind.Race race, BlockPos at) {
		for (BlockPos pos : BlockPos.betweenClosed(at.offset(-2, -1, -2), at.offset(2, 2, 2))) {
			if (pos.getX() == at.getX() && pos.getZ() == at.getZ()) continue; // not where it stands
			BlockState state = level.getBlockState(pos);
			switch (race) {
				case TERRAN -> { // a lantern on the floor
					if (state.isAir() && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), net.minecraft.core.Direction.UP)) {
						level.setBlock(pos, Blocks.LANTERN.defaultBlockState(), Block.UPDATE_ALL);
						return pos.immutable();
					}
				}
				default -> { // a lump of the wall: glowing flesh, a sea lantern
					if (natural(level, pos, state) && exposed(level, pos)) {
						level.setBlock(pos, race == UnitKind.Race.ZERG ? StarMineBlocks.GLOWING_ZERG_FLESH.defaultBlockState()
							: Blocks.SEA_LANTERN.defaultBlockState(), Block.UPDATE_ALL);
						return pos.immutable();
					}
				}
			}
		}
		return null;
	}

	private static boolean natural(ServerLevel level, BlockPos pos, BlockState state) {
		float hardness = state.getDestroySpeed(level, pos);
		return state.isSolid() && hardness >= 0 && hardness < 5 && level.getBlockEntity(pos) == null;
	}

	private static boolean exposed(ServerLevel level, BlockPos pos) {
		for (net.minecraft.core.Direction side : net.minecraft.core.Direction.values()) if (level.getBlockState(pos.relative(side)).isAir()) return true;
		return false;
	}

}
