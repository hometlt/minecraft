package dev.starmine.race;

import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import static dev.starmine.entity.UnitKind.*;
import static dev.starmine.race.Times.seconds;

/**
 * Protoss (SC2 LotV): a probe warps a building in and is free at once (the building builds itself),
 * pylons give supply and power — a building other than a nexus, pylon or assimilator must stand within
 * {@link #POWER_RADIUS} blocks of a finished pylon of its base, and stops training without one; units
 * train in queues at the nexus, gateway, robotics facility and stargate; plasma shields absorb damage
 * first and come back ({@link StarUnit} shields). Weapons and armor 2-3 need a twilight council.
 */
public final class ProtossRace implements RaceRules {
	static final ProtossRace INSTANCE = new ProtossRace();
	/** SC2 pylon power radius 6.5 x 1.153 blocks per unit. */
	public static final double POWER_RADIUS = 7.5;

	private ProtossRace() {
	}

	@Override
	public Race race() {
		return Race.PROTOSS;
	}

	@Override
	public UnitKind main() {
		return NEXUS;
	}

	@Override
	public UnitKind worker() {
		return PROBE;
	}

	@Override
	public UnitKind gasBuilding() {
		return ASSIMILATOR;
	}

	@Override
	public UnitKind supply() {
		return PYLON;
	}

	@Override
	public List<UnitKind> aiBuildOrder() {
		return List.of(GATEWAY, FORGE, CYBERNETICS_CORE, PHOTON_CANNON, GATEWAY, TWILIGHT_COUNCIL, ROBOTICS_FACILITY, SHIELD_BATTERY,
			STARGATE, TEMPLAR_ARCHIVES, ROBOTICS_BAY, FLEET_BEACON, DARK_SHRINE);
	}

	@Override
	public List<UnitKind> buildings() {
		return List.of(NEXUS, PYLON, ASSIMILATOR, GATEWAY, FORGE, CYBERNETICS_CORE, PHOTON_CANNON, SHIELD_BATTERY, TWILIGHT_COUNCIL,
			ROBOTICS_FACILITY, STARGATE, TEMPLAR_ARCHIVES, DARK_SHRINE, ROBOTICS_BAY, FLEET_BEACON);
	}

