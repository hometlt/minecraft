package dev.starmine.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.starmine.StarMine;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * StarMine's own stand-in models (owner 17.10: decent models for units and buildings whose SC2 assets are not
 * imported, instead of an outline box). Each kind is put together from coloured boxes by its shape — a biped
 * soldier, a zerg beast, a zerg flyer, a terran vehicle, a ship, a protoss walker, an orb, an egg, and a building of
 * its race — sized to the kind's width and height, in its race's colours (zerg flesh and carapace, terran steel
 * and blue, protoss gold, white and glowing blue), with glowing eyes, crystals and engines. Legs swing as it walks,
 * wings beat, a turret turns to where it looks.
 */
final class FallbackModels {
	private static final ResourceLocation WHITE = StarMine.id("textures/entity/fallback.png");
	private static final Map<UnitKind, List<Part>> CACHE = new EnumMap<>(UnitKind.class);

	/** What moves a part: nothing, a leg (pairs swing opposite), a wing (beats), the turret (turns), the head. */
	enum Move { NONE, LEG_A, LEG_B, WING_L, WING_R, TURRET, HEAD, SPIN }

	/** A box from (x0, y0, z0) to (x1, y1, z1), blocks, the unit's feet at the origin and its front towards +z. */
	record Part(float x0, float y0, float z0, float x1, float y1, float z1, int colour, boolean glow, Move move, float px, float py, float pz) {
	}

	private FallbackModels() {
	}

	/** The shape a kind is drawn as. */
	enum Shape { BIPED, BEAST, SERPENT, BALL, ZERG_FLYER, OVERLORD, VEHICLE, TANK, MECH, SHIP, PROTOSS_WALKER, ORB, HOVER, EGG, LARVA,
		ZERG_BUILDING, CRAWLER, TERRAN_BUILDING, DEPOT, TURRET, PROTOSS_BUILDING, PYLON, CANNON, PLATFORM }

	static Shape shape(UnitKind kind) {
		return switch (kind) {
			case MARINE, MARAUDER, REAPER, GHOST, SCV, ZEALOT, ADEPT, HIGH_TEMPLAR, DARK_TEMPLAR, QUEEN, KERRIGAN, RAYNOR, ARTANIS, SWANN, KARAX, HELLBAT, VIKING_ASSAULT -> Shape.BIPED;
			case ZERGLING, ROACH, RAVAGER, LURKER, ULTRALISK, SWARM_HOST, INFESTOR, BROODLING, LOCUST, DRONE, CHANGELING, ABATHUR -> Shape.BEAST;
			case HYDRALISK -> Shape.SERPENT;
			case BANELING -> Shape.BALL;
			case MUTALISK, CORRUPTOR, BROOD_LORD, VIPER -> Shape.ZERG_FLYER;
			case OVERLORD, OVERSEER -> Shape.OVERLORD;
			case HELLION, WIDOW_MINE, CYCLONE -> Shape.VEHICLE;
			case SIEGE_TANK -> Shape.TANK;
			case THOR -> Shape.MECH;
			case VIKING, MEDIVAC, BANSHEE, LIBERATOR, RAVEN, BATTLECRUISER, PHOENIX, VOID_RAY, ORACLE, TEMPEST, CARRIER, INTERCEPTOR, MOTHERSHIP, WARP_PRISM -> Shape.SHIP;
			case STALKER, IMMORTAL, COLOSSUS -> Shape.PROTOSS_WALKER;
			case ARCHON, DISRUPTOR -> Shape.ORB;
			case PROBE, SENTRY, OBSERVER -> Shape.HOVER;
			case EGG -> Shape.EGG;
			case LARVA -> Shape.LARVA;
			case SPINE_CRAWLER, SPORE_CRAWLER -> Shape.CRAWLER;
			case SUPPLY_DEPOT, BUNKER -> Shape.DEPOT;
			case MISSILE_TURRET, AUTO_TURRET, SENSOR_TOWER -> Shape.TURRET;
			case PYLON -> Shape.PYLON;
			case PHOTON_CANNON, SHIELD_BATTERY -> Shape.CANNON;
			case ZERG_PLATFORM, TERRAN_PLATFORM, PROTOSS_PLATFORM -> Shape.PLATFORM;
			default -> !kind.structure() ? Shape.BIPED : switch (kind.race) {
				case ZERG -> Shape.ZERG_BUILDING;
				case TERRAN -> Shape.TERRAN_BUILDING;
				case PROTOSS -> Shape.PROTOSS_BUILDING;
			};
		};
	}

