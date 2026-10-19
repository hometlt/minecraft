package dev.starmine.ability;

import dev.starmine.StarMine;
import dev.starmine.entity.Heroes;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

/**
 * The fights of the main bases' guardians (owner 19.10). They call no troops at their phases any more
 * ({@link Heroes}); their base's army comes to them when they are threatened ({@link #threatened}), and each fights in
 * its own way, what a phase brings staying on through the next ones:
 *
 * - Karax: from the start air drones (interceptors: quick, little health) that go for the player, and photon cannons he
 *   warps in for a while near them; at his second phase two khaydarin monoliths rise by him — they strike from afar
 *   with a beam that burns through up to {@link #BURN} blocks to reach the player (break a monolith's crystal and it is
 *   dead); at his third the Spear of Adun's purifier beam comes down from orbit, follows the player, cuts through
 *   blocks and burns hard.
 * - Swann: grenades from his launcher, and mines he lays toward the player that blow up ({@link #MINE_RADIUS} round,
 *   blocks too) when stepped near; at his second phase flaming Betty turrets that throw fire; at his third Odin (a
 *   great Thor) with his barrage, whose shells break blocks, and once a nuclear missile onto the player, its red dot
 *   seen seconds before.
 *
 * - Abathur (owner 19.10): toxic nests he lays toward the player — creep spreads from them, they are seen for
 *   {@link #NEST_SEEN} ticks, then only by a detector — that burst in poison when stepped near; at his second phase he
 *   evolves his units (a roach to a ravager, a hydralisk to a lurker, a zergling to a baneling) and tentacles burst
 *   from the ground under the player and hold them; at his third tentacles strike from the walls where their shadows
 *   show, and once he sheds a copy of himself. The creep tumors of his hall heal him: burn them and the healing stops.
 *
 * At the third phase each takes a great form that breaks the blocks in its way as it moves ({@link #FORM}): Abathur
 * becomes a brutalisk that leaps at the player, a symbiote flying with it; Swann climbs into Odin; Karax into the
 * archive guardian, a great protoss craft flying low with a burning beam. Hurting the form hurts the guardian.
 *
 * Numbers are for a player in full armour in plain Minecraft: every blow can be dodged or outlasted.
 */
public final class GuardianFights {
	public static final String DRONE = "starmine_boss_drone", CANNON = "starmine_boss_cannon", BETTY = "starmine_betty", ODIN = "starmine_odin",
		FORM = "starmine_form", SYMBIOTE = "starmine_symbiote", CLONE = "starmine_clone", NEST = "starmine_nest:", CLONED = "starmine_cloned";
	public static final int NEST_EVERY = 300, NESTS_MAX = 3, NEST_SEEN = 100, EVOLVE_EVERY = 400, ROOTS_EVERY = 160, ROOTS_WARN = 25,
		SLAMS_EVERY = 100, SLAM_WARN = 30, LEAP_EVERY = 120;
	public static final float NEST_DAMAGE = 6, ROOTS_DAMAGE = 5, SLAM_DAMAGE = 8, LEAP_DAMAGE = 7;
	public static final String MONOLITH = "starmine_monolith:", MINE = "starmine_mine:", MONOLITHS_RAISED = "starmine_monoliths", ODIN_CALLED = "starmine_odin_called",
		NUKED = "starmine_nuked";
	/** How far the guardians see the player (blocks); how far they call their army (blocks). */
	public static final double SIGHT = 40, CALL = 160;
	/** Blocks a monolith's beam burns through; a mine's blast (blocks and damage). */
	public static final int BURN = 3, MINE_RADIUS = 5;
	public static final int DRONES_EVERY = 240, DRONES_MAX = 6, CANNON_EVERY = 300, CANNON_TICKS = 30 * 20, CANNONS_MAX = 2,
		MONOLITH_EVERY = 60, MONOLITH_CHARGE = 20, BEAM_EVERY = 440, BEAM_WARN = 40, BEAM_TICKS = 160,
		GRENADE_EVERY = 70, MINE_EVERY = 200, MINES_MAX = 4, MINE_FUSE = 16, BETTY_EVERY = 500, BETTIES_MAX = 3, BETTY_FIRE = 30,
		BARRAGE_EVERY = 140, NUKE_WARN = 120;
	/** Damage (half hearts, before armour). */
	public static final float MONOLITH_DAMAGE = 6, BEAM_DAMAGE = 6, GRENADE_DAMAGE = 7, MINE_DAMAGE = 14, SHELL_DAMAGE = 8, NUKE_DAMAGE = 32;
	public static final double BEAM_SPEED = 0.18, BEAM_RADIUS = 2.2;
	private static final DustParticleOptions RED = new DustParticleOptions(new Vector3f(1f, 0.05f, 0.05f), 1.6f);
	private static final DustParticleOptions GOLD = new DustParticleOptions(new Vector3f(1f, 0.85f, 0.2f), 1.3f);

	/** A shot on its way: lands at `to` after `left` ticks (a grenade, a shell, the nuke). */
	private static final class Strike {
		final Vec3 from, to;
		final int flight;
		int left;
		final float damage, reach, breaks;
		final boolean nuke;

		Strike(Vec3 from, Vec3 to, int flight, float damage, float reach, float breaks, boolean nuke) {
			this.from = from;
			this.to = to;
			this.flight = this.left = flight;
			this.damage = damage;
			this.reach = reach;
			this.breaks = breaks;
			this.nuke = nuke;
		}
	}

	/** What a guardian's fight keeps between ticks (not saved: timers start again after a reload). */
	private static final class Fight {
		int drones = 60, cannon = 120, beam = 200, grenade = 40, mine = 100, betty = 60, barrage = 80, called;
		int nest = 80, evolve = EVOLVE_EVERY, roots = 60, slams = 60, leap = 60, formBeam = 40;
		final Map<BlockPos, Integer> nestsLaid = new HashMap<>();
		final List<Strike> warnings = new ArrayList<>();
		@Nullable Vec3 beamAt;
		int beamLeft;
		final Map<BlockPos, Integer> charging = new HashMap<>(), fuses = new HashMap<>();
		final List<Strike> strikes = new ArrayList<>();
	}

	private static final Map<UUID, Fight> FIGHTS = new HashMap<>();

	private GuardianFights() {
	}

