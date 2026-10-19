package dev.starmine.ability;

import dev.starmine.StarMine;
import dev.starmine.entity.Fx;
import dev.starmine.entity.Heroes;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

/**
 * The bosses' powers by phase (owner 19.10: each phase brings stronger powers; all beatable in netherite). Every strike
 * that hurts much is shown first — a line or a ring of motes — so a player who watches can step out of it.
 *
 * Kerrigan: pools of liquid creep ({@link #LIQUID_CREEP}) lie on her arena — standing in one she heals; hurt she goes to
 *   them; the player breaks them (one blow each). New pools grow at each phase. Third phase: the blade storm, a ring of
 *   dashes round her foe, each cutting what it crosses ({@link #bladeStorm}).
 * Raynor: his energy fills ({@link #ENERGY_FULL}); full, he takes aim for two seconds (a red line shows it, fixed for its
 *   last half second) and fires a round that pierces everything for 30 blocks ({@link #pierce}). Second phase: the
 *   Hyperion's laser draws a line across the deck ({@link #lineStrike}). Third: needle rounds in a fan.
 * Artanis: second phase, walls of psionic force ({@link #PSI_BARRIER}) cut the arena for eight seconds; third, the Spear of
 *   Adun burns three lines across it from orbit.
 * The heroes of the other worlds: Tosh a lockdown (2), a nuke (3); Zeratul a void prison (2), a storm of blinks (3);
 * Horner a missile barrage, Stukov infested terrans, Selendis purifier lines (3); Zagara's brood grows by phase
 * ({@link dev.starmine.world.DimensionHeroes}).
 */
public final class BossPowers {
	public static final Block LIQUID_CREEP = Registry.register(BuiltInRegistries.BLOCK, StarMine.id("liquid_creep"), new Pool(BlockBehaviour.Properties.of()
		.strength(0.1f).noOcclusion().noLootTable().sound(SoundType.SLIME_BLOCK).lightLevel(state -> 5).pushReaction(PushReaction.DESTROY)));
	public static final Block PSI_BARRIER = Registry.register(BuiltInRegistries.BLOCK, StarMine.id("psi_barrier"), new Block(BlockBehaviour.Properties.of()
		.strength(-1.0f, 3600000.0f).noOcclusion().noLootTable().lightLevel(state -> 12).pushReaction(PushReaction.BLOCK).sound(SoundType.AMETHYST)));
	/** Kerrigan heals this share of her health a second in a pool; pools: how many, how far from her place. */
	public static final float POOL_HEAL = 0.02f;
	public static final int POOLS = 4, POOL_RING = 8, STORM_EVERY = 240, STORM_DASHES = 6;
	public static final int ENERGY_FULL = 100, AIM_TICKS = 40, PIERCE_REACH = 30, LASER_EVERY = 300, NEEDLES_EVERY = 80;
	public static final int BARRIER_EVERY = 400, BARRIER_TICKS = 160, SPEAR_EVERY = 360;
	public static final float PIERCE_DAMAGE = 20, LASER_DAMAGE = 14, NEEDLE_DAMAGE = 3, SPEAR_DAMAGE = 12, STORM_DAMAGE = 6;
	private static final DustParticleOptions RED = new DustParticleOptions(new Vector3f(1f, 0.1f, 0.1f), 1.2f);
	private static final DustParticleOptions GOLD = new DustParticleOptions(new Vector3f(1f, 0.85f, 0.3f), 1.4f);
	private static final DustParticleOptions CREEP = new DustParticleOptions(new Vector3f(0.6f, 0.2f, 0.7f), 1.2f);

	private static final class State {
		int energy, aim, poolsAt;
		@Nullable Vec3 aimAt;
	}

	private static final Map<UUID, State> STATES = new HashMap<>();

	private BossPowers() {
	}

	public static void load() {
	}

