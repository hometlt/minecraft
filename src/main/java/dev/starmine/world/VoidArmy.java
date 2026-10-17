package dev.starmine.world;

import dev.starmine.StarMine;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveLogic;
import dev.starmine.hive.HiveSavedData;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * The player's army for the Void (owner 17.10): the freed heroes' strike force — transports full of workers and
 * ground troops of all three races, and their air fleets. It is the player's, of the player's base in the Void
 * (made for it, with minerals and vespene to start: there one commands, builds and trains as at home). It sets off
 * through the artifact's portal in the scene of the artifact made whole ({@link #assembled}); a player who comes
 * into the Void before that finds it waiting by the first island ({@link #arrive}). It is sent once per player.
 */
public final class VoidArmy {
	public static final String VOID_DIMENSION = "starmine:void";
	public static final int START_MINERALS = 600, START_VESPENE = 300;
	/** Per race: transports, each with its cargo, and flyers. */
	private record Wing(UnitKind transport, List<UnitKind> cargo) {
	}

	private static final List<Wing> TRANSPORTS = List.of(
		new Wing(UnitKind.OVERLORD, List.of(UnitKind.ZERGLING, UnitKind.ZERGLING, UnitKind.ZERGLING, UnitKind.ZERGLING, UnitKind.ROACH, UnitKind.DRONE)),
		new Wing(UnitKind.OVERLORD, List.of(UnitKind.DRONE, UnitKind.DRONE, UnitKind.HYDRALISK, UnitKind.QUEEN)),
		new Wing(UnitKind.MEDIVAC, List.of(UnitKind.MARINE, UnitKind.MARINE, UnitKind.MARINE, UnitKind.MARINE, UnitKind.MARAUDER)),
		new Wing(UnitKind.MEDIVAC, List.of(UnitKind.SCV, UnitKind.SCV, UnitKind.SCV, UnitKind.MARINE, UnitKind.MARINE)),
		new Wing(UnitKind.WARP_PRISM, List.of(UnitKind.ZEALOT, UnitKind.ZEALOT, UnitKind.STALKER, UnitKind.PROBE, UnitKind.PROBE)));
	private static final List<UnitKind> FLYERS = List.of(UnitKind.MUTALISK, UnitKind.MUTALISK, UnitKind.MUTALISK, UnitKind.CORRUPTOR,
		UnitKind.VIKING, UnitKind.VIKING, UnitKind.BANSHEE, UnitKind.PHOENIX, UnitKind.PHOENIX, UnitKind.VOID_RAY);

	private VoidArmy() {
	}

	/** The player's base in the Void, made the first time (it stands by the portal back). */
	public static Hive base(ServerLevel any, UUID player) {
		HiveSavedData data = HiveSavedData.get(any.getServer());
		Hive base = data.baseOf(player, VOID_DIMENSION);
		if (base != null) return base;
		Hive home = data.baseOrHome(player, "minecraft:overworld");
		base = new Hive(UUID.randomUUID(), VOID_DIMENSION, VoidWorld.RETURN.offset(0, 1, 10));
		base.owner = player;
		base.race = home != null && home.race != null ? home.race : UnitKind.Race.TERRAN;
		base.minerals = START_MINERALS;
		base.vespene = START_VESPENE;
		data.add(base);
		return base;
	}

	/** Spawns the army round `centre` (the flyers and transports `lift` over the ground), the player's; returns its transports and flyers. */
	public static List<StarUnit> spawn(ServerLevel level, Vec3 centre, double lift, UUID player) {
		Hive base = base(level, player);
		List<StarUnit> roots = new ArrayList<>();
		int i = 0, total = TRANSPORTS.size() + FLYERS.size();
		for (Wing wing : TRANSPORTS) {
			StarUnit transport = unit(level, base, wing.transport(), ring(level, centre, i++, total, lift));
			if (transport == null) continue;
			roots.add(transport);
			for (UnitKind kind : wing.cargo()) {
				StarUnit rider = unit(level, base, kind, transport.position());
				if (rider != null && !rider.startRiding(transport, true)) rider.setPos(transport.getX(), transport.getY() - lift, transport.getZ());
			}
		}
		for (UnitKind kind : FLYERS) {
			StarUnit flyer = unit(level, base, kind, ring(level, centre, i++, total, lift));
			if (flyer != null) roots.add(flyer);
		}
		HiveSavedData.get(level.getServer()).setDirty();
		return roots;
	}

	private static Vec3 ring(ServerLevel level, Vec3 centre, int i, int total, double lift) {
		double a = Math.PI * 2 * i / total, r = 9 + (i % 2) * 3;
		int x = (int) Math.floor(centre.x + Math.cos(a) * r), z = (int) Math.floor(centre.z + Math.sin(a) * r);
		int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		if (ground <= level.getMinBuildHeight() + 1) ground = (int) centre.y;
		return new Vec3(x + 0.5, Math.max(ground, centre.y) + lift, z + 0.5);
	}

	private static StarUnit unit(ServerLevel level, Hive base, UnitKind kind, Vec3 at) {
		StarUnit unit = HiveLogic.spawn(level, base, kind, at, null);
		if (unit == null) return null;
		unit.setOwner(base.owner);
		level.sendParticles(ParticleTypes.REVERSE_PORTAL, at.x, at.y + 1, at.z, 12, 0.6, 0.6, 0.6, 0.1);
		return unit;
	}

	/** A player comes into the Void: their army waits by the first island, the first time (unless it is on its way). */
	public static void arrive(ServerLevel voidLevel, ServerPlayer player) {
		VoidWorld.State state = VoidWorld.State.get(voidLevel.getServer());
		if (!state.armies.add(player.getUUID())) return;
		state.setDirty();
		spawn(voidLevel, Vec3.atBottomCenterOf(VoidWorld.RETURN).add(0, 0, 14), 3, player.getUUID());
	}

	// ---- the scene of the artifact made whole ----

	public static final String CLONE = "starmine_scene";

	/**
	 * The artifact made whole in the overworld by `player` (owner 17.10): the three freed heroes come to it, the
	 * player's army gathers round it and goes up its beam into the portal, the heroes speak (subtitles), then go back
	 * to their ships. Played once per player: the army is sent once.
	 */
	public static void assembled(ServerLevel level, BlockPos pedestal, ServerPlayer player) {
		VoidWorld.State state = VoidWorld.State.get(level.getServer());
		if (state.armies.contains(player.getUUID())) return;
		state.armies.add(player.getUUID());
		state.setDirty();
		Vec3 centre = Vec3.atBottomCenterOf(pedestal);
		List<StarUnit> heroes = new ArrayList<>();
		List<StarUnit> army = new ArrayList<>();
		List<ServerPlayer> audience = level.players().stream().filter(p -> p.distanceToSqr(centre) < 64 * 64).toList();
		String a = "cutscene.starmine.artifact.";
		Cutscene.play(level, audience.isEmpty() ? List.of(player) : audience, List.of(
			Cutscene.Beat.act(30, () -> heroes.addAll(heroes(level, centre, player.getUUID()))),
			Cutscene.Beat.say("artanis", a + "1", 80),
			Cutscene.Beat.say("kerrigan", a + "2", 80),
			Cutscene.Beat.say("raynor", a + "3", 70),
			Cutscene.Beat.act(20, () -> army.addAll(spawn(level, centre, 4, player.getUUID()))),
			Cutscene.Beat.say("artanis", a + "4", 70),
			Cutscene.Beat.act(10, () -> {
				Vec3 portal = Vec3.atCenterOf(Artifact.portalCentre(level, pedestal));
				for (StarUnit unit : army) if (unit.isAlive()) unit.command(StarUnit.Order.MOVE, portal);
			}),
			Cutscene.Beat.say("raynor", a + "5", 90),
			Cutscene.Beat.say("kerrigan", a + "6", 80),
			Cutscene.Beat.act(20, () -> { // whoever has not gone up the beam yet is carried now
				for (StarUnit unit : army)
					if (unit.isAlive() && unit.level() == level) {
						level.sendParticles(ParticleTypes.END_ROD, unit.getX(), unit.getY(), unit.getZ(), 10, 0.5, 1, 0.5, 0.2);
						VoidWorld.send(level, unit);
					}
			}),
			Cutscene.Beat.say("artanis", a + "7", 80)), () -> {
			for (StarUnit hero : heroes) { // back to their ships in the stratosphere
				level.sendParticles(ParticleTypes.REVERSE_PORTAL, hero.getX(), hero.getY() + 1, hero.getZ(), 40, 0.5, 1, 0.5, 0.2);
				hero.discard();
			}
			level.playSound(null, pedestal, SoundEvents.ENDERMAN_TELEPORT, SoundSource.NEUTRAL, 1.0f, 0.8f);
		});
	}

	/** The three heroes at the artifact for the scene: the player's, still, unharmed, freed (no motes, no bar). */
	private static List<StarUnit> heroes(ServerLevel level, Vec3 centre, UUID player) {
		List<StarUnit> out = new ArrayList<>();
		UnitKind[] kinds = {UnitKind.KERRIGAN, UnitKind.RAYNOR, UnitKind.ARTANIS};
		for (int i = 0; i < kinds.length; i++) {
			double a = Math.PI * 2 * i / kinds.length;
			int x = (int) Math.floor(centre.x + Math.cos(a) * 5), z = (int) Math.floor(centre.z + Math.sin(a) * 5);
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
			StarUnit hero = StarMine.type(kinds[i]).create(level);
			if (hero == null) continue;
			float yaw = (float) Math.toDegrees(Math.atan2(centre.z - (z + 0.5), centre.x - (x + 0.5))) - 90f;
			hero.moveTo(x + 0.5, y, z + 0.5, yaw, 0);
			hero.setYHeadRot(yaw);
			hero.setYBodyRot(yaw);
			hero.setOwner(player);
			hero.addTag(HeroLiberation.FREED);
			hero.addTag(CLONE);
			hero.setNoAi(true);
			hero.setInvulnerable(true);
			level.addFreshEntity(hero);
			level.sendParticles(ParticleTypes.REVERSE_PORTAL, x + 0.5, y + 1, z + 0.5, 40, 0.5, 1, 0.5, 0.2);
			out.add(hero);
		}
		level.playSound(null, BlockPos.containing(centre), SoundEvents.ENDERMAN_TELEPORT, SoundSource.NEUTRAL, 1.0f, 1.2f);
		return out;
	}
}
