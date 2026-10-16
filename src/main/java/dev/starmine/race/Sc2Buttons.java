package dev.starmine.race;

import dev.starmine.ability.Ability;
import dev.starmine.entity.UnitKind;

/**
 * The SC2 button of every command card action (owner 16.10): the id of its `CButton` in StarCraft II's
 * VoidMulti Data. Modkit exports each button's icon (`scripts/export-starmine-icons.mjs` of
 * ModKit-Blizzard reads the string literals of this file) to
 * `textures/gui/sc2/command/<id in lower case>.png`; the card draws it when present
 * ({@link dev.starmine.client.CommandScreen}), else its text. One table: a new ability or research gets
 * its button here (the switches are exhaustive, so the compiler asks for it); a game test checks it
 * ({@link RaceGameTests#everyCommandHasAnSc2Button}). Button ids stand after `->` or in `{…, …}` arrays (that is what the exporter reads); no other literal there.
 */
public final class Sc2Buttons {
	private Sc2Buttons() {
	}

	/** The PNG of a button, under the mod's namespace. */
	public static String texture(String button) {
		return "textures/gui/sc2/command/" + button.toLowerCase(java.util.Locale.ROOT) + ".png";
	}

	/** A plain command of the card (`command.starmine.<action>`), as the race's units show it; null if SC2 has none. */
	public static String command(String action, UnitKind.Race race) {
		return switch (action) {
			case "move" -> "Move";
			case "stop" -> "Stop";
			case "hold" -> "MoveHoldPosition";
			case "patrol" -> "MovePatrol";
			case "attack" -> "Attack";
			case "gather" -> switch (race) {
				case TERRAN -> "GatherTerr";
				case PROTOSS -> "GatherProt";
				case ZERG -> "GatherZerg";
			};
			case "return" -> "ReturnCargo";
			case "build" -> switch (race) {
				case TERRAN -> "TerranBuild";
				case PROTOSS -> "ProtossBuild";
				case ZERG -> "ZergBuild";
			};
			case "advanced" -> switch (race) {
				case TERRAN -> "TerranBuildAdvanced";
				case PROTOSS -> "ProtossBuildAdvanced";
				case ZERG -> "ZergBuildAdvanced";
			};
			case "rally" -> "Rally";
			case "cancel" -> "Cancel";
			case "back" -> "Cancel";
			default -> null;
		};
	}

