package dev.starmine.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.starmine.StarMine;
import dev.starmine.world.Amun;
import dev.starmine.world.VoidForces;
import dev.starmine.world.VoidRift;
import dev.starmine.world.VoidShard;
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EnderDragonRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.EyesLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import org.joml.Quaternionf;

/** How the Void's foes look (owner 17.10): Amun, the void shards with their beams on him, the rifts. */
final class VoidRenderers {
	private VoidRenderers() {
	}

	static void register() {
		EntityModelLayerRegistry.registerModelLayer(AmunModel.LAYER, AmunModel::createBodyLayer);
		EntityRendererRegistry.register(VoidForces.AMUN, AmunRenderer::new);
		EntityRendererRegistry.register(VoidForces.SHARD, ShardRenderer::new);
		EntityRendererRegistry.register(VoidForces.RIFT, RiftRenderer::new);
	}

	/** Amun, three times his model's size; his eyes burn in the dark. */
	static final class AmunRenderer extends MobRenderer<Amun, AmunModel> {
		private static final ResourceLocation SKIN = StarMine.id("textures/entity/amun.png");
		private static final RenderType EYES = RenderType.eyes(StarMine.id("textures/entity/amun_eyes.png"));
		static final float SCALE = 3.0f;

		AmunRenderer(EntityRendererProvider.Context context) {
			super(context, new AmunModel(context.bakeLayer(AmunModel.LAYER)), 4.0f);
			addLayer(new EyesLayer<>(this) {
				@Override
				public RenderType renderType() {
					return EYES;
				}
			});
		}

		@Override
		protected int getBlockLightLevel(Amun amun, net.minecraft.core.BlockPos pos) {
			return 11; // the Void's eternal night: he glows dimly of himself, or he could not be seen
		}

		@Override
		protected void scale(Amun amun, PoseStack pose, float partial) {
			pose.scale(SCALE, SCALE, SCALE);
		}

		@Override
		public ResourceLocation getTextureLocation(Amun amun) {
			return SKIN;
		}

		@Override
		protected RenderType getRenderType(Amun amun, boolean visible, boolean translucent, boolean outline) {
			return RenderType.entityCutoutNoCull(SKIN); // the wings are planes: seen from both sides
		}

		@Override
		public boolean shouldRender(Amun amun, net.minecraft.client.renderer.culling.Frustum frustum, double x, double y, double z) {
			return true;
		}
	}

	/** A void shard: the end crystal's shape in red and black, turning; its beam on Amun when he is near. */
	static final class ShardRenderer extends net.minecraft.client.renderer.entity.EntityRenderer<VoidShard> {
		private static final ResourceLocation SKIN = StarMine.id("textures/entity/void_shard.png");
		private static final RenderType TYPE = RenderType.entityCutoutNoCull(SKIN);
		private static final float SIN_45 = (float) Math.sin(Math.PI / 4);
		private final ModelPart cube, glass;

		ShardRenderer(EntityRendererProvider.Context context) {
			super(context);
			shadowRadius = 0.5f;
			ModelPart root = context.bakeLayer(ModelLayers.END_CRYSTAL);
			glass = root.getChild("glass");
			cube = root.getChild("cube");
		}