	/** Every tick of a guardian under Amun's hold. */
	public static void tick(StarUnit hero, ServerLevel level) {
		Fight fight = FIGHTS.computeIfAbsent(hero.getUUID(), id -> new Fight());
		strikes(hero, level, fight);
		if (hero.kind == UnitKind.KARAX) monoliths(hero, level, fight);
		if (hero.kind == UnitKind.SWANN) mines(hero, level, fight);
		LivingEntity foe = foe(hero, level);
		int phase = Heroes.phase(hero);
		if (hero.kind == UnitKind.KARAX) karax(hero, level, fight, foe, phase);
		else if (hero.kind == UnitKind.SWANN) swann(hero, level, fight, foe, phase);
		else if (hero.kind == UnitKind.ABATHUR) abathur(hero, level, fight, foe, phase);
		if (phase >= 3) form(hero, level, fight, foe);
	}

	// ---- the great forms (owner 19.10) ----

	/** The great form a guardian takes at its third phase: its kind, its name, how much larger. */
	static UnitKind formKind(UnitKind guardian) {
		return switch (guardian) {
			case ABATHUR -> UnitKind.ULTRALISK; // the brutalisk (drawn as a great ultralisk until its own model is in)
			case SWANN -> UnitKind.THOR; // Odin
			default -> UnitKind.TEMPEST; // Karax's archive guardian
		};
	}

	static String formName(UnitKind guardian) {
		return switch (guardian) {
			case ABATHUR -> "entity.starmine.brutalisk";
			case SWANN -> "entity.starmine.odin";
			default -> "entity.starmine.archive_guardian";
		};
	}

	/** The guardian's form, or null (none yet, or gone). */
	public static @Nullable StarUnit formOf(StarUnit hero, ServerLevel level) {
		if (hero.getVehicle() instanceof StarUnit form && form.getTags().contains(FORM)) return form;
		List<StarUnit> forms = summons(hero, level, FORM);
		return forms.isEmpty() ? null : forms.get(0);
	}

