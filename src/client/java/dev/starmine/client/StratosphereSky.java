package dev.starmine.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import dev.starmine.StarMine;
import dev.starmine.world.Stratosphere;
import net.fabricmc.fabric.api.client.rendering.v1.DimensionRenderingRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * The stratosphere's sky (owner 17.10): black above with the stars always out, the sun and the moon on the
 * overworld's day and night; below, to the horizon, the Earth — land and seas under drifting clouds, a band of blue
 * air at its rim, lit by the same day and night — moving under the viewer as they fly. No clouds of its own.
 */
public final class StratosphereSky {
	private static final ResourceLocation SUN = ResourceLocation.withDefaultNamespace("textures/environment/sun.png");
	private static final ResourceLocation MOON = ResourceLocation.withDefaultNamespace("textures/environment/moon_phases.png");
	private static final ResourceLocation EARTH = StarMine.id("stratosphere_earth");
	/** Blocks of the world under one texel of the Earth's texture (it is drawn far below: the view sees a lot of it). */
	private static final double EARTH_BLOCKS = 48;
	private static final int EARTH_SIZE = 512, STARS = 1600;
	private static float[] stars;
	private static DynamicTexture earth;

	private StratosphereSky() {
	}

	static void register() {
		DimensionRenderingRegistry.registerDimensionEffects(Stratosphere.KEY.location(), new Effects());
		DimensionRenderingRegistry.registerSkyRenderer(Stratosphere.KEY, StratosphereSky::render);
		DimensionRenderingRegistry.registerCloudRenderer(Stratosphere.KEY, context -> { }); // the clouds lie on the Earth below
	}

	/** No clouds, no ground fog, a black sky: the fog colour fades with the light as the overworld's does. */
	static final class Effects extends DimensionSpecialEffects {
		Effects() {
			super(Float.NaN, false, SkyType.NONE, false, false);
		}

		@Override
		public Vec3 getBrightnessDependentFogColor(Vec3 colour, float brightness) {
			return colour.multiply(brightness * 0.6 + 0.1, brightness * 0.6 + 0.1, brightness * 0.6 + 0.15);
		}

		@Override
		public boolean isFoggyAt(int x, int z) {
			return false;
		}
	}