	@Override
	public Economy economy(UnitKind kind) {
		return switch (kind) {
			case PROBE -> new Economy(50, 0, 1, seconds(12), 0, 1);
			case ZEALOT -> new Economy(100, 0, 2, seconds(27), 0, 2);
			case STALKER -> new Economy(125, 50, 2, seconds(30), 0, 2);
			case SENTRY -> new Economy(50, 100, 2, seconds(26), 0, 2);
			case ADEPT -> new Economy(100, 25, 2, seconds(30), 0, 2);
			case HIGH_TEMPLAR -> new Economy(50, 150, 2, seconds(39), 0, 2);
			case DARK_TEMPLAR -> new Economy(125, 125, 2, seconds(39), 0, 2);
			case ARCHON -> new Economy(0, 0, 4, seconds(9), 0, 4);
			case IMMORTAL -> new Economy(275, 100, 4, seconds(39), 0, 4);
			case COLOSSUS -> new Economy(300, 200, 6, seconds(54), 0, 8);
			case DISRUPTOR -> new Economy(150, 150, 3, seconds(36), 0, 4);
			case OBSERVER -> new Economy(25, 75, 1, seconds(21), 0, 0);
			case WARP_PRISM -> new Economy(250, 0, 2, seconds(36), 0, 0);
			case PHOENIX -> new Economy(150, 100, 2, seconds(25), 0, 0);
			case VOID_RAY -> new Economy(250, 150, 4, seconds(37), 0, 0);
			case ORACLE -> new Economy(150, 150, 3, seconds(37), 0, 0);
			case TEMPEST -> new Economy(250, 175, 5, seconds(43), 0, 0);
			case CARRIER -> new Economy(350, 250, 6, seconds(64), 0, 0);
			case MOTHERSHIP -> new Economy(400, 400, 8, seconds(89), 0, 0);
			case NEXUS -> new Economy(400, 0, 0, seconds(71), 15, 0);
			case PYLON -> new Economy(100, 0, 0, seconds(18), 8, 0);
			case ASSIMILATOR -> new Economy(75, 0, 0, seconds(21), 0, 0);
			case GATEWAY -> new Economy(150, 0, 0, seconds(46), 0, 0);
			case FORGE -> new Economy(150, 0, 0, seconds(32), 0, 0);
			case CYBERNETICS_CORE -> new Economy(150, 0, 0, seconds(36), 0, 0);
			case PHOTON_CANNON -> new Economy(150, 0, 0, seconds(29), 0, 0);
			case SHIELD_BATTERY -> new Economy(100, 0, 0, seconds(29), 0, 0);
			case TWILIGHT_COUNCIL -> new Economy(150, 100, 0, seconds(36), 0, 0);
			case ROBOTICS_FACILITY -> new Economy(150, 100, 0, seconds(46), 0, 0);
			case STARGATE -> new Economy(150, 150, 0, seconds(43), 0, 0);
			case TEMPLAR_ARCHIVES -> new Economy(150, 200, 0, seconds(36), 0, 0);
			case DARK_SHRINE -> new Economy(150, 150, 0, seconds(71), 0, 0);
			case ROBOTICS_BAY -> new Economy(150, 150, 0, seconds(46), 0, 0);
			case FLEET_BEACON -> new Economy(300, 200, 0, seconds(43), 0, 0);
			default -> new Economy(0, 0, 0, 0, 0, 1); // the interceptor
		};
	}

	@Override
	public @Nullable UnitKind requires(UnitKind kind) {
		return switch (kind) {
			case PROBE, PYLON, ASSIMILATOR, GATEWAY, FORGE -> NEXUS;
			case ZEALOT, CYBERNETICS_CORE -> GATEWAY;
			case PHOTON_CANNON -> FORGE;
			case STALKER, SENTRY, ADEPT, SHIELD_BATTERY, TWILIGHT_COUNCIL, ROBOTICS_FACILITY, STARGATE -> CYBERNETICS_CORE;
			case TEMPLAR_ARCHIVES, DARK_SHRINE -> TWILIGHT_COUNCIL;
			case HIGH_TEMPLAR -> TEMPLAR_ARCHIVES;
			case DARK_TEMPLAR -> DARK_SHRINE;
			case IMMORTAL, OBSERVER, WARP_PRISM, ROBOTICS_BAY -> ROBOTICS_FACILITY;
			case COLOSSUS, DISRUPTOR -> ROBOTICS_BAY;
			case PHOENIX, VOID_RAY, ORACLE, FLEET_BEACON -> STARGATE;
			case TEMPEST, CARRIER, MOTHERSHIP -> FLEET_BEACON;
			default -> null;
		};
	}

	@Override
	public @Nullable UnitKind producer(UnitKind kind) {
		return switch (kind) {
			case PROBE, MOTHERSHIP -> NEXUS;
			case ZEALOT, STALKER, SENTRY, ADEPT, HIGH_TEMPLAR, DARK_TEMPLAR -> GATEWAY;
			case IMMORTAL, COLOSSUS, DISRUPTOR, OBSERVER, WARP_PRISM -> ROBOTICS_FACILITY;
			case PHOENIX, VOID_RAY, ORACLE, TEMPEST, CARRIER -> STARGATE;
			default -> null;
		};
	}

