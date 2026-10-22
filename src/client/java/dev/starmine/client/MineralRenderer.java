package dev.starmine.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.starmine.StarMine;
import dev.starmine.mineral.Minerals;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import org.joml.Vector3f;

/**
 * A mineral crystal (owner 16.10) drawn in SC2's manner over its 2 x 2 x 2 blocks: a cluster of blue
 * shards — tall faceted prisms ending in points, leaning out from the middle — glowing a little. It
 * shrinks as it is mined (down to half its height before it is gone). Own art, no SC2 assets.
 */
public final class MineralRenderer implements BlockEntityRenderer<Minerals.Core> {
	private static final ResourceLocation TEXTURE = StarMine.id("textures/block/mineral_crystal.png");
	/** Light a crystal glows with at least. */
	private static final int GLOW = LightTexture.pack(12, 15);

	public MineralRenderer(BlockEntityRendererProvider.Context context) {
	}

	@Override
	public boolean shouldRenderOffScreen(Minerals.Core core) {
		return true; // it is larger than its block
	}

	/**
	 * Owner 18.10: seen from afar, as SC2's fields are — the game draws a block entity only within 64 blocks, and from
	 * the command view's height a field 60 blocks off showed nothing but its dark earth.
	 */
	@Override
	public int getViewDistance() {
		return 256;
	}

	@Override
	public boolean shouldRender(Minerals.Core core, net.minecraft.world.phys.Vec3 camera) {
		return net.minecraft.world.phys.Vec3.atCenterOf(core.getBlockPos()).closerThan(camera, getViewDistance());
	}

	@Override
	public void render(Minerals.Core core, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
		VertexConsumer out = buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
		RandomSource random = RandomSource.create(core.getBlockPos().asLong());
		float left = core.visualHeight() / 1.85f;
		int glow = Math.max(light, GLOW);
		int shards = 6 + random.nextInt(3);
		pose.pushPose();
		pose.scale(core.size() / 2f, 1f, core.size() / 2f);
		for (int i = 0; i < 8; i++) {
			double angle = i * Math.PI / 4;
			pose.pushPose();
			pose.translate(1 + Math.cos(angle) * .65, 0, 1 + Math.sin(angle) * .65);
			shard(pose.last(), out, .45f, .3f, overlay, glow, 220, core.rich());
			pose.popPose();
		}
		for (int i = 0; i < shards; i++) {
			float x, z, height, width;
			if (i == 0) { // the tallest in the middle
				x = 1f;
				z = 1f;
				height = 1.85f;
				width = 0.55f;
			} else {
				double angle = (i + random.nextFloat() * 0.5) * Math.PI * 2 / (shards - 1);
				float r = 0.45f + random.nextFloat() * 0.35f;
				x = 1f + (float) Math.cos(angle) * r;
				z = 1f + (float) Math.sin(angle) * r;
				height = 0.8f + random.nextFloat() * 0.8f;
				width = 0.3f + random.nextFloat() * 0.2f;
			}
			pose.pushPose();
			pose.translate(x, 0, z);
			pose.mulPose(Axis.YP.rotationDegrees(random.nextFloat() * 360));
			float lean = i == 0 ? 0 : 10 + random.nextFloat() * 18;
			pose.mulPose(Axis.ZP.rotationDegrees(lean));
			int shade = i == 0 ? 255 : 220 + random.nextInt(36);
			if (i < core.visibleShards(shards))
				shard(pose.last(), out, width, height * left, overlay, glow, shade, core.rich());
			pose.popPose();
		}
		pose.popPose();
	}

	/** A six-sided prism `width` across rising to 70 % of `height`, then a point. */
	private static void shard(PoseStack.Pose pose, VertexConsumer out, float width, float height, int overlay, int light, int shade, boolean gold) {
		float r = width / 2, body = height * 0.7f;
		Vector3f[] ring = new Vector3f[6];
		for (int k = 0; k < 6; k++) {
			double a = k * Math.PI / 3;
			ring[k] = new Vector3f((float) Math.cos(a) * r, 0, (float) Math.sin(a) * r);
		}
		for (int k = 0; k < 6; k++) {
			Vector3f a = ring[k], b = ring[(k + 1) % 6];
			Vector3f normal = new Vector3f(a.x + b.x, 0, a.z + b.z).normalize();
			float u0 = k / 6f, u1 = (k + 1) / 6f;
			// the side
			vertex(out, pose, a.x, 0, a.z, u0, 1, normal, overlay, light, shade, gold);
			vertex(out, pose, b.x, 0, b.z, u1, 1, normal, overlay, light, shade, gold);
			vertex(out, pose, b.x, body, b.z, u1, 0.3f, normal, overlay, light, shade, gold);
			vertex(out, pose, a.x, body, a.z, u0, 0.3f, normal, overlay, light, shade, gold);
			// the point (a quad with its two top corners together)
			Vector3f up = new Vector3f(normal.x, 0.6f, normal.z).normalize();
			vertex(out, pose, a.x, body, a.z, u0, 0.3f, up, overlay, light, shade, gold);
			vertex(out, pose, b.x, body, b.z, u1, 0.3f, up, overlay, light, shade, gold);
			vertex(out, pose, 0, height, 0, (u0 + u1) / 2, 0, up, overlay, light, shade, gold);
			vertex(out, pose, 0, height, 0, (u0 + u1) / 2, 0, up, overlay, light, shade, gold);
		}
	}

	private static void vertex(VertexConsumer out, PoseStack.Pose pose, float x, float y, float z, float u, float v, Vector3f normal,
			int overlay, int light, int shade, boolean gold) {
		// owner 17.10: a gold crystal of the Void — the blue shards tinted gold
		out.addVertex(pose, x, y, z).setColor(gold ? 255 : shade, gold ? shade * 200 / 255 : shade, gold ? 70 : 255, 255).setUv(u, v).setOverlay(overlay).setLight(light)
			.setNormal(pose, normal.x, normal.y, normal.z);
	}
}