	public static String ability(Ability ability) {
		return switch (ability) {
			case BURROW -> "BurrowDown";
			case CORROSIVE_BILE -> "RavagerCorrosiveBile";
			case CREEP_TUMOR -> "BuildCreepTumor";
			case INJECT_LARVA -> "MorphMorphalisk";
			case TRANSFUSION -> "Transfusion";
			case SPAWN_CHANGELING -> "SpawnChangeling";
			case NEURAL_PARASITE -> "NeuralParasite";
			case FUNGAL_GROWTH -> "FungalGrowth";
			case BLINDING_CLOUD -> "BlindingCloud";
			case ABDUCT -> "FaceEmbrace";
			case PARASITIC_BOMB -> "ParasiticBomb";
			case CONSUME -> "ViperConsume";
			case CAUSTIC_SPRAY -> "CausticSpray";
			case STIMPACK -> "Stim";
			case KD8_CHARGE -> "KD8Charge";
			case CLOAK -> "CloakOnGhost";
			case EMP -> "EMP";
			case STEADY_TARGETING -> "ChannelSnipe";
			case NUKE -> "NukeCalldown";
			case HELLBAT -> "HellionTank";
			case SIEGE_MODE -> "SiegeMode";
			case HIGH_IMPACT -> "ArmorpiercingMode";
			case VIKING_LAND -> "AssaultMode";
			case AFTERBURNERS -> "MedivacSpeedBoost";
			case YAMATO -> "YamatoGun";
			case TACTICAL_JUMP -> "Hyperjump";
			case REPAIR -> "Repair";
			case ORBITAL_COMMAND -> "OrbitalCommand";
			case PLANETARY_FORTRESS -> "PlanetaryFortress";
			case MULE -> "CalldownMULE";
			case SCANNER_SWEEP -> "Scan";
			case LOWER_DEPOT -> "Lower";
			case BUILD_TECHLAB -> "TechLabBarracks";
			case BUILD_REACTOR -> "Reactor";
			case LIFT_OFF -> "Lift";
			case CHARGE -> "Charge";
			case BLINK -> "Blink";
			case PSIONIC_TRANSFER -> "AdeptPhaseShift";
			case FORCE_FIELD -> "ForceField";
			case GUARDIAN_SHIELD -> "GuardianShield";
			case HALLUCINATION -> "Hallucination";
			case PSIONIC_STORM -> "PsiStorm";
			case FEEDBACK -> "Feedback";
			case ARCHON_WARP -> "AWrp";
			case SHADOW_STRIDE -> "DarkTemplarBlink";
			case PURIFICATION_NOVA -> "PurificationNovaTargeted";
			case PHASING_MODE -> "PhasingMode";
			case GRAVITON_BEAM -> "GravitonBeam";
			case PRISMATIC_ALIGNMENT -> "VoidRaySwarmDamageBoost";
			case PULSAR_BEAM -> "OracleWeaponOn";
			case REVELATION -> "OracleRevelation";
			case STASIS_WARD -> "OracleBuildStasisTrap";
			case DISINTEGRATION -> "TempestDisruptionBlast";
			case MASS_RECALL -> "MothershipMassRecall";
			case TIME_WARP -> "TemporalField";
			case CHRONO_BOOST -> "ChronoBoostEnergyCost";
			case BATTERY_OVERCHARGE -> "BatteryOvercharge";
			case WARP_GATE -> "UpgradeToWarpGate";
			// heroes: the multiplayer Data has none; the co-op commanders' buttons
			case LEAPING_STRIKE -> "CommanderTraitKerriganLeapingStrike";
			case PSIONIC_SHIFT -> "CommanderTraitKerriganPsionicShift";
			case HYPERION_STRIKE -> "CommanderTraitRaynorHyperionYamatoCannon";
			case LIGHTNING_DASH -> "Charge";
			case LOAD -> "BunkerLoad";
			case UNLOAD -> "BunkerUnloadAll";
		};
	}

	/** A research at its level (weapons and armor 1-3 of the race, the others one button). */
	public static String research(Research research, UnitKind.Race race, int level) {
		int at = Math.max(1, Math.min(3, level)) - 1;
		return switch (research) {
			case WEAPONS -> switch (race) {
				case TERRAN -> new String[] {"TerranInfantryWeaponsLevel1", "TerranInfantryWeaponsLevel2", "TerranInfantryWeaponsLevel3"}[at];
				case PROTOSS -> new String[] {"ProtossGroundWeaponsLevel1", "ProtossGroundWeaponsLevel2", "ProtossGroundWeaponsLevel3"}[at];
				case ZERG -> new String[] {"zergmeleeweapons1", "zergmeleeweapons2", "zergmeleeweapons3"}[at];
			};
			case ARMOR -> switch (race) {
				case TERRAN -> new String[] {"TerranInfantryArmorLevel1", "TerranInfantryArmorLevel2", "TerranInfantryArmorLevel3"}[at];
				case PROTOSS -> new String[] {"ProtossGroundArmorLevel1", "ProtossGroundArmorLevel2", "ProtossGroundArmorLevel3"}[at];
				case ZERG -> new String[] {"zerggroundarmor1", "zerggroundarmor2", "zerggroundarmor3"}[at];
			};
			case LAIR -> "Lair";
			case HIVE -> "Hive";
			case METABOLIC_BOOST -> "zerglingmovementspeed";
			case CENTRIFUGAL_HOOKS -> "EvolveCentrificalHooks";
			case COMBAT_SHIELD -> "ResearchShieldWall";
			case STIMPACK -> "Stimpack";
			case CONCUSSIVE_SHELLS -> "ResearchPunisherGrenades";
			case CHARGE -> "ResearchCharge";
			case BLINK -> "ResearchStalkerTeleport";
		};
	}
}
