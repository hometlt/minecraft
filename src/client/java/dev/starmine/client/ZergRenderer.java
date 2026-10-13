package dev.starmine.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.starmine.StarMine;
import dev.starmine.entity.ZergMob;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Draws any zerg from its StarMine model with CPU linear-blend skinning.
 *
 * Clip choice: death while dying; an attack clip for its length after a swing; otherwise stand
 * cross-faded into walk by movement speed (flying kinds by their velocity). A death that only exists
 * as a separate SC2 model (gore burst, ragdoll pieces) draws that model instead of the unit and
 * nothing once its clip has ended; with no death at all the body sinks into the ground. Each model
 * part is drawn with its own texture; triangles go out as quads whose last corner repeats the third.
 */
public class ZergRenderer extends EntityRenderer<ZergMob> {
	private float[] palette = new float[0], second = new float[0];
	private float[] skinned = new float[0], skinnedNormals = new float[0];

	public ZergRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 0.5f;
	}

	@Override
	public ResourceLocation getTextureLocation(ZergMob entity) {
		return StarMine.id("textures/entity/sc2/" + entity.kind.id + ".png");
	}

	@Override
	public void render(ZergMob entity, float entityYaw, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int packedLight) {
		SkinnedModel model = StarMineModels.get(entity.kind.id);
		float sink = 0f;
		boolean visible = model != null;
		if (model != null && entity.deathTime > 0 && !model.clips.containsKey("death")) {
			float dying = (entity.deathTime - 1 + partialTick) / 20f;
			SkinnedModel burst = StarMineModels.get(entity.kind.id + "_death");
			if (burst != null) {
				model = burst;
				SkinnedModel.Clip clip = burst.clips.get("death");
				visible = clip != null && (clip.loop || dying <= clip.seconds(burst.fps));
			} else {
				sink = dying / (entity.kind.deathTicks() / 20f) * entity.kind.height;
			}
		}
		if (visible) {
			this.shadowRadius = entity.kind.width * 0.5f;
			choosePose(model, entity, partialTick);
			skin(model);
			poseStack.pushPose();
			if (sink > 0f) poseStack.translate(0f, -sink, 0f);
			float bodyYaw = Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot);
			poseStack.mulPose(Axis.YP.rotationDegrees(-bodyYaw));
			int overlay = OverlayTexture.pack(OverlayTexture.u(0f), OverlayTexture.v(entity.hurtTime > 0 && entity.deathTime == 0));
			PoseStack.Pose pose = poseStack.last();
			for (SkinnedModel.Part part : model.parts) {
				VertexConsumer consumer = buffers.getBuffer(RenderType.entityCutoutNoCull(StarMine.id("textures/entity/sc2/" + part.texture() + ".png")));
				draw(model, part, pose, consumer, packedLight, overlay);
			}
			poseStack.popPose();
		}
		super.render(entity, entityYaw, partialTick, poseStack, buffers, packedLight);
	}

	private void choosePose(SkinnedModel model, ZergMob entity, float partialTick) {
		int size = model.slots * 12;
		if (palette.length != size) {
			palette = new float[size];
			second = new float[size];
		}
		float time = (entity.tickCount + partialTick) / 20f;
		SkinnedModel.Clip death = model.clips.get("death");
		if (entity.deathTime > 0 && death != null) {
			model.pose(death, (entity.deathTime - 1 + partialTick) / 20f, palette);
			return;
		}
		if (entity.deathTime > 0 && model.clips.isEmpty()) return;
		SkinnedModel.Clip stand = model.clips.get("stand");
		if (stand == null) stand = model.clips.values().iterator().next();
		if (stand == null) return;
		model.pose(stand, time, palette);
		SkinnedModel.Clip walk = model.clips.get("walk");
		float moving = entity.kind.flying
			? (float) entity.getDeltaMovement().horizontalDistance() * 6f
			: entity.walkAnimation.speed(partialTick) * 2.5f;
		moving = Math.min(1f, moving);
		if (walk != null && moving > 0.01f) {
			model.pose(walk, time * 1.4f, second);
			SkinnedModel.blend(palette, second, moving);
		}
		int attacks = model.attacks.size();
		if (attacks > 0) {
			SkinnedModel.Clip attack = model.attacks.get(Math.floorMod(entity.attackVariant, attacks));
			float since = (entity.tickCount - entity.attackStartTick + partialTick) / 20f;
			float length = attack.seconds(model.fps);
			if (since >= 0 && since < length) {
				model.pose(attack, since, second);
				SkinnedModel.blend(palette, second, Math.min(1f, Math.min(since, length - since) / 0.08f));
			}
		}
	}

	private void skin(SkinnedModel model) {
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

	private void draw(SkinnedModel model, SkinnedModel.Part part, PoseStack.Pose pose, VertexConsumer consumer, int light, int overlay) {
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
			.setColor(255, 255, 255, 255)
			.setUv(model.uvs[v * 2], model.uvs[v * 2 + 1])
			.setOverlay(overlay)
			.setLight(light)
			.setNormal(pose, skinnedNormals[v * 3], skinnedNormals[v * 3 + 1], skinnedNormals[v * 3 + 2]);
	}
}
