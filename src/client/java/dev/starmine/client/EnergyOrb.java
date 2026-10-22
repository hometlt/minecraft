package dev.starmine.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.starmine.StarMine;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Owner 22.10: the glowing see-through sphere of energy round an archon and round a sentry's crystal. In SC2 it is a
 * particle/shader effect of the model, lost in the conversion to StarMine's skinned models, so it is drawn here: a soft
 * translucent full-bright shell, a brighter additive rim that breathes, and a small hot core. Units: blocks.
 */
final class EnergyOrb {
	private static final ResourceLocation WHITE = StarMine.id("textures/misc/fog.png");
	private static final int RINGS = 12, SEGMENTS = 20;

	private EnergyOrb() {
	}

	/** Draws the kind's orb, if it has one, in the entity's space (feet at the origin, not yet turned). */
	static void draw(StarUnit entity, float partialTick, PoseStack pose, MultiBufferSource buffers) {
		if (entity.deathTime > 0 || entity.isInvisible()) return;
		float radius, centre;
		int colour; // ARGB of the shell
		if (entity.kind == UnitKind.ARCHON) {
			radius = entity.kind.width * 0.62f;
			centre = entity.kind.height * 0.55f;
			colour = 0x58A8C8FF;
		} else if (entity.kind == UnitKind.SENTRY) {
			radius = entity.kind.width * 0.34f;
			centre = entity.kind.height * 0.62f;
			colour = 0x4890E0FF;
		} else return;
		float time = (entity.tickCount + partialTick) / 20f;
		float breath = 1f + 0.04f * Mth.sin(time * 3.1f);
		if (entity.cloaked() && !entity.revealed()) return;
		pose.pushPose();
		pose.translate(0, centre + (entity.kind.flying ? 0 : 0.05f * Mth.sin(time * 1.7f)), 0);
		pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(time * 25f));
		shell(pose, buffers.getBuffer(RenderType.entityTranslucentEmissive(WHITE)), radius * breath, colour);
		rim(pose, buffers.getBuffer(RenderType.lightning()), radius * breath * 1.04f, colour, time);
		shell(pose, buffers.getBuffer(RenderType.entityTranslucentEmissive(WHITE)), radius * 0.28f, 0xC0FFFFFF);
		pose.popPose();
	}

	/** A sphere of the colour, its normals outward (seen from both sides: the type draws no culling of its own back). */
	private static void shell(PoseStack pose, VertexConsumer out, float radius, int colour) {
		PoseStack.Pose last = pose.last();
		float a = (colour >>> 24) / 255f, r = (colour >> 16 & 255) / 255f, g = (colour >> 8 & 255) / 255f, b = (colour & 255) / 255f;
		for (int i = 0; i < RINGS; i++)
			for (int j = 0; j < SEGMENTS; j++) {
				float[][] corners = {point(i, j), point(i, j + 1), point(i + 1, j + 1), point(i + 1, j)};
				for (float[] c : corners)
					out.addVertex(last, c[0] * radius, c[1] * radius, c[2] * radius).setColor(r, g, b, a).setUv(0.5f, 0.5f)
						.setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(last, c[0], c[1], c[2]);
			}
	}

	/**
	 * The rim: three additive bands of light turning over the surface, flickering — the arcs of energy on the sphere.
	 */
	private static void rim(PoseStack pose, VertexConsumer out, float radius, int colour, float time) {
		var matrix = pose.last().pose();
		float r = (colour >> 16 & 255) / 255f, g = (colour >> 8 & 255) / 255f, b = (colour & 255) / 255f;
		for (int arc = 0; arc < 3; arc++) {
			float tilt = arc * 1.05f + time * 0.6f;
			float glow = 0.35f + 0.25f * Mth.sin(time * 5f + arc * 2f);
			for (int j = 0; j < SEGMENTS * 2; j++) {
				float u0 = j * Mth.TWO_PI / (SEGMENTS * 2), u1 = (j + 1) * Mth.TWO_PI / (SEGMENTS * 2), w = 0.035f * radius;
				float[] p0 = arcPoint(u0, tilt), p1 = arcPoint(u1, tilt);
				float[][] quad = {{p0[0], p0[1], p0[2], -w}, {p1[0], p1[1], p1[2], -w}, {p1[0], p1[1], p1[2], w}, {p0[0], p0[1], p0[2], w}};
				for (int side = 0; side < 2; side++) // both windings: the type culls back faces
					for (int k = 0; k < 4; k++) {
						float[] q = quad[side == 0 ? k : 3 - k];
						out.addVertex(matrix, q[0] * radius, q[1] * radius + q[3], q[2] * radius).setColor(r, g, b, glow);
					}
			}
		}
	}

	/** A great circle of the unit sphere, tilted about x by the angle. */
	private static float[] arcPoint(float u, float tilt) {
		float x = Mth.cos(u), y = Mth.sin(u);
		return new float[] {x, y * Mth.cos(tilt), y * Mth.sin(tilt)};
	}

	private static float[] point(int ring, int segment) {
		float theta = ring * Mth.PI / RINGS, phi = segment * Mth.TWO_PI / SEGMENTS;
		float s = Mth.sin(theta);
		return new float[] {s * Mth.cos(phi), Mth.cos(theta), s * Mth.sin(phi)};
	}
}