	static void render(WorldRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null) return;
		float partial = context.tickCounter().getGameTimeDeltaPartialTick(false);
		Matrix4f view = new Matrix4f(context.positionMatrix());
		RenderSystem.depthMask(false);
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		float day = 1f - level.getSkyDarken(partial); // 0 at noon .. ~0.8 at night as the overworld's
		float light = Mth.clamp(1.05f - day * 1.15f, 0.12f, 1f);
		drawStars(view, Mth.clamp(0.55f + (1 - light) * 0.6f, 0.4f, 1f));
		drawSunAndMoon(view, level, partial);
		drawEarth(view, mc, light);
		RenderSystem.disableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.depthMask(true);
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
	}

	private static void drawStars(Matrix4f view, float brightness) {
		if (stars == null) {
			RandomSource random = RandomSource.create(10842L);
			stars = new float[STARS * 4];
			for (int i = 0; i < STARS; i++) {
				double x, y, z, d;
				do {
					x = random.nextDouble() * 2 - 1;
					y = random.nextDouble() * 2 - 1;
					z = random.nextDouble() * 2 - 1;
					d = x * x + y * y + z * z;
				} while (d > 1 || d < 0.01);
				d = Math.sqrt(d);
				stars[i * 4] = (float) (x / d);
				stars[i * 4 + 1] = (float) (y / d);
				stars[i * 4 + 2] = (float) (z / d);
				stars[i * 4 + 3] = 0.15f + random.nextFloat() * 0.25f;
			}
		}
		RenderSystem.setShader(GameRenderer::getPositionColorShader);
		BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
		for (int i = 0; i < STARS; i++) {
			float x = stars[i * 4], y = stars[i * 4 + 1], z = stars[i * 4 + 2], size = stars[i * 4 + 3];
			if (y < -0.25f) continue; // below the Earth's rim
			Vec3 at = new Vec3(x, y, z).scale(100);
			Vec3 a = new Vec3(x, y, z).cross(Math.abs(y) > 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize().scale(size);
			Vec3 b = new Vec3(x, y, z).cross(a).normalize().scale(size);
			float shade = brightness * (0.6f + (i % 7) * 0.06f);
			quad(buffer, view, at.add(a).add(b), at.add(a).subtract(b), at.subtract(a).subtract(b), at.subtract(a).add(b), shade, shade, shade, shade);
		}
		BufferUploader.drawWithShader(buffer.buildOrThrow());
	}

	private static void drawSunAndMoon(Matrix4f view, ClientLevel level, float partial) {
		Matrix4f turned = new Matrix4f(view).rotate(Axis.YP.rotationDegrees(-90f)).rotate(Axis.XP.rotationDegrees(level.getTimeOfDay(partial) * 360f));
		RenderSystem.setShader(GameRenderer::getPositionTexShader);
		RenderSystem.blendFunc(com.mojang.blaze3d.platform.GlStateManager.SourceFactor.SRC_ALPHA, com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE);
		float size = 34f;
		RenderSystem.setShaderTexture(0, SUN);
		BufferBuilder sun = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
		sun.addVertex(turned, -size, 100f, -size).setUv(0f, 0f);
		sun.addVertex(turned, size, 100f, -size).setUv(1f, 0f);
		sun.addVertex(turned, size, 100f, size).setUv(1f, 1f);
		sun.addVertex(turned, -size, 100f, size).setUv(0f, 1f);
		BufferUploader.drawWithShader(sun.buildOrThrow());
		int phase = level.getMoonPhase();
		float u0 = (phase % 4) / 4f, v0 = (phase / 4 % 2) / 2f, u1 = u0 + 0.25f, v1 = v0 + 0.5f;
		size = 22f;
		RenderSystem.setShaderTexture(0, MOON);
		BufferBuilder moon = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
		moon.addVertex(turned, -size, -100f, size).setUv(u1, v1);
		moon.addVertex(turned, size, -100f, size).setUv(u0, v1);
		moon.addVertex(turned, size, -100f, -size).setUv(u0, v0);
		moon.addVertex(turned, -size, -100f, -size).setUv(u1, v0);
		BufferUploader.drawWithShader(moon.buildOrThrow());
		RenderSystem.defaultBlendFunc();
	}

	/**
	 * The Earth: a disc far below the camera reaching almost to the far plane (so it fills the view down from just
	 * under the horizon), its texture moving with the camera's place; then the blue band of the air at its rim.
	 */
	private static void drawEarth(Matrix4f view, Minecraft mc, float light) {
		if (earth == null) {
			earth = new DynamicTexture(paintEarth());
			mc.getTextureManager().register(EARTH, earth);
		}
		double far = mc.gameRenderer.getDepthFar();
		float radius = (float) (far * 0.97), depth = (float) (far * 0.16);
		Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
		float du = (float) (cam.x / (EARTH_BLOCKS * EARTH_SIZE)), dv = (float) (cam.z / (EARTH_BLOCKS * EARTH_SIZE));
		float span = 3.5f; // seen from so high, a wide stretch: several continents to the horizon
		RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
		RenderSystem.setShaderTexture(0, EARTH);
		BufferBuilder ground = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
		int rings = 6, slices = 48;
		for (int ring = 0; ring < rings; ring++) {
			float r0 = radius * ring / rings, r1 = radius * (ring + 1) / rings;
			for (int s = 0; s < slices; s++) {
				double a0 = s * Math.PI * 2 / slices, a1 = (s + 1) * Math.PI * 2 / slices;
				float[][] p = {
					{(float) Math.cos(a0) * r0, (float) Math.sin(a0) * r0}, {(float) Math.cos(a1) * r0, (float) Math.sin(a1) * r0},
					{(float) Math.cos(a1) * r1, (float) Math.sin(a1) * r1}, {(float) Math.cos(a0) * r1, (float) Math.sin(a0) * r1}};
				int[][] tris = {{0, 1, 2}, {0, 2, 3}};
				for (int[] tri : tris)
					for (int k : tri) {
						float x = p[k][0], z = p[k][1], edge = (float) Math.sqrt(x * x + z * z) / radius;
						float y = -depth + edge * edge * depth * 0.35f; // the planet curves away toward the rim
						float haze = Mth.clamp((edge - 0.55f) / 0.45f, 0f, 1f);
						float shade = light * (1f - haze * 0.35f);
						ground.addVertex(view, x, y, z).setUv(du + x / radius * span, dv + z / radius * span)
							.setColor(shade * (1 - haze * 0.3f), shade * (1 - haze * 0.15f), Math.min(1f, shade + haze * 0.25f), 1f);
					}
			}
		}
		BufferUploader.drawWithShader(ground.buildOrThrow());
		// the air at the rim: a glowing band from the Earth's edge up toward the horizon
		RenderSystem.setShader(GameRenderer::getPositionColorShader);
		BufferBuilder air = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
		float bottom = -depth + depth * 0.35f - 2f, top = bottom + depth * 0.5f;
		for (int s = 0; s < slices; s++) {
			double a0 = s * Math.PI * 2 / slices, a1 = (s + 1) * Math.PI * 2 / slices;
			float x0 = (float) Math.cos(a0) * radius, z0 = (float) Math.sin(a0) * radius, x1 = (float) Math.cos(a1) * radius, z1 = (float) Math.sin(a1) * radius;
			float r = Math.min(1f, 0.3f * light + 0.04f), g = Math.min(1f, 0.55f * light + 0.06f), b = Math.min(1f, 0.95f * light + 0.2f);
			air.addVertex(view, x0, bottom, z0).setColor(r, g, b, 0.9f);
			air.addVertex(view, x1, bottom, z1).setColor(r, g, b, 0.9f);
			air.addVertex(view, x1, top, z1).setColor(r, g, b, 0f);
			air.addVertex(view, x0, top, z0).setColor(r, g, b, 0f);
		}
		BufferUploader.drawWithShader(air.buildOrThrow());
	}

	/** The Earth's face: seas and land from smooth noise (shores, plains, woods, deserts, mountains, ice) under clouds. */
	private static NativeImage paintEarth() {
		NativeImage image = new NativeImage(EARTH_SIZE, EARTH_SIZE, false);
		for (int y = 0; y < EARTH_SIZE; y++)
			for (int x = 0; x < EARTH_SIZE; x++) {
				double land = noise(x, y, 96, 1) * 0.6 + noise(x, y, 32, 2) * 0.3 + noise(x, y, 8, 3) * 0.1;
				double wet = noise(x, y, 64, 4), cloud = noise(x, y, 48, 5) * 0.65 + noise(x, y, 12, 6) * 0.35;
				int r, g, b;
				if (land < 0.52) { // sea, darker when deep
					double deep = (0.52 - land) / 0.52;
					r = (int) (20 + 20 * (1 - deep));
					g = (int) (60 + 60 * (1 - deep));
					b = (int) (120 + 70 * (1 - deep));
				} else if (land < 0.545) { // shore
					r = 200; g = 190; b = 140;
				} else if (land > 0.78) { // mountains, snow on the highest
					boolean snow = land > 0.84;
					r = snow ? 235 : 120; g = snow ? 240 : 110; b = snow ? 245 : 100;
				} else if (wet < 0.4) { // deserts and dry plains
					r = 190; g = 165; b = 100;
				} else { // grass and woods
					double wood = (wet - 0.4) / 0.6;
					r = (int) (80 - 40 * wood); g = (int) (140 - 30 * wood); b = (int) (60 - 20 * wood);
				}
				if (cloud > 0.58) { // clouds over it
					double c = Math.min(1, (cloud - 0.58) / 0.12) * 0.85;
					r = (int) (r + (245 - r) * c);
					g = (int) (g + (248 - g) * c);
					b = (int) (b + (252 - b) * c);
				}
				image.setPixelRGBA(x, y, 0xFF000000 | b << 16 | g << 8 | r); // ABGR
			}
		return image;
	}

	/** Smooth value noise in 0..1 that tiles over the texture (cells of `cell` texels). */
	private static double noise(int x, int y, int cell, int seed) {
		int cells = EARTH_SIZE / cell;
		double fx = (double) x / cell, fy = (double) y / cell;
		int x0 = Mth.floor(fx), y0 = Mth.floor(fy);
		double tx = smooth(fx - x0), ty = smooth(fy - y0);
		double a = hash(Math.floorMod(x0, cells), Math.floorMod(y0, cells), seed), b = hash(Math.floorMod(x0 + 1, cells), Math.floorMod(y0, cells), seed);
		double c = hash(Math.floorMod(x0, cells), Math.floorMod(y0 + 1, cells), seed), d = hash(Math.floorMod(x0 + 1, cells), Math.floorMod(y0 + 1, cells), seed);
		return Mth.lerp(ty, Mth.lerp(tx, a, b), Mth.lerp(tx, c, d));
	}

	private static double smooth(double t) {
		return t * t * (3 - 2 * t);
	}

	private static double hash(int x, int y, int seed) {
		long h = x * 374761393L + y * 668265263L + seed * 2147483647L;
		h = (h ^ (h >> 13)) * 1274126177L;
		return ((h ^ (h >> 16)) & 0xFFFF) / 65535.0;
	}

	private static void quad(BufferBuilder buffer, Matrix4f view, Vec3 a, Vec3 b, Vec3 c, Vec3 d, float r, float g, float bl, float alpha) {
		buffer.addVertex(view, (float) a.x, (float) a.y, (float) a.z).setColor(r, g, bl, alpha);
		buffer.addVertex(view, (float) b.x, (float) b.y, (float) b.z).setColor(r, g, bl, alpha);
		buffer.addVertex(view, (float) c.x, (float) c.y, (float) c.z).setColor(r, g, bl, alpha);
		buffer.addVertex(view, (float) d.x, (float) d.y, (float) d.z).setColor(r, g, bl, alpha);
	}
}
