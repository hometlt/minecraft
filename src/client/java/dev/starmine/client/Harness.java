package dev.starmine.client;

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
	private static boolean worldAsked, started;
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
		if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) {
			if (!worldAsked && (mc.screen instanceof TitleScreen || mc.screen instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen)) {
				worldAsked = true;
				createWorld(mc);
			}
			return;
		}
		// a harness client runs in the background: never paused by a lost focus or a stray menu
		mc.options.pauseOnLostFocus = false;
		if (mc.screen != null && !(mc.screen instanceof CommandScreen)) mc.setScreen(null);
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
		rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
		rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
		rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
		LevelSettings settings = new LevelSettings(name, GameType.CREATIVE, false, Difficulty.NORMAL, true, rules, WorldDataConfiguration.DEFAULT);
		mc.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(18102026L, false, false),
			registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), mc.screen);
	}

	private static void plan(Minecraft mc) {
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
		if (SCENARIOS.equals("all") || SCENARIOS.contains("ground")) ground(mc);
		if (SCENARIOS.equals("all") || SCENARIOS.contains("cutaway")) cutaway(mc);
		if (SCENARIOS.equals("all") || SCENARIOS.contains("addon")) addOn(mc);
		if (SCENARIOS.contains("cards")) cards(mc);
		if (SCENARIOS.equals("all") || SCENARIOS.contains("sheriff")) sheriff(mc);
		if (SCENARIOS.equals("all") || SCENARIOS.contains("rts")) rts(mc);
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
		String summary = "[harness] done: " + ok + " OK, " + fail + " FAIL";
		lines.add(summary);
		LOG.info(summary);
		new File(mc.gameDirectory, "harness").mkdirs();
		try (PrintWriter out = new PrintWriter(new File(mc.gameDirectory, "harness/results.txt"), StandardCharsets.UTF_8)) {
			lines.forEach(out::println);
		} catch (IOException e) {
			LOG.error("[harness] cannot write results", e);
		}
		mc.stop();
	}
}