	/** Every tick of a hero under Amun's hold (not freed, alive). */
	public static void tick(StarUnit hero, ServerLevel level) {
		State state = STATES.computeIfAbsent(hero.getUUID(), id -> new State());
		LivingEntity foe = hero.getTarget();
		int phase = Heroes.phase(hero), t = hero.tickCount;
		switch (hero.kind) {
			case KERRIGAN -> kerrigan(hero, level, state, foe, phase, t);
			case RAYNOR -> raynor(hero, level, state, foe, phase, t);
			case ARTANIS -> {
				if (foe == null) return;
				if (phase >= 2 && t % (phase == 3 ? BARRIER_EVERY * 3 / 4 : BARRIER_EVERY) == 0) barriers(hero, level, foe);
				if (phase == 3 && t % SPEAR_EVERY == 0) spear(hero, level, foe.position(), 3);
			}
			case TOSH -> {
				if (foe == null) return;
				if (phase >= 2 && t % 200 == 0) lockdown(hero, level, foe);
				if (phase == 3 && t % 400 == 0) nuke(hero, level, foe.position(), 5, 18);
			}
			case ZERATUL -> {
				if (foe == null) return;
				if (phase >= 2 && t % 240 == 0) voidPrison(hero, level, foe);
				if (phase == 3 && t % 300 == 0) bladeStorm(hero, level, foe, 4);
			}
			case HORNER -> {
				if (foe != null && phase >= 2 && t % (phase == 3 ? 140 : 200) == 0)
					for (int i = 0; i < (phase == 3 ? 6 : 4); i++) {
						double a = level.random.nextDouble() * Math.PI * 2, d = level.random.nextDouble() * 5;
						nuke(hero, level, foe.position().add(Math.cos(a) * d, 0, Math.sin(a) * d), 2.5, 7);
					}
			}
			case STUKOV -> {
				if (foe != null && phase == 3 && t % 300 == 0) infest(hero, level, foe);
			}
			case SELENDIS -> {
				if (foe != null && phase == 3 && t % SPEAR_EVERY == 0) spear(hero, level, foe.position(), 2);
			}
			default -> {
			}
		}
	}

	public static void forget(StarUnit hero) {
		STATES.remove(hero.getUUID());
	}

	// ---- shared: strikes shown first ----

