package dev.starmine.ability;

import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveSavedData;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The overseer's changeling as the owner designed it (docs/HIVE_DESIGN.md 7b; owner 19.10: who it meets decides):
 * among another side's troops it takes their race's basic soldier's look and walks with them (SC2); by a player with no
 * army about them it takes that player's look ({@link StarUnit#disguise}: the player's UUID, drawn with their skin) and
 * keeps 8–16 blocks behind them; by villagers it looks like one ("villager") and stays in the village, strolling among
 * them. Its own side still sees a changeling. When those it followed are gone, the next it meets give it a new look.
 * What it sees it tells its hive ({@link Hive#scouted}): the hive raids there when it knows of nothing
 * closer. It dies from any damage, lives SC2's 150 s, never attacks; iron golems take it for a villager
 * while it looks like one. A wild overseer sends one out every 2–3 minutes, at most 2 alive
 * ({@link #due}).
 */
public final class Changeling {
	public static final String VILLAGER = "villager";
	/** Blocks it sees a villager or player from. */
	public static final double SIGHT = 16;
	/** How close it stays to a villager, and the band it keeps behind a player. */
	public static final double AMONG = 5, BEHIND_MIN = 8, BEHIND_MAX = 16;
	/** How long the hive remembers what it was told. */
	public static final int REMEMBER_TICKS = 5 * 60 * 20;
	/** A wild overseer's changelings: one every 2–3 minutes, at most this many alive. */
	public static final int MAX_ALIVE = 2, EVERY_TICKS = 120 * 20, EVERY_MORE_TICKS = 60 * 20;

	private Changeling() {
	}

	/** Each second: takes a look if it has none, keeps track of whom it copied, tells its hive. */
	static void tick(StarUnit unit, ServerLevel level) {
		if (unit.tickCount % 20 != 0) return;
		LivingEntity spied = spied(unit, level);
		if (spied == null) { // owner 19.10: whoever it meets now decides its look (it keeps it while they are near)
			spied = firstSeen(unit, level);
			if (spied != null) unit.setDisguise(look(spied));
		}
		unit.abilities.spied = spied == null ? null : spied.getUUID();
		if (spied == null) return;
		Hive base = Abilities.base(unit);
		if (base != null) {
			base.scouted = spied.blockPosition();
			base.scoutedUntil = level.getGameTime() + REMEMBER_TICKS;
			HiveSavedData.get(level.getServer()).setDirty();
		}
	}

	/** The one it follows: the same entity while it is near, else a villager like the one it copied, or that very player. */
	static @Nullable LivingEntity spied(StarUnit unit, ServerLevel level) {
		UUID id = unit.abilities.spied;
		if (id != null && level.getEntity(id) instanceof LivingEntity known && known.isAlive() && known.distanceTo(unit) < SIGHT * 2) return known;
		String look = unit.disguise();
		if (look.isEmpty()) return null;
		if (look.equals(VILLAGER)) return nearest(unit, level.getEntitiesOfClass(AbstractVillager.class, unit.getBoundingBox().inflate(SIGHT), Entity::isAlive));
		if (look.startsWith(KIND)) { // among the troops of the race it looks like
			UnitKind as = disguisedAs(unit);
			return as == null ? null : nearest(unit, level.getEntitiesOfClass(StarUnit.class, unit.getBoundingBox().inflate(SIGHT * 2),
				other -> other.isAlive() && other.kind.race == as.race && other.kind != UnitKind.CHANGELING && !other.kind.structure()
					&& !dev.starmine.faction.Faction.allied(other, unit)));
		}
		try {
			Player player = level.getPlayerByUUID(UUID.fromString(look));
			return player != null && player.isAlive() && player.distanceTo(unit) < SIGHT * 2 ? player : null;
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/**
	 * Whom it takes after (owner 19.10): troops of another side — a race's wild troops or a player's army — first (SC2: it
	 * takes the look of their race's basic soldier, a marine, a zealot, a zergling, and walks among them); else a player with
	 * no army about them (it looks like that player and follows them); else a villager (it looks like one and stays in the
	 * village).
	 */
	static @Nullable LivingEntity firstSeen(StarUnit unit, ServerLevel level) {
		StarUnit soldier = nearest(unit, level.getEntitiesOfClass(StarUnit.class, unit.getBoundingBox().inflate(SIGHT),
			other -> other.isAlive() && other != unit && other.kind != UnitKind.CHANGELING && !other.kind.structure() && !other.kind.boss()
				&& !dev.starmine.faction.Faction.allied(other, unit) && unit.hasLineOfSight(other)));
		if (soldier != null) return soldier;
		Player player = nearest(unit, level.getEntitiesOfClass(Player.class, unit.getBoundingBox().inflate(SIGHT),
			other -> other.isAlive() && !other.isSpectator() && unit.hasLineOfSight(other)));
		if (player != null) return player; // its army about it would have been seen first
		return nearest(unit, level.getEntitiesOfClass(AbstractVillager.class, unit.getBoundingBox().inflate(SIGHT),
			other -> other.isAlive() && unit.hasLineOfSight(other)));
	}

	/** The look it takes after `seen`: a race's soldier, that player, or a villager. */
	static String look(LivingEntity seen) {
		return seen instanceof Player ? seen.getUUID().toString() : seen instanceof StarUnit soldier ? KIND + basic(soldier.kind.race).name() : VILLAGER;
	}

	/** The look of another race's soldier: "kind:MARINE". */
	public static final String KIND = "kind:";

	/** A race's basic soldier, whose look a changeling takes among that race's troops. */
	public static UnitKind basic(UnitKind.Race race) {
		return switch (race) {
			case ZERG -> UnitKind.ZERGLING;
			case TERRAN -> UnitKind.MARINE;
			case PROTOSS -> UnitKind.ZEALOT;
		};
	}

	/** The kind it looks like (null: none, a villager or a player). */
	public static @Nullable UnitKind disguisedAs(StarUnit unit) {
		String look = unit.disguise();
		if (!look.startsWith(KIND)) return null;
		try {
			return UnitKind.valueOf(look.substring(KIND.length()));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static <T extends LivingEntity> @Nullable T nearest(StarUnit unit, List<T> list) {
		T best = null;
		for (T other : list) if (best == null || other.distanceToSqr(unit) < best.distanceToSqr(unit)) best = other;
		return best;
	}

	/** Whether a wild overseer sends a changeling out now. */
	static boolean due(StarUnit overseer, ServerLevel level) {
		if (level.getGameTime() < overseer.abilities.nextChangeling) return false;
		long alive = level.getEntitiesOfClass(StarUnit.class, overseer.getBoundingBox().inflate(128),
			other -> other.kind == UnitKind.CHANGELING && other.isAlive() && overseer.getUUID().equals(other.summoner())).size();
		return alive < MAX_ALIVE;
	}

	/** After a changeling is out: the next one in 2–3 minutes. */
	static void spawned(StarUnit overseer, ServerLevel level) {
		overseer.abilities.nextChangeling = level.getGameTime() + EVERY_TICKS + level.random.nextInt(EVERY_MORE_TICKS);
	}

	/** It walks with the villagers or after the player it copied; otherwise it wanders as zerg do. */
	public static final class Spy extends Goal {
		private final StarUnit unit;

		public Spy(StarUnit unit) {
			this.unit = unit;
			this.setFlags(EnumSet.of(Goal.Flag.MOVE));
		}

		@Override
		public boolean canUse() {
			return unit.abilities.spied != null && unit.order() == StarUnit.Order.NONE;
		}

		@Override
		public boolean requiresUpdateEveryTick() {
			return true;
		}

		@Override
		public void tick() {
			if (!(unit.level() instanceof ServerLevel level) || unit.tickCount % 10 != 0) return;
			LivingEntity spied = spied(unit, level);
			if (spied == null) return;
			double distance = unit.distanceTo(spied);
			if (spied instanceof Player) {
				if (distance > BEHIND_MAX) unit.getNavigation().moveTo(spied, 1.0);
				else if (distance < BEHIND_MIN) { // back off to the middle of its band
					Vec3 away = unit.position().subtract(spied.position()).multiply(1, 0, 1).normalize().scale((BEHIND_MIN + BEHIND_MAX) / 2);
					Vec3 to = spied.position().add(away);
					unit.getNavigation().moveTo(to.x, to.y, to.z, 1.0);
				} else unit.getNavigation().stop();
			} else if (distance > AMONG) unit.getNavigation().moveTo(spied, 0.8);
			else if (unit.getRandom().nextInt(8) == 0) { // strolls about among them
				Vec3 to = spied.position().add(unit.getRandom().nextGaussian() * 3, 0, unit.getRandom().nextGaussian() * 3);
				unit.getNavigation().moveTo(to.x, to.y, to.z, 0.6);
			}
		}
	}
}