	/** Its form: taken once (the guardian climbs in, hidden), kept near its hall, breaking what stands in its way. */
	private static void form(StarUnit hero, ServerLevel level, Fight fight, @Nullable LivingEntity foe) {
		StarUnit form = formOf(hero, level);
		if (form == null) {
			if (hero.getTags().contains("starmine_formed")) return; // taken and lost: not again
			hero.addTag("starmine_formed");
			UnitKind kind = formKind(hero.kind);
			form = hero.kind == UnitKind.SWANN ? odin(hero, level) : null;
			if (form == null) form = summon(hero, level, kind, hero.position().add(0, kind.flying ? 3 : 0, 0), ODIN);
			if (form == null) return;
			form.addTag(FORM);
			form.setCustomName(Component.translatable(formName(hero.kind)));
			var scale = form.getAttribute(Attributes.SCALE);
			if (scale != null && scale.getModifier(StarMine.id("form_size")) == null)
				scale.addPermanentModifier(new AttributeModifier(StarMine.id("form_size"), hero.kind == UnitKind.ABATHUR ? 0.8 : 0.4,
					AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
			hero.startRiding(form, true);
			hero.setInvisible(true);
			level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, form.getX(), form.getY() + 1, form.getZ(), 1, 0, 0, 0, 0);
			say(hero, level, "message.starmine.guardian_form");
			if (hero.kind == UnitKind.ABATHUR) { // the brutalisk's symbiote
				StarUnit symbiote = summon(hero, level, UnitKind.MUTALISK, form.position().add(0, 4, 0), SYMBIOTE);
				if (symbiote != null) symbiote.setCustomName(Component.translatable("entity.starmine.symbiote"));
			}
		}
		if (!hero.isPassenger()) {
			hero.startRiding(form, true);
			hero.setInvisible(true);
		}
		if (foe != null && form.getTarget() != foe) form.setTarget(foe);
		BlockPos home = hero.nest();
		if (home != null && form.distanceToSqr(Vec3.atBottomCenterOf(home)) > Math.pow(dev.starmine.world.HeroLiberation.GUARD_RADIUS, 2)) {
			form.getNavigation().moveTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 1.2);
			form.setTarget(null);
		}
		if (form.tickCount % 2 == 0) crush(form, level);
		for (StarUnit symbiote : summons(hero, level, SYMBIOTE)) { // it keeps with the brutalisk
			if (symbiote.distanceTo(form) > 10) symbiote.getMoveControl().setWantedPosition(form.getX(), form.getY() + 5, form.getZ(), 1.2);
			if (foe != null && symbiote.getTarget() == null) symbiote.setTarget(foe);
		}
		if (foe == null) return;
		if (hero.kind == UnitKind.ABATHUR && --fight.leap <= 0) { // the brutalisk leaps at the player
			double d = form.distanceTo(foe);
			if (d > 6 && d < 24 && form.onGround()) {
				fight.leap = LEAP_EVERY;
				Vec3 way = foe.position().subtract(form.position());
				form.setDeltaMovement(way.x * 0.11, 0.9 + d * 0.012, way.z * 0.11);
				form.hurtMarked = true;
				fight.strikes.add(new Strike(form.position(), foe.position(), 22, LEAP_DAMAGE, 3, 1.5f, false));
				level.playSound(null, form.blockPosition(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 2f, 0.6f);
			}
		}
		if (hero.kind == UnitKind.KARAX && --fight.formBeam <= 0) { // the archive guardian's burning beam
			fight.formBeam = 50;
			monolithBeam(hero, level, form.position().add(0, form.getBbHeight() * 0.4, 0), foe);
		}
	}

	/**
	 * A form breaks what is in its way: the blocks in its body (not under its feet — it walks; a flyer's all), armour
	 * only worn ({@link dev.starmine.block.BlockWear}).
	 */
	static void crush(StarUnit form, ServerLevel level) {
		AABB box = form.getBoundingBox().inflate(0.4, 0, 0.4);
		double from = form.flying() ? box.minY : form.getY() + 0.5;
		for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, from, box.minZ), BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
			BlockState state = level.getBlockState(pos);
			if (state.isAir() || state.getCollisionShape(level, pos).isEmpty()) continue;
			dev.starmine.block.BlockWear.strike(level, pos.immutable(), 40, form);
		}
	}

	// ---- Abathur ----

	private static void abathur(StarUnit hero, ServerLevel level, Fight fight, @Nullable LivingEntity foe, int phase) {
		nests(hero, level, fight);
		if (hero.tickCount % 40 == 0) heal(hero, level);
		if (foe == null) return;
		if (--fight.nest <= 0) {
			fight.nest = NEST_EVERY;
			if (hero.getTags().stream().filter(t -> t.startsWith(NEST)).count() < NESTS_MAX) layNest(hero, level, fight, foe);
		}
		if (phase >= 2 && --fight.evolve <= 0) {
			fight.evolve = EVOLVE_EVERY;
			evolve(hero, level);
		}
		if (phase >= 2 && --fight.roots <= 0) { // tentacles from the ground under the player: they hold and hurt
			fight.roots = ROOTS_EVERY;
			fight.warnings.add(new Strike(foe.position(), foe.position(), ROOTS_WARN, ROOTS_DAMAGE, 1.8f, 0, false));
		}
		if (phase >= 3 && --fight.slams <= 0) { // tentacles from the walls, three strikes where their shadows show
			fight.slams = SLAMS_EVERY;
			for (int i = 0; i < 3; i++)
				fight.warnings.add(new Strike(foe.position(), foe.position().add(level.random.nextGaussian() * 3, 0, level.random.nextGaussian() * 3),
					SLAM_WARN, SLAM_DAMAGE, 2.5f, 0, true));
		}
		if (phase >= 3 && !hero.getTags().contains(CLONED)) { // once, a copy of himself with half his health
			hero.addTag(CLONED);
			StarUnit clone = summon(hero, level, UnitKind.INFESTOR, hero.position().add(2, 0, 2), CLONE);
			if (clone != null) {
				clone.setCustomName(Component.translatable("entity.starmine.abathur_copy"));
				var health = clone.getAttribute(Attributes.MAX_HEALTH);
				if (health != null) health.setBaseValue(hero.getMaxHealth() / 2);
				clone.setHealth(clone.getMaxHealth());
				clone.setTarget(foe);
				level.sendParticles(ParticleTypes.ITEM_SLIME, clone.getX(), clone.getY() + 1, clone.getZ(), 40, 1, 1, 1, 0.1);
			}
		}
		warnings(hero, level, fight);
	}

	/** The tentacles' shadows: they strike when their time comes (roots: held; slams: thrown up). */
	private static void warnings(StarUnit hero, ServerLevel level, Fight fight) {
		for (Iterator<Strike> it = fight.warnings.iterator(); it.hasNext(); ) {
			Strike strike = it.next();
			if (strike.left % 3 == 0) level.sendParticles(ParticleTypes.SQUID_INK, strike.to.x, strike.to.y + 0.1, strike.to.z, 6, strike.reach / 2, 0.02, strike.reach / 2, 0);
			if (--strike.left > 0) continue;
			it.remove();
			level.sendParticles(ParticleTypes.ITEM_SLIME, strike.to.x, strike.to.y + 1, strike.to.z, 30, 0.5, 1.2, 0.5, 0.1);
			level.playSound(null, strike.to.x, strike.to.y, strike.to.z, SoundEvents.WARDEN_EMERGE, SoundSource.HOSTILE, 1f, 1.4f);
			for (LivingEntity other : level.getEntitiesOfClass(LivingEntity.class, new AABB(strike.to, strike.to).inflate(strike.reach, 2, strike.reach),
				e -> e != hero && dev.starmine.faction.Faction.hostile(hero, e) && Math.hypot(e.getX() - strike.to.x, e.getZ() - strike.to.z) <= strike.reach)) {
				other.hurt(hero.attackSource(), strike.damage);
				if (strike.nuke) other.push(0, 0.9, 0); // a slam from the walls throws up
				else Status.apply(other, Status.ROOTED, 50);
				other.hurtMarked = true;
			}
		}
	}

	/** A toxic nest toward the player: creep spreads from it; seen a while, then hidden from all but detectors. */
	private static void layNest(StarUnit hero, ServerLevel level, Fight fight, LivingEntity foe) {
		BlockPos cell = null;
		for (int attempt = 0; attempt < 8 && cell == null; attempt++) {
			Vec3 at = hero.position().lerp(foe.position(), 0.4 + level.random.nextDouble() * 0.4).add(level.random.nextGaussian() * 2, 0, level.random.nextGaussian() * 2);
			cell = floor(level, BlockPos.containing(at));
		}
		if (cell == null) {
			fight.nest = 20; // no room this time: soon again
			return;
		}
		hero.addTag(NEST + cell.asLong());
		fight.nestsLaid.put(cell.immutable(), hero.tickCount);
		for (int i = 0; i < 40; i++) dev.starmine.creep.CreepGrowth.grow(level, cell, 3, 2);
		level.playSound(null, cell, SoundEvents.SLIME_BLOCK_PLACE, SoundSource.HOSTILE, 1.5f, 0.6f);
	}

	/** Its nests: seen a while after they are laid (or near a foe's detector); one a foe steps near bursts in poison. */
	private static void nests(StarUnit hero, ServerLevel level, Fight fight) {
		if (hero.tickCount % 2 != 0) return;
		List<String> gone = new ArrayList<>();
		for (String tag : hero.getTags()) {
			if (!tag.startsWith(NEST)) continue;
			BlockPos nest = BlockPos.of(Long.parseLong(tag.substring(NEST.length())));
			if (!level.isLoaded(nest)) continue;
			int laid = fight.nestsLaid.getOrDefault(nest, -NEST_SEEN);
			boolean seen = hero.tickCount - laid < NEST_SEEN || !level.getEntitiesOfClass(StarUnit.class, new AABB(nest).inflate(11),
				u -> u.owner().isPresent() && u.isAlive() && detector(u.kind)).isEmpty();
			if (seen && hero.tickCount % 6 == 0)
				level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, nest.getX() + 0.5, nest.getY() + 0.4, nest.getZ() + 0.5, 4, 0.4, 0.2, 0.4, 0);
			boolean near = !level.getEntitiesOfClass(LivingEntity.class, new AABB(nest).inflate(1.5, 1.5, 1.5),
				e -> e != hero && dev.starmine.faction.Faction.hostile(hero, e)).isEmpty();
			if (!near) continue;
			gone.add(tag);
			fight.nestsLaid.remove(nest);
			level.sendParticles(ParticleTypes.ITEM_SLIME, nest.getX() + 0.5, nest.getY() + 0.5, nest.getZ() + 0.5, 50, 1.5, 0.6, 1.5, 0.1);
			level.playSound(null, nest, SoundEvents.SLIME_DEATH, SoundSource.HOSTILE, 2f, 0.5f);
			for (LivingEntity other : level.getEntitiesOfClass(LivingEntity.class, new AABB(nest).inflate(3),
				e -> e != hero && dev.starmine.faction.Faction.hostile(hero, e))) {
				other.hurt(hero.attackSource(), NEST_DAMAGE);
				other.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.POISON, 100, 0));
			}
		}
		gone.forEach(hero::removeTag);
	}

	static boolean detector(UnitKind kind) {
		return kind == UnitKind.OBSERVER || kind == UnitKind.OVERSEER || kind == UnitKind.RAVEN || kind == UnitKind.MISSILE_TURRET
			|| kind == UnitKind.SPORE_CRAWLER || kind == UnitKind.PHOTON_CANNON;
	}

	/** One of his units nearby evolves: a roach to a ravager, a hydralisk to a lurker, a zergling to a baneling. */
	public static @Nullable StarUnit evolve(StarUnit hero, ServerLevel level) {
		UUID base = hero.hiveId();
		for (StarUnit unit : level.getEntitiesOfClass(StarUnit.class, hero.getBoundingBox().inflate(32),
				u -> u.isAlive() && u.owner().isEmpty() && base != null && base.equals(u.hiveId()) && evolved(u.kind) != null)) {
			StarUnit into = StarMine.type(evolved(unit.kind)).create(level);
			if (into == null) continue;
			into.moveTo(unit.getX(), unit.getY(), unit.getZ(), unit.getYRot(), 0);
			dev.starmine.hive.Hive hive = dev.starmine.hive.HiveSavedData.get(level.getServer()).get(base);
			if (unit.nest() != null) into.makeNest(unit.nest());
			if (hive != null) into.joinHive(hive);
			into.setTarget(unit.getTarget());
			unit.discard();
			level.addFreshEntity(into);
			level.sendParticles(ParticleTypes.ITEM_SLIME, into.getX(), into.getY() + 1, into.getZ(), 30, 0.6, 0.8, 0.6, 0.1);
			level.playSound(null, into.blockPosition(), SoundEvents.SNIFFER_EGG_HATCH, SoundSource.HOSTILE, 1.5f, 0.7f);
			return into;
		}
		return null;
	}

	static @Nullable UnitKind evolved(UnitKind kind) {
		return switch (kind) {
			case ROACH -> UnitKind.RAVAGER;
			case HYDRALISK -> UnitKind.LURKER;
			case ZERGLING -> UnitKind.BANELING;
			default -> null;
		};
	}

	/** The creep tumors near him heal him (half a heart a second each, at most two hearts); burnt, they heal no more. */
	static int heal(StarUnit hero, ServerLevel level) {
		int tumors = 0;
		BlockPos at = hero.blockPosition();
		for (BlockPos pos : BlockPos.betweenClosed(at.offset(-20, -3, -20), at.offset(20, 6, 20)))
			if (level.getBlockState(pos).is(dev.starmine.block.StarMineBlocks.CREEP_TUMOR) && ++tumors >= 4) break;
		if (tumors > 0 && hero.getHealth() < hero.getMaxHealth()) {
			hero.heal(tumors * 2);
			level.sendParticles(ParticleTypes.HEART, hero.getX(), hero.getY() + hero.getBbHeight(), hero.getZ(), 1, 0.3, 0.2, 0.3, 0);
		}
		return tumors;
	}

	/** Whom it fights: its target if that is a player or a player's unit, else the nearest player in sight. */
	static @Nullable LivingEntity foe(StarUnit hero, ServerLevel level) {
		LivingEntity target = hero.getTarget();
		if (target != null && target.isAlive() && (target instanceof Player || target instanceof StarUnit unit && unit.owner().isPresent())
			&& dev.starmine.faction.Faction.hostile(hero, target) && target.distanceTo(hero) <= SIGHT * 1.5) return target;
		Player near = level.getNearestPlayer(hero.getX(), hero.getY(), hero.getZ(), SIGHT,
			player -> player instanceof Player p && !p.isCreative() && !p.isSpectator() && p.isAlive());
		return near;
	}

	// ---- Karax ----

	private static void karax(StarUnit hero, ServerLevel level, Fight fight, @Nullable LivingEntity foe, int phase) {
		if (foe == null) return;
		if (--fight.drones <= 0) {
			fight.drones = DRONES_EVERY;
			int out = summons(hero, level, DRONE).size();
			for (int i = 0; i < Math.min(3, DRONES_MAX - out); i++) {
				StarUnit drone = summon(hero, level, UnitKind.INTERCEPTOR, hero.position().add((i - 1) * 1.5, hero.getBbHeight() + 1.5, 0), DRONE);
				if (drone != null) {
					Status.apply(drone, Status.TEMPORARY, 60 * 20);
					drone.setTarget(foe);
				}
			}
			level.playSound(null, hero.blockPosition(), SoundEvents.BEACON_POWER_SELECT, SoundSource.HOSTILE, 1.5f, 1.6f);
		}
		if (--fight.cannon <= 0) {
			fight.cannon = CANNON_EVERY;
			if (summons(hero, level, CANNON).size() < CANNONS_MAX) {
				Vec3 at = aside(level, foe.position(), hero.position(), 7, 4);
				if (at != null) {
					StarUnit cannon = summon(hero, level, UnitKind.PHOTON_CANNON, at, CANNON);
					if (cannon != null) Status.apply(cannon, Status.TEMPORARY, CANNON_TICKS);
					level.sendParticles(ParticleTypes.END_ROD, at.x, at.y + 1, at.z, 40, 1, 1, 1, 0.05);
					level.playSound(null, at.x, at.y, at.z, SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 1.2f, 1.4f);
				}
			}
		}
		if (phase >= 2 && !hero.getTags().contains(MONOLITHS_RAISED)) {
			hero.addTag(MONOLITHS_RAISED);
			for (int side = -1; side <= 1; side += 2) {
				Vec3 dir = foe.position().subtract(hero.position()).multiply(1, 0, 1);
				dir = dir.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : dir.normalize();
				Vec3 at = hero.position().add(new Vec3(-dir.z, 0, dir.x).scale(side * 10));
				BlockPos foot = floor(level, BlockPos.containing(at));
				if (foot != null) raiseMonolith(hero, level, foot);
			}
			say(hero, level, "message.starmine.karax_monoliths");
		}
		if (phase >= 3) purifier(hero, level, fight, foe);
	}

	/** A khaydarin monolith: a dark pillar three high with its crystal on top (the part that shoots, and that breaks it). */
	static void raiseMonolith(StarUnit hero, ServerLevel level, BlockPos foot) {
		for (int up = 0; up < 3; up++) level.setBlockAndUpdate(foot.above(up), Blocks.DARK_PRISMARINE.defaultBlockState());
		BlockPos core = foot.above(3);
		level.setBlockAndUpdate(core, Blocks.AMETHYST_BLOCK.defaultBlockState());
		level.setBlockAndUpdate(core.above(), Blocks.AMETHYST_CLUSTER.defaultBlockState());
		hero.addTag(MONOLITH + core.asLong());
		level.sendParticles(ParticleTypes.END_ROD, core.getX() + 0.5, core.getY() + 0.5, core.getZ() + 0.5, 60, 0.5, 2, 0.5, 0.1);
		level.playSound(null, core, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.HOSTILE, 3f, 0.6f);
	}

	/** The monoliths' crystals: those still standing charge and fire at the player from afar. */
	private static void monoliths(StarUnit hero, ServerLevel level, Fight fight) {
		List<String> gone = new ArrayList<>();
		for (String tag : hero.getTags()) {
			if (!tag.startsWith(MONOLITH)) continue;
			BlockPos core = BlockPos.of(Long.parseLong(tag.substring(MONOLITH.length())));
			if (!level.isLoaded(core)) continue;
			if (!level.getBlockState(core).is(Blocks.AMETHYST_BLOCK)) {
				gone.add(tag);
				level.sendParticles(ParticleTypes.EXPLOSION, core.getX() + 0.5, core.getY() + 0.5, core.getZ() + 0.5, 3, 0.5, 0.5, 0.5, 0);
				continue;
			}
			LivingEntity foe = foe(hero, level);
			Vec3 from = Vec3.atCenterOf(core);
			Integer charge = fight.charging.get(core);
			if (charge == null) {
				if (foe != null && foe.position().distanceTo(from) <= SIGHT * 1.2 && hero.tickCount % MONOLITH_EVERY == Math.floorMod(core.hashCode(), MONOLITH_EVERY))
					fight.charging.put(core.immutable(), MONOLITH_CHARGE);
				continue;
			}
			level.sendParticles(GOLD, from.x, from.y, from.z, 3, 0.4, 0.4, 0.4, 0);
			if (charge > 1) {
				fight.charging.put(core, charge - 1);
				continue;
			}
			fight.charging.remove(core);
			if (foe != null) monolithBeam(hero, level, from, foe);
		}
		gone.forEach(hero::removeTag);
	}

	/**
	 * A monolith's beam at the player: it burns through up to {@link #BURN} blocks on its way (none that cannot be broken,
	 * nor obsidian); stopped by more, it does not reach them.
	 */
	public static boolean monolithBeam(StarUnit hero, ServerLevel level, Vec3 from, LivingEntity foe) {
		Vec3 to = foe.getEyePosition().add(0, -0.4, 0);
		double length = from.distanceTo(to);
		int burned = 0;
		BlockPos last = BlockPos.containing(from);
		boolean reached = true;
		for (double t = 0.8; t < length; t += 0.25) {
			Vec3 at = from.lerp(to, t / length);
			BlockPos pos = BlockPos.containing(at);
			if (pos.equals(last)) continue;
			last = pos;
			if ((int) (t * 4) % 2 == 0) level.sendParticles(ParticleTypes.END_ROD, at.x, at.y, at.z, 1, 0.02, 0.02, 0.02, 0);
			BlockState state = level.getBlockState(pos);
			if (state.isAir() || state.getCollisionShape(level, pos).isEmpty()) continue;
			if (burned >= BURN || !breakable(level, pos, state)) {
				reached = false;
				dev.starmine.block.BlockWear.hit(level, pos, MONOLITH_DAMAGE * 4, hero); // armour and obsidian: worn, not burned through
				level.sendParticles(ParticleTypes.LAVA, at.x, at.y, at.z, 4, 0.2, 0.2, 0.2, 0);
				break;
			}
			level.destroyBlock(pos, false);
			level.sendParticles(ParticleTypes.FLAME, at.x, at.y, at.z, 6, 0.3, 0.3, 0.3, 0.02);
			burned++;
		}
		level.playSound(null, from.x, from.y, from.z, SoundEvents.BEACON_DEACTIVATE, SoundSource.HOSTILE, 2f, 2f);
		if (!reached) return false;
		foe.hurt(hero.attackSource(), MONOLITH_DAMAGE);
		foe.igniteForSeconds(2);
		return true;
	}

	/** The Spear of Adun's purifier beam: from orbit onto the player, after a warning; it follows them, cuts and burns. */
	private static void purifier(StarUnit hero, ServerLevel level, Fight fight, LivingEntity foe) {
		if (fight.beamAt == null) {
			if (--fight.beam > 0) return;
			fight.beam = BEAM_EVERY;
			fight.beamAt = foe.position();
			fight.beamLeft = BEAM_WARN + BEAM_TICKS;
			say(hero, level, "message.starmine.karax_purifier");
			level.playSound(null, foe.blockPosition(), SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 3f, 0.5f);
		}
		Vec3 at = fight.beamAt;
		boolean warming = fight.beamLeft > BEAM_TICKS;
		if (!warming) { // it follows the player, slower than they run
			Vec3 to = new Vec3(foe.getX() - at.x, 0, foe.getZ() - at.z);
			if (to.length() > BEAM_SPEED) to = to.normalize().scale(BEAM_SPEED);
			at = new Vec3(at.x + to.x, foe.getY() + (at.y - foe.getY()) * 0.7, at.z + to.z);
			fight.beamAt = at;
		}
		if (hero.tickCount % 2 == 0) {
			if (warming) Abilities.ring(level, at.add(0, 0.2, 0), BEAM_RADIUS, ParticleTypes.ELECTRIC_SPARK);
			else for (int up = 0; up < 24; up += 2) level.sendParticles(ParticleTypes.END_ROD, at.x, at.y + up, at.z, 2, 0.5, 0.6, 0.5, 0.02);
		}
		if (!warming && hero.tickCount % 5 == 0) {
			level.sendParticles(ParticleTypes.FLASH, at.x, at.y + 0.5, at.z, 1, 0, 0, 0, 0);
			for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(at.x - 2, at.y - 1, at.z - 2), BlockPos.containing(at.x + 2, at.y + 3, at.z + 2))) {
				if (Math.pow(pos.getX() + 0.5 - at.x, 2) + Math.pow(pos.getZ() + 0.5 - at.z, 2) > 1.7 * 1.7) continue;
				dev.starmine.block.BlockWear.strike(level, pos, BEAM_DAMAGE * 3, hero);
			}
		}
		if (!warming && hero.tickCount % 10 == 0) {
			level.playSound(null, at.x, at.y, at.z, SoundEvents.FIRECHARGE_USE, SoundSource.HOSTILE, 1.5f, 0.5f);
			for (LivingEntity other : level.getEntitiesOfClass(LivingEntity.class, new AABB(at, at).inflate(BEAM_RADIUS, 4, BEAM_RADIUS),
				e -> dev.starmine.faction.Faction.hostile(hero, e) && Math.hypot(e.getX() - fight.beamAt.x, e.getZ() - fight.beamAt.z) <= BEAM_RADIUS)) {
				other.hurt(hero.attackSource(), BEAM_DAMAGE);
				other.igniteForSeconds(3);
			}
		}
		if (--fight.beamLeft <= 0) fight.beamAt = null;
	}

	// ---- Swann ----

	private static void swann(StarUnit hero, ServerLevel level, Fight fight, @Nullable LivingEntity foe, int phase) {
		StarUnit odin = odin(hero, level);
		if (foe == null) return;
		double d = foe.distanceTo(hero);
		if (--fight.grenade <= 0 && d >= 3 && d <= 28) { // the grenade launcher
			fight.grenade = GRENADE_EVERY;
			Vec3 from = hero.position().add(0, hero.getBbHeight() * 0.8, 0);
			fight.strikes.add(new Strike(from, foe.position(), 12 + (int) (d / 2), GRENADE_DAMAGE, 3, 0, false));
			level.playSound(null, hero.blockPosition(), SoundEvents.CROSSBOW_SHOOT, SoundSource.HOSTILE, 1.5f, 0.6f);
		}
		if (--fight.mine <= 0) {
			fight.mine = MINE_EVERY;
			if (hero.getTags().stream().filter(t -> t.startsWith(MINE)).count() < MINES_MAX) layMine(hero, level, foe);
		}
		if (phase >= 2 && --fight.betty <= 0) { // flaming Betty turrets, more as they fall
			fight.betty = BETTY_EVERY;
			int standing = summons(hero, level, BETTY).size();
			for (int i = standing; i < Math.min(BETTIES_MAX, standing + 2); i++) {
				Vec3 at = aside(level, hero.position(), foe.position(), 6, 3);
				if (at == null) continue;
				StarUnit betty = summon(hero, level, UnitKind.AUTO_TURRET, at, BETTY);
				if (betty != null) betty.setCustomName(Component.translatable("entity.starmine.flaming_betty"));
				level.sendParticles(ParticleTypes.FLAME, at.x, at.y + 1, at.z, 30, 0.6, 0.6, 0.6, 0.05);
			}
		}
		if (phase >= 2 && hero.tickCount % BETTY_FIRE == 0)
			for (StarUnit betty : summons(hero, level, BETTY)) {
				if (betty.distanceTo(foe) > 18 || !betty.hasLineOfSight(foe)) continue;
				Vec3 from = betty.position().add(0, betty.getBbHeight() + 0.3, 0);
				Vec3 dir = foe.getEyePosition().subtract(from).normalize();
				var ball = new net.minecraft.world.entity.projectile.SmallFireball(level, betty, dir);
				ball.setPos(from.x, from.y, from.z);
				level.addFreshEntity(ball);
				level.playSound(null, betty.blockPosition(), SoundEvents.BLAZE_SHOOT, SoundSource.HOSTILE, 1f, 1.2f);
			}
		if (false && phase >= 3 && !hero.getTags().contains(ODIN_CALLED)) { // Odin is Swann's form now (form())
			hero.addTag(ODIN_CALLED);
			Vec3 at = aside(level, hero.position(), foe.position(), 8, 4);
			odin = summon(hero, level, UnitKind.THOR, at != null ? at : hero.position(), ODIN);
			if (odin != null) {
				odin.setCustomName(Component.translatable("entity.starmine.odin"));
				var health = odin.getAttribute(Attributes.MAX_HEALTH);
				if (health != null) health.addPermanentModifier(new AttributeModifier(StarMine.id("odin_health"), 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
				var scale = odin.getAttribute(Attributes.SCALE);
				if (scale != null) scale.addPermanentModifier(new AttributeModifier(StarMine.id("odin_size"), 0.4, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
				odin.setHealth(odin.getMaxHealth());
				odin.setTarget(foe);
				level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, odin.getX(), odin.getY() + 1, odin.getZ(), 1, 0, 0, 0, 0);
			}
			say(hero, level, "message.starmine.swann_odin");
		}
		if (odin != null && odin.isAlive()) odinTick(hero, odin, level, fight, foe);
	}

	private static @Nullable StarUnit odin(StarUnit hero, ServerLevel level) {
		List<StarUnit> odins = summons(hero, level, ODIN);
		return odins.isEmpty() ? null : odins.get(0);
	}

	/** Odin's barrage (shells that break blocks) and, once, his nuclear missile onto the player. */
	private static void odinTick(StarUnit hero, StarUnit odin, ServerLevel level, Fight fight, LivingEntity foe) {
		double d = odin.distanceTo(foe);
		if (--fight.barrage <= 0 && d <= 32) {
			fight.barrage = BARRAGE_EVERY;
			Vec3 from = odin.position().add(0, odin.getBbHeight(), 0);
			for (int i = 0; i < 5; i++) {
				Vec3 to = foe.position().add(level.random.nextGaussian() * 2, 0, level.random.nextGaussian() * 2);
				fight.strikes.add(new Strike(from, to, 18 + i * 5, SHELL_DAMAGE, 3, 1.6f, false));
			}
			level.playSound(null, odin.blockPosition(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 2f, 1.6f);
		}
		if (!odin.getTags().contains(NUKED) && odin.tickCount > 200 && d <= 48) {
			odin.addTag(NUKED);
			fight.strikes.add(new Strike(foe.position().add(0, 60, 0), foe.position(), NUKE_WARN, NUKE_DAMAGE, 9, 4, true));
			say(hero, level, "message.starmine.nuke_incoming");
		}
	}

	/** A mine toward the player: on the floor somewhere between Swann and them. */
	public static @Nullable BlockPos layMine(StarUnit hero, ServerLevel level, LivingEntity foe) {
		for (int attempt = 0; attempt < 8; attempt++) {
			Vec3 at = hero.position().lerp(foe.position(), 0.35 + level.random.nextDouble() * 0.45)
				.add(level.random.nextGaussian() * 2, 0, level.random.nextGaussian() * 2);
			BlockPos foot = floor(level, BlockPos.containing(at));
			if (foot == null || !level.getBlockState(foot).isAir()) continue;
			level.setBlockAndUpdate(foot, Blocks.HEAVY_WEIGHTED_PRESSURE_PLATE.defaultBlockState());
			hero.addTag(MINE + foot.asLong());
			level.playSound(null, foot, SoundEvents.IRON_TRAPDOOR_CLOSE, SoundSource.HOSTILE, 1f, 1.5f);
			return foot;
		}
		return null;
	}

	/** Swann's mines: one a foe comes near clicks and blows up; one picked up is gone. */
	private static void mines(StarUnit hero, ServerLevel level, Fight fight) {
		if (hero.tickCount % 2 != 0) return;
		List<String> gone = new ArrayList<>();
		for (String tag : hero.getTags()) {
			if (!tag.startsWith(MINE)) continue;
			BlockPos mine = BlockPos.of(Long.parseLong(tag.substring(MINE.length())));
			if (!level.isLoaded(mine)) continue;
			if (!level.getBlockState(mine).is(Blocks.HEAVY_WEIGHTED_PRESSURE_PLATE)) {
				gone.add(tag);
				fight.fuses.remove(mine);
				continue;
			}
			Integer fuse = fight.fuses.get(mine);
			if (fuse == null) {
				if (hero.tickCount % 20 == 0) level.sendParticles(RED, mine.getX() + 0.5, mine.getY() + 0.2, mine.getZ() + 0.5, 1, 0, 0, 0, 0);
				boolean near = !level.getEntitiesOfClass(LivingEntity.class, new AABB(mine).inflate(1.3, 1.5, 1.3),
					e -> e != hero && dev.starmine.faction.Faction.hostile(hero, e)).isEmpty();
				if (near) {
					fight.fuses.put(mine.immutable(), MINE_FUSE);
					level.playSound(null, mine, SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.HOSTILE, 2f, 2f);
				}
				continue;
			}
			level.sendParticles(RED, mine.getX() + 0.5, mine.getY() + 0.3, mine.getZ() + 0.5, 3, 0.2, 0.1, 0.2, 0);
			if (fuse > 2) {
				fight.fuses.put(mine, fuse - 2);
				continue;
			}
			fight.fuses.remove(mine);
			gone.add(tag);
			level.removeBlock(mine, false);
			blast(hero, level, Vec3.atBottomCenterOf(mine), MINE_DAMAGE, MINE_RADIUS, MINE_RADIUS);
		}
		gone.forEach(hero::removeTag);
	}

	// ---- shared ----

	/** The shots on their way: an arc of smoke (a red dot and a column for the nuke), then their blast. */
	private static void strikes(StarUnit hero, ServerLevel level, Fight fight) {
		for (Iterator<Strike> it = fight.strikes.iterator(); it.hasNext(); ) {
			Strike strike = it.next();
			strike.left--;
			double t = 1 - strike.left / (double) strike.flight;
			if (strike.nuke) {
				if (strike.left % 4 == 0) {
					Abilities.ring(level, strike.to.add(0, 0.2, 0), strike.reach, RED);
					level.sendParticles(RED, strike.to.x, strike.to.y + 0.3, strike.to.z, 6, 0.1, 0.1, 0.1, 0);
				}
				if (strike.left % 20 == 0) level.playSound(null, strike.to.x, strike.to.y, strike.to.z, SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.HOSTILE, 3f, 0.5f);
				if (strike.left < 30) level.sendParticles(ParticleTypes.FLAME, strike.to.x, strike.to.y + strike.left * 2, strike.to.z, 4, 0.2, 0.5, 0.2, 0.01);
			} else if (t >= 0) {
				Vec3 at = strike.from.lerp(strike.to, t).add(0, Math.sin(t * Math.PI) * Math.max(3, strike.from.distanceTo(strike.to) / 3), 0);
				level.sendParticles(ParticleTypes.SMOKE, at.x, at.y, at.z, 2, 0.05, 0.05, 0.05, 0);
			}
			if (strike.left > 0) continue;
			it.remove();
			blast(hero, level, strike.to, strike.damage, strike.reach, strike.breaks);
		}
	}

	/**
	 * A blast at `at`: the guardian's foes within `reach` hurt (less farther off; an explosion, so armour and blast
	 * protection count), the blocks within `breaks` broken (the outer ones by chance), none that cannot be.
	 */
	static void blast(StarUnit hero, ServerLevel level, Vec3 at, float damage, double reach, double breaks) {
		level.sendParticles(reach >= 6 ? ParticleTypes.EXPLOSION_EMITTER : ParticleTypes.EXPLOSION, at.x, at.y + 0.5, at.z, reach >= 6 ? 3 : 4, reach / 4, 0.5, reach / 4, 0);
		level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, reach >= 6 ? 6f : 2f, reach >= 6 ? 0.5f : 1f);
		if (breaks > 0) {
			int r = (int) Math.ceil(breaks);
			for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(at).offset(-r, -r, -r), BlockPos.containing(at).offset(r, r, r))) {
				double d = Math.sqrt(pos.distToCenterSqr(at.x, at.y + 0.5, at.z));
				if (d > breaks || d > breaks * 0.7 && level.random.nextFloat() < 0.4) continue;
				dev.starmine.block.BlockWear.strike(level, pos, damage * 4, hero); // armour and obsidian only worn
			}
		}
		var source = level.damageSources().explosion(hero, hero);
		for (LivingEntity other : level.getEntitiesOfClass(LivingEntity.class, new AABB(at, at).inflate(reach),
			e -> e != hero && dev.starmine.faction.Faction.hostile(hero, e))) {
			double d = other.position().distanceTo(at);
			if (d > reach) continue;
			other.hurt(source, (float) (damage * (1 - 0.6 * d / reach)));
			Vec3 push = other.position().subtract(at).add(0, 0.5, 0).normalize().scale(0.6 * (1 - d / reach) + 0.2);
			other.push(push.x, push.y * 0.6, push.z);
		}
	}

	/** Whether a blow may break a block: not one that cannot be broken, nor obsidian, nor one with a block entity. */
	static boolean breakable(ServerLevel level, BlockPos pos, BlockState state) {
		float hardness = state.getDestroySpeed(level, pos);
		return hardness >= 0 && hardness < 50 && level.getBlockEntity(pos) == null;
	}

	/** The guardian's summons of a kind (by tag) round it. */
	public static List<StarUnit> summons(StarUnit hero, ServerLevel level, String tag) {
		return level.getEntitiesOfClass(StarUnit.class, hero.getBoundingBox().inflate(64),
			unit -> unit.isAlive() && unit.getTags().contains(tag) && hero.getUUID().equals(unit.summoner()));
	}

	private static @Nullable StarUnit summon(StarUnit hero, ServerLevel level, UnitKind kind, Vec3 at, String tag) {
		StarUnit unit = StarMine.type(kind).create(level);
		if (unit == null) return null;
		Vec3 spot = kind.structure() ? at : dev.starmine.entity.Spots.near(level, kind, at);
		unit.moveTo(spot.x, spot.y, spot.z, level.random.nextFloat() * 360, 0);
		ZergAbilities.summon(hero, unit, level);
		if (unit.summoner() == null) unit.summonedBy(hero);
		unit.addTag(tag);
		unit.setPersistenceRequired();
		level.addFreshEntity(unit);
		return unit;
	}

	/** A floor cell (air over a solid block) near `pos`, within 4 up or down. */
	static @Nullable BlockPos floor(ServerLevel level, BlockPos pos) {
		for (int dy = 0; dy <= 8; dy++) {
			BlockPos at = pos.above(dy % 2 == 0 ? dy / 2 : -(dy + 1) / 2);
			if (level.getBlockState(at).getCollisionShape(level, at).isEmpty() && level.getBlockState(at.above()).getCollisionShape(level, at.above()).isEmpty()
				&& level.getBlockState(at.below()).isFaceSturdy(level, at.below(), net.minecraft.core.Direction.UP)) return at;
		}
		return null;
	}

	/** A floor spot `distance` (± `spread`) from `near`, on the side away from `from`. */
	private static @Nullable Vec3 aside(ServerLevel level, Vec3 near, Vec3 from, double distance, double spread) {
		for (int attempt = 0; attempt < 10; attempt++) {
			double angle = Math.atan2(near.z - from.z, near.x - from.x) + Math.PI + (level.random.nextDouble() - 0.5) * Math.PI;
			double r = distance + (level.random.nextDouble() - 0.5) * spread;
			BlockPos foot = floor(level, BlockPos.containing(near.x + Math.cos(angle) * r, near.y, near.z + Math.sin(angle) * r));
			if (foot != null) return Vec3.atBottomCenterOf(foot);
		}
		return null;
	}

	private static void say(StarUnit hero, ServerLevel level, String key) {
		for (ServerPlayer player : level.players())
			if (player.distanceTo(hero) <= Heroes.BAR_RANGE) player.displayClientMessage(Component.translatable(key, hero.getDisplayName()), true);
	}

	// ---- the army, the end ----

	/**
	 * Its guardian threatened, a base's army comes (owner 19.10): its warriors within {@link #CALL} blocks — the
	 * levels' guards too — go for the attacker when near it, else march to the guardian. At most every 5 seconds.
	 */
	public static void threatened(StarUnit hero, ServerLevel level, LivingEntity attacker) {
		if (dev.starmine.world.HeroLiberation.freed(hero) || hero.hiveId() == null) return;
		Fight fight = FIGHTS.computeIfAbsent(hero.getUUID(), id -> new Fight());
		if (hero.tickCount < fight.called) return;
		fight.called = hero.tickCount + 100;
		UUID base = hero.hiveId();
		for (StarUnit unit : level.getEntitiesOfClass(StarUnit.class, hero.getBoundingBox().inflate(CALL, 48, CALL),
			unit -> unit != hero && unit.isAlive() && base.equals(unit.hiveId()) && unit.owner().isEmpty() && !unit.kind.structure()
				&& !unit.kind.worker() && unit.kind.attack != UnitKind.Attack.NONE && !unit.kind.boss())) {
			if (unit.distanceTo(attacker) < 20) unit.setTarget(attacker);
			else if (unit.getTarget() == null) unit.command(StarUnit.Order.ATTACK_MOVE, hero.position());
		}
	}

	/** The guardian is freed: what it raised and called goes (its monoliths, mines, turrets, Odin, drones). */
	public static void end(StarUnit hero, ServerLevel level) {
		FIGHTS.remove(hero.getUUID());
		for (String tag : List.copyOf(hero.getTags())) {
			if (tag.startsWith(MONOLITH)) {
				BlockPos core = BlockPos.of(Long.parseLong(tag.substring(MONOLITH.length())));
				for (int down = -1; down <= 3; down++) {
					BlockPos at = core.below(down);
					BlockState state = level.getBlockState(at);
					if (state.is(Blocks.AMETHYST_BLOCK) || state.is(Blocks.AMETHYST_CLUSTER) || state.is(Blocks.DARK_PRISMARINE)) level.removeBlock(at, false);
				}
			} else if (tag.startsWith(MINE)) {
				BlockPos mine = BlockPos.of(Long.parseLong(tag.substring(MINE.length())));
				if (level.getBlockState(mine).is(Blocks.HEAVY_WEIGHTED_PRESSURE_PLATE)) level.removeBlock(mine, false);
			} else continue;
			hero.removeTag(tag);
		}
		if (hero.isPassenger()) hero.stopRiding();
		hero.setInvisible(false);
		for (String tag : List.copyOf(hero.getTags())) if (tag.startsWith(NEST)) hero.removeTag(tag);
		for (String tag : List.of(DRONE, CANNON, BETTY, ODIN, FORM, SYMBIOTE, CLONE)) summons(hero, level, tag).forEach(unit -> unit.discard());
	}

	/** Forgets a guardian that is gone. */
	public static void gone(StarUnit hero) {
		FIGHTS.remove(hero.getUUID());
	}
}