	/** Who a boss's strike hurts: its foes (players and their side) near a segment. */
	static List<LivingEntity> onLine(StarUnit hero, ServerLevel level, Vec3 from, Vec3 to, double width) {
		AABB box = new AABB(from, to).inflate(width + 1);
		List<LivingEntity> hit = new ArrayList<>();
		Vec3 line = to.subtract(from);
		double length = line.lengthSqr();
		for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, e -> e != hero && e.isAlive() && dev.starmine.faction.Faction.hostile(hero, e))) {
			Vec3 at = e.position().add(0, e.getBbHeight() / 2, 0);
			double k = length == 0 ? 0 : Math.max(0, Math.min(1, at.subtract(from).dot(line) / length));
			if (from.add(line.scale(k)).distanceTo(at) <= width + e.getBbWidth() / 2) hit.add(e);
		}
		return hit;
	}

	/** A line of motes from `from` to `to` (a warning). */
	static void warnLine(ServerLevel level, Vec3 from, Vec3 to, DustParticleOptions dust) {
		double length = from.distanceTo(to);
		for (double d = 0; d <= length; d += 0.7) {
			Vec3 at = from.lerp(to, length == 0 ? 0 : d / length);
			level.sendParticles(dust, at.x, at.y + 0.2, at.z, 1, 0.05, 0.05, 0.05, 0);
		}
	}

	/**
	 * A line struck after a warning: motes along it for `warn` ticks, then a beam (from the sky when `sky`) that hurts those
	 * on it. The Hyperion's laser, the Spear of Adun.
	 */
	public static void lineStrike(StarUnit hero, ServerLevel level, Vec3 from, Vec3 to, int warn, float damage, DustParticleOptions dust, int colour, boolean sky) {
		Delayed.repeat(level, 5, warn / 5, pulse -> warnLine(level, from, to, dust));
		Delayed.after(level, warn, () -> {
			if (!hero.isAlive()) return;
			if (sky) for (double k = 0; k <= 1.0; k += 0.25) {
				Vec3 at = from.lerp(to, k);
				Fx.beam(level, Fx.Style.BEAM, at.add(0, 30, 0), at, colour, 12, 1.2f);
			}
			Fx.beam(level, Fx.Style.BEAM, from.add(0, 0.6, 0), to.add(0, 0.6, 0), colour, 15, 0.8f);
			level.playSound(null, BlockPos.containing(from.lerp(to, 0.5)), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 1.5f, 1.4f);
			for (LivingEntity e : onLine(hero, level, from, to, 1.2)) {
				e.hurt(hero.attackSource(), damage);
				e.igniteForSeconds(3);
			}
		});
	}

	/** A line `length` long through `at`, across, the way it is chosen at random. */
	static Vec3[] across(ServerLevel level, Vec3 at, double length) {
		double a = level.random.nextDouble() * Math.PI;
		Vec3 half = new Vec3(Math.cos(a), 0, Math.sin(a)).scale(length / 2);
		return new Vec3[] {at.subtract(half), at.add(half)};
	}

	/** A blast at `at` after a warning ring of `radius`: a nuke (Tosh), a missile (Horner). Blocks are spared. */
	static void nuke(StarUnit hero, ServerLevel level, Vec3 at, double radius, float damage) {
		Delayed.repeat(level, 5, 12, pulse -> {
			for (int i = 0; i < 24; i++) {
				double a = Math.PI * 2 * i / 24;
				level.sendParticles(RED, at.x + Math.cos(a) * radius, at.y + 0.2, at.z + Math.sin(a) * radius, 1, 0, 0, 0, 0);
			}
		});
		Delayed.after(level, 60, () -> {
			if (!hero.isAlive()) return;
			level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y + 1, at.z, 1, 0, 0, 0, 0);
			level.playSound(null, BlockPos.containing(at), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 3.0f, 0.7f);
			for (LivingEntity e : Abilities.around(hero, at, radius, true)) e.hurt(hero.attackSource(), damage);
		});
	}

	// ---- Kerrigan ----

	static void kerrigan(StarUnit hero, ServerLevel level, State state, @Nullable LivingEntity foe, int phase, int t) {
		BlockPos home = hero.nest();
		if (home != null && foe != null && state.poolsAt < phase) { // the pools: at the fight's start and again at each phase
			pools(level, home);
			state.poolsAt = phase;
		}
		if (t % 20 == 0 && inPool(level, hero)) { // in a pool: she heals
			hero.heal(hero.getMaxHealth() * POOL_HEAL);
			level.sendParticles(CREEP, hero.getX(), hero.getY() + 0.5, hero.getZ(), 10, 0.5, 0.6, 0.5, 0);
		}
		if (t % 60 == 0 && home != null && hero.getHealth() < hero.getMaxHealth() * 0.6f) { // hurt: to the nearest pool
			BlockPos pool = nearestPool(level, home);
			if (pool != null && !inPool(level, hero)) hero.getNavigation().moveTo(pool.getX() + 0.5, pool.getY(), pool.getZ() + 0.5, 1.3);
		}
		if (foe != null && phase == 3 && t % STORM_EVERY == 0) bladeStorm(hero, level, foe, STORM_DASHES);
	}

	/** Pools of liquid creep round the arena's middle (on its floor, where there is room). */
	public static void pools(ServerLevel level, BlockPos home) {
		for (int i = 0; i < POOLS; i++) {
			double a = Math.PI * 2 * i / POOLS + level.random.nextDouble() * 0.6;
			BlockPos middle = home.offset((int) Math.round(Math.cos(a) * POOL_RING), 0, (int) Math.round(Math.sin(a) * POOL_RING));
			for (int dx = -1; dx <= 1; dx++)
				for (int dz = -1; dz <= 1; dz++) {
					BlockPos at = middle.offset(dx, 0, dz);
					if (level.getBlockState(at).isAir() && level.getBlockState(at.below()).isFaceSturdy(level, at.below(), Direction.UP))
						level.setBlock(at, LIQUID_CREEP.defaultBlockState(), Block.UPDATE_ALL);
				}
			level.sendParticles(CREEP, middle.getX() + 0.5, middle.getY() + 0.3, middle.getZ() + 0.5, 20, 1, 0.2, 1, 0);
		}
	}

	static boolean inPool(ServerLevel level, StarUnit hero) {
		BlockPos feet = hero.blockPosition();
		for (BlockPos at : BlockPos.betweenClosed(feet.offset(-1, -1, -1), feet.offset(1, 0, 1))) if (level.getBlockState(at).is(LIQUID_CREEP)) return true;
		return false;
	}

	static @Nullable BlockPos nearestPool(ServerLevel level, BlockPos home) {
		BlockPos best = null;
		for (BlockPos at : BlockPos.betweenClosed(home.offset(-12, -1, -12), home.offset(12, 1, 12)))
			if (level.getBlockState(at).is(LIQUID_CREEP) && (best == null || at.distSqr(home) < best.distSqr(home))) best = at.immutable();
		return best;
	}

	/** The blade storm: dashes round the foe, from point to point of a ring, cutting what each crosses. */
	public static void bladeStorm(StarUnit hero, ServerLevel level, LivingEntity foe, int dashes) {
		Vec3 centre = foe.position();
		double start = level.random.nextDouble() * Math.PI * 2, radius = 4;
		level.playSound(null, hero.blockPosition(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.HOSTILE, 2.0f, 0.6f);
		for (int i = 0; i < dashes; i++) {
			int step = i;
			Delayed.after(level, 6 + i * 6, () -> {
				if (!hero.isAlive()) return;
				double a = start + Math.PI * 2 * step / dashes * 2.5; // across the ring, a star of cuts
				Vec3 from = hero.position(), point = centre.add(Math.cos(a) * radius, 0, Math.sin(a) * radius);
				Vec3 to = Abilities.standable(hero, level, point);
				if (to == null) return;
				for (LivingEntity e : onLine(hero, level, from.add(0, 1, 0), to.add(0, 1, 0), 1.0)) e.hurt(hero.attackSource(), STORM_DAMAGE);
				warnLine(level, from, to, CREEP);
				level.sendParticles(ParticleTypes.SWEEP_ATTACK, to.x, to.y + 1, to.z, 3, 0.5, 0.3, 0.5, 0);
				Abilities.teleport(hero, to);
			});
		}
	}

	/** A pool of liquid creep: a thin film on the floor, gone at one blow. */
	static final class Pool extends Block {
		private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 2, 16);

		Pool(Properties properties) {
			super(properties);
		}

		@Override
		protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
			return SHAPE;
		}
	}

	// ---- Raynor ----

	static void raynor(StarUnit hero, ServerLevel level, State state, @Nullable LivingEntity foe, int phase, int t) {
		if (foe == null) {
			state.aim = 0;
			return;
		}
		if (state.aim > 0) { // taking aim: still, a red line to his foe (fixed for the last half second), then the shot
			hero.getNavigation().stop();
			hero.setDeltaMovement(0, hero.getDeltaMovement().y, 0);
			if (state.aim > 10) state.aimAt = foe.position().add(0, foe.getBbHeight() * 0.6, 0);
			Vec3 eye = hero.getEyePosition(), way = state.aimAt.subtract(eye).normalize();
			if (state.aim % 2 == 0) warnLine(level, eye, eye.add(way.scale(PIERCE_REACH)), RED);
			if (--state.aim == 0) pierce(hero, level, eye, way);
			return;
		}
		if (t % 20 == 0) state.energy = Math.min(ENERGY_FULL, state.energy + 2 + phase); // faster each phase
		if (state.energy >= ENERGY_FULL && hero.hasLineOfSight(foe)) {
			state.energy = 0;
			state.aim = AIM_TICKS;
			state.aimAt = foe.position();
			level.playSound(null, hero.blockPosition(), SoundEvents.CROSSBOW_LOADING_MIDDLE.value(), SoundSource.HOSTILE, 2.0f, 0.6f);
			return;
		}
		if (phase >= 2 && t % (phase == 3 ? LASER_EVERY * 2 / 3 : LASER_EVERY) == 0) { // the Hyperion's laser across the deck
			Vec3[] line = across(level, foe.position(), 30);
			lineStrike(hero, level, line[0], line[1], 30, LASER_DAMAGE, RED, 0xff3020, true);
		}
		if (phase == 3 && t % NEEDLES_EVERY == 0) needles(hero, level, foe);
	}

	/** The charged round: through everything for {@link #PIERCE_REACH} blocks; all on its way hurt. */
	public static void pierce(StarUnit hero, ServerLevel level, Vec3 from, Vec3 way) {
		Vec3 to = from.add(way.scale(PIERCE_REACH));
		Fx.beam(level, Fx.Style.BEAM, from, to, 0xffe080, 10, 0.4f);
		level.playSound(null, hero.blockPosition(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 2.0f, 1.8f);
		for (LivingEntity e : onLine(hero, level, from, to, 0.8)) e.hurt(hero.attackSource(), PIERCE_DAMAGE);
	}

	/** Needle rounds: five in a fan at the foe. */
	static void needles(StarUnit hero, ServerLevel level, LivingEntity foe) {
		Vec3 eye = hero.getEyePosition(), aim = foe.position().add(0, foe.getBbHeight() * 0.6, 0).subtract(eye).normalize();
		for (int i = -2; i <= 2; i++) {
			double turn = Math.toRadians(i * 9);
			Vec3 way = new Vec3(aim.x * Math.cos(turn) - aim.z * Math.sin(turn), aim.y, aim.x * Math.sin(turn) + aim.z * Math.cos(turn));
			Vec3 to = eye.add(way.scale(20));
			Fx.beam(level, Fx.Style.TRACER, eye, to, 0xc0ff60, 6, 0.08f);
			for (LivingEntity e : onLine(hero, level, eye, to, 0.5)) e.hurt(hero.attackSource(), NEEDLE_DAMAGE);
		}
		level.playSound(null, hero.blockPosition(), SoundEvents.ARROW_SHOOT, SoundSource.HOSTILE, 1.5f, 1.6f);
	}

	// ---- Artanis, Selendis ----

	/** Walls of psionic force: one across between him and his foe, one beside; gone after {@link #BARRIER_TICKS}. */
	static void barriers(StarUnit hero, ServerLevel level, LivingEntity foe) {
		Vec3 between = hero.position().lerp(foe.position(), 0.5), way = foe.position().subtract(hero.position()).multiply(1, 0, 1);
		if (way.lengthSqr() < 1e-4) way = new Vec3(1, 0, 0);
		way = way.normalize();
		Vec3 side = new Vec3(-way.z, 0, way.x);
		List<BlockPos> made = new ArrayList<>();
		for (Vec3 centre : new Vec3[] {between, between.add(side.scale(8)).add(way.scale(3))})
			for (int w = -3; w <= 3; w++)
				for (int up = 0; up < 3; up++) {
					BlockPos at = BlockPos.containing(centre.add(side.scale(w))).above(up);
					if (!level.getBlockState(at).isAir() || at.closerThan(foe.blockPosition(), 1.5) || at.closerThan(hero.blockPosition(), 1.5)) continue;
					level.setBlock(at, PSI_BARRIER.defaultBlockState(), Block.UPDATE_ALL);
					made.add(at);
				}
		level.playSound(null, BlockPos.containing(between), SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 1.5f, 1.6f);
		Delayed.after(level, BARRIER_TICKS, () -> {
			for (BlockPos at : made) if (level.getBlockState(at).is(PSI_BARRIER)) level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		});
	}

	/** The Spear of Adun: `lines` purifier lines from orbit across the place of the foe. */
	public static void spear(StarUnit hero, ServerLevel level, Vec3 at, int lines) {
		Vec3[] first = across(level, at, 26);
		Vec3 along = first[1].subtract(first[0]).normalize(), side = new Vec3(-along.z, 0, along.x);
		for (int i = 0; i < lines; i++) {
			Vec3 shift = side.scale((i - (lines - 1) / 2.0) * 5);
			lineStrike(hero, level, first[0].add(shift), first[1].add(shift), 40 + i * 10, SPEAR_DAMAGE, GOLD, 0xffe060, true);
		}
	}

	// ---- the other worlds' heroes ----

	/** Tosh's lockdown: his foe stunned two seconds. */
	static void lockdown(StarUnit hero, ServerLevel level, LivingEntity foe) {
		Fx.beam(level, Fx.Style.LASER, hero.getEyePosition(), foe.position().add(0, foe.getBbHeight() * 0.6, 0), 0x40a0ff, 8, 0.2f);
		Status.apply(foe, Status.STUNNED, 40);
		level.sendParticles(ParticleTypes.ELECTRIC_SPARK, foe.getX(), foe.getY() + 1, foe.getZ(), 30, 0.4, 0.8, 0.4, 0.1);
	}

	/** Zeratul's void prison: three seconds held. */
	static void voidPrison(StarUnit hero, ServerLevel level, LivingEntity foe) {
		Status.apply(foe, Status.STUNNED, 60);
		level.sendParticles(ParticleTypes.REVERSE_PORTAL, foe.getX(), foe.getY() + 1, foe.getZ(), 60, 0.5, 1, 0.5, 0.05);
		level.playSound(null, foe.blockPosition(), SoundEvents.ENDERMAN_STARE, SoundSource.HOSTILE, 1.0f, 1.4f);
	}

	/** Stukov's infestation: three infested terrans round his foe. */
	static void infest(StarUnit hero, ServerLevel level, LivingEntity foe) {
		for (int i = 0; i < 3; i++) {
			double a = Math.PI * 2 * i / 3;
			Vec3 at = foe.position().add(Math.cos(a) * 3, 0, Math.sin(a) * 3);
			StarUnit unit = dev.starmine.hive.HiveLogic.spawnNest(level, null, UnitKind.INFESTED_TERRAN, at, hero.blockPosition());
			if (unit != null) unit.setTarget(foe);
			level.sendParticles(ParticleTypes.ITEM_SLIME, at.x, at.y + 0.5, at.z, 15, 0.4, 0.4, 0.4, 0.05);
		}
	}
}
