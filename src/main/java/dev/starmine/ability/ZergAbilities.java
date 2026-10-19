package dev.starmine.ability;

import dev.starmine.StarMine;
import dev.starmine.block.StarMineBlocks;
import dev.starmine.creep.CreepGrowth;
import dev.starmine.creep.CreepSources;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveLogic;
import dev.starmine.hive.HiveSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import static dev.starmine.entity.UnitKind.BLOCKS_PER_UNIT;
import static dev.starmine.entity.UnitKind.SCALE;

/**
 * Zerg abilities' effects (owner 14.10, docs/ABILITIES.md), with SC2 LotV numbers (damage x
 * {@link UnitKind#SCALE}, radii x {@link UnitKind#BLOCKS_PER_UNIT}). Burrowing is a mode ({@link Modes}:
 * cloaked for free, still — a roach crawls and heals, a lurker shoots only so); its detection is the
 * common one ({@link Cloak}). Here: the ravager's corrosive bile, the queen's creep tumor, inject larva
 * and transfusion, the overseer's changeling, the infestor's neural parasite and fungal growth, the
 * viper's abduct, blinding cloud, parasitic bomb and consume (the corruptor's caustic spray is a lasting
 * channel, {@link Channels}).
 */
public final class ZergAbilities {
	/** SC2 corrosive bile: 60 damage; radius 0.5, a block more so a hit can land in Minecraft. */
	public static final double BILE_DAMAGE = 60, BILE_RADIUS = 0.5 * BLOCKS_PER_UNIT + 1;
	/** SC2 fungal growth: 30 damage over 2.85 s, roots as long, radius 2.25. */
	public static final double FUNGAL_DAMAGE = 30, FUNGAL_RADIUS = 2.25 * BLOCKS_PER_UNIT;
	public static final int FUNGAL_TICKS = 57;
	/** SC2 transfusion: 75 at once and 50 over 7.14 s. */
	public static final double TRANSFUSION_NOW = 75, TRANSFUSION_LATER = 50;
	/** SC2 inject larva: 3 larvae after 29 s. */
	public static final int INJECT_TICKS = 29 * 20, INJECT_LARVAE = 3;
	/** SC2 changeling lives 150 s. */
	public static final int CHANGELING_TICKS = 150 * 20;
	/** SC2 neural parasite holds 11 s. */
	public static final int PARASITE_TICKS = 11 * 20;
	/** SC2 blinding cloud: radius 2, 5.71 s. */
	public static final double CLOUD_RADIUS = 2 * BLOCKS_PER_UNIT + 0.4;
	public static final int CLOUD_TICKS = 114;
	/** SC2 parasitic bomb: 120 damage over 7 s to air units within 3 of the target. */
	public static final double BOMB_DAMAGE = 120, BOMB_RADIUS = 3 * BLOCKS_PER_UNIT;
	/** SC2 consume: 200 of a building's life over 14 s for 50 energy. */
	public static final double CONSUME_LIFE = 200, CONSUME_ENERGY = 50;

	private ZergAbilities() {
	}