		@Override
		public void render(VoidShard shard, float yaw, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
			float age = shard.tickCount + partial;
			float bob = Mth.sin(age * 0.2f) / 2 + 0.5f;
			bob = (bob * bob + bob) * 0.4f - 1.4f;
			pose.pushPose();
			pose.scale(2, 2, 2);
			pose.translate(0, -0.5f, 0);
			VertexConsumer buffer = buffers.getBuffer(TYPE);
			float spin = age * 3;
			int full = net.minecraft.client.renderer.LightTexture.FULL_BRIGHT;
			pose.translate(0, 1.5f + bob / 2, 0);
			pose.mulPose(Axis.YP.rotationDegrees(spin));
			pose.mulPose(new Quaternionf().setAngleAxis((float) Math.PI / 3, SIN_45, 0, SIN_45));
			glass.render(pose, buffer, full, OverlayTexture.NO_OVERLAY);
			pose.scale(0.875f, 0.875f, 0.875f);
			pose.mulPose(new Quaternionf().setAngleAxis((float) Math.PI / 3, SIN_45, 0, SIN_45));
			pose.mulPose(Axis.YP.rotationDegrees(spin));
			glass.render(pose, buffer, full, OverlayTexture.NO_OVERLAY);
			pose.scale(0.875f, 0.875f, 0.875f);
			pose.mulPose(new Quaternionf().setAngleAxis((float) Math.PI / 3, SIN_45, 0, SIN_45));
			pose.mulPose(Axis.YP.rotationDegrees(spin));
			cube.render(pose, buffer, full, OverlayTexture.NO_OVERLAY);
			pose.popPose();
			Entity amun = shard.amun() < 0 ? null : shard.level().getEntity(shard.amun());
			if (amun != null) { // the beam, from the shard up to Amun
				float x = (float) (Mth.lerp(partial, amun.xo, amun.getX()) - Mth.lerp(partial, shard.xo, shard.getX()));
				float y = (float) (Mth.lerp(partial, amun.yo, amun.getY()) + amun.getBbHeight() / 2 - Mth.lerp(partial, shard.yo, shard.getY()) - 1.6);
				float z = (float) (Mth.lerp(partial, amun.zo, amun.getZ()) - Mth.lerp(partial, shard.zo, shard.getZ()));
				pose.pushPose();
				pose.translate(0, 1.6, 0);
				EnderDragonRenderer.renderCrystalBeams(x, y, z, partial, shard.tickCount, pose, buffers, light);
				pose.popPose();
			}
			super.render(shard, yaw, partial, pose, buffers, light);
		}

		@Override
		public ResourceLocation getTextureLocation(VoidShard shard) {
			return SKIN;
		}

		@Override
		public boolean shouldRender(VoidShard shard, net.minecraft.client.renderer.culling.Frustum frustum, double x, double y, double z) {
			return true;
		}
	}

	/** A void rift: a turning disc of the Void, upright, facing the viewer. */
	static final class RiftRenderer extends net.minecraft.client.renderer.entity.EntityRenderer<VoidRift> {
		private static final ResourceLocation SKIN = StarMine.id("textures/entity/void_rift.png");

		RiftRenderer(EntityRendererProvider.Context context) {
			super(context);
		}

		@Override
		public void render(VoidRift rift, float yaw, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
			pose.pushPose();
			pose.translate(0, rift.getBbHeight() / 2, 0);
			pose.mulPose(entityRenderDispatcher.cameraOrientation());
			float age = rift.tickCount + partial;
			float pulse = 1 + Mth.sin(age * 0.15f) * 0.06f;
			int full = net.minecraft.client.renderer.LightTexture.FULL_BRIGHT;
			for (int layer = 0; layer < 2; layer++) {
				pose.pushPose();
				pose.mulPose(Axis.ZP.rotationDegrees(age * (layer == 0 ? 4 : -6)));
				float w = (layer == 0 ? 1.6f : 1.1f) * pulse, h = (layer == 0 ? 2.2f : 1.6f) * pulse;
				VertexConsumer buffer = buffers.getBuffer(RenderType.entityTranslucentEmissive(SKIN));
				var last = pose.last();
				int alpha = layer == 0 ? 230 : 160;
				vertex(buffer, last, -w, -h, 0, 1, full, alpha);
				vertex(buffer, last, w, -h, 1, 1, full, alpha);
				vertex(buffer, last, w, h, 1, 0, full, alpha);
				vertex(buffer, last, -w, h, 0, 0, full, alpha);
				pose.popPose();
			}
			pose.popPose();
			super.render(rift, yaw, partial, pose, buffers, light);
		}

		private static void vertex(VertexConsumer buffer, PoseStack.Pose pose, float x, float y, float u, float v, int light, int alpha) {
			buffer.addVertex(pose, x, y, 0).setColor(255, 255, 255, alpha).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
				.setNormal(pose, 0, 0, 1);
		}

		@Override
		public ResourceLocation getTextureLocation(VoidRift rift) {
			return SKIN;
		}
	}
}
