package dev.starmine.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.starmine.StarMine;
import dev.starmine.ability.NukeMissile;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3f;

/** The nuclear missile (owner 17.10): a long white body with a black-and-yellow band, a red nose, four fins and its flame. Own art. */
public final class NukeRenderer extends EntityRenderer<NukeMissile> {
	private static final ResourceLocation WHITE = StarMine.id("textures/entity/shot.png");

	public NukeRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 0;
	}

	@Override
	public ResourceLocation getTextureLocation(NukeMissile entity) {
		return WHITE;
	}

	@Override
	public void render(NukeMissile missile, float entityYaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
		VertexConsumer out = buffers.getBuffer(RenderType.entityCutoutNoCull(WHITE));
		pose.pushPose();
		pose.translate(0, 0.8, 0);
		pose.mulPose(Axis.ZP.rotationDegrees(missile.falling() ? -90 : 90)); // x along its flight: up, or down
		PoseStack.Pose last = pose.last();
		ShotRenderer.prism(last, out, -1.1f, 0.6f, 0.22f, 10, 0xFFE8E8E4, light);
		ShotRenderer.prism(last, out, 0.1f, 0.3f, 0.23f, 10, 0xFFE0C020, light);
		ShotRenderer.prism(last, out, -0.1f, 0.1f, 0.23f, 10, 0xFF202020, light);
		ShotRenderer.cone(last, out, 0.6f, 1.1f, 0.22f, 10, 0xFFC02018, light);
		for (int k = 0; k < 4; k++) {
			pose.pushPose();
			pose.mulPose(Axis.XP.rotationDegrees(k * 90 + 45));
			ShotRenderer.quad(pose.last(), out, new Vector3f(-1.1f, 0, 0), new Vector3f(-0.6f, 0, 0), new Vector3f(-0.8f, 0.45f, 0), new Vector3f(-1.2f, 0.45f, 0),
				0xFF505860, light);
			pose.popPose();
		}
		ShotRenderer.cone(last, out, -1.1f, -1.9f, 0.18f, 8, 0xFFFFB040, LightTexture.FULL_BRIGHT); // the flame
		pose.popPose();
		super.render(missile, entityYaw, partialTick, pose, buffers, light);
	}
}
