package dev.starmine.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.starmine.world.VisionBlocker;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;

/**
 * A sight blocker as a thicket (owner 17.10: as high as the blocker, {@link VisionBlocker#hiding}):
 * in every block of its ground a pair of crossed grass cards, one tall card stack from the ground to
 * its top (the tall grass texture, tinted green, lower blocks the stalks, the top block the tips),
 * each a little off the block's middle. It has no body: units walk through it; it only hides.
 */
final class VisionBlockerRenderer extends EntityRenderer<VisionBlocker> {
	private static final ResourceLocation STALKS = ResourceLocation.withDefaultNamespace("textures/block/tall_grass_bottom.png");
	private static final ResourceLocation TIPS = ResourceLocation.withDefaultNamespace("textures/block/tall_grass_top.png");
	private static final float R = 0.36f, G = 0.62f, B = 0.24f;

	VisionBlockerRenderer(EntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	public boolean shouldRender(VisionBlocker entity, net.minecraft.client.renderer.culling.Frustum frustum, double x, double y, double z) {
		return frustum.isVisible(entity.hiding()) && entity.distanceToSqr(x, y, z) < 160 * 160;
	}

	@Override
	public void render(VisionBlocker entity, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
		AABB box = entity.hiding();
		int sizeX = (int) Math.round(box.getXsize()), sizeZ = (int) Math.round(box.getZsize()), height = (int) Math.round(box.getYsize());
		VertexConsumer stalks = buffers.getBuffer(RenderType.entityCutoutNoCull(STALKS));
		VertexConsumer tips = buffers.getBuffer(RenderType.entityCutoutNoCull(TIPS));
		PoseStack.Pose at = pose.last();
		for (int ix = 0; ix < sizeX; ix++)
			for (int iz = 0; iz < sizeZ; iz++) {
				long seed = Mth.getSeed((int) box.minX + ix, 0, (int) box.minZ + iz);
				float cx = ix + 0.5f + ((seed & 15) - 7.5f) / 40f, cz = iz + 0.5f + (((seed >> 4) & 15) - 7.5f) / 40f;
				int top = height - (int) ((seed >> 8) & 1); // a little ragged on top
				for (int y = 0; y < top; y++) {
					VertexConsumer out = y == top - 1 ? tips : stalks;
					card(at, out, cx - R, y, cz - R, cx + R, cz + R, light);
					card(at, out, cx - R, y, cz + R, cx + R, cz - R, light);
				}
			}
	}

	/** One vertical card from (x0, z0) to (x1, z1), a block high from `y`. */
	private static void card(PoseStack.Pose pose, VertexConsumer out, float x0, float y, float z0, float x1, float z1, int light) {
		vertex(pose, out, x0, y, z0, 0, 1, light);
		vertex(pose, out, x1, y, z1, 1, 1, light);
		vertex(pose, out, x1, y + 1, z1, 1, 0, light);
		vertex(pose, out, x0, y + 1, z0, 0, 0, light);
	}

	private static void vertex(PoseStack.Pose pose, VertexConsumer out, float x, float y, float z, float u, float v, int light) {
		out.addVertex(pose, x, y, z).setColor(R, G, B, 1f).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, 0, 1, 0);
	}

	@Override
	public ResourceLocation getTextureLocation(VisionBlocker entity) {
		return STALKS;
	}
}