	@Override
	public char hotkey(UnitKind kind) {
		return switch (kind) {
			case PROBE, SENTRY, ORACLE, PYLON -> 'E';
			case ZEALOT -> 'Z';
			case STALKER, STARGATE -> 'S';
			case ADEPT, WARP_PRISM, ASSIMILATOR -> 'A';
			case HIGH_TEMPLAR, TEMPEST, TEMPLAR_ARCHIVES -> 'T';
			case DARK_TEMPLAR, DISRUPTOR, DARK_SHRINE -> 'D';
			case IMMORTAL -> 'I';
			case COLOSSUS, CARRIER, PHOTON_CANNON, TWILIGHT_COUNCIL -> 'C';
			case OBSERVER -> 'O';
			case PHOENIX -> 'X';
			case VOID_RAY -> 'V';
			case MOTHERSHIP -> 'M';
			case NEXUS -> 'N';
			case GATEWAY -> 'G';
			case FORGE, FLEET_BEACON -> 'F';
			case CYBERNETICS_CORE -> 'Y';
			case SHIELD_BATTERY, ROBOTICS_BAY -> 'B';
			case ROBOTICS_FACILITY -> 'R';
			default -> ' ';
		};
	}

	@Override
	public boolean upgradeAllowed(Hive base, int level) {
		return level == 1 || base.count(TWILIGHT_COUNCIL) > 0;
	}

	/** Buildings needing power: all but the nexus, pylons and assimilators. */
	public static boolean needsPower(UnitKind kind) {
		return kind.race == Race.PROTOSS && kind.building() && kind != NEXUS && kind != PYLON && kind != ASSIMILATOR;
	}

	/**
	 * A finished pylon of the base (`baseId`, null: any) within power range of a point, or a warp prism of
	 * the base in phasing mode within its smaller field (SC2 radius 3.75).
	 */
	public static boolean poweredAt(Level level, double x, double y, double z, @Nullable UUID baseId) {
		AABB area = new AABB(x, y, z, x, y, z).inflate(POWER_RADIUS, 12, POWER_RADIUS);
		return !level.getEntitiesOfClass(StarUnit.class, area, mob -> mob.isAlive() && (baseId == null || baseId.equals(mob.hiveId()))
			&& (mob.kind == PYLON && !mob.underConstruction() && Math.hypot(mob.getX() - x, mob.getZ() - z) <= POWER_RADIUS
				|| mob.kind == WARP_PRISM && mob.mode() == dev.starmine.ability.Modes.PHASING && Math.hypot(mob.getX() - x, mob.getZ() - z) <= PRISM_RADIUS))
			.isEmpty();
	}

	/** SC2 warp prism phasing field radius 3.75 x 1.153 blocks per unit + 1. */
	public static final double PRISM_RADIUS = 3.75 * BLOCKS_PER_UNIT + 1;

	@Override
	public @Nullable String siteRefusal(Level level, UnitKind kind, BlockPos site, @Nullable UUID baseId) {
		if (!needsPower(kind) || poweredAt(level, site.getX() + 0.5, site.getY(), site.getZ() + 0.5, baseId)) return null;
		return "message.starmine.need_power";
	}

	@Override
	public boolean powered(StarUnit building) {
		return !needsPower(building.kind) || poweredAt(building.level(), building.getX(), building.getY(), building.getZ(), building.hiveId());
	}

	/** The probe warps the building in and is free at once: it builds itself. */
	@Override
	public @Nullable StarUnit placeBuilding(StarUnit worker, ServerLevel level, Hive base, UnitKind kind, BlockPos site) {
		StarUnit building = Placement.put(worker, level, base, kind, site);
		if (building != null) {
			level.sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD, building.getX(), building.getY() + 1, building.getZ(),
				30, kind.width / 3, 1, kind.width / 3, 0.02);
			worker.cancelBuild();
		}
		return building;
	}

	/** Burning with blue fire, light smoke. */
	@Override
	public List<net.minecraft.core.particles.ParticleOptions> damagedParticles() {
		return List.of(net.minecraft.core.particles.ParticleTypes.SOUL_FIRE_FLAME, net.minecraft.core.particles.ParticleTypes.SMOKE);
	}
}
