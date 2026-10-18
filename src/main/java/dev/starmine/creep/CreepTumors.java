package dev.starmine.creep;

import dev.starmine.block.CreepTumorBlockEntity;
import dev.starmine.block.StarMineBlocks;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Creep tumors as in SC2 (owner 17.10):
 * - a tumor shows for its first {@link #SHOWN_TICKS}, then burrows: only its own side and detectors see it (a
 *   detector unit or building in sight, a raven, an observer, an overseer; a player under a detector potion);
 * - it has {@link #HEALTH} hit points: shots, arrows and blows wear it down ({@link #hurt}); units that see a foe's
 *   tumor in their reach shoot it of their own accord ({@link #attackNear});
 * - it spreads once, as in SC2, soon after it burrows ({@link CreepTumorBlockEntity}).
 */
public final class CreepTumors {
	/** SC2: a tumor is seen while it rises, then burrows (about 5 s); 50 hit points. */
	public static final int SHOWN_TICKS = 100;
	public static final double HEALTH = 50 * UnitKind.SCALE;
	/** How far a detector potion detects (SC2 11). */
	public static final double POTION_DETECT = 11 * UnitKind.BLOCKS_PER_UNIT;

	private CreepTumors() {
	}

	public static @Nullable CreepTumorBlockEntity at(Level level, BlockPos pos) {
		return level.getBlockEntity(pos) instanceof CreepTumorBlockEntity tumor ? tumor : null;
	}

	/** Whether `looker` is of the tumor's side: its player (and their units), or for a wild tumor the wild zerg. */
	public static boolean ownSide(@Nullable UUID owner, Entity looker) {
		if (owner == null) return looker instanceof StarUnit unit && unit.owner().isEmpty() && unit.kind.race == UnitKind.Race.ZERG;
		if (looker instanceof Player player) return owner.equals(player.getUUID());
		return looker instanceof StarUnit unit && unit.owner().filter(owner::equals).isPresent();
	}

	/** Whether `looker` sees the tumor now: risen, its own side, or a detector of the looker's side near it. */
	public static boolean seen(Level level, BlockPos pos, Entity looker) {
		CreepTumorBlockEntity tumor = at(level, pos);
		if (tumor == null) return false;
		if (tumor.plantedAt() < 0 || level.getGameTime() - tumor.plantedAt() < SHOWN_TICKS || ownSide(tumor.owner(), looker)) return true;
		Vec3 centre = Vec3.atCenterOf(pos);
		double search = 16;
		for (StarUnit detector : level.getEntitiesOfClass(StarUnit.class, new net.minecraft.world.phys.AABB(pos).inflate(search),
			unit -> unit.isAlive() && unit.kind.detector() && !unit.underConstruction() && unit.distanceToSqr(centre) <= unit.kind.sight() * unit.kind.sight()))
			if (detector == looker || dev.starmine.faction.Faction.allied(detector, looker)) return true;
		for (LivingEntity other : level.getEntitiesOfClass(LivingEntity.class, new net.minecraft.world.phys.AABB(pos).inflate(POTION_DETECT),
			other -> !(other instanceof StarUnit) && other.isAlive() && other.distanceToSqr(centre) <= POTION_DETECT * POTION_DETECT
				&& other.hasEffect(dev.starmine.gear.TraitPotions.effect(dev.starmine.entity.Trait.DETECTOR))))
			if (other == looker || dev.starmine.faction.Faction.allied(other, looker)) return true;
		return false;
	}

	/**
	 * SC2: a player orders their creep tumor to spread - once, to a creep cell within its reach ({@link CreepSources#TUMOR_RADIUS}):
	 * the clicked block, or the creep cell nearest it. False (and a message) when it cannot.
	 */
	public static boolean direct(ServerLevel level, net.minecraft.server.level.ServerPlayer player, BlockPos from, BlockPos to) {
		CreepTumorBlockEntity tumor = at(level, from);
		if (tumor == null || !player.getUUID().equals(tumor.owner())) return false;
		if (tumor.spent()) {
			player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.starmine.tumor_spent"), true);
			return false;
		}
		BlockPos best = null;
		for (BlockPos cell : BlockPos.betweenClosed(to.offset(-2, -2, -2), to.offset(2, 2, 2))) {
			if (cell.distSqr(from) > CreepSources.TUMOR_RADIUS * CreepSources.TUMOR_RADIUS || !level.getBlockState(cell).is(dev.starmine.block.StarMineBlocks.CREEP)) continue;
			if (best == null || cell.distSqr(to) < best.distSqr(to)) best = cell.immutable();
		}
		if (best == null || !CreepGrowth.placeTumor(level, best, 2, player.getUUID())) {
			player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.starmine.tumor_far"), true);
			return false;
		}
		tumor.spend();
		return true;
	}

	/** The tumor takes damage (a shot, an arrow, a blow); at none left it bursts. */
	public static void hurt(ServerLevel level, BlockPos pos, double damage) {
		CreepTumorBlockEntity tumor = at(level, pos);
		if (tumor == null) return;
		Vec3 centre = Vec3.atCenterOf(pos);
		level.sendParticles(ParticleTypes.ITEM_SLIME, centre.x, centre.y, centre.z, 6, 0.2, 0.2, 0.2, 0.05);
		if (tumor.damage(damage) > 0) return;
		level.sendParticles(ParticleTypes.SQUID_INK, centre.x, centre.y, centre.z, 20, 0.3, 0.3, 0.3, 0.05);
		level.playSound(null, pos, SoundEvents.SLIME_DEATH, SoundSource.HOSTILE, 1.2f, 0.7f);
		level.destroyBlock(pos, false);
	}

	/**
	 * A unit with a weapon and no target shoots a foe's tumor it sees within its reach (SC2 players do it by hand; here
	 * it does it itself). Once a second.
	 */
	public static void attackNear(StarUnit unit, ServerLevel level) {
		if (unit.getTarget() != null || unit.kind.structure() && unit.weapon().damage() <= 0 || unit.weapon().damage() <= 0
			|| !unit.weaponReady() || !unit.kind.hitsGround()) return;
		double reach = unit.weapon().range() + 1;
		BlockPos best = null;
		double bestDistance = reach * reach;
		for (BlockPos pos : CreepSources.tumors(level).all()) {
			double distance = pos.distToCenterSqr(unit.position());
			if (distance > bestDistance || !level.isLoaded(pos)) continue;
			CreepTumorBlockEntity tumor = at(level, pos);
			if (tumor == null || ownSide(tumor.owner(), unit) || unit.kind.race == UnitKind.Race.ZERG && tumor.owner() == null && unit.owner().isEmpty()) continue;
			if (!seen(level, pos, unit)) continue;
			best = pos;
			bestDistance = distance;
		}
		if (best == null) return;
		Vec3 at = Vec3.atCenterOf(best);
		unit.getLookControl().setLookAt(at.x, at.y, at.z);
		unit.fireAtPoint(at);
		unit.startCooldown(Math.max(4, unit.weapon().interval()));
		hurt(level, best, unit.weapon().damage() + unit.attackBonus());
	}
}
