package dev.starmine.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.starmine.StarMine;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;

/**
 * Draws a StarMine model: CPU linear-blend skinning by a palette, then every part with its own
 * texture (`textures/entity/sc2/<texture>.png`) as cutout quads whose last corner repeats the third.
 * Shared by the zerg renderer and the creep tumor's block entity renderer.
 */
public final class SkinnedModelDrawer {
	private float[] skinned = new float[0], skinnedNormals = new float[0];

	private int color = 0xFFFFFFFF;

	/** Draws the last skinned pose. */
	public void draw(SkinnedModel model, PoseStack.Pose pose, MultiBufferSource buffers, int light, int overlay) {
		color = 0xFFFFFFFF;
		for (SkinnedModel.Part part : model.parts) {
			VertexConsumer consumer = buffers.getBuffer(RenderType.entityCutoutNoCull(StarMine.id("textures/entity/sc2/" + part.texture() + ".png")));
			drawPart(model, part, pose, consumer, light, overlay);
		}
	}

	/** Draws the last skinned pose solid, tinted by `argb` (a hero drawn with another unit's model). */
	public void drawTinted(SkinnedModel model, PoseStack.Pose pose, MultiBufferSource buffers, int light, int overlay, int argb) {
		color = argb;
		for (SkinnedModel.Part part : model.parts) {
			VertexConsumer consumer = buffers.getBuffer(RenderType.entityCutoutNoCull(StarMine.id("textures/entity/sc2/" + part.texture() + ".png")));
			drawPart(model, part, pose, consumer, light, overlay);
		}
		color = 0xFFFFFFFF;
	}

	/** Draws the last skinned pose see-through and tinted by `argb` (a building being placed). */
	public void drawTranslucent(SkinnedModel model, PoseStack.Pose pose, MultiBufferSource buffers, int light, int overlay, int argb) {
		color = argb;
		for (SkinnedModel.Part part : model.parts) {
			VertexConsumer consumer = buffers.getBuffer(RenderType.entityTranslucent(StarMine.id("textures/entity/sc2/" + part.texture() + ".png")));
			drawPart(model, part, pose, consumer, light, overlay);
		}
		color = 0xFFFFFFFF;
	}

	/** Deforms the model by `palette` (linear-blend skinning) into this drawer's buffers. */
	public void skin(SkinnedModel model, float[] palette) {
		int n = model.vertexCount;
		if (skinned.length != n * 3) {
			skinned = new float[n * 3];
			skinnedNormals = new float[n * 3];
		}
		float[] p = palette;
		for (int v = 0; v < n; v++) {
			float px = model.positions[v * 3], py = model.positions[v * 3 + 1], pz = model.positions[v * 3 + 2];
			float nx = model.normals[v * 3], ny = model.normals[v * 3 + 1], nz = model.normals[v * 3 + 2];
			float x = 0, y = 0, z = 0, ax = 0, ay = 0, az = 0;
			for (int k = 0; k < 4; k++) {
				float w = model.weights[v * 4 + k];
				if (w == 0f) continue;
				int m = (model.joints[v * 4 + k] & 0xFF) * 12;
				x += w * (p[m] * px + p[m + 1] * py + p[m + 2] * pz + p[m + 3]);
				y += w * (p[m + 4] * px + p[m + 5] * py + p[m + 6] * pz + p[m + 7]);
				z += w * (p[m + 8] * px + p[m + 9] * py + p[m + 10] * pz + p[m + 11]);
				ax += w * (p[m] * nx + p[m + 1] * ny + p[m + 2] * nz);
				ay += w * (p[m + 4] * nx + p[m + 5] * ny + p[m + 6] * nz);
				az += w * (p[m + 8] * nx + p[m + 9] * ny + p[m + 10] * nz);
			}
			float length = Mth.sqrt(ax * ax + ay * ay + az * az);
			if (length > 1e-6f) {
				ax /= length;
				ay /= length;
				az /= length;
			}
			skinned[v * 3] = x;
			skinned[v * 3 + 1] = y;
			skinned[v * 3 + 2] = z;
			skinnedNormals[v * 3] = ax;
			skinnedNormals[v * 3 + 1] = ay;
			skinnedNormals[v * 3 + 2] = az;
		}
	}

	private void drawPart(SkinnedModel model, SkinnedModel.Part part, PoseStack.Pose pose, VertexConsumer consumer, int light, int overlay) {
		int[] indices = model.indices;
		int end = part.first() + part.count();
		for (int t = part.first(); t + 2 < end; t += 3) {
			vertex(model, pose, consumer, indices[t], light, overlay);
			vertex(model, pose, consumer, indices[t + 1], light, overlay);
			vertex(model, pose, consumer, indices[t + 2], light, overlay);
			vertex(model, pose, consumer, indices[t + 2], light, overlay);
		}
	}

	private void vertex(SkinnedModel model, PoseStack.Pose pose, VertexConsumer consumer, int v, int light, int overlay) {
		consumer.addVertex(pose, skinned[v * 3], skinned[v * 3 + 1], skinned[v * 3 + 2])
			.setColor(color)
			.setUv(model.uvs[v * 2], model.uvs[v * 2 + 1])
			.setOverlay(overlay)
			.setLight(light)
			.setNormal(pose, skinnedNormals[v * 3], skinnedNormals[v * 3 + 1], skinnedNormals[v * 3 + 2]);
	}
}