	/** Bile lands 2.5 s after the cast: everyone there is hurt (its own side too, SC2); force fields and light blocks (leaves, glass, plants) melt. */
	static void bile(StarUnit unit, ServerLevel level, @Nullable Vec3 point) {
		if (point == null) return;
		level.sendParticles(ParticleTypes.ITEM_SLIME, point.x, point.y + 0.5, point.z, 50, BILE_RADIUS / 2, 0.4, BILE_RADIUS / 2, 0.15);
		level.playSound(null, point.x, point.y, point.z, SoundEvents.SLIME_SQUISH, SoundSource.HOSTILE, 1.5f, 0.6f);
		for (LivingEntity other : Abilities.around(unit, point, BILE_RADIUS, false)) // SC2: anyone under it, its own side too
			other.hurt(unit.attackSource(), (float) (BILE_DAMAGE * SCALE));
		boolean griefing = level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING);
		BlockPos centre = BlockPos.containing(point);
		for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-1, -1, -1), centre.offset(1, 2, 1))) {
			BlockState state = level.getBlockState(pos);
			if (state.is(StarMineBlocks.FORCE_FIELD)) level.removeBlock(pos, false);
			else if (griefing && !state.isAir() && state.getFluidState().isEmpty() && !StarMineBlocks.isCreep(state)) {
				float hardness = state.getDestroySpeed(level, pos);
				if (hardness >= 0 && hardness <= 0.3f) level.destroyBlock(pos, false);
			}
		}
	}

	/** The queen plants a creep tumor on creep at the point (a creep cell next to it will do). */
	static void creepTumor(StarUnit unit, ServerLevel level, @Nullable Vec3 point) {
		if (point == null) return;
		BlockPos at = BlockPos.containing(point);
		for (BlockPos pos : BlockPos.betweenClosed(at.offset(-1, -1, -1), at.offset(1, 1, 1)))
			if (level.getBlockState(pos).is(StarMineBlocks.CREEP) && CreepGrowth.placeTumor(level, pos.immutable(), 1, unit.owner().orElse(null))) {
				unit.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
				return;
			}
		unit.setEnergy(unit.energy() + Ability.CREEP_TUMOR.energy); // no creep there: nothing planted, nothing spent
	}

	/** Whether a creep tumor can be planted near the point (creep, room among the tumors). */
	public static boolean tumorSpot(ServerLevel level, BlockPos at) {
		return level.getBlockState(at).is(StarMineBlocks.CREEP) && CreepSources.nearestTumor(level, at) >= CreepSources.TUMOR_SPACING;
	}

	/** The queen injects a hatchery: in 29 s it gives 3 larvae beyond its usual 3; on one already injected it queues (SC2 LotV: injects stack). */
	static void inject(StarUnit unit, ServerLevel level, @Nullable LivingEntity target) {
		if (!(target instanceof StarUnit hatchery)) return;
		if (Status.has(hatchery, Status.INJECTED)) hatchery.abilities.queuedInjects++;
		else Status.apply(hatchery, Status.INJECTED, INJECT_TICKS);
		level.sendParticles(ParticleTypes.ITEM_SLIME, hatchery.getX(), hatchery.getY() + hatchery.getBbHeight(), hatchery.getZ(), 30, 1, 0.5, 1, 0.1);
	}

	/** An inject is over: the larvae come out around the hatchery, and a queued one starts. */
	static void injectDone(StarUnit hatchery) {
		if (!(hatchery.level() instanceof ServerLevel level) || hatchery.hiveId() == null) return;
		if (hatchery.abilities.queuedInjects > 0) {
			hatchery.abilities.queuedInjects--;
			Status.apply(hatchery, Status.INJECTED, INJECT_TICKS);
		}
		Hive hive = HiveSavedData.get(level.getServer()).get(hatchery.hiveId());
		if (hive == null) return;
		for (int i = 0; i < INJECT_LARVAE; i++) {
			double angle = level.random.nextDouble() * Math.PI * 2, radius = hatchery.getBbWidth() / 2 + 1;
			double x = hatchery.getX() + Math.cos(angle) * radius, z = hatchery.getZ() + Math.sin(angle) * radius;
			BlockPos ground = HiveLogic.groundNear(level, (int) Math.floor(x), hatchery.getBlockY(), (int) Math.floor(z));
			HiveLogic.larva(level, hive, hatchery, ground != null ? Vec3.atBottomCenterOf(ground) : new Vec3(x, hatchery.getY(), z));
		}
		HiveSavedData.get(level.getServer()).setDirty();
	}

	/** The queen heals 75 at once and 50 more over 7 s. */
	static void transfusion(StarUnit unit, ServerLevel level, @Nullable LivingEntity target) {
		if (target == null) return;
		unit.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
		target.heal((float) (TRANSFUSION_NOW * SCALE));
		level.sendParticles(ParticleTypes.HEART, target.getX(), target.getY() + target.getBbHeight(), target.getZ(), 5, 0.4, 0.3, 0.4, 0.0);
		Delayed.repeat(level, 20, 7, n -> {
			if (target.isAlive()) target.heal((float) (TRANSFUSION_LATER / 7 * SCALE));
		});
	}

	/** The overseer sends out a changeling: nobody attacks it unprovoked; it lives 150 s, takes a look and spies ({@link Changeling}). */
	static void changeling(StarUnit unit, ServerLevel level) {
		StarUnit changeling = StarMine.type(UnitKind.CHANGELING).create(level);
		if (changeling == null) return;
		Vec3 at = new Vec3(unit.body().getX(), unit.groundBelow(unit.body().getX(), unit.body().getZ()), unit.body().getZ());
		changeling.moveTo(at.x, at.y, at.z, unit.getYRot(), 0);
		summon(unit, changeling, level);
		Status.apply(changeling, Status.TEMPORARY, CHANGELING_TICKS);
		level.addFreshEntity(changeling);
		Changeling.spawned(unit, level);
		level.sendParticles(ParticleTypes.SQUID_INK, at.x, at.y + 1, at.z, 10, 0.3, 0.3, 0.3, 0.02);
	}

	/** A summon joins its summoner's side and base without counting in it. */
	public static void summon(StarUnit summoner, StarUnit summon, ServerLevel level) {
		Hive hive = summoner.hiveId() == null ? null : HiveSavedData.get(level.getServer()).get(summoner.hiveId());
		if (hive != null) summon.joinAsSummon(hive, summoner.getUUID());
		else summon.summonedBy(summoner);
	}

	/** The infestor takes a unit or creature over for 11 s while it holds the channel. */
	static void neuralParasite(StarUnit unit, ServerLevel level, @Nullable LivingEntity target) {
		if (target != null) Channels.start(unit, Ability.NEURAL_PARASITE, target, PARASITE_TICKS);
	}

	/** Fungal growth: foes in the area are rooted for 2.85 s and take 30 over it. */
	static void fungal(StarUnit unit, ServerLevel level, @Nullable Vec3 point) {
		if (point == null) return;
		var caught = Abilities.around(unit, point, FUNGAL_RADIUS, true);
		for (LivingEntity other : caught) Status.apply(other, Status.ROOTED, FUNGAL_TICKS);
		Abilities.ring(level, point, FUNGAL_RADIUS, ParticleTypes.SPORE_BLOSSOM_AIR);
		Delayed.repeat(level, 20, 3, n -> {
			for (LivingEntity other : caught) if (other.isAlive()) other.hurt(unit.attackSource(), (float) (FUNGAL_DAMAGE / 3 * SCALE));
			level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, point.x, point.y + 0.5, point.z, 30, FUNGAL_RADIUS / 2, 0.3, FUNGAL_RADIUS / 2, 0);
		});
	}

	/** A cloud for 5.71 s: foes under it shoot only up close; players under it are blinded. */
	static void blindingCloud(StarUnit unit, ServerLevel level, @Nullable Vec3 point) {
		if (point == null) return;
		Delayed.repeat(level, 5, CLOUD_TICKS / 5, n -> {
			for (LivingEntity other : Abilities.around(unit, point, CLOUD_RADIUS, false)) // SC2: every ground unit under it, its own side's too
				if (!(other instanceof StarUnit star && (star.flying() || star.kind.structure()))) Status.apply(other, Status.BLINDED, 10);
			if (n % 2 == 0) level.sendParticles(ParticleTypes.LARGE_SMOKE, point.x, point.y + 1, point.z, 25, CLOUD_RADIUS / 2, 0.6, CLOUD_RADIUS / 2, 0.01);
		});
	}

	/** Abduct: the target is pulled to the viper (onto the ground under it when it walks). */
	static void abduct(StarUnit unit, ServerLevel level, @Nullable LivingEntity target) {
		if (target == null) return;
		var body = unit.body();
		Vec3 at = target instanceof StarUnit star && star.flying() || target.isFallFlying()
			? body.position().add(body.getLookAngle().scale(unit.getBbWidth() / 2 + target.getBbWidth() / 2 + 0.5))
			: new Vec3(body.getX(), unit.groundBelow(body.getX(), body.getZ()), body.getZ());
		Channels.beam(level, unit, target, ParticleTypes.PORTAL);
		target.teleportTo(at.x, at.y, at.z);
		target.setDeltaMovement(Vec3.ZERO);
		if (target instanceof StarUnit star) star.getNavigation().stop();
	}

	/** A parasitic bomb: the target and flying foes around it take 120 over 7 s. */
	static void parasiticBomb(StarUnit unit, ServerLevel level, @Nullable LivingEntity target) {
		if (target == null) return;
		Delayed.repeat(level, 20, 7, n -> {
			if (!target.isAlive()) return;
			for (LivingEntity other : Abilities.around(unit, target.position(), BOMB_RADIUS, true))
				if (other == target || (other instanceof StarUnit star ? star.flying() : !other.onGround()))
					other.hurt(unit.attackSource(), (float) (BOMB_DAMAGE / 7 * SCALE));
			level.sendParticles(ParticleTypes.SQUID_INK, target.getX(), target.getY() + 0.5, target.getZ(), 15, BOMB_RADIUS / 2, 0.5, BOMB_RADIUS / 2, 0.02);
		});
	}

	/** Consume: the viper drains 200 of its own building's life over 14 s and gains 50 energy. */
	static void consume(StarUnit unit, ServerLevel level, @Nullable LivingEntity target) {
		if (target == null) return;
		Delayed.repeat(level, 20, 14, n -> {
			if (!target.isAlive() || !unit.isAlive()) return;
			target.setHealth((float) Math.max(1, target.getHealth() - CONSUME_LIFE / 14 * SCALE));
			unit.setEnergy((float) Math.min(unit.maxEnergy(), unit.energy() + CONSUME_ENERGY / 14));
			Channels.beam(level, unit, target, ParticleTypes.DAMAGE_INDICATOR);
		});
	}
}