	/** A race's colours: main, second, accent, glow. */
	private static int[] colours(UnitKind kind) {
		return switch (kind.race) {
			case ZERG -> new int[] {0x5C3A6A, 0x3A2630, 0xB8708C, 0xFF9030};
			case TERRAN -> new int[] {0x6E7682, 0x3C4654, kind == UnitKind.RAYNOR || kind == UnitKind.MARAUDER ? 0xB84030 : 0x3C6EB4, 0x70C8FF};
			case PROTOSS -> new int[] {kind == UnitKind.DARK_TEMPLAR ? 0x4A3A60 : 0xD8B048, kind == UnitKind.DARK_TEMPLAR ? 0x2A2238 : 0xE8E2D2, 0x6A5030, 0x40C8FF};
		};
	}

	/** Draws a unit's stand-in; the pose is at its feet, turned its way. */
	static void draw(StarUnit unit, float partial, PoseStack pose, MultiBufferSource buffers, int light, int overlay, int alpha) {
		List<Part> parts = CACHE.computeIfAbsent(unit.kind, FallbackModels::build);
		float walk = unit.walkAnimation.position(partial), speed = Math.min(1f, unit.walkAnimation.speed(partial) * 2.5f);
		float age = unit.tickCount + partial;
		float swing = Mth.sin(walk * 0.9f) * 0.7f * speed;
		float flap = Mth.sin(age * 0.35f) * 0.55f;
		float look = Mth.wrapDegrees(Mth.rotLerp(partial, unit.yHeadRotO, unit.yHeadRot) - Mth.rotLerp(partial, unit.yBodyRotO, unit.yBodyRot));
		VertexConsumer buffer = buffers.getBuffer(alpha < 255 ? RenderType.entityTranslucent(WHITE) : RenderType.entityCutoutNoCull(WHITE));
		for (Part part : parts) {
			pose.pushPose();
			switch (part.move()) {
				case LEG_A, LEG_B -> rotate(pose, part, Axis.XP.rotation(part.move() == Move.LEG_A ? swing : -swing));
				case WING_L -> rotate(pose, part, Axis.ZP.rotation(flap));
				case WING_R -> rotate(pose, part, Axis.ZP.rotation(-flap));
				case TURRET, HEAD -> rotate(pose, part, Axis.YP.rotationDegrees(-Mth.clamp(look, part.move() == Move.HEAD ? -50 : -180, part.move() == Move.HEAD ? 50 : 180)));
				case SPIN -> rotate(pose, part, Axis.YP.rotation(age * 0.05f));
				default -> {
				}
			}
			box(buffer, pose.last(), part, part.glow() ? LightTexture.FULL_BRIGHT : light, overlay, alpha);
			pose.popPose();
		}
	}

	private static void rotate(PoseStack pose, Part part, org.joml.Quaternionf turn) {
		pose.translate(part.px(), part.py(), part.pz());
		pose.mulPose(turn);
		pose.translate(-part.px(), -part.py(), -part.pz());
	}

