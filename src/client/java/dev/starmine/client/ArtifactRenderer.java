package dev.starmine.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.starmine.world.Artifact;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.phys.Vec3;

/** A whole artifact's beam (owner 17.10): violet, as a beacon's, from its top part up to the portal it opened. */
final class ArtifactRenderer implements BlockEntityRenderer<Artifact.Core> {
	private static final int COLOUR = 0xFFB070FF;

	ArtifactRenderer(BlockEntityRendererProvider.Context context) {
	}

	@Override
	public void render(Artifact.Core core, float partial, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
		if (core.getLevel() == null || !core.getBlockState().getValue(Artifact.ACTIVE)) return;
		int height = core.beam();
		if (height <= 0) return;
		BeaconRenderer.renderBeaconBeam(pose, buffers, BeaconRenderer.BEAM_LOCATION, partial, 1.0f, core.getLevel().getGameTime(), 4, height,
			COLOUR, 0.25f, 0.3f);
	}

	@Override
	public boolean shouldRenderOffScreen(Artifact.Core core) {
		return true;
	}

	@Override
	public int getViewDistance() {
		return 256;
	}

	@Override
	public boolean shouldRender(Artifact.Core core, Vec3 camera) {
		return Vec3.atCenterOf(core.getBlockPos()).multiply(1, 0, 1).closerThan(camera.multiply(1, 0, 1), getViewDistance());
	}
}
