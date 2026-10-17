package dev.starmine.world;

import dev.starmine.entity.StarUnit;
import dev.starmine.faction.Faction;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.DragonFireball;
import net.minecraft.world.entity.projectile.WitherSkull;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Amun, the fallen xel'naga (owner 17.10): the last foe, a great winged beast over his island in the Void.
 *
 * - He flies, circling his island, then over his foes (players and their units in the Void); whatever blocks
 *   stand in his way he breaks — the islands crumble where he passes.
 * - Weapons: void bolts (wither skulls, now and then one that breaks blocks) and dragon's breath from afar;
 *   a pulse that throws back and hurts all round him up close.
 * - Every {@link #RIFT_EVERY} ticks he opens a {@link VoidRift} near a foe, up to {@link #RIFTS} at once.
 * - While a {@link VoidShard} stands he cannot be hurt, and each heals him.
 * - Dead, his rifts close, and everyone in the Void has won: the game is complete (the end poem and credits).
 */
public class Amun extends Monster implements Enemy, VoidForces.Member {
	public static final float HEALTH = 1000;
	public static final int RIFT_EVERY = 900, RIFTS = 4, BOLT_EVERY = 50, PULSE_EVERY = 30;
	public static final double SIGHT = 96, PULSE_RANGE = 8;
	public static final float PULSE_DAMAGE = 12, HEAL_PER_SHARD = 1;
	private final ServerBossEvent bar = new ServerBossEvent(getDisplayName(), BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.NOTCHED_20);
	private @Nullable BlockPos home;
	private @Nullable LivingEntity foe;
	private int shards = -1;
	private double circle;

	public Amun(EntityType<? extends Amun> type, Level level) {
		super(type, level);
		setNoGravity(true);
		setPersistenceRequired();
		noPhysics = true;
		xpReward = 500;
		noCulling = true;
	}

	/** The nearest living Amun within `reach` of `at`. */
	public static @Nullable Amun find(ServerLevel level, Vec3 at, double reach) {
		return level.getEntitiesOfClass(Amun.class, new AABB(at, at).inflate(reach), Entity::isAlive).stream()
			.min(java.util.Comparator.comparingDouble(amun -> amun.distanceToSqr(at))).orElse(null);
	}

	/** Who Amun's side fights: players (not creative or spectating) and players' units. */
	static boolean foe(LivingEntity entity) {
		if (!entity.isAlive() || Faction.amun(entity)) return false;
		if (entity instanceof Player player) return !player.isCreative() && !player.isSpectator();
		return entity instanceof StarUnit unit && unit.owner().isPresent();
	}

	public void home(BlockPos at) {
		home = at.immutable();
	}

	/** Shards standing anywhere in the Void, loaded or not. */
	public int shards() {
		return level() instanceof ServerLevel level ? VoidWorld.State.get(level.getServer()).shards : Math.max(0, shards);
	}

	@Override
	public void aiStep() {
		super.aiStep();
		if (!(level() instanceof ServerLevel level)) {
			if (random.nextInt(2) == 0)
				level().addParticle(ParticleTypes.REVERSE_PORTAL, getRandomX(1), getRandomY(), getRandomZ(1), 0, -0.05, 0);
			return;
		}
		if (isNoAi()) return;
		if (home == null) home = blockPosition();
		if (tickCount % 20 == 0 || shards < 0) {
			shards = VoidWorld.State.get(level.getServer()).shards; // the shards standing anywhere, loaded or not
			int beaming = level.getEntities(VoidForces.SHARD, shard -> shard.isAlive() && shard.distanceToSqr(this) < VoidShard.REACH * VoidShard.REACH).size();
			if (shards > 0 && beaming > 0 && getHealth() < getMaxHealth()) heal(beaming * HEAL_PER_SHARD);
			foe = level.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(SIGHT, SIGHT / 2, SIGHT), Amun::foe).stream()
				.min(java.util.Comparator.comparingDouble(this::distanceToSqr)).orElse(null);
		}
		fly();
		if (tickCount % 2 == 0) breakThrough(level);
		if (foe != null && foe.isAlive()) {
			if (tickCount % BOLT_EVERY == 0) bolt(level, foe);
			if (tickCount % PULSE_EVERY == 0 && distanceTo(foe) < PULSE_RANGE + getBbWidth() / 2) pulse(level);
			if (tickCount % RIFT_EVERY == RIFT_EVERY / 3) rift(level, foe);
		}
		bar.setProgress(getHealth() / getMaxHealth());
		bar.setName(shards > 0 ? Component.translatable("boss.starmine.amun_shielded", getDisplayName(), shards) : getDisplayName());
		if (tickCount % 10 == 0)
			for (ServerPlayer player : level.players()) {
				boolean near = isAlive() && player.distanceToSqr(this) < 160 * 160;
				if (near && !bar.getPlayers().contains(player)) bar.addPlayer(player);
				else if (!near && bar.getPlayers().contains(player)) bar.removePlayer(player);
			}
	}

	/** Circling his island, or over his foe; turned the way he flies. */
	private void fly() {
		Vec3 goal;
		circle += 0.012;
		if (foe != null && foe.isAlive()) {
			double r = 14 + 4 * Math.sin(circle * 3);
			goal = foe.position().add(Math.cos(circle * 2.5) * r, 10 + 3 * Math.sin(circle * 5), Math.sin(circle * 2.5) * r);
		} else {
			BlockPos at = home != null ? home : blockPosition();
			goal = Vec3.atCenterOf(at).add(Math.cos(circle) * 34, 26 + 4 * Math.sin(circle * 3), Math.sin(circle) * 34);
		}
		Vec3 way = goal.subtract(position());
		double speed = foe != null ? 0.55 : 0.4;
		Vec3 want = way.lengthSqr() > speed * speed ? way.normalize().scale(speed) : way;
		Vec3 motion = getDeltaMovement().scale(0.9).add(want.scale(0.1));
		setDeltaMovement(motion);
		move(net.minecraft.world.entity.MoverType.SELF, motion);
		if (motion.horizontalDistanceSqr() > 0.001) {
			float yaw = (float) (Mth.atan2(motion.z, motion.x) * Mth.RAD_TO_DEG) - 90f;
			setYRot(Mth.approachDegrees(getYRot(), yaw, 6f));
			yBodyRot = getYRot();
			yHeadRot = getYRot();
		}
	}

	/** Blocks in his body are broken (not the unbreakable: the portals, bedrock). */
	private void breakThrough(ServerLevel level) {
		AABB box = getBoundingBox().inflate(0.5);
		boolean broke = false;
		for (BlockPos pos : BlockPos.betweenClosed(Mth.floor(box.minX), Mth.floor(box.minY), Mth.floor(box.minZ),
				Mth.floor(box.maxX), Mth.floor(box.maxY), Mth.floor(box.maxZ))) {
			BlockState state = level.getBlockState(pos);
			if (state.isAir() || state.getDestroySpeed(level, pos) < 0 || !state.getFluidState().isEmpty()) continue;
			level.destroyBlock(pos, false, this);
			broke = true;
		}
		if (broke && random.nextInt(4) == 0) playSound(SoundEvents.ENDER_DRAGON_GROWL, 2.0f, 0.6f);
	}

	/** A void bolt: a wither skull (every fourth one breaks blocks), or dragon's breath. */
	private void bolt(ServerLevel level, LivingEntity target) {
		Vec3 from = position().add(0, getBbHeight() * 0.5, 0).add(getLookAngle().scale(3));
		Vec3 way = target.position().add(0, target.getBbHeight() / 2, 0).subtract(from).normalize();
		if (random.nextInt(3) == 0) {
			DragonFireball breath = new DragonFireball(level, this, way);
			breath.setPos(from);
			level.addFreshEntity(breath);
		} else {
			WitherSkull skull = new WitherSkull(level, this, way);
			skull.setPos(from);
			skull.setDangerous(random.nextInt(4) == 0);
			level.addFreshEntity(skull);
		}
		level.playSound(null, blockPosition(), SoundEvents.WITHER_SHOOT, SoundSource.HOSTILE, 3.0f, 0.5f);
	}

	/** A pulse of void: everyone close is hurt and thrown back. */
	private void pulse(ServerLevel level) {
		level.sendParticles(ParticleTypes.SONIC_BOOM, getX(), getY() + getBbHeight() / 2, getZ(), 1, 0, 0, 0, 0);
		level.sendParticles(ParticleTypes.REVERSE_PORTAL, getX(), getY() + getBbHeight() / 2, getZ(), 120, 3, 2, 3, 0.4);
		playSound(SoundEvents.WARDEN_SONIC_BOOM, 3.0f, 0.6f);
		for (LivingEntity hit : level.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(PULSE_RANGE), Amun::foe)) {
			hit.hurt(damageSources().mobAttack(this), PULSE_DAMAGE);
			Vec3 away = hit.position().subtract(position()).multiply(1, 0, 1).normalize().scale(1.6);
			hit.push(away.x, 0.6, away.z);
			hit.hurtMarked = true;
		}
	}

	/** A rift near the foe, on the ground (unless there are enough). */
	private void rift(ServerLevel level, LivingEntity target) {
		if (level.getEntities(VoidForces.RIFT, Entity::isAlive).size() >= RIFTS) return;
		for (int attempt = 0; attempt < 8; attempt++) {
			double a = random.nextDouble() * Math.PI * 2, r = 10 + random.nextDouble() * 8;
			int x = Mth.floor(target.getX() + Math.cos(a) * r), z = Mth.floor(target.getZ() + Math.sin(a) * r);
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			if (y <= level.getMinBuildHeight() + 1 || Math.abs(y - target.getY()) > 12) continue;
			VoidRift rift = VoidForces.RIFT.create(level);
			if (rift == null) return;
			rift.moveTo(x + 0.5, y + 0.5, z + 0.5, random.nextFloat() * 360, 0);
			level.addFreshEntity(rift);
			level.playSound(null, rift.blockPosition(), SoundEvents.END_PORTAL_SPAWN, SoundSource.HOSTILE, 2.0f, 0.6f);
			for (ServerPlayer player : level.players())
				if (player.distanceToSqr(rift) < 96 * 96) player.displayClientMessage(Component.translatable("message.starmine.void_rift"), true);
			return;
		}
	}

	@Override
	public boolean hurt(DamageSource source, float amount) {
		if (source.getEntity() == this || source.getEntity() != null && Faction.amun(source.getEntity())) return false;
		if (source.is(DamageTypeTags.IS_FALL) || source.is(DamageTypeTags.IS_DROWNING)) return false;
		if (shards() > 0 && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) { // shielded by the shards
			if (level() instanceof ServerLevel level && tickCount % 5 == 0)
				level.sendParticles(ParticleTypes.ENCHANT, getX(), getY() + getBbHeight() / 2, getZ(), 30, 3, 2, 3, 0.5);
			if (source.getEntity() instanceof ServerPlayer player)
				player.displayClientMessage(Component.translatable("message.starmine.amun_shielded", shards()), true);
			return false;
		}
		return super.hurt(source, amount);
	}

	@Override
	protected void checkFallDamage(double y, boolean onGround, BlockState state, BlockPos pos) {
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	public boolean removeWhenFarAway(double distance) {
		return false;
	}

	@Override
	public boolean canChangeDimensions(Level from, Level to) {
		return false;
	}

	@Override
	public void die(DamageSource source) {
		super.die(source);
		if (!(level() instanceof ServerLevel level)) return;
		bar.removeAllPlayers();
		for (VoidRift rift : level.getEntities(VoidForces.RIFT, Entity::isAlive)) rift.kill(); // his rifts close with him
		level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, getX(), getY() + 2, getZ(), 6, 3, 2, 3, 0);
		level.playSound(null, blockPosition(), SoundEvents.ENDER_DRAGON_DEATH, SoundSource.HOSTILE, 8.0f, 0.7f);
		for (ItemStack loot : List.of(new ItemStack(Items.DRAGON_EGG), new ItemStack(Items.NETHER_STAR, 2), new ItemStack(Items.DIAMOND, 16)))
			level.addFreshEntity(new ItemEntity(level, getX(), getY(), getZ(), loot));
		VoidWorld.State state = VoidWorld.State.get(level.getServer());
		state.amunDead = true;
		state.setDirty();
		for (ServerPlayer player : level.getServer().getPlayerList().getPlayers())
			player.displayClientMessage(Component.translatable("message.starmine.amun_dead"), false);
		dev.starmine.ability.Delayed.after(level, 200, () -> { // the game is complete: the end poem and credits, then home
			if (!VoidWorld.is(level)) return;
			for (ServerPlayer player : List.copyOf(level.players())) {
				dev.starmine.guide.Goals.award(player, dev.starmine.guide.Goals.Goal.AMUN);
				player.wonGame = true;
				player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.WIN_GAME, player.seenCredits ? 0 : 1));
				player.seenCredits = true;
			}
		});
	}

	@Override
	public void startSeenByPlayer(ServerPlayer player) {
		super.startSeenByPlayer(player);
	}

	@Override
	public void stopSeenByPlayer(ServerPlayer player) {
		super.stopSeenByPlayer(player);
		bar.removePlayer(player);
	}

	@Override
	public void addAdditionalSaveData(CompoundTag tag) {
		super.addAdditionalSaveData(tag);
		if (home != null) tag.put("StarMineHome", NbtUtils.writeBlockPos(home));
	}

	@Override
	public void readAdditionalSaveData(CompoundTag tag) {
		super.readAdditionalSaveData(tag);
		home = NbtUtils.readBlockPos(tag, "StarMineHome").orElse(null);
		if (hasCustomName()) bar.setName(getDisplayName());
	}
}
