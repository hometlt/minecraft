package dev.starmine.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.starmine.StarMine;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;

/** The flexible attachment between the native crawler base and its animated head. */
final class CrawlerNeck {
	private CrawlerNeck() {}

	static void draw(Vec3 tip, boolean uprooted, PoseStack.Pose pose, MultiBufferSource buffers, int light, int overlay) {
		if (tip == null) return;
		VertexConsumer out = buffers.getBuffer(RenderType.entityCutoutNoCull(StarMine.id("textures/entity/sc2/spinecrawler_diffuse.png")));
		Vec3 start = new Vec3(0, uprooted ? 1 : .6, 0), bend = new Vec3(.15, Math.max(uprooted ? 1.6 : 1.4, tip.y * .65), -.15);
		Vec3[] previous = ring(start, bend.subtract(start), .18);
		for (int segment = 1; segment <= 12; segment++) {
			double t = segment / 12.0, s = 1 - t;
			Vec3 centre = start.scale(s * s).add(bend.scale(2 * s * t)).add(tip.scale(t * t));
			Vec3 tangent = bend.subtract(start).scale(s).add(tip.subtract(bend).scale(t));
			Vec3[] next = ring(centre, tangent, .18 - .07 * t);
			for (int side = 0; side < 8; side++) {
				int after = (side + 1) % 8;
				Vec3 normal = next[side].subtract(centre).normalize();
				vertex(out, pose, previous[side], normal, side / 8f, (segment - 1) / 12f, light, overlay);
				vertex(out, pose, next[side], normal, side / 8f, segment / 12f, light, overlay);
				vertex(out, pose, next[after], normal, (side + 1) / 8f, segment / 12f, light, overlay);
				vertex(out, pose, previous[after], normal, (side + 1) / 8f, (segment - 1) / 12f, light, overlay);
			}
			previous = next;
		}
	}

	private static Vec3[] ring(Vec3 centre, Vec3 tangent, double radius) {
		Vec3 forward = tangent.normalize();
		Vec3 right = forward.cross(Math.abs(forward.y) > .95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize();
		Vec3 up = right.cross(forward).normalize();
		Vec3[] ring = new Vec3[8];
		for (int i = 0; i < ring.length; i++) {
			double angle = i * Math.PI / 4;
			ring[i] = centre.add(right.scale(Math.cos(angle) * radius)).add(up.scale(Math.sin(angle) * radius));
		}
		return ring;
	}

	private static void vertex(VertexConsumer out, PoseStack.Pose pose, Vec3 p, Vec3 n, float u, float v, int light, int overlay) {
		out.addVertex(pose, (float) p.x, (float) p.y, (float) p.z).setColor(0xFFFFFFFF)
			.setUv(.4f + u * .15f, .4f + v * .2f).setOverlay(overlay).setLight(light)
			.setNormal(pose, (float) n.x, (float) n.y, (float) n.z);
	}
}
