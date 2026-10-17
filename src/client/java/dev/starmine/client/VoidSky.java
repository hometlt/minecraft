package dev.starmine.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.starmine.world.VoidWorld;
import net.fabricmc.fabric.api.client.rendering.v1.DimensionRenderingRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * The Void's sky (owner 17.10): black, eternal night, no sun, moon, stars or clouds — only a dark red glow low
 * round the horizon and, now and then, a red flash: a jagged streak across the sky that lights it for a moment.
 */
public final class VoidSky {
	/** Ticks of one window of the sky; a window has a flash in it by this chance. */
	private static final int WINDOW = 30;
	private static final float FLASH_CHANCE = 0.4f;

	private VoidSky() {
	}

	static void register() {
		DimensionRenderingRegistry.registerDimensionEffects(VoidWorld.KEY.location(), new Effects());
		DimensionRenderingRegistry.registerSkyRenderer(VoidWorld.KEY, VoidSky::render);
		DimensionRenderingRegistry.registerCloudRenderer(VoidWorld.KEY, context -> { });
	}

	/** No clouds, a near-black fog that reddens with the flashes. */
	static final class Effects extends DimensionSpecialEffects {
		Effects() {
			super(Float.NaN, false, SkyType.NONE, false, true);
		}

		@Override
		public Vec3 getBrightnessDependentFogColor(Vec3 colour, float brightness) {
			Minecraft mc = Minecraft.getInstance();
			float flash = mc.level == null ? 0 : flash(mc.level.getGameTime(), mc.getTimer().getGameTimeDeltaPartialTick(false));
			return new Vec3(0.03 + flash * 0.25, 0.005, 0.01 + flash * 0.03);
		}

		@Override
		public boolean isFoggyAt(int x, int z) {
			return false;
		}
	}

	/** How bright the flash is now, 0..1. */
	static float flash(long time, float partial) {
		long window = Math.floorDiv(time, WINDOW);
		RandomSource random = RandomSource.create(window * 341873128712L + 132897987541L);
		if (random.nextFloat() >= FLASH_CHANCE) return 0;
		int start = random.nextInt(WINDOW - 12);
		float t = (time - window * WINDOW) + partial - start;
		if (t < 0 || t > 10) return 0;
		return t < 1.5f ? t / 1.5f : Mth.clamp(1 - (t - 1.5f) / 8.5f, 0, 1) * (0.7f + 0.3f * Mth.sin(t * 3));
	}

	static void render(WorldRenderContext context) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) return;
		float partial = context.tickCounter().getGameTimeDeltaPartialTick(false);
		Matrix4f view = new Matrix4f(context.positionMatrix());
		RenderSystem.depthMask(false);
		RenderSystem.enableBlend();
		RenderSystem.blendFunc(com.mojang.blaze3d.platform.GlStateManager.SourceFactor.SRC_ALPHA, com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE);
		RenderSystem.setShader(GameRenderer::getPositionColorShader);
		float flash = flash(level.getGameTime(), partial);
		BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
		horizon(buffer, view, 0.35f + flash * 0.4f);
		if (flash > 0) streak(buffer, view, Math.floorDiv(level.getGameTime(), WINDOW), flash);
		BufferUploader.drawWithShader(buffer.buildOrThrow());
		RenderSystem.disableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.depthMask(true);
	}

	/** A band of dark red round the horizon, fading upwards. */
	private static void horizon(BufferBuilder buffer, Matrix4f view, float alpha) {
		int sides = 32;
		for (int i = 0; i < sides; i++) {
			double a0 = Math.PI * 2 * i / sides, a1 = Math.PI * 2 * (i + 1) / sides;
			float x0 = (float) Math.cos(a0) * 100, z0 = (float) Math.sin(a0) * 100, x1 = (float) Math.cos(a1) * 100, z1 = (float) Math.sin(a1) * 100;
			buffer.addVertex(view, x0, -12, z0).setColor(0.5f, 0.02f, 0.04f, alpha);
			buffer.addVertex(view, x1, -12, z1).setColor(0.5f, 0.02f, 0.04f, alpha);
			buffer.addVertex(view, x1, 28, z1).setColor(0.3f, 0f, 0.02f, 0f);
			buffer.addVertex(view, x0, 28, z0).setColor(0.3f, 0f, 0.02f, 0f);
		}
	}

	/** The flash's streak: a jagged line from high in the sky down towards the horizon. */
	private static void streak(BufferBuilder buffer, Matrix4f view, long window, float flash) {
		RandomSource random = RandomSource.create(window * 9871L + 77L);
		double heading = random.nextDouble() * Math.PI * 2;
		Vec3 at = new Vec3(Math.cos(heading) * 40, 90, Math.sin(heading) * 40);
		Vec3 down = new Vec3(Math.cos(heading) * 60, -14, Math.sin(heading) * 60).subtract(at).scale(1 / 9.0);
		for (int i = 0; i < 9; i++) {
			Vec3 next = at.add(down).add(random.nextGaussian() * 6, random.nextGaussian() * 2, random.nextGaussian() * 6);
			Vec3 side = next.subtract(at).cross(at).normalize().scale(0.9 - i * 0.07);
			float a = flash * (1 - i * 0.06f);
			vertex(buffer, view, at.add(side), 1f, 0.25f, 0.2f, a);
			vertex(buffer, view, at.subtract(side), 1f, 0.25f, 0.2f, a);
			vertex(buffer, view, next.subtract(side), 1f, 0.15f, 0.1f, a);
			vertex(buffer, view, next.add(side), 1f, 0.15f, 0.1f, a);
			at = next;
		}
	}

	private static void vertex(BufferBuilder buffer, Matrix4f view, Vec3 at, float r, float g, float b, float a) {
		buffer.addVertex(view, (float) at.x, (float) at.y, (float) at.z).setColor(r, g, b, a);
	}
}
