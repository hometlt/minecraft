package dev.starmine.client;

import net.minecraft.core.BlockPos;

import dev.starmine.StarMine;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Dev-only scenario harness for a real client (universal-modder's "scenario harness in a Fabric dev client"):
 * inert unless the client is started with `-Dstarmine.harness=<scenarios>` (`gradlew runHarness`). From the title
 * screen it creates a fresh superflat world, then plays a queue of steps — server-side setup on the integrated
 * server, the thing under test through the real client (camera, crosshair pick, attack packet) — and logs
 * `[harness] <check>: OK|FAIL` lines and a summary to `harness/results.txt` in the run directory, with screenshots
 * of failures under `screenshots/`. The client quits when the queue is done.
 * <p>
 * Scenario `hitbox` (owner 18.10: «сложно бить некоторых мобов ближним оружием»): for every ground unit, its
 * model's extents (rest pose, as drawn) are measured; the unit stands with its visible front 2 blocks from a
 * survival player, who aims at the model's middle, top and side and swings. A miss means the crosshair is on the
 * model but not on the hitbox, or the hitbox lies beyond reach while the model does not. `harness/models.tsv`
 * lists model extents against hitboxes for every kind.
 * <p>
 * Scenario `cutaway` (owner 18.10: armies in tunnels): a hill with a tunnel and a marine in it; the command view
 * looks at it, the cutaway's level comes down through the hill and the click under the screen's middle must reach
 * the tunnel's floor instead of the hilltop; the radius cut round the camera, and leaving the view puts it all back.
 */
public final class Harness {
	private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("StarMine/harness");
	private static final String SCENARIOS = System.getProperty("starmine.harness");
	/** Where the player stands: the world's spawn column. */
	private static final int X = 0, Z = 0;
	private record Step(int pause, Runnable action) {
	}
	/** A unit's model as drawn: half width across, height, in blocks. */
	private record Extent(double half, double height) {
	}

	private static final ArrayDeque<Step> steps = new ArrayDeque<>();
	private static final List<String> lines = new ArrayList<>();
	private static boolean worldAsked, started, finished;
	private static int delay, ok, fail;
	private static volatile int groundY = -60;
	private static volatile Entity current;

	private Harness() {
	}

	public static void register() {
		if (SCENARIOS == null) return;
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(Harness::tick);
	}

	private static void tick(Minecraft mc) {
		if (finished) return;
		if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) {
			if (!worldAsked && (mc.screen instanceof TitleScreen || mc.screen instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen)) {
				worldAsked = true;
				if (SCENARIOS.contains("amphionmode")) SkirmishWorlds.open(mc, UnitKind.Race.TERRAN, UnitKind.Race.ZERG);
				else createWorld(mc);
			}
			return;
		}
		// a harness client runs in the background: never paused by a lost focus or a stray menu
		mc.options.pauseOnLostFocus = false;
		if (mc.screen != null && !(mc.screen instanceof CommandScreen) && !(mc.screen instanceof SkirmishScreen)) mc.setScreen(null);
		if (!started) {
			started = true;
			delay = 100; // let the world settle and the terrain build
			plan(mc);
		}
		if (delay > 0) {
			delay--;
			return;
		}
		Step step = steps.poll();
		if (step == null) {
			finish(mc);
			return;
		}
		step.action.run();
		delay = step.pause;
	}

	private static void createWorld(Minecraft mc) {
		String name = "harness-" + System.currentTimeMillis();
		GameRules rules = new GameRules();
		if (SCENARIOS.contains("match") || SCENARIOS.contains("economicrecovery") || SCENARIOS.contains("underground") || SCENARIOS.contains("skirmishmode")) {
			rules.getRule(StarMine.SPAWN_BASES).set(false, null);
			rules.getRule(StarMine.SPAWN_HIVE).set(false, null);
			rules.getRule(StarMine.AI_PRESSURE).set(0, null);
		}
		rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
		rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
		rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
		LevelSettings settings = new LevelSettings(name, GameType.CREATIVE, false, Difficulty.NORMAL, true, rules, WorldDataConfiguration.DEFAULT);
		mc.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(18102026L, false, false),
			registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), mc.screen);
	}

	private static void plan(Minecraft mc) {
		if (SCENARIOS.contains("amphionmode")) { amphionMode(mc); return; }
		server(mc, (level, player) -> {
			groundY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, X, Z);
			player.setGameMode(GameType.SURVIVAL);
			player.getAbilities().invulnerable = true;
			player.onUpdateAbilities();
			player.teleportTo(level, X + 0.5, groundY, Z + 0.5, 0, 0);
			level.setDayTime(6000);
		});
		steps.add(new Step(40, () -> {
		}));
		if (SCENARIOS.equals("all") || SCENARIOS.contains("hitbox")) hitbox(mc);
		if (SCENARIOS.equals("all") || java.util.Arrays.asList(SCENARIOS.split(",")).contains("ground")) ground(mc);
		if (SCENARIOS.equals("all") || SCENARIOS.contains("cutaway")) cutaway(mc);
		if (SCENARIOS.equals("all") || SCENARIOS.contains("addon")) addOn(mc);
		if (SCENARIOS.contains("cards")) cards(mc);
		if (SCENARIOS.equals("all") || SCENARIOS.contains("sheriff")) sheriff(mc);
		if (SCENARIOS.equals("all") || SCENARIOS.contains("rts")) rts(mc);
		if (SCENARIOS.contains("teams")) teams(mc);
		if (SCENARIOS.contains("portals")) portals(mc);
		if (SCENARIOS.contains("hero")) hero(mc);
		if (SCENARIOS.contains("rtsfeatures")) rtsFeatures(mc);
		if (SCENARIOS.contains("construction")) construction(mc);
		if (SCENARIOS.contains("review")) review(mc);
		if (SCENARIOS.contains("geysers")) geysers(mc);
		if (SCENARIOS.contains("economicrecovery")) economicRecovery(mc);
		if (SCENARIOS.contains("underground")) undergroundBattle(mc);
		if (SCENARIOS.contains("skirmishmode")) skirmishMode(mc);
		if (SCENARIOS.contains("match")) {
			steps.add(new Step(20, () -> SkirmishHarness.start(mc)));
			steps.add(new Step(20, () -> awaitMatch(mc)));
		}
	}

	private static final List<StarUnit> undergroundArmy = new ArrayList<>();

	private static void amphionMode(Minecraft mc) {
		steps.add(new Step(20, () -> awaitSkirmishStart(mc, 0)));
		steps.add(new Step(160, () -> { BlockFog.stop(mc); CommandView.lookAt(136, 100); }));
		steps.add(new Step(40, () -> {
			check("amphion: locked RTS", SkirmishScreen.active && CommandView.active() && mc.player.isSpectator(), "native template started in RTS");
			server(mc, (level, player) -> {
				var data = dev.starmine.hive.HiveSavedData.get(level.getServer());
				var home = data.baseOf(player.getUUID(), level.dimension().location().toString());
				check("amphion: native map retained", dev.starmine.world.SkirmishMap.nativeWorld(), "old MapOverlay disabled");
				check("amphion: both bases active", home != null && data.all().stream().anyMatch(b -> b.owner == null && b.race == UnitKind.Race.ZERG), "selected player and AI races founded at authored starts");
				check("amphion: authored starting position", home != null && Math.abs(home.hatchery.getX() - 136) <= 2 && Math.abs(home.hatchery.getZ() - 100) <= 2,
					home == null ? "no owned base" : home.hatchery.toShortString());
				var ai = data.all().stream().filter(b -> b.owner == null && b.race == UnitKind.Race.ZERG).findFirst().orElse(null);
				check("amphion: AI at its authored start", ai != null && Math.abs(ai.hatchery.getX() - 136) <= 2 && Math.abs(ai.hatchery.getZ() - 439) <= 2,
					ai == null ? "no AI base" : ai.hatchery.toShortString());
				check("amphion: terrain locked", dev.starmine.world.Skirmish.terrainLocked(level), "no breaking or placing in a skirmish");
				if (home != null) check("amphion: five starting minerals", dev.starmine.mineral.Minerals.coresNear(level, home.hatchery, 36).size() == 5, "normalized native field");
				check("amphion: stable native ramp", Math.abs(level.getBlockState(new BlockPos(382, 86, 270)).getCollisionShape(level, new BlockPos(382, 86, 270)).max(net.minecraft.core.Direction.Axis.Y) - .5) < .001,
					"authored lower slab retained at 382,86,270");
			});
			check("amphion: explored map overview", SkirmishOverview.inside(136, 439) && SkirmishOverview.at(136, 100) != 0 && SkirmishOverview.at(136, 439) != 0, "whole map painted on the minimap");
			int cx = 136 >> 4, cz = 100 >> 4, loaded = 0;
			for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) if (mc.level.getChunkSource().hasChunk(cx + dx, cz + dz)) loaded++;
			LOG.info("[harness] amphion view: {}; player {}; {} of 49 chunks round the base on the client; surface {}; renderer {}", CommandView.describe(),
				mc.player.blockPosition().toShortString(), loaded, mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, 136, 100),
				mc.levelRenderer.getSectionStatistics());
			shot(mc, "amphion-player-base");
		}));
		steps.add(new Step(160, () -> CommandView.lookAt(136, 439)));
		steps.add(new Step(20, () -> shot(mc, "amphion-ai-base")));
		steps.add(new Step(160, () -> CommandView.lookAt(382, 270)));
		steps.add(new Step(20, () -> shot(mc, "amphion-ramp")));
	}

	private static void skirmishMode(Minecraft mc) {
		steps.add(new Step(20, () -> mc.setScreen(new SkirmishScreen(null))));
		steps.add(new Step(20, () -> shot(mc, "skirmish-settings")));
		steps.add(new Step(20, () -> net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
			new dev.starmine.world.Skirmish.Request(UnitKind.Race.TERRAN.ordinal(), UnitKind.Race.ZERG.ordinal(), false))));
		steps.add(new Step(20, () -> awaitSkirmishStart(mc, 0)));
		steps.add(new Step(40, () -> {
			check("skirmish: persistent RTS", SkirmishScreen.active && CommandView.active(), "server mode state and command camera");
			check("skirmish: no Steve", mc.player.isSpectator() && !CommandView.heroSelected(), "observer body, no playable hero");
			server(mc, (level, player) -> {
				var data = dev.starmine.hive.HiveSavedData.get(level.getServer());
				var own = data.baseOf(player.getUUID(), level.dimension().location().toString());
				check("skirmish: selected player race", own != null && own.race == UnitKind.Race.TERRAN, "owned terran main and starting workers");
				check("skirmish: selected AI race", data.all().stream().anyMatch(b -> b.owner == null && b.race == UnitKind.Race.ZERG), "normal zerg AI base");
				if (own != null) {
					var cores = dev.starmine.mineral.Minerals.coresNear(level, own.hatchery, 36);
					check("skirmish: five starting minerals", cores.size() == 5, cores.toString());
					check("skirmish: geysers near start", dev.starmine.vespene.Vespene.outletsNear(level, own.hatchery, 40, 16).size() >= 2, "native vein outlets");
					var ray = StarMine.type(UnitKind.VOID_RAY).create(level); ray.setOwner(player.getUUID());
					ray.moveTo(player.getX() + 16, player.getY() + 5, player.getZ() + 8, 0, 0); level.addFreshEntity(ray);
					var target = net.minecraft.world.entity.EntityType.HUSK.create(level); target.setNoAi(true);
					target.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(2000); target.setHealth(2000);
					target.moveTo(player.getX() + 16, player.getY(), player.getZ() + 18, 0, 0); level.addFreshEntity(target); ray.setTarget(target);
					var pylon = StarMine.type(UnitKind.PYLON).create(level); pylon.setOwner(player.getUUID()); pylon.setNoAi(true);
					pylon.moveTo(player.getX() + 26, player.getY(), player.getZ() + 8, 0, 0); level.addFreshEntity(pylon);
				}
			});
		}));
		steps.add(new Step(80, () -> { CommandView.lookAt(mc.player.getX() + 10, mc.player.getZ()); shot(mc, "skirmish-resources-and-beam"); }));
		steps.add(new Step(40, () -> mc.setScreen(new SkirmishScreen(new CommandScreen()))));
		steps.add(new Step(20, () -> shot(mc, "skirmish-leave-settings")));
		steps.add(new Step(20, () -> net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new dev.starmine.world.Skirmish.Request(0, 0, true))));
		steps.add(new Step(40, () -> check("skirmish: leave restores survival", !SkirmishScreen.active && !mc.player.isSpectator(), "player mode restored")));
	}

	private static void awaitSkirmishStart(Minecraft mc, int attempts) {
		if (SkirmishScreen.active && BaseStatusClient.status.present()) return;
		if (attempts >= 120) { check("skirmish: server finished setup", false, "timed out waiting for mode and founded base"); return; }
		steps.addFirst(new Step(20, () -> awaitSkirmishStart(mc, attempts + 1)));
	}
	private static final List<dev.starmine.hive.Hive> undergroundBases = new ArrayList<>();
	private static net.minecraft.world.entity.monster.warden.Warden undergroundWarden;
	private static void undergroundBattle(Minecraft mc) {
		steps.add(new Step(100, () -> server(mc, (level, player) -> {
			var saved = dev.starmine.hive.HiveSavedData.get(level.getServer());
			saved.structuresDone.add("DEPOSIT:SPAWN");
			for (int cx = 56; cx <= 69; cx++) for (int cz = -4; cz <= 4; cz++) level.setChunkForced(cx, cz, true);
			var floor = net.minecraft.world.level.block.Blocks.DEEPSLATE_TILES.defaultBlockState();
			for (int x = 912; x <= 1088; x++) for (int z = -48; z <= 48; z++) {
				level.setBlock(new net.minecraft.core.BlockPos(x, -41, z), floor, 2);
				level.setBlock(new net.minecraft.core.BlockPos(x, -15, z), floor, 2);
				if (x == 912 || x == 1088 || z == -48 || z == 48)
					for (int y = -40; y < -15; y++) level.setBlock(new net.minecraft.core.BlockPos(x, y, z), floor, 2);
			}
			int placed;
			try {
				placed = level.getServer().getCommands().getDispatcher().execute("place template minecraft:ancient_city/city_center/city_center_1 980 -40 -24",
					level.getServer().createCommandSourceStack().withLevel(level).withSuppressedOutput());
			} catch (Exception e) { placed = 0; LOG.warn("Ancient-city template placement failed", e); }
			check("underground: native ancient-city template", placed > 0, "command result=" + placed);
			// Wide bases and their main connection; native city architecture remains beside this avenue.
			for (int x = 914; x <= 1086; x++) for (int z = -45; z <= 45; z++) {
				boolean clear = Math.abs(z) <= 7 || Math.abs(x - 944) <= 28 && Math.abs(z) <= 28 || Math.abs(x - 1056) <= 28 && Math.abs(z) <= 28;
				if (clear) for (int y = -40; y < -20; y++) level.setBlock(new net.minecraft.core.BlockPos(x, y, z), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
			}
			for (int side = 0; side < 2; side++) {
				int x = side == 0 ? 944 : 1056;
				var at = new net.minecraft.core.BlockPos(x, -40, 0);
				var base = new dev.starmine.hive.Hive(java.util.UUID.randomUUID(), level.dimension().location().toString(), at);
				base.race = side == 0 ? UnitKind.Race.TERRAN : UnitKind.Race.ZERG; base.minerals = 2000; base.vespene = 0;
				saved.add(base); undergroundBases.add(base);
				var mainKind = base.race.rules().main(); base.join(mainKind);
				var main = dev.starmine.hive.HiveLogic.spawn(level, base, mainKind, Vec3.atBottomCenterOf(at), null);
				dev.starmine.colony.Founding.startWorkers(level, base, main);
				for (int i = -2; i <= 2; i++) dev.starmine.mineral.Minerals.place(level, at.offset(i * 3, 0, -20), (pos, state) -> level.setBlock(pos, state, 2));
				boolean vein = dev.starmine.vespene.VespeneVeinFeature.build(level, net.minecraft.util.RandomSource.create(701 + side), x, 22, -40);
				check("underground: native gas " + base.race, vein, "hall y=-40");
				var gasKind = base.race.rules().gasBuilding();
				var outlets = dev.starmine.vespene.Vespene.outletsNear(level, new net.minecraft.core.BlockPos(x, -40, 22), 16, 24);
				outlets.stream().max(java.util.Comparator.comparingInt(net.minecraft.core.BlockPos::getY)).ifPresent(outlet -> {
					base.join(gasKind); dev.starmine.hive.HiveLogic.spawn(level, base, gasKind, Vec3.atBottomCenterOf(outlet.above()), null);
				});
				for (int i = 0; i < 8; i++) {
					var kind = side == 0 ? (i < 6 ? UnitKind.MARINE : UnitKind.SIEGE_TANK) : (i < 5 ? UnitKind.ZERGLING : UnitKind.ROACH);
					var unit = dev.starmine.hive.HiveLogic.spawn(level, base, kind, new Vec3(x + (side == 0 ? 18 : -18), -40, (i - 4) * 3), null);
					undergroundArmy.add(unit);
					unit.command(StarUnit.Order.ATTACK_MOVE, new Vec3(1000, -40, 0));
				}
			}
			undergroundWarden = net.minecraft.world.entity.EntityType.WARDEN.create(level); undergroundWarden.moveTo(1000.5, -40, 2.5, 0, 0); level.addFreshEntity(undergroundWarden);
			undergroundWarden.setTarget(undergroundArmy.getFirst());
			undergroundWarden.setPersistenceRequired();
			undergroundWarden.increaseAngerAt(undergroundArmy.getFirst(), 150, true);
			player.setGameMode(GameType.SPECTATOR);
			player.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.NIGHT_VISION, 12000, 0, false, false));
			player.teleportTo(level, 1000.5, -31, 42, 180, 18);
		})));
		steps.add(new Step(1800, () -> { BlockFog.stop(mc); shot(mc, "underground-battle"); }));
		steps.add(new Step(30, () -> server(mc, (level, player) -> {
			boolean losses = undergroundArmy.stream().anyMatch(u -> !u.isAlive() || u.getHealth() < u.getMaxHealth());
			boolean wardenHit = undergroundWarden.getHealth() < undergroundWarden.getMaxHealth()
				|| undergroundArmy.stream().anyMatch(u -> u.getLastHurtByMob() == undergroundWarden);
			check("underground: armies fought", losses, "survivors=" + undergroundArmy.stream().filter(Entity::isAlive).count());
			check("underground: Warden engaged", wardenHit, "health=" + undergroundWarden.getHealth());
			for (var base : undergroundBases) check("underground: mining " + base.race, base.mineralsCollected > 0 || base.vespeneCollected > 0,
				"mined=" + base.mineralsCollected + ", gas=" + base.vespeneCollected);
			for (var base : undergroundBases) check("underground: gas delivered " + base.race, base.vespeneCollected > 0, "gas=" + base.vespeneCollected);
		})));
		steps.add(new Step(20, () -> shot(mc, "underground-aftermath")));
		steps.add(new Step(20, () -> server(mc, (level, player) -> {
			for (int cx = 56; cx <= 69; cx++) for (int cz = -4; cz <= 4; cz++) level.setChunkForced(cx, cz, false);
		})));
	}

	private static java.util.UUID reviewHidden;
	private static final List<java.util.UUID> bankruptBases = new ArrayList<>();
	private static void economicRecovery(Minecraft mc) {
		steps.add(new Step(220, () -> server(mc, (level, player) -> {
			var data = dev.starmine.hive.HiveSavedData.get(level.getServer());
			data.structuresDone.add("DEPOSIT:SPAWN");
			for (int rx = 1; rx <= 4; rx++) for (int rz = -2; rz <= 2; rz++) data.structuresDone.add("DEPOSIT:" + rx + ":" + rz);
			player.setGameMode(GameType.SPECTATOR); player.teleportTo(level, 1000.5, groundY + 3, 24.5, 180, 0);
			for (int cx = 58; cx <= 66; cx++) for (int cz = -3; cz <= 3; cz++) level.getChunk(cx, cz);
			UnitKind[] kinds = {UnitKind.COMMAND_CENTER, UnitKind.NEXUS};
			for (int i = 0; i < kinds.length; i++) {
				int x = 976 + i * 48;
				var at = new net.minecraft.core.BlockPos(x, groundY, 0);
				var base = new dev.starmine.hive.Hive(java.util.UUID.randomUUID(), level.dimension().location().toString(), at);
				base.race = kinds[i].race; base.minerals = 0; data.add(base); bankruptBases.add(base.id);
				StarUnit main = StarMine.type(kinds[i]).create(level); main.moveTo(kinds[i].gridCentre(Vec3.atBottomCenterOf(at)));
				main.joinHive(base); base.join(kinds[i]); level.addFreshEntity(main);
				boolean resource = dev.starmine.mineral.Minerals.place(level, at.north(18), (pos, state) -> level.setBlock(pos, state, 2));
				check("recovery: native mineral " + base.race, resource, at.toShortString());
			}
		})));
		steps.add(new Step(700, () -> server(mc, (level, player) -> {
			for (var id : bankruptBases) {
				var base = dev.starmine.hive.HiveSavedData.get(level.getServer()).get(id);
				check("recovery: free worker queued " + base.race, base.pending(base.race.rules().worker()) == 1 && base.minerals == 0,
					"pending=" + base.pending(base.race.rules().worker()) + ", bank=" + base.minerals);
			}
		})));
		steps.add(new Step(40, () -> server(mc, (level, player) -> {
			for (var id : bankruptBases) {
				var base = dev.starmine.hive.HiveSavedData.get(level.getServer()).get(id);
				check("recovery: income resumed " + base.race, base.count(base.race.rules().worker()) > 0 && base.mineralsCollected > 0,
					"workers=" + base.count(base.race.rules().worker()) + ", mined=" + base.mineralsCollected);
			}
		})));
		steps.add(new Step(40, () -> { CommandView.enter(mc); CommandView.hero = false; CommandView.lookAt(1000, 0); CommandView.climb(10); BlockFog.stop(mc); }));
		steps.add(new Step(20, () -> shot(mc, "economic-recovery")));
	}

	private static net.minecraft.core.BlockPos freshGeyser, oldGeyserGround;
	private static void geysers(Minecraft mc) {
		steps.add(new Step(80, () -> server(mc, (level, player) -> {
			player.setGameMode(GameType.CREATIVE);
			var saved = dev.starmine.hive.HiveSavedData.get(level.getServer());
			saved.structuresDone.add("DEPOSIT:SPAWN");
			for (int cx = -3; cx <= 3; cx++) for (int cz = -4; cz <= 1; cz++) level.getChunk(cx, cz);
			for (var p : net.minecraft.core.BlockPos.betweenClosed(-32, groundY, -40, 32, groundY + 16, 0))
				if (!level.getBlockState(p).isAir()) level.setBlock(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
			boolean built = dev.starmine.vespene.VespeneVeinFeature.build(level, net.minecraft.util.RandomSource.create(9051), -16, -24, true,
				net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR, net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE);
			check("geysers: native generation", built, "real terrain feature");
			var root = (dev.starmine.vespene.VespeneRootBlockEntity) level.getBlockEntity(new net.minecraft.core.BlockPos(-16, level.getMinBuildHeight() + 1, -24));
			freshGeyser = root.baseOf(level, root.outlet(0));
			oldGeyserGround = new net.minecraft.core.BlockPos(16, groundY - 1, -24);
			var oldRoot = new net.minecraft.core.BlockPos(16, level.getMinBuildHeight() + 1, -24);
			var top = oldGeyserGround.above(12);
			for (int y = 1; y <= 12; y++) {
				int radius = y <= 6 ? 10 : Math.max(1, 10 - (int) Math.ceil((y - 6) * 1.5));
				for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++)
					level.setBlock(oldGeyserGround.offset(x, y, z), dev.starmine.vespene.Vespene.ROCK.defaultBlockState(), 2);
			}
			List<net.minecraft.core.BlockPos> channel = new ArrayList<>();
			for (var p = oldRoot.above(); p.getY() < top.getY(); p = p.above()) {
				channel.add(p); level.setBlock(p, dev.starmine.vespene.Vespene.VESPENE.defaultBlockState(), 2);
			}
			level.setBlock(top, dev.starmine.vespene.Vespene.VESPENE.defaultBlockState().setValue(dev.starmine.vespene.VespeneBlock.OUTLET, true), 2);
			level.setBlock(oldRoot, dev.starmine.vespene.Vespene.ROOT.defaultBlockState(), 2);
			((dev.starmine.vespene.VespeneRootBlockEntity) level.getBlockEntity(oldRoot)).setVein(channel, List.of(top));
			player.teleportTo(level, -6, groundY + 2, -13, 135, 8);
		})));
		steps.add(new Step(40, () -> server(mc, (level, player) -> {
			for (var base : List.of(freshGeyser, oldGeyserGround)) {
				boolean shape = true;
				for (int y = 1; y <= 13; y++) for (int x = -10; x <= 10; x++) for (int z = -10; z <= 10; z++) {
					if (x == 0 && z == 0 && y <= 4) continue;
					int r = y <= 2 ? 3 : y == 3 ? 2 : 1;
					boolean inside = y <= 4 && Math.max(Math.abs(x), Math.abs(z)) <= r && Math.abs(x) + Math.abs(z) <= r + 1;
					shape &= level.getBlockState(base.offset(x, y, z)).is(dev.starmine.vespene.Vespene.ROCK) == inside;
				}
				check("geysers: " + (base.equals(freshGeyser) ? "generated" : "migrated") + " 7x7x4", shape, base.toShortString());
			}
		})));
		steps.add(new Step(20, () -> { aim(mc, Vec3.atCenterOf(freshGeyser.above(2))); shot(mc, "geysers-new-side"); }));
		steps.add(new Step(40, () -> server(mc, (level, player) -> player.teleportTo(level, 27, groundY + 2, -13, 135, 8))));
		steps.add(new Step(20, () -> { aim(mc, Vec3.atCenterOf(oldGeyserGround.above(2))); shot(mc, "geysers-migrated-side"); }));
		steps.add(new Step(40, () -> { CommandView.enter(mc); CommandView.hero = false; CommandView.lookAt(0, -24); }));
		steps.add(new Step(20, () -> shot(mc, "geysers-top")));
		steps.add(new Step(20, () -> { mc.setScreen(null); CommandView.exit(mc); }));
	}

	private static void review(Minecraft mc) {
		steps.add(new Step(60, () -> server(mc, (level, player) -> {
			player.setGameMode(GameType.CREATIVE); player.teleportTo(level, .5, groundY, 32.5, 180, 0);
			UnitKind[] kinds = {UnitKind.SUPPLY_DEPOT, UnitKind.SUPPLY_DEPOT, UnitKind.SPINE_CRAWLER, UnitKind.SPORE_CRAWLER,
				UnitKind.SPINE_CRAWLER, UnitKind.SPORE_CRAWLER};
			for (int i = 0; i < kinds.length; i++) {
				StarUnit unit = StarMine.type(kinds[i]).create(level); unit.setOwner(player.getUUID()); unit.setNoAi(true);
				unit.moveTo(-25 + i * 10, groundY, -10, 180, 0); level.addFreshEntity(unit);
				if (i == 1) unit.setMode(dev.starmine.ability.Modes.LOWERED);
				if (i >= 4) unit.setMode(dev.starmine.ability.Modes.UPROOTED);
			}
			var target = net.minecraft.world.entity.EntityType.HUSK.create(level); target.setNoAi(true);
			target.moveTo(-5, groundY, 0, 0, 0); target.setHealth(20); target.setInvulnerable(true); level.addFreshEntity(target);
			StarUnit hidden = StarMine.type(UnitKind.ROACH).create(level); hidden.setOwner(java.util.UUID.randomUUID()); hidden.setNoAi(true);
			hidden.moveTo(-25, groundY, 5, 0, 0); hidden.setMode(dev.starmine.ability.Modes.BURROWED); level.addFreshEntity(hidden); reviewHidden = hidden.getUUID();
			StarUnit scout = StarMine.type(UnitKind.MARINE).create(level); scout.setOwner(player.getUUID()); scout.setNoAi(true);
			scout.moveTo(-25, groundY, 3, 0, 0); level.addFreshEntity(scout);
		})));
		steps.add(new Step(160, () -> { CommandView.enter(mc); CommandView.hero = false; CommandView.lookAt(0, -10); CommandView.climb(12); }));
		steps.add(new Step(40, () -> {
			try {
				Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
				boolean pack = ((java.util.Optional<?>) iris.getMethod("getCurrentPack").invoke(null)).isPresent();
				Object manager = iris.getMethod("getPipelineManager").invoke(null);
				Object pipeline = ((java.util.Optional<?>) manager.getClass().getMethod("getPipeline").invoke(manager)).orElse(null);
				check("review: shaders active", pack && pipeline != null && !pipeline.getClass().getSimpleName().contains("Vanilla"),
					iris.getMethod("getCurrentPackName").invoke(null) + " " + (pipeline == null ? "none" : pipeline.getClass().getName()));
			} catch (ReflectiveOperationException e) { check("review: shaders active", false, e.toString()); }
			for (Entity e : mc.level.entitiesForRendering()) if (e instanceof StarUnit crawler && crawler.kind == UnitKind.SPINE_CRAWLER) {
				boolean uprooted = crawler.mode() == dev.starmine.ability.Modes.UPROOTED;
				SkinnedModel model = StarMineModels.get(uprooted ? "spinecrawler_uprooted" : "spinecrawler");
				boolean connected = model != null && model.spineHead != null;
				if (connected) {
					float[] palette = new float[model.slots * 12];
					SkinnedModelDrawer drawer = new SkinnedModelDrawer();
					for (String clipName : new String[] {"stand", "walk"}) {
						SkinnedModel.Clip clip = model.clips.get(clipName);
						if (clip == null) continue;
						for (int frame = 0; frame <= 12; frame++) {
							model.pose(clip, clip.seconds(model.fps) * frame / 12, palette); drawer.skin(model, palette);
							Vec3 tip = drawer.aimSpine(crawler, model, 0);
							connected &= tip != null && Double.isFinite(tip.lengthSqr());
						}
					}
				}
				check("review: spine neck " + (uprooted ? "uprooted" : "rooted"), connected, "head attachment throughout native stand and walk clips");
			}
			for (Entity e : mc.level.entitiesForRendering()) if (e.getUUID().equals(reviewHidden)) {
				check("review: undetected enemy hidden", !CommandView.visible(e), "ordinary allied sight without detection");
				CommandView.selection.add(e.getId());
				check("review: hidden enemy loses selection", !CommandView.selected().contains(e), "health/shields use the same visibility predicate");
			}
			shot(mc, "review-shaders-buildings");
		}));
		steps.add(new Step(40, () -> { CommandView.lookAt(-5, -10); CommandView.climb(-7); }));
		steps.add(new Step(20, () -> shot(mc, "review-shaders-crawlers")));
		steps.add(new Step(40, () -> CommandView.lookAt(20, -10)));
		steps.add(new Step(20, () -> shot(mc, "review-shaders-uprooted")));
		steps.add(new Step(40, () -> CommandView.lookAt(-20, -10)));
		steps.add(new Step(20, () -> shot(mc, "review-shaders-depot")));
		steps.add(new Step(40, () -> server(mc, (level, player) -> {
			StarUnit detector = StarMine.type(UnitKind.OBSERVER).create(level); detector.setOwner(player.getUUID()); detector.setNoAi(true);
			detector.moveTo(-25, groundY + 3, 5, 0, 0); level.addFreshEntity(detector);
		})));
		steps.add(new Step(20, () -> {
			for (Entity e : mc.level.entitiesForRendering()) if (e.getUUID().equals(reviewHidden))
				check("review: detector reveals enemy", CommandView.visible(e), "allied observer within detection range");
		}));
		steps.add(new Step(20, () -> { mc.setScreen(null); }));
	}

	private static void construction(Minecraft mc) {
		steps.add(new Step(40, () -> server(mc, (level, player) -> {
			player.setGameMode(GameType.CREATIVE);
			player.teleportTo(level, .5, groundY, 16.5, 180, 0);
			int i = 0;
			for (UnitKind kind : new UnitKind[] {UnitKind.BARRACKS, UnitKind.SPAWNING_POOL, UnitKind.GATEWAY}) {
				StarUnit unit = StarMine.type(kind).create(level);
				unit.setOwner(player.getUUID()); unit.setNoAi(true);
				unit.moveTo(-16 + i++ * 16, groundY, -10, 0, 0); level.addFreshEntity(unit); unit.startConstruction(400);
				if (kind == UnitKind.BARRACKS) {
					StarUnit scv = StarMine.type(UnitKind.SCV).create(level);
					scv.setOwner(player.getUUID()); scv.setNoAi(true); scv.moveTo(unit.position().add(6, 0, 0));
					scv.setConstructing(unit.getUUID()); level.addFreshEntity(scv);
				}
			}
		})));
		steps.add(new Step(40, () -> { CommandView.enter(mc); CommandView.hero = false; CommandView.lookAt(0, -10); CommandView.climb(10); }));
		steps.add(new Step(120, () -> shot(mc, "construction-early")));
		steps.add(new Step(120, () -> shot(mc, "construction-middle")));
		steps.add(new Step(120, () -> shot(mc, "construction-late")));
		steps.add(new Step(20, () -> { shot(mc, "construction-complete"); mc.setScreen(null); }));
	}

	private static int lastMatchShot = -1;
	private static void awaitMatch(Minecraft mc) {
		if (!SkirmishHarness.done) {
			// Arena setup raises the floor from superflat -60 to zero; wait for the player teleport before opening the camera.
			if (!CommandView.active() && (SkirmishHarness.seconds < 1 || mc.player.getY() < -2)) {
				steps.addFirst(new Step(20, () -> awaitMatch(mc)));
				return;
			}
			if (!CommandView.active()) CommandView.enter(mc);
			if (SkirmishHarness.AI_VS_AI) BlockFog.stop(mc);
			int frame = SkirmishHarness.round * 100 + SkirmishHarness.seconds / 120;
			if (frame != lastMatchShot) {
				lastMatchShot = frame;
				CommandView.lookAt(SkirmishHarness.seconds >= 720 ? 64 : -64, 0);
				String prefix = SkirmishHarness.AI_VS_AI ? "ai-vs-ai-" : "match-";
				if (SkirmishHarness.SINGLE) prefix += "budget-";
				shot(mc, prefix + frame);
			}
			steps.addFirst(new Step(20, () -> awaitMatch(mc)));
			return;
		}
		for (var result : SkirmishHarness.results) for (String key : List.of("playerPlayed", "aiDeveloped", "aiBuiltDefences", "aiDefended", "aiAttacked", "aiExpandedEconomy"))
			check("match " + result.get("match") + " " + key, Boolean.TRUE.equals(result.get(key)), result.toString());
		mc.setScreen(null);
	}

	private static net.minecraft.core.BlockPos itemSite;
	private static java.util.UUID heroFoe;
	private static Vec3 idleHero;
	private static java.util.UUID trainingBuilding;
	private static final java.util.List<Integer> audioUnits = new java.util.ArrayList<>();

	private static void rtsFeatures(Minecraft mc) {
		steps.add(new Step(30, () -> server(mc, (level, player) -> {
			player.setGameMode(GameType.SURVIVAL);
			player.teleportTo(level, .5, groundY, .5, 0, 0);
			player.getInventory().setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BRICKS, 64));
			player.getInventory().setItem(1, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_PICKAXE));
			player.getInventory().setItem(2, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BOW));
			player.getInventory().setItem(9, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ARROW, 64));
			itemSite = new net.minecraft.core.BlockPos(3, groundY, 0);
			level.setBlock(itemSite, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
			var foe = net.minecraft.world.entity.EntityType.COW.create(level);
			foe.moveTo(8.5, groundY, .5, 0, 0); foe.setNoAi(true); level.addFreshEntity(foe); heroFoe = foe.getUUID();
		})));
		steps.add(new Step(20, () -> {
			mc.options.guiScale().set(1); mc.resizeDisplay();
			CommandView.enter(mc);
		}));
		steps.add(new Step(30, () -> {
			CommandScreen screen = (CommandScreen) mc.screen;
			double[] at = CommandView.project(mc.player.position(), screen.width, screen.height);
			screen.boxSelect(at[0] - 10, at[1] - 10, at[0] + 10, at[1] + 10);
			check("hero: box selects player", CommandView.heroSelected(), "frame around player body");
			shot(mc, "hero-stats");
			screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_1, 0, 0);
			double[] site = CommandView.project(new Vec3(3.5, groundY, .5), screen.width, screen.height);
			screen.mouseClicked(site[0], site[1], org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT);
		}));
		steps.add(new Step(10, () -> {
			server(mc, (level, player) -> check("hero: places selected block", level.getBlockState(itemSite).is(net.minecraft.world.level.block.Blocks.BRICKS)
				&& player.getInventory().getItem(0).getCount() == 63, "normal survival placement and cost"));
			HeroPilot.hand(1);
			HeroPilot.use(null, new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(itemSite).add(-.5, 0, 0), net.minecraft.core.Direction.WEST, itemSite, false));
		}));
		steps.add(new Step(80, () -> {}));
		steps.add(new Step(20, () -> {
			server(mc, (level, player) -> check("hero: mines with selected tool", level.getBlockState(itemSite).isAir(), "vanilla mining packets"));
			HeroPilot.hand(2);
			for (Entity e : mc.level.entitiesForRendering()) if (e.getUUID().equals(heroFoe)) HeroPilot.use((net.minecraft.world.entity.LivingEntity) e, null);
		}));
		steps.add(new Step(100, () -> {}));
		steps.add(new Step(20, () -> {
			server(mc, (level, player) -> {
				Entity e = level.getEntity(heroFoe);
				check("hero: attacks with selected bow", e == null || !e.isAlive() || ((net.minecraft.world.entity.LivingEntity)e).getHealth() < 10, "real arrows damage target");
				if (e != null) e.discard();
			});
			HeroPilot.stop(); idleHero = mc.player.position();
		}));
		steps.add(new Step(10, () -> {
			check("hero: idle legs stay still", mc.player.walkAnimation.speed() == 0 && mc.player.position().distanceToSqr(idleHero) < .01, "zero walk animation and stationary body");
			mc.setScreen(null);
			server(mc, (level, player) -> player.setGameMode(GameType.CREATIVE));
		}));
		serverProgressAndAudio(mc);
	}

	private static void serverProgressAndAudio(Minecraft mc) {
		steps.add(new Step(30, () -> server(mc, (level, player) -> {
			var base = new dev.starmine.hive.Hive(java.util.UUID.randomUUID(), level.dimension().location().toString(), new net.minecraft.core.BlockPos(12, groundY, -12));
			base.owner = player.getUUID(); base.minerals = 10000; base.vespene = 10000;
			base.race = UnitKind.Race.TERRAN;
			dev.starmine.hive.HiveSavedData.get(level.getServer()).add(base);
			var building = StarMine.type(UnitKind.COMMAND_CENTER).create(level);
			building.joinHive(base); building.moveTo(12.5, groundY, -12.5, 0, 0); building.setNoAi(true); level.addFreshEntity(building); base.join(UnitKind.COMMAND_CENTER);
			trainingBuilding = building.getUUID();
			check("building: training queued", building.train(UnitKind.SCV), "full-health producer has a progress bar");
			var growing = StarMine.type(UnitKind.PYLON).create(level);
			growing.setOwner(player.getUUID()); growing.moveTo(-8.5, groundY, -12.5, 0, 0); growing.setNoAi(true); level.addFreshEntity(growing); growing.startConstruction(1200);
			int i = 0;
			for (UnitKind kind : new UnitKind[] {UnitKind.MARINE, UnitKind.ZERGLING, UnitKind.ZEALOT}) {
				var unit = StarMine.type(kind).create(level); unit.setOwner(player.getUUID()); unit.moveTo(-4.5 + i++ * 4, groundY, -4.5, 0, 0); unit.setNoAi(true); level.addFreshEntity(unit); audioUnits.add(unit.getId());
			}
		})));
		steps.add(new Step(30, () -> {
			CommandView.enter(mc); CommandView.hero = false; CommandView.selection.clear(); CommandView.lookAt(0, -8); CommandView.climb(16);
		}));
		steps.add(new Step(10, () -> {
			server(mc, (level, player) -> {
				var building = (StarUnit) level.getEntity(trainingBuilding);
				check("building: training progress synchronized", building.progress() > 0 && building.queueIds().contains("scv"), "production advances with no selection");
			});
			shot(mc, "building-progress");
			boolean all = java.util.Arrays.stream(UnitKind.values()).allMatch(kind -> mc.getSoundManager().getSoundEvent(dev.starmine.StarMine.id(kind.id + ".select")) != null
				&& mc.getSoundManager().getSoundEvent(dev.starmine.StarMine.id(kind.id + ".yes")) != null);
			check("audio: all unit command events loaded", all, "selection and order event for every UnitKind");
		}));
		for (int i = 0; i < 3; i++) {
			int index = i;
			steps.add(new Step(8, () -> {
				CommandView.selection.clear(); CommandView.selection.add(audioUnits.get(index)); RtsAudio.selection();
			}));
			steps.add(new Step(8, () -> {
				var unit = (StarUnit) mc.level.getEntity(audioUnits.get(index));
				check("audio " + unit.kind.race + ": music and selection", RtsAudio.music != null && mc.getSoundManager().isActive(RtsAudio.music)
					&& RtsAudio.music.getLocation().equals(StarMine.id("music.rts." + unit.kind.race.name().toLowerCase(java.util.Locale.ROOT)))
					&& RtsAudio.voice != null && mc.getSoundManager().isActive(RtsAudio.voice), "race track and local voice playing");
				CommandView.send("move", unit.position().add(1, 0, 0), -1, null);
			}));
			steps.add(new Step(2, () -> {
				var unit = (StarUnit) mc.level.getEntity(audioUnits.get(index));
				check("audio " + unit.kind.race + ": order voice", RtsAudio.voice != null && RtsAudio.voice.getLocation().equals(StarMine.id(unit.kind.id + ".yes"))
					&& mc.getSoundManager().isActive(RtsAudio.voice), "Yes command response");
			}));
		}
		steps.add(new Step(10, () -> mc.setScreen(null)));
		steps.add(new Step(5, () -> check("audio: music stops on RTS exit", RtsAudio.music == null, "normal Minecraft music resumes")));
	}

	/** Real player input and movement packets with a detached command camera, in both game modes. */
	private static void hero(Minecraft mc) {
		for (GameType mode : new GameType[] {GameType.CREATIVE, GameType.SURVIVAL}) {
			steps.add(new Step(30, () -> server(mc, (level, player) -> {
				player.setGameMode(mode);
				player.teleportTo(level, X + 0.5, groundY, Z + 0.5, 0, 0);
			})));
			steps.add(new Step(20, () -> {
				mc.options.guiScale().set(1);
				mc.resizeDisplay();
				if (!CommandView.active()) CommandView.enter(mc);
				CommandView.lookAt(X + 0.5, Z + 0.5);
				((CommandScreen) mc.screen).keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_F4, 0, 0);
			}));
			steps.add(new Step(80, () -> {
				CommandScreen screen = (CommandScreen) mc.screen;
				Vec3 goal = new Vec3(X + 6.5, groundY, Z + 0.5);
				double[] point = CommandView.project(goal, screen.width, screen.height);
				check("hero " + mode + ": F4 selection", CommandView.heroSelected(), "player body selected");
				if (point != null) screen.mouseClicked(point[0], point[1], org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT);
				check("hero " + mode + ": right click accepted", HeroPilot.busy(), "world point=" + java.util.Arrays.toString(point));
			}));
			steps.add(new Step(10, () -> {
				Vec3 client = mc.player.position();
				server(mc, (level, player) -> {
					Vec3 goal = new Vec3(X + 6.5, groundY, Z + 0.5);
					check("hero " + mode + ": right click moves on server", player.position().distanceToSqr(goal) < 2.5
						&& client.distanceToSqr(player.position()) < 1, "server=" + player.position() + ", client=" + client);
				});
				HeroPilot.move(new Vec3(X + 40.5, groundY, Z + 0.5));
			}));
			steps.add(new Step(20, () -> {
				((CommandScreen) mc.screen).keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_S, 0, 0);
			}));
			steps.add(new Step(5, () -> {
				check("hero " + mode + ": stop", !HeroPilot.busy() && CommandView.bodyHeld()
					&& mc.player.input instanceof net.minecraft.client.player.KeyboardInput, "body held, normal input restored");
				HeroPilot.move(new Vec3(X + 40.5, groundY, Z + 0.5));
				mc.setScreen(null);
			}));
			steps.add(new Step(10, () -> check("hero " + mode + ": exit releases control", !CommandView.active() && !HeroPilot.busy()
				&& mc.player.input instanceof net.minecraft.client.player.KeyboardInput, "no stale order after leaving RTS")));
		}
		steps.add(new Step(20, () -> server(mc, (level, player) -> {
			player.setGameMode(GameType.CREATIVE);
			player.getInventory().add(new net.minecraft.world.item.ItemStack(dev.starmine.colony.CommandPanel.ITEM));
		})));
	}

	private static volatile boolean portalChecksDone;
	private static final java.util.List<java.util.UUID> portalArmy = new java.util.ArrayList<>();
	private static ServerLevel portalArmyWorld;
	private static net.minecraft.core.BlockPos portalArmySite;

	/** Real dimensions for pair checks: the standard GameTest server does not load StarMine dimensions. */
	private static void portals(Minecraft mc) {
		server(mc, (level, player) -> {
			try {
				var empty = dev.starmine.world.VoidWorld.level(level.getServer());
				var nether = level.getServer().getLevel(net.minecraft.world.level.Level.NETHER);
				var end = level.getServer().getLevel(net.minecraft.world.level.Level.END);
				if (empty == null || nether == null || end == null) throw new IllegalStateException("missing dimensions");
				var c = new net.minecraft.core.BlockPos(20, groundY + 7, 20);
				level.setBlock(c, dev.starmine.world.VoidIslands.STONE.defaultBlockState(), 2);
				for (var dir : net.minecraft.core.Direction.values()) for (int n=1; n<=4; n++)
					level.setBlock(c.relative(dir,n), dev.starmine.world.VoidCraft.BLOCK.defaultBlockState(), 2);
				player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.FLINT_AND_STEEL));
				var hit = new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(c.east(4)), net.minecraft.core.Direction.EAST, c.east(4), false);
				var result = net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.invoker().interact(player,level,net.minecraft.world.InteractionHand.MAIN_HAND,hit);
				check("portal flint activation", result.consumesAction() && level.getBlockState(c).isAir() && player.getMainHandItem().getDamageValue()==1, "24 arms, diameter nine, one flint use");
				var a = level.getEntitiesOfClass(dev.starmine.world.VoidRift.class,new net.minecraft.world.phys.AABB(c).inflate(10),p->!p.invasion()).stream().findFirst().orElseThrow();
				var mapped = dev.starmine.world.VoidGate.target(level,empty,c);
				var b = empty.getEntitiesOfClass(dev.starmine.world.VoidRift.class,new net.minecraft.world.phys.AABB(mapped.atY(-64)).inflate(15,384,15),p->!p.invasion()).stream().findFirst().orElseThrow();
				check("portal first visit prepares islands",dev.starmine.world.VoidWorld.State.get(level.getServer()).islands,"crystal gate uses the shared Void initializer");
				check("portal scale and colours", mapped.getX()==c.getX()*16 && mapped.getZ()==c.getZ()*16 && a.color()!=b.color(), "16 Void blocks = one outside, different endpoint colours");
				Entity cow = net.minecraft.world.entity.EntityType.COW.create(level);
				cow.moveTo(0,groundY,0,0,0); level.addFreshEntity(cow);
				cow.moveTo(a.position());
				java.util.UUID cowId=cow.getUUID(); a.tick();
				Entity moved = empty.getEntity(cowId);
				check("portal neutral mob crosses",moved!=null && moved.level()==empty,"cow to the Void");
				if (moved!=null) {
					check("portal cooldown",b.travel(moved)==null,"no instant bounce");
					moved.setPortalCooldown(0);
					Entity back = b.travel(moved);
					check("portal return",back!=null && back.level()==level,"exact original endpoint");
					if(back!=null) back.discard(); else moved.discard();
				}
				StarUnit medivac=StarMine.type(UnitKind.MEDIVAC).create(level), marine=StarMine.type(UnitKind.MARINE).create(level);
				medivac.setOwner(player.getUUID()); marine.setOwner(player.getUUID());
				medivac.moveTo(0,groundY+4,0,0,0); marine.moveTo(0,groundY,0,0,0);
				level.addFreshEntity(medivac); level.addFreshEntity(marine); marine.startRiding(medivac,true);
				Entity ship=a.travel(medivac);
				check("portal transport passengers",ship!=null && ship.level()==empty && ship.getPassengers().size()==1 && ship.getPassengers().get(0).level()==empty,"transport and marine together");
				if(ship!=null) { java.util.List.copyOf(ship.getPassengers()).forEach(Entity::discard); ship.discard(); }
				else { marine.discard(); medivac.discard(); }
				var saved=new net.minecraft.nbt.CompoundTag(); b.addAdditionalSaveData(saved);
				b.discard(); a.kill();
				var loaded=dev.starmine.world.VoidForces.RIFT.create(empty);
				loaded.readAdditionalSaveData(saved); loaded.moveTo(b.position()); empty.addFreshEntity(loaded); loaded.tick();
				check("portal destruction survives reload",loaded.isRemoved(),"unloaded counterpart stays destroyed");
				var direct=dev.starmine.world.VoidGate.pair(level,new Vec3(40,groundY+2,20),nether,new Vec3(100.5,140,100.5),3,player.getUUID());
				check("portal direct worlds",direct!=null,"Overworld to Nether without intermediate Void");
				if(direct!=null) direct.kill();
				check("portal coordinates",dev.starmine.world.VoidGate.target(empty,end,new net.minecraft.core.BlockPos(-17,64,32)).equals(new net.minecraft.core.BlockPos(-2,0,2))
					&& dev.starmine.world.VoidGate.target(level,nether,new net.minecraft.core.BlockPos(160,64,-80)).equals(new net.minecraft.core.BlockPos(20,0,-10)),"negative rounding, direct Nether scale");
				var destinations=java.util.List.of(empty,nether,end,dev.starmine.world.Stratosphere.level(level.getServer()));
				for(int i=0;i<4;i++) {
					var to=destinations.get(i); var there=new Vec3(200.5+i*30,140,200.5);
					to.getChunkAt(net.minecraft.core.BlockPos.containing(there));
					var floor=net.minecraft.core.BlockPos.containing(there).below();
					for(var pos:net.minecraft.core.BlockPos.betweenClosed(floor.offset(-7,0,-7),floor.offset(7,0,7)))
						if(to.getBlockState(pos).isAir()) to.setBlock(pos,dev.starmine.world.VoidIslands.BRICKS.defaultBlockState(),2);
					dev.starmine.world.VoidGate.pair(level,new Vec3(-24+i*16,groundY,-16),to,there,3+i*2,player.getUUID());
				}
				portalArmyWorld = empty;
				portalArmySite = new net.minecraft.core.BlockPos(500,160,500);
				empty.setChunkForced(500 >> 4, 500 >> 4, true);
				for (var pos : net.minecraft.core.BlockPos.betweenClosed(portalArmySite.offset(-8,-1,-8),portalArmySite.offset(8,-1,8)))
					empty.setBlock(pos,dev.starmine.world.VoidIslands.BRICKS.defaultBlockState(),2);
				var armyGate=dev.starmine.world.VoidGate.pair(level,new Vec3(0.5,groundY,-45.5),empty,Vec3.atBottomCenterOf(portalArmySite),7,player.getUUID());
				var base=new dev.starmine.hive.Hive(java.util.UUID.randomUUID(),level.dimension().location().toString(),new net.minecraft.core.BlockPos(0,groundY,-25));
				base.owner=player.getUUID(); dev.starmine.hive.HiveSavedData.get(level.getServer()).add(base);
				player.getInventory().add(new net.minecraft.world.item.ItemStack(dev.starmine.colony.CommandPanel.ITEM));
				int[] ids=new int[5];
				for(int i=0;i<5;i++) {
					var unit=StarMine.type(i==4?UnitKind.BATTLECRUISER:UnitKind.MARINE).create(level);
					unit.joinHive(base); unit.moveTo(-6+i*3,groundY+(i==4?20:0),-28,0,0); level.addFreshEntity(unit);
					ids[i]=unit.getId(); portalArmy.add(unit.getUUID());
				}
				dev.starmine.colony.Commands.command(player,new dev.starmine.colony.ColonyNetwork.UnitCommand(ids,"portal",armyGate.getX(),armyGate.getY(),armyGate.getZ(),armyGate.getId(),""));
				check("portal army order accepted",java.util.Arrays.stream(ids).allMatch(id->((StarUnit)level.getEntity(id)).order()==StarUnit.Order.MOVE),"four marines and a battlecruiser");
				player.teleportTo(level,0,groundY+8,25,java.util.Set.of(),180,8);
			} catch(Exception e) { check("portal integration exception",false,e.toString()); LOG.error("portal checks",e); }
			finally { portalChecksDone=true; }
		});
		steps.add(new Step(20,()->awaitPortals(mc)));
		steps.add(new Step(300,()->{}));
		steps.add(new Step(20,()->server(mc,(level,player)->{
			long arrived=portalArmy.stream().filter(id->portalArmyWorld.getEntity(id) instanceof StarUnit).count();
			check("portal army walks and flies through",arrived==5,arrived+" of five arrived, including the descending battlecruiser");
			portalArmyWorld.setChunkForced(portalArmySite.getX()>>4,portalArmySite.getZ()>>4,false);
			player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
			player.getInventory().add(new net.minecraft.world.item.ItemStack(dev.starmine.world.VoidGate.SHARD,64));
			for(var block:dev.starmine.world.VoidCraft.BLOCKS.values()) player.getInventory().add(new net.minecraft.world.item.ItemStack(block,64));
		})));
		steps.add(new Step(20,()->shot(mc,"portals")));
	}
	private static void awaitPortals(Minecraft mc) {
		if(!portalChecksDone) steps.addFirst(new Step(20,()->awaitPortals(mc)));
	}

	/**
	 * Owner 19.10, team colours: rows of the wild sides, the player's own and Amun's, and the forms with models of their
	 * own (crawlers uprooted, oversight, surveillance, the overlord's sacs), shot from the front, twice a few frames apart
	 * (a mask fighting the model's depth flickers between the shots) and close. Shots only: the owner looks at them.
	 */
	private static void teams(Minecraft mc) {
		UnitKind[] row = {UnitKind.ZERGLING, UnitKind.HYDRALISK, UnitKind.ULTRALISK, UnitKind.MARINE, UnitKind.SIEGE_TANK, UnitKind.THOR,
			UnitKind.ZEALOT, UnitKind.STALKER, UnitKind.COLOSSUS};
		String[] sides = {"wild", "player", "amun"};
		for (int side = 0; side < sides.length; side++) {
			int s = side;
			lineUp(mc, row.length, 6, (level, player, i) -> {
				StarUnit unit = StarMine.type(row[i]).create(level);
				if (unit != null && s == 1) unit.setOwner(player.getUUID());
				if (unit != null && s == 2) unit.addTag(dev.starmine.world.VoidForces.TAG);
				return unit;
			});
			steps.add(new Step(3, () -> shot(mc, "teams-" + sides[s] + "-a")));
			steps.add(new Step(10, () -> shot(mc, "teams-" + sides[s] + "-b")));
		}
		Object[][] forms = {{UnitKind.SPINE_CRAWLER, dev.starmine.ability.Modes.NORMAL}, {UnitKind.SPINE_CRAWLER, dev.starmine.ability.Modes.UPROOTED},
			{UnitKind.SPORE_CRAWLER, dev.starmine.ability.Modes.UPROOTED}, {UnitKind.OVERSEER, dev.starmine.ability.Modes.NORMAL},
			{UnitKind.OVERSEER, dev.starmine.ability.Modes.OVERSIGHT}, {UnitKind.OBSERVER, dev.starmine.ability.Modes.SURVEILLANCE},
			{UnitKind.OVERLORD, dev.starmine.ability.Modes.SACS}, {UnitKind.NEXUS, dev.starmine.ability.Modes.NORMAL},
			{UnitKind.COMMAND_CENTER, dev.starmine.ability.Modes.NORMAL}};
		lineUp(mc, forms.length, 9, (level, player, i) -> {
			StarUnit unit = StarMine.type((UnitKind) forms[i][0]).create(level);
			if (unit != null) unit.setMode((int) forms[i][1]);
			return unit;
		});
		steps.add(new Step(3, () -> shot(mc, "teams-forms")));
	}

	private interface Spawn {
		StarUnit create(ServerLevel level, ServerPlayer player, int index);
	}

	/** Clears the field and stands `count` units in a row across the view, `gap` blocks apart, the player looking at them. */
	private static void lineUp(Minecraft mc, int count, int gap, Spawn spawn) {
		int z = Z + 18;
		steps.add(new Step(30, () -> server(mc, (level, player) -> {
			for (Entity old : level.getEntitiesOfClass(Entity.class, player.getBoundingBox().inflate(120), e -> !(e instanceof ServerPlayer))) old.discard();
			for (int i = 0; i < count; i++) {
				StarUnit unit = spawn.create(level, player, i);
				if (unit == null) continue;
				unit.moveTo(X + (i - (count - 1) / 2.0) * gap + 0.5, groundY, z + 0.5, 180, 0);
				unit.setNoAi(true);
				level.addFreshEntity(unit);
			}
			player.teleportTo(level, X + 0.5, groundY + 9, Z - 14.5, 0, 15);
		})));
		steps.add(new Step(80, () -> aim(mc, new Vec3(X, groundY + 1.5, z))));
	}

	/**
	 * Owner 19.10: the command view far from the body keeps the body (the hero) and the world round it; leaving it
	 * the world is drawn again round the player.
	 */
	private static void rts(Minecraft mc) {
		steps.add(new Step(20, () -> {
			CommandView.enter(mc);
		}));
		steps.add(new Step(40, () -> {
			shot(mc, "rts-hero");
			check("rts: the body's chunk is here while the view looks at it", mc.level.getChunkSource().hasChunk(mc.player.chunkPosition().x, mc.player.chunkPosition().z), "");
			CommandView.lookAt(X + 400.5, Z + 0.5);
		}));
		steps.add(new Step(200, () -> {
			CommandView.lookAt(mc.player.getX(), mc.player.getZ());
		}));
		steps.add(new Step(60, () -> shot(mc, "rts-back-over-body")));
		steps.add(new Step(10, () -> mc.setScreen(null)));
		steps.add(new Step(100, () -> {
		}));
		steps.add(new Step(200, () -> {
			int x = mc.player.chunkPosition().x, z = mc.player.chunkPosition().z, missing = 0;
			for (int dx = -4; dx <= 4; dx++)
				for (int dz = -4; dz <= 4; dz++) if (!mc.level.getChunkSource().hasChunk(x + dx, z + dz)) missing++;
			String stats = mc.levelRenderer.getSectionStatistics();
			int drawn = Integer.parseInt(stats.replaceAll("^C: ([0-9]+)/.*$", "$1"));
			check("rts: after leaving the view the world is drawn", drawn > 0, stats);
			check("rts: after leaving the view the world round the player is here", missing == 0, missing + " of 81 chunks missing; "
				+ mc.levelRenderer.getSectionStatistics());
			shot(mc, "rts-after");
		}));
	}

	/** The sheriff's stone house (dev.starmine.world.SheriffHouses), its door toward the camera, from both sides. */
	private static void sheriff(Minecraft mc) {
		int hx = X + 14, hz = Z;
		steps.add(new Step(40, () -> server(mc, (level, player) -> {
			for (Entity old : level.getEntitiesOfClass(Entity.class, player.getBoundingBox().inflate(60), e -> !(e instanceof ServerPlayer))) old.discard();
			dev.starmine.world.SheriffHouses.build(level, new net.minecraft.core.BlockPos(hx, groundY, hz), net.minecraft.core.Direction.WEST,
				net.minecraft.util.RandomSource.create(3));
			player.teleportTo(level, hx - 9.5, groundY, hz + 4.5, 0, 0);
		})));
		steps.add(new Step(60, () -> aim(mc, new Vec3(hx, groundY + 2, hz))));
		steps.add(new Step(20, () -> shot(mc, "sheriff-front")));
		steps.add(new Step(10, () -> server(mc, (level, player) -> player.teleportTo(level, hx + 9.5, groundY + 3, hz - 6.5, 0, 0))));
		steps.add(new Step(40, () -> aim(mc, new Vec3(hx, groundY + 1, hz))));
		steps.add(new Step(20, () -> {
			shot(mc, "sheriff-back");
			check("sheriff: the sheriff stands by his house", mc.level != null && !mc.level.getEntitiesOfClass(dev.starmine.gear.Townsfolk.class,
				new net.minecraft.world.phys.AABB(hx - 16, groundY - 4, hz - 16, hx + 16, groundY + 8, hz + 16)).isEmpty(), "");
		}));
	}

	/** Every kind's command card as the player sees it (one of the player's own, selected), to `harness/cards.tsv`. */
	private static void cards(Minecraft mc) {
		StringBuilder table = new StringBuilder("kind\tslot\tbutton\tkey\n");
		StarUnit[] shown = new StarUnit[1];
		steps.add(new Step(10, () -> CommandView.enter(mc)));
		for (UnitKind kind : UnitKind.values()) {
			steps.add(new Step(8, () -> server(mc, (level, player) -> {
				StarUnit unit = StarMine.type(kind).create(level);
				if (unit == null) return;
				unit.moveTo(X + 6.5, groundY, Z + 0.5, 0, 0);
				unit.setNoAi(true);
				unit.setOwner(player.getUUID());
				level.addFreshEntity(unit);
				shown[0] = unit;
			})));
			steps.add(new Step(8, () -> {
				if (shown[0] == null) return;
				CommandView.selection.clear();
				CommandView.selection.add(shown[0].getId());
			}));
			steps.add(new Step(4, () -> {
				if (mc.screen instanceof CommandScreen screen)
					for (String line : screen.card().split("\n")) if (!line.isEmpty()) table.append(kind.name()).append('\t').append(line).append('\n');
				StarUnit unit = shown[0];
				shown[0] = null;
				if (unit != null) server(mc, (level, player) -> {
					var gone = level.getEntity(unit.getUUID());
					if (gone != null) gone.discard();
				});
			}));
		}
		steps.add(new Step(5, () -> {
			write(mc, "cards.tsv", table.toString());
			check("cards: every kind's card dumped", table.length() > 100, table.length() + " chars");
		}));
	}

	/** A barracks with its tech lab beside it, and a reactor left on the ground by a building that lifted off. */
	private static void addOn(Minecraft mc) {
		int bx = X + 20, bz = Z;
		steps.add(new Step(40, () -> server(mc, (level, player) -> {
			for (Entity old : level.getEntitiesOfClass(StarUnit.class, player.getBoundingBox().inflate(60))) old.discard();
			StarUnit barracks = StarMine.type(UnitKind.BARRACKS).create(level);
			barracks.moveTo(bx + 0.5, groundY, bz + 0.5, 0, 0);
			barracks.setNoAi(true);
			level.addFreshEntity(barracks);
			barracks.setMode(dev.starmine.ability.Modes.TECHLAB);
			player.teleportTo(level, bx - 2.5, groundY + 22, bz + 34.5, 180, 30);
		})));
		steps.add(new Step(60, () -> {
			AddOnRenderer.sites = new int[] {bx - 18, groundY, bz, dev.starmine.ability.Modes.REACTOR};
			aim(mc, new Vec3(bx - 4, groundY, bz));
			check("addon: tech lab and reactor models are loaded", StarMineModels.get("techlab") != null && StarMineModels.get("reactor") != null, "");
		}));
		steps.add(new Step(20, () -> shot(mc, "addon")));
		steps.add(new Step(10, () -> AddOnRenderer.sites = new int[0]));
	}

	private static void cutaway(Minecraft mc) {
		int hillX = 13, hillZ = 0;
		steps.add(new Step(40, () -> server(mc, (level, player) -> {
			for (Entity old : level.getEntitiesOfClass(StarUnit.class, player.getBoundingBox().inflate(40))) old.discard();
			int y = groundY;
			for (net.minecraft.core.BlockPos pos : net.minecraft.core.BlockPos.betweenClosed(hillX - 7, y, hillZ - 7, hillX + 7, y + 7, hillZ + 7))
				level.setBlock(pos, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 2);
			for (net.minecraft.core.BlockPos pos : net.minecraft.core.BlockPos.betweenClosed(hillX - 7, y, hillZ - 1, hillX + 7, y + 2, hillZ + 1))
				level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
			StarUnit marine = StarMine.type(UnitKind.MARINE).create(level);
			marine.moveTo(hillX + 0.5, y, hillZ + 0.5, 90, 0);
			marine.setNoAi(true);
			level.addFreshEntity(marine);
			current = marine;
			// the player stands in the tunnel: their eyes explore it (blocks nobody has seen are not drawn, BlockFog)
			player.teleportTo(level, hillX - 4 + 0.5, y, hillZ + 0.5, -90, 0);
		})));
		steps.add(new Step(40, () -> {
			CommandView.enter(mc);
			CommandView.lookAt(hillX + 0.5, hillZ + 0.5);
		}));
		int[] gui = new int[2];
		steps.add(new Step(5, () -> {
			gui[0] = mc.getWindow().getGuiScaledWidth();
			gui[1] = mc.getWindow().getGuiScaledHeight();
			var hit = CommandView.block(gui[0] / 2.0, gui[1] / 2.0, gui[0], gui[1]);
			check("cutaway: the hilltop is under the middle at first", hit != null && hit.getBlockPos().getY() == groundY + 7,
				"hit " + (hit == null ? "nothing" : hit.getBlockPos().toShortString()));
			shot(mc, "cutaway-before");
			// down into the tunnel (under its roof, over its floor)
			for (int i = 0; i < 12 && net.minecraft.util.Mth.floor(CommandView.height) - CommandView.cutDepth >= groundY + 3; i++) CommandView.cutLevel(1);
		}));
		steps.add(new Step(60, () -> {
			int top = BlockFog.cutTop();
			var hit = CommandView.block(gui[0] / 2.0, gui[1] / 2.0, gui[0], gui[1]);
			check("cutaway: the level is cut through the hill", top < groundY + 3 && top >= groundY, "cut above y " + top + ", ground " + groundY);
			check("cutaway: a click goes through the cut hill to the tunnel's floor", hit != null && hit.getBlockPos().getY() <= top && hit.getBlockPos().getY() < groundY + 3,
				"hit " + (hit == null ? "nothing" : hit.getBlockPos().toShortString()));
			check("cutaway: the hilltop is not drawn", BlockFog.hidden(hillX, groundY + 7, hillZ + 4), "y " + (groundY + 7));
			int light = net.minecraft.client.renderer.LevelRenderer.getLightColor(mc.level, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
				new net.minecraft.core.BlockPos(hillX, top + 1, hillZ + 4));
			check("cutaway: the cut is lit", light == net.minecraft.client.renderer.LightTexture.FULL_BRIGHT, "light " + Integer.toHexString(light));
			shot(mc, "cutaway-level");
		}));
		steps.add(new Step(100, () -> {
			shot(mc, "cutaway-level-late");
			for (int i = 0; i < 40; i++) CommandView.cutLevel(-1); // up past the camera: off
			CommandView.cutNear(1);
			CommandView.cutNear(1);
		}));
		steps.add(new Step(60, () -> {
			check("cutaway: raising the level past the camera turns it off", CommandView.cutDepth < 0 && BlockFog.cutTop() == Integer.MAX_VALUE,
				"depth " + CommandView.cutDepth);
			var hit = CommandView.block(gui[0] / 2.0, gui[1] / 2.0, gui[0], gui[1]);
			check("cutaway: the radius round the camera cuts into the hill", CommandView.cutRadius > 0 && hit != null && hit.getBlockPos().getY() < groundY + 7, // under the hilltop
				"radius " + CommandView.cutRadius + ", hit " + (hit == null ? "nothing" : hit.getBlockPos().toShortString()));
			shot(mc, "cutaway-near");
			mc.setScreen(null); // leaves the view
		}));
		steps.add(new Step(10, () -> check("cutaway: leaving the view puts the land back", CommandView.cutRadius == 0 && CommandView.cutDepth < 0
			&& !BlockFog.clipped(hillX, groundY + 7, hillZ), "radius " + CommandView.cutRadius + ", depth " + CommandView.cutDepth)));
	}

	/** The model of a kind as drawn: its size at rest (the exporter's header, posed) times the renderer's scale. */
	private static Extent extent(UnitKind kind) {
		SkinnedModel model = StarMineModels.get(UnitRenderer.shown(kind).id);
		if (model == null || model.size[1] <= 0) return null;
		double scale = kind.modelScale();
		return new Extent(Math.max(model.size[0], model.size[2]) / 2 * scale, model.size[1] * scale);
	}

	private static void hitbox(Minecraft mc) {
		StringBuilder table = new StringBuilder("kind\tmodelHalf\tmodelHeight\tboxHalf\tboxHeight\thalfRatio\theightRatio\n");
		for (UnitKind kind : UnitKind.values()) {
			Extent e = extent(kind);
			if (e == null) continue;
			table.append(String.format(java.util.Locale.ROOT, "%s\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f%n", kind.id, e.half, e.height, kind.width / 2.0,
				kind.height, e.half / (kind.width / 2.0), e.height / kind.height));
		}
		write(mc, "models.tsv", table.toString());
		for (UnitKind kind : UnitKind.values()) {
			Extent e = extent(kind);
			if (e == null || kind.flying || kind.structure() || kind == UnitKind.EGG) continue;
			double distance = e.half + 2.0; // the model's front 2 blocks from the player's feet
			Vec3 at = new Vec3(X + 0.5, 0, Z + 0.5 + distance);
			steps.add(new Step(10, () -> server(mc, (level, player) -> {
				for (Entity old : level.getEntitiesOfClass(StarUnit.class, player.getBoundingBox().inflate(24))) old.discard();
				StarUnit unit = StarMine.type(kind).create(level);
				unit.moveTo(at.x, groundY, at.z, 180, 0);
				unit.setNoAi(true);
				unit.setYHeadRot(180);
				unit.yBodyRot = 180;
				level.addFreshEntity(unit);
				current = unit;
			})));
			double[][] aims = {{0, 0.5}, {0, 0.8}, {0.6, 0.5}};
			String[] names = {"middle", "top", "side"};
			for (int a = 0; a < aims.length; a++) {
				double side = aims[a][0] * e.half, up = Math.min(aims[a][1] * e.height, 3.0); // a player swings at what is within arm's reach (a colossus's body hangs 10 blocks up)
				String name = names[a];
				steps.add(new Step(3, () -> aim(mc, new Vec3(at.x + side, groundY + up, at.z))));
				steps.add(new Step(1, () -> {
					Entity unit = current;
					boolean hit = mc.hitResult instanceof EntityHitResult result && unit != null && result.getEntity().getId() == unit.getId();
					check(kind.id + " hitbox " + name, hit, String.format(java.util.Locale.ROOT, "model half %.2f h %.2f, box half %.2f h %.2f",
						e.half, e.height, kind.width / 2.0, kind.height));
					if (!hit) shot(mc, kind.id + "-" + name);
					if (hit && name.equals("middle") && mc.gameMode != null) mc.gameMode.attack(mc.player, ((EntityHitResult) mc.hitResult).getEntity());
				}));
				if (name.equals("middle")) steps.add(new Step(3, () -> server(mc, (level, player) -> {
					Entity unit = current;
					boolean landed = unit instanceof StarUnit star && star.getLastHurtByMob() == player;
					// reported on the client thread through lines, so order stays readable
					mc.execute(() -> check(kind.id + " swing lands", landed, "the server " + (landed ? "took" : "refused") + " the hit"));
				})));
			}
		}
	}

	/**
	 * The lowest point of a kind's drawn model in its stand pose, in blocks above its feet (CPU skinning of the
	 * stand clip's first frame, as SkinnedModelDrawer skins it, times the renderer's scale). Above 0 the model floats.
	 */
	private static double standingBottom(UnitKind kind) {
		SkinnedModel model = StarMineModels.get(UnitRenderer.shown(kind).id);
		if (model == null || model.clips.isEmpty()) return Double.NaN;
		SkinnedModel.Clip stand = model.clips.getOrDefault("stand", model.clips.values().iterator().next());
		float[] palette = new float[model.slots * 12];
		model.pose(stand, 0, palette);
		double lowest = Double.POSITIVE_INFINITY;
		for (int v = 0; v < model.vertexCount; v++) {
			double x = model.positions[v * 3], y = model.positions[v * 3 + 1], z = model.positions[v * 3 + 2], at = 0, weight = 0;
			for (int k = 0; k < 4; k++) {
				float w = model.weights[v * 4 + k];
				if (w <= 0) continue;
				int s = (model.joints[v * 4 + k] & 0xFF) * 12;
				at += w * (palette[s + 4] * x + palette[s + 5] * y + palette[s + 6] * z + palette[s + 7]);
				weight += w;
			}
			if (weight > 0) lowest = Math.min(lowest, at / weight);
		}
		return lowest * kind.modelScale();
	}

	/**
	 * Scenario `ground` (owner 18.10: «проверь что все юниты ходят по земле, а не парят над ней»): every ground unit
	 * is spawned with its AI on, stands for two seconds, and must have its feet on the ground and its drawn model's
	 * lowest point (stand pose) within 0.2 blocks of them.
	 */
	private static void ground(Minecraft mc) {
		StringBuilder table = new StringBuilder("kind\tstandingBottom\n");
		for (UnitKind kind : UnitKind.values()) {
			if (kind.flying || kind.structure() || kind == UnitKind.EGG) continue;
			double bottom = standingBottom(kind);
			if (Double.isNaN(bottom)) continue;
			table.append(String.format(java.util.Locale.ROOT, "%s\t%.2f%n", kind.id, bottom));
			Vec3 at = new Vec3(X + 0.5, 0, Z + 6.5);
			steps.add(new Step(40, () -> server(mc, (level, player) -> {
				for (Entity old : level.getEntitiesOfClass(StarUnit.class, player.getBoundingBox().inflate(24))) old.discard();
				StarUnit unit = StarMine.type(kind).create(level);
				unit.moveTo(at.x, groundY + 1, at.z, 90, 0);
				level.addFreshEntity(unit);
				current = unit;
			})));
			steps.add(new Step(1, () -> aim(mc, new Vec3(at.x, groundY + 0.5, at.z))));
			steps.add(new Step(2, () -> server(mc, (level, player) -> {
				Entity unit = current;
				double feet = unit == null ? Double.NaN : unit.getY() - groundY;
				mc.execute(() -> {
					check(kind.id + " feet on the ground", Math.abs(feet) < 0.1, String.format(java.util.Locale.ROOT, "feet %.2f above the ground", feet));
					// models stand on their SC2 origin: a few float in StarCraft II itself (a probe, a sentry, a high templar, an
					// archon, a disruptor, a drone), and feet may dip into the ground as SC2 draws them; a model higher than
					// that, or lower than a block, is misplaced
					boolean hovers = kind == UnitKind.PROBE || kind == UnitKind.SENTRY || kind == UnitKind.HIGH_TEMPLAR || kind == UnitKind.ARCHON
						|| kind == UnitKind.DISRUPTOR || kind == UnitKind.DRONE;
					boolean drawn = bottom >= -1.0 && (bottom <= 0.2 || hovers && bottom <= 1.2);
					check(kind.id + " model on its feet", drawn, String.format(java.util.Locale.ROOT, "model bottom %.2f above its feet", bottom));
					if (!drawn || Math.abs(feet) >= 0.1) shot(mc, kind.id + "-ground");
				});
			})));
		}
		write(mc, "ground.tsv", table.toString());
	}

	/** Turns the player's eyes on `target` (the client's camera: the pick follows it next tick). */
	private static void aim(Minecraft mc, Vec3 target) {
		Vec3 eye = mc.player.getEyePosition();
		Vec3 d = target.subtract(eye);
		float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z)), pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
		mc.player.setYRot(yaw);
		mc.player.setXRot(pitch);
		mc.player.yRotO = yaw;
		mc.player.xRotO = pitch;
	}

	private static void server(Minecraft mc, java.util.function.BiConsumer<ServerLevel, ServerPlayer> action) {
		var server = mc.getSingleplayerServer();
		server.execute(() -> {
			ServerPlayer player = server.getPlayerList().getPlayers().get(0);
			action.accept(player.serverLevel(), player);
		});
	}

	private static void check(String name, boolean passed, String detail) {
		if (passed) ok++;
		else fail++;
		String line = "[harness] " + name + ": " + (passed ? "OK" : "FAIL") + " (" + detail + ")";
		lines.add(line);
		LOG.info(line);
	}

	private static void shot(Minecraft mc, String name) {
		Screenshot.grab(mc.gameDirectory, "harness-" + name + ".png", mc.getMainRenderTarget(), message -> {
		});
	}

	private static void write(Minecraft mc, String file, String text) {
		try {
			File dir = new File(mc.gameDirectory, "harness");
			Files.createDirectories(dir.toPath());
			Files.writeString(new File(dir, file).toPath(), text, StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOG.error("[harness] cannot write " + file, e);
		}
	}

	private static void finish(Minecraft mc) {
		finished = true;
		String summary = "[harness] done: " + ok + " OK, " + fail + " FAIL";
		lines.add(summary);
		LOG.info(summary);
		new File(mc.gameDirectory, "harness").mkdirs();
		try (PrintWriter out = new PrintWriter(new File(mc.gameDirectory, "harness/results.txt"), StandardCharsets.UTF_8)) {
			lines.forEach(out::println);
		} catch (IOException e) {
			LOG.error("[harness] cannot write results", e);
		}
		if (!Boolean.getBoolean("starmine.harness.keepOpen")) mc.stop();
	}
}
