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
	private float floor = Float.NEGATIVE_INFINITY;

	/** Rooted crawler rigs retain their underground legs; cut them at the terrain plane even in cutaway views. */
	public void clipBelow(float y) { floor = y; }

	/** Connect the baked head and base, aiming at the synchronized strike point. */
	net.minecraft.world.phys.Vec3 aimSpine(dev.starmine.entity.StarUnit unit, SkinnedModel model, float partial) {
		// shortcut: SMM1 has no attachment names; replace the height mask when the export carries those names.
		if (model.spineHead == null) return null;
		boolean uprooted = unit.mode() == dev.starmine.ability.Modes.UPROOTED;
		float bottom = Float.POSITIVE_INFINITY;
		for (int v = 0; v < model.vertexCount; v++) if (model.spineHead[v]) bottom = Math.min(bottom, skinned[v * 3 + 1]);
		if (!Float.isFinite(bottom)) return null;
		double x = 0, z = 0; int count = 0;
		for (int v = 0; v < model.vertexCount; v++) if (model.spineHead[v] && skinned[v * 3 + 1] <= bottom + .12f) {
			x += skinned[v * 3]; z += skinned[v * 3 + 2]; count++;
		}
		if (count == 0) return null;
		var tip = new net.minecraft.world.phys.Vec3(x / count, bottom, z / count);
		float since = (unit.tickCount - unit.attackStartTick + partial) / 20f;
		double thrust = !uprooted && since >= 0 && since <= .8f ? Math.sin(Math.PI * since / .8f) : 0;
		var aim = unit.strikePoint();
		if (thrust > 0 && aim.lengthSqr() > .01) {
			double vertical = unit.kind.width / Math.max(model.size[0], model.size[2]);
			var delta = new net.minecraft.world.phys.Vec3(aim.x * model.size[0] / unit.kind.width - tip.x,
				Math.max(.6, aim.y / vertical) - tip.y, aim.z * model.size[2] / unit.kind.width - tip.z).scale(thrust);
			for (int v = 0; v < model.vertexCount; v++) if (model.spineHead[v]) {
				skinned[v * 3] += (float) delta.x; skinned[v * 3 + 1] += (float) delta.y; skinned[v * 3 + 2] += (float) delta.z;
			}
			tip = tip.add(delta);
		}
		return tip;
	}

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

	/** Which textures have a team colour mask (`<texture>_team.png`), looked up once each. */
	private static final java.util.Map<String, Boolean> MASKS = new java.util.HashMap<>();

	/**
	 * Owner 19.10, SC2 team colours: the parts whose texture has a team colour mask (`<texture>_team.png`, exported with the
	 * model: grey shading where the team colour shows, its alpha how much) are drawn again in `argb` over the last pose.
	 */
	public void drawTeam(SkinnedModel model, PoseStack.Pose pose, MultiBufferSource buffers, int light, int overlay, int argb) {
		color = argb | 0xFF000000;
		for (SkinnedModel.Part part : model.parts) {
			if (!MASKS.computeIfAbsent(part.texture(), texture -> net.minecraft.client.Minecraft.getInstance().getResourceManager()
				.getResource(StarMine.id("textures/entity/sc2/" + texture + "_team.png")).isPresent())) continue;
			VertexConsumer consumer = buffers.getBuffer(RenderType.entityTranslucent(StarMine.id("textures/entity/sc2/" + part.texture() + "_team.png")));
			drawPart(model, part, pose, consumer, light, overlay);
		}
		color = 0xFFFFFFFF;
	}

	/** Forgets which textures have masks (a resource reload). */
	public static void forgetMasks() {
		MASKS.clear();
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
			if (skinned[indices[t] * 3 + 1] < floor && skinned[indices[t + 1] * 3 + 1] < floor && skinned[indices[t + 2] * 3 + 1] < floor) continue;
			vertex(model, pose, consumer, indices[t], light, overlay);
			vertex(model, pose, consumer, indices[t + 1], light, overlay);
			vertex(model, pose, consumer, indices[t + 2], light, overlay);
			vertex(model, pose, consumer, indices[t + 2], light, overlay);
		}
	}

	private void vertex(SkinnedModel model, PoseStack.Pose pose, VertexConsumer consumer, int v, int light, int overlay) {
		consumer.addVertex(pose, skinned[v * 3], Math.max(floor, skinned[v * 3 + 1]), skinned[v * 3 + 2])
			.setColor(color)
			.setUv(model.uvs[v * 2], model.uvs[v * 2 + 1])
			.setOverlay(overlay)
			.setLight(light)
			.setNormal(pose, skinnedNormals[v * 3], skinnedNormals[v * 3 + 1], skinnedNormals[v * 3 + 2]);
	}
}
