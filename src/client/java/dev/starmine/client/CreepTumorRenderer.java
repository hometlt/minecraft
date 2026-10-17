package dev.starmine.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.starmine.block.CreepTumorBlock;
import dev.starmine.block.CreepTumorBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;

/**
 * Draws a creep tumor from its SC2 model, its base on the face it grows from (floor, wall or
 * ceiling), playing its stand loop; neighbouring tumors are out of step. Burrowed after its first seconds (owner
 * 17.10, SC2): drawn only for its own side and where a detector of the player's side sees it; the creep under it is the
 * block model.
 */
public class CreepTumorRenderer implements BlockEntityRenderer<CreepTumorBlockEntity> {
	private final SkinnedModelDrawer drawer = new SkinnedModelDrawer();
	private float[] palette = new float[0];

	public CreepTumorRenderer(BlockEntityRendererProvider.Context context) {
	}

	@Override
	public void render(CreepTumorBlockEntity tumor, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light, int overlay) {
		SkinnedModel model = StarMineModels.get("creeptumor");
		if (model == null || tumor.getLevel() == null) return;
		var player = net.minecraft.client.Minecraft.getInstance().player; // owner 17.10: burrowed, only its side and detectors see it
		if (player != null && !dev.starmine.creep.CreepTumors.seen(tumor.getLevel(), tumor.getBlockPos(), player)) return;
		SkinnedModel.Clip stand = model.clips.get("stand");
		if (stand == null) return;
		if (palette.length != model.slots * 12) palette = new float[model.slots * 12];
		float time = (tumor.getLevel().getGameTime() + partialTick) / 20f + (tumor.getBlockPos().hashCode() & 63) * 0.1f;
		model.pose(stand, time, palette);
		drawer.skin(model, palette);
		Direction facing = tumor.getBlockState().getValue(CreepTumorBlock.FACING);
		poseStack.pushPose();
		poseStack.translate(0.5, 0.5, 0.5);
		poseStack.mulPose(facing.getRotation());
		poseStack.translate(0, -0.5, 0);
		float size = Math.max(model.size[0], model.size[2]);
		if (size > 1.4f) poseStack.scale(1.4f / size, 1.4f / size, 1.4f / size);
		drawer.draw(model, poseStack.last(), buffers, light, OverlayTexture.NO_OVERLAY);
		poseStack.popPose();
	}
}
