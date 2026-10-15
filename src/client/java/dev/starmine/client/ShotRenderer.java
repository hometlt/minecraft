package dev.starmine.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.starmine.StarMine;
import dev.starmine.entity.UnitKind;
import dev.starmine.entity.ZergSpit;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Vector3f;

/**
 * Shots in flight (owner 16.10: a model for each kind of missile), drawn from simple solids along the
 * direction they fly: a rocket — a grey body, a red nose, fins and a flame at its tail; a grenade — a dark
 * shell; plasma — a bright blue crystal turning; acid — a green drop; a needle spine — a long bone point;
 * corrosive spray — a violet drop; a brood lord's egg — a pale egg. Own art.
 */
public final class ShotRenderer extends EntityRenderer<ZergSpit> {
	private static final ResourceLocation WHITE = StarMine.id("textures/entity/shot.png");

	public ShotRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 0;
	}

	@Override
	public ResourceLocation getTextureLocation(ZergSpit entity) {
		return WHITE;
	}

	@Override
	public void render(ZergSpit shot, float entityYaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
		UnitKind.Shot style = shot.shot();
		boolean glow = style == UnitKind.Shot.PLASMA || style == UnitKind.Shot.ACID || style == UnitKind.Shot.CORROSIVE || style == UnitKind.Shot.ROCKET;
		int packed = glow ? LightTexture.FULL_BRIGHT : light;
		VertexConsumer out = buffers.getBuffer(RenderType.entityCutoutNoCull(WHITE));
		pose.pushPose();
		pose.translate(0, 0.1, 0);
		// along its flight: x forward
		pose.mulPose(Axis.YP.rotationDegrees(Mth.lerp(partialTick, shot.yRotO, shot.getYRot()) - 90));
		pose.mulPose(Axis.ZP.rotationDegrees(Mth.lerp(partialTick, shot.xRotO, shot.getXRot())));
		PoseStack.Pose last = pose.last();
		float spin = (shot.tickCount + partialTick) * 20;
		switch (style) {
			case ROCKET -> {
				prism(last, out, -0.35f, 0.3f, 0.07f, 8, 0xFF9AA0A8, packed);
				cone(last, out, 0.3f, 0.48f, 0.07f, 8, 0xFFD03020, packed);
				for (int k = 0; k < 4; k++) { // fins
					pose.pushPose();
					pose.mulPose(Axis.XP.rotationDegrees(k * 90));
					quad(pose.last(), out, new Vector3f(-0.35f, 0, 0), new Vector3f(-0.2f, 0, 0), new Vector3f(-0.25f, 0.14f, 0), new Vector3f(-0.38f, 0.14f, 0),
						0xFF606870, packed);
					pose.popPose();
				}
				cone(last, out, -0.35f, -0.65f, 0.06f, 6, 0xFFFFB040, LightTexture.FULL_BRIGHT); // the flame
			}
			case GRENADE -> {
				pose.mulPose(Axis.XP.rotationDegrees(spin));
				crystal(pose.last(), out, 0.13f, 0.13f, 0xFF35383C, packed);
			}
			case PLASMA -> {
				pose.mulPose(Axis.XP.rotationDegrees(spin));
				crystal(pose.last(), out, 0.28f, 0.14f, 0xFF60C0FF, packed);
				crystal(pose.last(), out, 0.16f, 0.08f, 0xFFE8F8FF, packed);
			}
			case NEEDLE -> {
				prism(last, out, -0.4f, 0.1f, 0.05f, 5, 0xFFE0D8C0, packed);
				cone(last, out, 0.1f, 0.45f, 0.05f, 5, 0xFFF4EEDC, packed);
			}
			case CORROSIVE -> {
				pose.mulPose(Axis.XP.rotationDegrees(spin));
				crystal(pose.last(), out, 0.3f, 0.18f, 0xFFA040D0, packed);
			}
			case EGG -> crystal(last, out, 0.3f, 0.22f, 0xFFE8E0C8, packed);
			default -> { // acid: a drop drawn out behind
				crystal(last, out, 0.26f, 0.15f, 0xFF70E040, packed);
				cone(last, out, -0.1f, -0.4f, 0.1f, 6, 0xFF50B030, packed);
			}
		}
		pose.popPose();
		super.render(shot, entityYaw, partialTick, pose, buffers, light);
	}

	/** A prism along x from x0 to x1, `sides` sided, radius r. */
	private static void prism(PoseStack.Pose last, VertexConsumer out, float x0, float x1, float r, int sides, int colour, int light) {
		for (int k = 0; k < sides; k++) {
			double a = k * Math.PI * 2 / sides, b = (k + 1) * Math.PI * 2 / sides;
			float ay = (float) Math.cos(a) * r, az = (float) Math.sin(a) * r, by = (float) Math.cos(b) * r, bz = (float) Math.sin(b) * r;
			quad(last, out, new Vector3f(x0, ay, az), new Vector3f(x1, ay, az), new Vector3f(x1, by, bz), new Vector3f(x0, by, bz), colour, light);
		}
	}

	/** A cone along x: base of radius r at x0, point at x1. */
	private static void cone(PoseStack.Pose last, VertexConsumer out, float x0, float x1, float r, int sides, int colour, int light) {
		for (int k = 0; k < sides; k++) {
			double a = k * Math.PI * 2 / sides, b = (k + 1) * Math.PI * 2 / sides;
			Vector3f tip = new Vector3f(x1, 0, 0);
			quad(last, out, new Vector3f(x0, (float) Math.cos(a) * r, (float) Math.sin(a) * r), new Vector3f(x0, (float) Math.cos(b) * r, (float) Math.sin(b) * r),
				tip, tip, colour, light);
		}
	}

	/** An eight-faced crystal: `length` along x, `width` across. */
	private static void crystal(PoseStack.Pose last, VertexConsumer out, float length, float width, int colour, int light) {
		Vector3f front = new Vector3f(length / 2, 0, 0), back = new Vector3f(-length / 2, 0, 0);
		Vector3f[] ring = {new Vector3f(0, width, 0), new Vector3f(0, 0, width), new Vector3f(0, -width, 0), new Vector3f(0, 0, -width)};
		for (int k = 0; k < 4; k++) {
			Vector3f a = ring[k], b = ring[(k + 1) % 4];
			quad(last, out, a, b, front, front, colour, light);
			quad(last, out, b, a, back, back, colour, light);
		}
	}

	private static void quad(PoseStack.Pose last, VertexConsumer out, Vector3f a, Vector3f b, Vector3f c, Vector3f d, int colour, int light) {
		Vector3f normal = new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a));
		if (normal.lengthSquared() < 1e-8) normal = new Vector3f(new Vector3f(c).sub(b)).cross(new Vector3f(d).sub(b));
		if (normal.lengthSquared() < 1e-8) normal.set(0, 1, 0);
		normal.normalize();
		for (Vector3f v : new Vector3f[] {a, b, c, d})
			out.addVertex(last, v.x, v.y, v.z).setColor(colour).setUv(0.5f, 0.5f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
				.setNormal(last, normal.x, normal.y, normal.z);
	}
}