	private static void box(VertexConsumer buffer, PoseStack.Pose pose, Part p, int light, int overlay, int alpha) {
		int r = p.colour() >> 16 & 255, g = p.colour() >> 8 & 255, b = p.colour() & 255;
		float x0 = p.x0(), y0 = p.y0(), z0 = p.z0(), x1 = p.x1(), y1 = p.y1(), z1 = p.z1();
		face(buffer, pose, r, g, b, alpha, light, overlay, 0, 1, 0, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
		face(buffer, pose, r, g, b, alpha, light, overlay, 0, -1, 0, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
		face(buffer, pose, r, g, b, alpha, light, overlay, 0, 0, 1, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
		face(buffer, pose, r, g, b, alpha, light, overlay, 0, 0, -1, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0);
		face(buffer, pose, r, g, b, alpha, light, overlay, 1, 0, 0, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1);
		face(buffer, pose, r, g, b, alpha, light, overlay, -1, 0, 0, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
	}

	private static void face(VertexConsumer buffer, PoseStack.Pose pose, int r, int g, int b, int a, int light, int overlay, float nx, float ny, float nz,
			float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz, float dx, float dy, float dz) {
		buffer.addVertex(pose, ax, ay, az).setColor(r, g, b, a).setUv(0, 0).setOverlay(overlay).setLight(light).setNormal(pose, nx, ny, nz);
		buffer.addVertex(pose, bx, by, bz).setColor(r, g, b, a).setUv(0, 1).setOverlay(overlay).setLight(light).setNormal(pose, nx, ny, nz);
		buffer.addVertex(pose, cx, cy, cz).setColor(r, g, b, a).setUv(1, 1).setOverlay(overlay).setLight(light).setNormal(pose, nx, ny, nz);
		buffer.addVertex(pose, dx, dy, dz).setColor(r, g, b, a).setUv(1, 0).setOverlay(overlay).setLight(light).setNormal(pose, nx, ny, nz);
	}

	// ---- building the shapes ----

	/** Collects parts in a box of the kind's width W and height H. */
	private static final class Kit {
		final List<Part> parts = new ArrayList<>();
		final float w, h;

		Kit(UnitKind kind) {
			w = kind.width;
			h = kind.height;
		}

		/** A box in fractions of the width (x, z: -0.5..0.5) and height (y: 0..1). */
		Kit add(float x0, float y0, float z0, float x1, float y1, float z1, int colour) {
			return add(x0, y0, z0, x1, y1, z1, colour, false, Move.NONE, 0, 0, 0);
		}

		Kit glow(float x0, float y0, float z0, float x1, float y1, float z1, int colour) {
			return add(x0, y0, z0, x1, y1, z1, colour, true, Move.NONE, 0, 0, 0);
		}

		Kit add(float x0, float y0, float z0, float x1, float y1, float z1, int colour, boolean glow, Move move, float px, float py, float pz) {
			parts.add(new Part(x0 * w, y0 * h, z0 * w, x1 * w, y1 * h, z1 * w, colour, glow, move, px * w, py * h, pz * w));
			return this;
		}

		Kit moving(float x0, float y0, float z0, float x1, float y1, float z1, int colour, Move move, float px, float py, float pz) {
			return add(x0, y0, z0, x1, y1, z1, colour, false, move, px, py, pz);
		}
	}

	static List<Part> build(UnitKind kind) {
		Kit k = new Kit(kind);
		int[] c = colours(kind);
		int main = c[0], second = c[1], accent = c[2], glow = c[3];
		switch (shape(kind)) {
			case BIPED -> {
				boolean heavy = kind == UnitKind.MARAUDER || kind == UnitKind.HELLBAT || kind == UnitKind.VIKING_ASSAULT || kind == UnitKind.RAYNOR;
				float body = heavy ? 0.42f : 0.32f;
				k.moving(-0.2f, 0, -0.1f, -0.03f, 0.45f, 0.1f, second, Move.LEG_A, 0, 0.45f, 0);
				k.moving(0.03f, 0, -0.1f, 0.2f, 0.45f, 0.1f, second, Move.LEG_B, 0, 0.45f, 0);
				k.add(-body, 0.43f, -0.16f, body, 0.8f, 0.16f, main);
				k.add(-body - 0.06f, 0.68f, -0.18f, body + 0.06f, 0.82f, 0.18f, accent); // shoulders
				k.moving(-0.13f, 0.8f, -0.13f, 0.13f, 1f, 0.13f, main, Move.HEAD, 0, 0.8f, 0);
				k.add(-0.1f, 0.88f, 0.12f, 0.1f, 0.93f, 0.14f, glow, true, Move.HEAD, 0, 0.8f, 0); // a visor or eyes
				k.moving(-body - 0.12f, 0.42f, -0.08f, -body, 0.78f, 0.08f, main, Move.LEG_B, 0, 0.75f, 0);
				k.moving(body, 0.42f, -0.08f, body + 0.12f, 0.78f, 0.08f, main, Move.LEG_A, 0, 0.75f, 0);
				if (kind.race == UnitKind.Race.TERRAN && kind != UnitKind.SCV) k.add(body - 0.02f, 0.5f, 0.05f, body + 0.14f, 0.62f, 0.5f, 0x2A2E34); // the gun
				if (kind == UnitKind.SCV) k.add(-0.12f, 0.5f, 0.15f, 0.12f, 0.6f, 0.45f, 0xD0A030); // the welder
				if (kind == UnitKind.ZEALOT || kind == UnitKind.DARK_TEMPLAR || kind == UnitKind.ARTANIS) { // psi blades
					k.glow(-body - 0.1f, 0.45f, 0.08f, -body - 0.04f, 0.5f, 0.55f, kind == UnitKind.DARK_TEMPLAR ? 0x60FF60 : glow);
					k.glow(body + 0.04f, 0.45f, 0.08f, body + 0.1f, 0.5f, 0.55f, kind == UnitKind.DARK_TEMPLAR ? 0x60FF60 : glow);
				}
				if (kind == UnitKind.QUEEN || kind == UnitKind.KERRIGAN) { // carapace spikes on the back
					k.add(-0.3f, 0.75f, -0.3f, -0.2f, 1.05f, -0.2f, accent);
					k.add(0.2f, 0.75f, -0.3f, 0.3f, 1.05f, -0.2f, accent);
				}
			}
			case BEAST -> {
				boolean big = kind == UnitKind.ULTRALISK || kind == UnitKind.LURKER || kind == UnitKind.SWARM_HOST;
				k.add(-0.32f, 0.3f, -0.42f, 0.32f, 0.82f, 0.3f, main);
				k.add(-0.36f, 0.55f, -0.38f, 0.36f, 0.9f, 0.2f, second); // carapace
				k.moving(-0.18f, 0.35f, 0.28f, 0.18f, 0.7f, 0.5f, main, Move.HEAD, 0, 0.5f, 0.28f);
				k.add(-0.14f, 0.55f, 0.48f, -0.06f, 0.62f, 0.5f, glow, true, Move.HEAD, 0, 0.5f, 0.28f);
				k.add(0.06f, 0.55f, 0.48f, 0.14f, 0.62f, 0.5f, glow, true, Move.HEAD, 0, 0.5f, 0.28f);
				for (int i = 0; i < 3; i++) {
					float z = -0.3f + i * 0.25f;
					Move a = i % 2 == 0 ? Move.LEG_A : Move.LEG_B, b = i % 2 == 0 ? Move.LEG_B : Move.LEG_A;
					k.moving(-0.42f, 0, z - 0.05f, -0.32f, 0.4f, z + 0.05f, second, a, -0.37f, 0.4f, z);
					k.moving(0.32f, 0, z - 0.05f, 0.42f, 0.4f, z + 0.05f, second, b, 0.37f, 0.4f, z);
				}
				if (big) { // kaiser blades or a spined back
					k.add(-0.5f, 0.6f, 0.1f, -0.4f, 0.7f, 0.6f, accent);
					k.add(0.4f, 0.6f, 0.1f, 0.5f, 0.7f, 0.6f, accent);
				} else k.add(-0.05f, 0.85f, -0.3f, 0.05f, 1f, 0.1f, accent); // a crest
			}
			case SERPENT -> {
				k.add(-0.25f, 0, -0.45f, 0.25f, 0.3f, 0.1f, second);
				k.add(-0.2f, 0.25f, -0.15f, 0.2f, 0.8f, 0.15f, main);
				k.moving(-0.3f, 0.75f, -0.1f, 0.3f, 1f, 0.3f, accent, Move.HEAD, 0, 0.8f, 0); // the hood
				k.add(-0.12f, 0.82f, 0.28f, 0.12f, 0.88f, 0.31f, glow, true, Move.HEAD, 0, 0.8f, 0);
				k.moving(-0.35f, 0.45f, 0, -0.2f, 0.7f, 0.4f, accent, Move.LEG_A, -0.27f, 0.7f, 0);
				k.moving(0.2f, 0.45f, 0, 0.35f, 0.7f, 0.4f, accent, Move.LEG_B, 0.27f, 0.7f, 0);
			}
			case BALL -> {
				k.add(-0.4f, 0.05f, -0.4f, 0.4f, 0.85f, 0.4f, 0x58A040);
				k.glow(-0.3f, 0.6f, -0.3f, 0.3f, 0.95f, 0.3f, 0x90FF60);
				k.add(-0.15f, 0, 0.3f, 0.15f, 0.3f, 0.5f, main);
			}
			case ZERG_FLYER -> {
				k.add(-0.18f, 0.35f, -0.45f, 0.18f, 0.65f, 0.4f, main);
				k.add(-0.12f, 0.4f, 0.35f, 0.12f, 0.6f, 0.55f, accent);
				k.glow(-0.08f, 0.5f, 0.54f, 0.08f, 0.56f, 0.56f, glow);
				k.moving(-0.5f, 0.5f, -0.25f, -0.18f, 0.55f, 0.25f, second, Move.WING_L, -0.18f, 0.52f, 0);
				k.moving(0.18f, 0.5f, -0.25f, 0.5f, 0.55f, 0.25f, second, Move.WING_R, 0.18f, 0.52f, 0);
				k.add(-0.06f, 0.4f, -0.65f, 0.06f, 0.5f, -0.45f, second); // the tail
			}
			case OVERLORD -> {
				k.add(-0.4f, 0.35f, -0.4f, 0.4f, 0.9f, 0.4f, main);
				k.add(-0.42f, 0.6f, -0.3f, 0.42f, 0.95f, 0.3f, second);
				k.glow(-0.15f, 0.6f, 0.4f, 0.15f, 0.7f, 0.42f, glow);
				for (int i = 0; i < 4; i++) // tentacles hanging below
					k.moving(-0.25f + i * 0.15f, 0, -0.05f, -0.2f + i * 0.15f, 0.38f, 0.05f, accent, i % 2 == 0 ? Move.LEG_A : Move.LEG_B, 0, 0.38f, 0);
				k.moving(-0.6f, 0.65f, -0.2f, -0.4f, 0.7f, 0.2f, second, Move.WING_L, -0.4f, 0.67f, 0);
				k.moving(0.4f, 0.65f, -0.2f, 0.6f, 0.7f, 0.2f, second, Move.WING_R, 0.4f, 0.67f, 0);
			}
			case VEHICLE -> {
				k.add(-0.35f, 0.25f, -0.45f, 0.35f, 0.6f, 0.45f, main);
				k.add(-0.25f, 0.6f, -0.2f, 0.25f, 0.8f, 0.2f, accent);
				k.glow(-0.25f, 0.4f, 0.45f, 0.25f, 0.5f, 0.47f, 0xFFA040);
				for (int i = 0; i < 4; i++) {
					float x = i % 2 == 0 ? -0.45f : 0.35f, z = i < 2 ? -0.4f : 0.2f;
					k.add(x, 0, z, x + 0.1f, 0.3f, z + 0.2f, 0x202226);
				}
			}
			case TANK -> {
				k.add(-0.5f, 0, -0.45f, -0.3f, 0.3f, 0.45f, 0x202226); // the tracks
				k.add(0.3f, 0, -0.45f, 0.5f, 0.3f, 0.45f, 0x202226);
				k.add(-0.32f, 0.2f, -0.42f, 0.32f, 0.5f, 0.42f, main);
				k.moving(-0.22f, 0.5f, -0.22f, 0.22f, 0.8f, 0.22f, accent, Move.TURRET, 0, 0.5f, 0);
				k.moving(-0.05f, 0.6f, 0.2f, 0.05f, 0.7f, 0.75f, second, Move.TURRET, 0, 0.5f, 0);
			}
			case MECH -> {
				k.moving(-0.3f, 0, -0.12f, -0.1f, 0.45f, 0.12f, second, Move.LEG_A, -0.2f, 0.45f, 0);
				k.moving(0.1f, 0, -0.12f, 0.3f, 0.45f, 0.12f, second, Move.LEG_B, 0.2f, 0.45f, 0);
				k.add(-0.35f, 0.42f, -0.25f, 0.35f, 0.85f, 0.25f, main);
				k.add(-0.5f, 0.6f, -0.2f, -0.35f, 0.95f, 0.2f, accent); // the cannons on its shoulders
				k.add(0.35f, 0.6f, -0.2f, 0.5f, 0.95f, 0.2f, accent);
				k.glow(-0.12f, 0.72f, 0.25f, 0.12f, 0.8f, 0.27f, glow);
			}
			case SHIP -> {
				boolean protoss = kind.race == UnitKind.Race.PROTOSS;
				boolean round = kind == UnitKind.CARRIER || kind == UnitKind.MOTHERSHIP || kind == UnitKind.VOID_RAY || kind == UnitKind.WARP_PRISM;
				if (round) {
					k.add(-0.45f, 0.35f, -0.4f, 0.45f, 0.65f, 0.45f, main);
					k.add(-0.3f, 0.6f, -0.25f, 0.3f, 0.8f, 0.3f, second);
					k.glow(-0.2f, 0.3f, -0.2f, 0.2f, 0.36f, 0.2f, glow);
					k.glow(-0.08f, 0.4f, 0.45f, 0.08f, 0.6f, 0.5f, glow);
				} else {
					k.add(-0.14f, 0.35f, -0.5f, 0.14f, 0.65f, 0.5f, main); // the hull
					k.add(-0.5f, 0.45f, -0.25f, 0.5f, 0.52f, 0.15f, second); // the wings
					k.add(-0.1f, 0.6f, 0, 0.1f, 0.75f, 0.3f, accent); // the cockpit
					k.glow(-0.4f, 0.4f, -0.3f, -0.28f, 0.52f, -0.26f, protoss ? glow : 0xFFA040); // engines
					k.glow(0.28f, 0.4f, -0.3f, 0.4f, 0.52f, -0.26f, protoss ? glow : 0xFFA040);
					k.add(-0.45f, 0.35f, -0.3f, -0.25f, 0.55f, 0.1f, main);
					k.add(0.25f, 0.35f, -0.3f, 0.45f, 0.55f, 0.1f, main);
				}
			}
			case PROTOSS_WALKER -> {
				boolean tall = kind == UnitKind.COLOSSUS;
				float hip = tall ? 0.75f : 0.45f;
				for (int i = 0; i < 4; i++) {
					float x = i % 2 == 0 ? -0.35f : 0.27f, z = i < 2 ? -0.3f : 0.22f;
					k.moving(x, 0, z, x + 0.08f, hip, z + 0.08f, second, (i == 0 || i == 3) ? Move.LEG_A : Move.LEG_B, x + 0.04f, hip, z + 0.04f);
				}
				k.add(-0.35f, hip, -0.35f, 0.35f, hip + 0.22f, 0.35f, main);
				k.add(-0.2f, hip + 0.2f, -0.2f, 0.2f, Math.min(1f, hip + 0.32f), 0.25f, second);
				k.glow(-0.08f, hip + 0.06f, 0.35f, 0.08f, hip + 0.14f, 0.37f, glow);
				if (tall) { // the thermal lances
					k.glow(-0.4f, hip + 0.02f, 0.1f, -0.36f, hip + 0.06f, 0.5f, 0xFF6040);
					k.glow(0.36f, hip + 0.02f, 0.1f, 0.4f, hip + 0.06f, 0.5f, 0xFF6040);
				}
			}
			case ORB -> {
				int core = kind == UnitKind.ARCHON ? 0x9070FF : 0x6040A0;
				k.glow(-0.3f, 0.2f, -0.3f, 0.3f, 0.8f, 0.3f, core);
				k.add(-0.38f, 0.3f, -0.18f, 0.38f, 0.7f, 0.18f, core, true, Move.SPIN, 0, 0, 0);
				k.add(-0.18f, 0.3f, -0.38f, 0.18f, 0.7f, 0.38f, glow, true, Move.SPIN, 0, 0, 0);
			}
			case HOVER -> {
				k.add(-0.3f, 0.3f, -0.35f, 0.3f, 0.7f, 0.35f, main);
				k.add(-0.2f, 0.65f, -0.2f, 0.2f, 0.85f, 0.25f, second);
				k.glow(-0.1f, 0.45f, 0.35f, 0.1f, 0.55f, 0.37f, glow);
				k.glow(-0.15f, 0.15f, -0.15f, 0.15f, 0.3f, 0.15f, glow);
			}
			case EGG -> {
				k.add(-0.35f, 0, -0.35f, 0.35f, 0.75f, 0.35f, main);
				k.add(-0.28f, 0.7f, -0.28f, 0.28f, 0.95f, 0.28f, second);
				k.glow(-0.1f, 0.3f, 0.35f, 0.1f, 0.5f, 0.36f, 0xFFB050);
			}
			case LARVA -> {
				k.add(-0.25f, 0, -0.45f, 0.25f, 0.6f, 0.3f, main);
				k.add(-0.2f, 0, 0.25f, 0.2f, 0.5f, 0.5f, accent);
			}
			case CRAWLER -> {
				k.add(-0.4f, 0, -0.4f, 0.4f, 0.2f, 0.4f, second); // its roots
				k.add(-0.15f, 0.15f, -0.15f, 0.15f, 0.7f, 0.15f, main);
				k.moving(-0.3f, 0.65f, -0.2f, 0.3f, 0.95f, 0.4f, accent, Move.TURRET, 0, 0.7f, 0);
				k.add(-0.08f, 0.8f, 0.38f, 0.08f, 1f, 0.5f, kind == UnitKind.SPORE_CRAWLER ? 0x90FF60 : 0xE0D0B0, false, Move.TURRET, 0, 0.7f, 0);
			}
			case ZERG_BUILDING -> {
				k.add(-0.5f, 0, -0.5f, 0.5f, 0.35f, 0.5f, second); // a mound of flesh
				k.add(-0.42f, 0.3f, -0.42f, 0.42f, 0.65f, 0.42f, main);
				k.add(-0.28f, 0.6f, -0.28f, 0.28f, 0.88f, 0.28f, main);
				k.add(-0.15f, 0.85f, -0.15f, 0.15f, 1f, 0.15f, accent);
				k.glow(-0.2f, 0.4f, 0.42f, 0.2f, 0.55f, 0.44f, kind == UnitKind.EXTRACTOR ? 0x60FF80 : glow);
				for (int i = 0; i < 4; i++) { // spines
					float x = i % 2 == 0 ? -0.45f : 0.38f, z = i < 2 ? -0.45f : 0.38f;
					k.add(x, 0.3f, z, x + 0.07f, 0.6f, z + 0.07f, accent);
				}
			}
			case TERRAN_BUILDING -> {
				k.add(-0.5f, 0, -0.5f, 0.5f, 0.12f, 0.5f, second); // the foundation
				k.add(-0.45f, 0.1f, -0.45f, 0.45f, 0.72f, 0.45f, main);
				k.add(-0.48f, 0.7f, -0.48f, 0.48f, 0.78f, 0.48f, second); // the roof
				k.add(-0.25f, 0.78f, -0.3f, 0.15f, 0.92f, 0.1f, main);
				k.add(0.25f, 0.78f, 0.2f, 0.32f, 1f, 0.27f, 0x30343A); // a chimney or an antenna
				k.glow(-0.4f, 0.45f, 0.45f, 0.4f, 0.52f, 0.46f, glow); // a row of windows
				k.glow(-0.46f, 0.45f, -0.4f, -0.45f, 0.52f, 0.4f, glow);
				k.glow(0.45f, 0.45f, -0.4f, 0.46f, 0.52f, 0.4f, glow);
				k.add(-0.15f, 0.1f, 0.45f, 0.15f, 0.35f, 0.47f, accent); // the door
			}
			case DEPOT -> {
				k.add(-0.5f, 0, -0.5f, 0.5f, 0.6f, 0.5f, main);
				k.add(-0.45f, 0.6f, -0.45f, 0.45f, 0.75f, 0.45f, second);
				k.add(-0.5f, 0.25f, -0.5f, 0.5f, 0.32f, 0.5f, accent);
				k.glow(-0.3f, 0.75f, -0.05f, 0.3f, 0.78f, 0.05f, 0xFFA040);
			}
			case TURRET -> {
				k.add(-0.4f, 0, -0.4f, 0.4f, 0.15f, 0.4f, second);
				k.add(-0.12f, 0.15f, -0.12f, 0.12f, 0.6f, 0.12f, main);
				k.moving(-0.4f, 0.6f, -0.2f, 0.4f, 0.85f, 0.25f, accent, Move.TURRET, 0, 0.6f, 0);
				k.add(-0.35f, 0.65f, 0.25f, -0.2f, 0.8f, 0.3f, 0x2A2E34, false, Move.TURRET, 0, 0.6f, 0);
				k.add(0.2f, 0.65f, 0.25f, 0.35f, 0.8f, 0.3f, 0x2A2E34, false, Move.TURRET, 0, 0.6f, 0);
			}
			case PROTOSS_BUILDING -> {
				k.add(-0.5f, 0, -0.5f, 0.5f, 0.1f, 0.5f, second); // the platform
				k.add(-0.4f, 0.1f, -0.4f, 0.4f, 0.55f, 0.4f, main);
				k.add(-0.3f, 0.55f, -0.3f, 0.3f, 0.75f, 0.3f, second);
				k.add(-0.18f, 0.75f, -0.18f, 0.18f, 0.88f, 0.18f, main);
				k.glow(-0.12f, 0.88f, -0.12f, 0.12f, 1f, 0.12f, glow); // its crystal
				k.glow(-0.42f, 0.25f, -0.1f, -0.4f, 0.4f, 0.1f, glow);
				k.glow(0.4f, 0.25f, -0.1f, 0.42f, 0.4f, 0.1f, glow);
				k.glow(-0.1f, 0.25f, 0.4f, 0.1f, 0.4f, 0.42f, glow);
				for (int i = 0; i < 4; i++) { // golden pillars at the corners
					float x = i % 2 == 0 ? -0.5f : 0.42f, z = i < 2 ? -0.5f : 0.42f;
					k.add(x, 0, z, x + 0.08f, 0.65f, z + 0.08f, 0xC09030);
				}
			}
			case PYLON -> {
				k.add(-0.35f, 0, -0.35f, 0.35f, 0.12f, 0.35f, second);
				k.add(-0.15f, 0.12f, -0.15f, 0.15f, 0.25f, 0.15f, main);
				k.add(-0.2f, 0.35f, -0.2f, 0.2f, 0.7f, 0.2f, glow, true, Move.SPIN, 0, 0, 0);
				k.add(-0.12f, 0.7f, -0.12f, 0.12f, 0.9f, 0.12f, glow, true, Move.SPIN, 0, 0, 0);
				k.add(-0.12f, 0.28f, -0.12f, 0.12f, 0.35f, 0.12f, glow, true, Move.SPIN, 0, 0, 0);
				k.add(-0.45f, 0.1f, -0.05f, -0.35f, 0.5f, 0.05f, main); // the golden arms
				k.add(0.35f, 0.1f, -0.05f, 0.45f, 0.5f, 0.05f, main);
			}
			case CANNON -> {
				k.add(-0.45f, 0, -0.45f, 0.45f, 0.25f, 0.45f, main);
				k.add(-0.3f, 0.25f, -0.3f, 0.3f, 0.75f, 0.3f, second);
				k.glow(-0.2f, 0.75f, -0.2f, 0.2f, 0.95f, 0.2f, kind == UnitKind.SHIELD_BATTERY ? 0x40FFE0 : glow);
			}
			case PLATFORM -> {
				k.add(-0.5f, 0, -0.5f, 0.5f, 0.3f, 0.5f, second);
				k.add(-0.42f, 0.3f, -0.42f, 0.42f, 0.45f, 0.42f, main);
				k.glow(-0.2f, 0.45f, -0.2f, 0.2f, 0.5f, 0.2f, glow);
			}
		}
		return List.copyOf(k.parts);
	}
}
