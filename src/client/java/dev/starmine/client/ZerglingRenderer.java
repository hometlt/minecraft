package dev.starmine.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.starmine.StarMine;
import dev.starmine.entity.Zergling;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * Draws a zergling from its StarMine model with CPU linear-blend skinning.
 *
 * Clip choice: death while dying; an attack variant for its length after a swing; otherwise stand
 * cross-faded into walk by the limb-swing speed. Triangles go to the entity cutout render type as
 * quads whose last corner repeats the third.
 */
public class ZerglingRenderer extends EntityRenderer<Zergling> {
	private static final ResourceLocation TEXTURE = StarMine.id("textures/entity/sc2/zergling.png");
	private static final String[] ATTACKS = {"attack", "attack1", "attack2"};
	private float[] palette = new float[0], second = new float[0];
	private float[] skinned = new float[0], skinnedNormals = new float[0];

	public ZerglingRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 0.4f;
	}

	@Override
	public ResourceLocation getTextureLocation(Zergling entity) {
		return TEXTURE;
	}

	@Override
	public void render(Zergling entity, float entityYaw, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int packedLight) {
		SkinnedModel model = StarMineModels.get("zergling");
		if (model != null) {
			choosePose(model, entity, partialTick);
			poseStack.pushPose();
			float bodyYaw = Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot);
			poseStack.mulPose(Axis.YP.rotationDegrees(-bodyYaw));
			int overlay = OverlayTexture.pack(OverlayTexture.u(0f), OverlayTexture.v(entity.hurtTime > 0 && entity.deathTime == 0));
			draw(model, poseStack.last(), buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE)), packedLight, overlay);
			poseStack.popPose();
		}
		super.render(entity, entityYaw, partialTick, poseStack, buffers, packedLight);
	}

	private void choosePose(SkinnedModel model, Zergling entity, float partialTick) {
		int size = model.slots * 12;
		if (palette.length != size) {
			palette = new float[size];
			second = new float[size];
		}
		float time = (entity.tickCount + partialTick) / 20f;
		if (entity.deathTime > 0) {
			model.pose(model.clips.get("death"), (entity.deathTime - 1 + partialTick) / 20f, palette);
			return;
		}
		SkinnedModel.Clip attack = model.clips.get(ATTACKS[Math.floorMod(entity.attackVariant, ATTACKS.length)]);
		float sinceAttack = (entity.tickCount - entity.attackStartTick + partialTick) / 20f;
		model.pose(model.clips.get("stand"), time, palette);
		float walk = Math.min(1f, entity.walkAnimation.speed(partialTick) * 2.5f);
		if (walk > 0.01f) {
			model.pose(model.clips.get("walk"), time * 1.4f, second);
			SkinnedModel.blend(palette, second, walk);
		}
		if (attack != null && sinceAttack >= 0 && sinceAttack < attack.seconds(model.fps)) {
			model.pose(attack, sinceAttack, second);
			float fade = Math.min(1f, Math.min(sinceAttack, attack.seconds(model.fps) - sinceAttack) / 0.08f);
			SkinnedModel.blend(palette, second, fade);
		}
	}

	private void draw(SkinnedModel model, PoseStack.Pose pose, VertexConsumer consumer, int light, int overlay) {
		int n = model.vertexCount;
		if (skinned.length != n * 3) {
			skinned = new float[n * 3];
			skinnedNormals = new float[n * 3];
		}
		for (int v = 0; v < n; v++) {
			float px = model.positions[v * 3], py = model.positions[v * 3 + 1], pz = model.positions[v * 3 + 2];
			float nx = model.normals[v * 3], ny = model.normals[v * 3 + 1], nz = model.normals[v * 3 + 2];
			float x = 0, y = 0, z = 0, ax = 0, ay = 0, az = 0;
			for (int k = 0; k < 4; k++) {
				float w = model.weights[v * 4 + k];
				if (w == 0f) continue;
				int m = (model.joints[v * 4 + k] & 0xFF) * 12;
				float[] p = palette;
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
		int[] indices = model.indices;
		for (int t = 0; t + 2 < indices.length; t += 3) {
			vertex(model, pose, consumer, indices[t], light, overlay);
			vertex(model, pose, consumer, indices[t + 1], light, overlay);
			vertex(model, pose, consumer, indices[t + 2], light, overlay);
			vertex(model, pose, consumer, indices[t + 2], light, overlay);
		}
	}

	private void vertex(SkinnedModel model, PoseStack.Pose pose, VertexConsumer consumer, int v, int light, int overlay) {
		consumer.addVertex(pose, skinned[v * 3], skinned[v * 3 + 1], skinned[v * 3 + 2])
			.setColor(255, 255, 255, 255)
			.setUv(model.uvs[v * 2], model.uvs[v * 2 + 1])
			.setOverlay(overlay)
			.setLight(light)
			.setNormal(pose, skinnedNormals[v * 3], skinnedNormals[v * 3 + 1], skinnedNormals[v * 3 + 2]);
	}
}
