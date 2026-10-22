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

/**
 * How the Void's foes look (owner 17.10): Amun, the void shards with their beams on him, the rifts. Each is drawn
 * from its StarCraft II model (amun, voidshard, voidrift: Amon, the shard fortress and the void rift of the Legacy of
 * the Void epilogue, imported by Modkit) when the assets carry it, else from the mod's own look below.
 */
final class VoidRenderers {
	private static final SkinnedModelDrawer DRAWER = new SkinnedModelDrawer();
	private static float[] palette = new float[0];

	private VoidRenderers() {
	}

	/** Draws an SC2 model standing at the entity, as tall as the entity, playing its stand; false if the assets lack it. */
	static boolean drawSc2(String name, Entity entity, float yaw, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
		SkinnedModel model = StarMineModels.get(name);
		SkinnedModel.Clip stand = model == null ? null : model.clips.get("stand");
		if (stand == null) return false;
		if (palette.length != model.slots * 12) palette = new float[model.slots * 12];
		model.pose(stand, (entity.tickCount + partial) / 20f, palette);
		DRAWER.skin(model, palette);
		pose.pushPose();
		pose.mulPose(Axis.YP.rotationDegrees(-yaw));
		float scale = entity.getBbHeight() / Math.max(0.01f, model.size[1]);
		pose.scale(scale, scale, scale);
		DRAWER.draw(model, pose.last(), buffers, light, OverlayTexture.NO_OVERLAY);
		pose.popPose();
		return true;
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
		public void render(Amun amun, float yaw, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
			float body = Mth.rotLerp(partial, amun.yBodyRotO, amun.yBodyRot);
			if (!drawSc2("amun", amun, body, partial, pose, buffers, net.minecraft.client.renderer.LightTexture.pack(11, 15)))
				super.render(amun, yaw, partial, pose, buffers, light);
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
			if (drawSc2("voidshard", shard, 0, partial, pose, buffers, net.minecraft.client.renderer.LightTexture.FULL_BRIGHT)) {
				beam(shard, partial, pose, buffers, light);
				super.render(shard, yaw, partial, pose, buffers, light);
				return;
			}
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
			beam(shard, partial, pose, buffers, light);
			super.render(shard, yaw, partial, pose, buffers, light);
		}

		/** The beam, from the shard up to Amun when he is near. */
		private static void beam(VoidShard shard, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
			Entity amun = shard.amun() < 0 ? null : shard.level().getEntity(shard.amun());
			if (amun != null) {
				float x = (float) (Mth.lerp(partial, amun.xo, amun.getX()) - Mth.lerp(partial, shard.xo, shard.getX()));
				float y = (float) (Mth.lerp(partial, amun.yo, amun.getY()) + amun.getBbHeight() / 2 - Mth.lerp(partial, shard.yo, shard.getY()) - 1.6);
				float z = (float) (Mth.lerp(partial, amun.zo, amun.getZ()) - Mth.lerp(partial, shard.zo, shard.getZ()));
				pose.pushPose();
				pose.translate(0, 1.6, 0);
				EnderDragonRenderer.renderCrystalBeams(x, y, z, partial, shard.tickCount, pose, buffers, light);
				pose.popPose();
			}
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

	/** Player gates and Amun's one-way rifts share a rotating spherical surface at their actual diameter. */
	static final class RiftRenderer extends net.minecraft.client.renderer.entity.EntityRenderer<VoidRift> {
		private static final ResourceLocation SKIN = StarMine.id("textures/entity/void_portal_surface.png");
		RiftRenderer(EntityRendererProvider.Context context) { super(context); }
		/**
		 * Owner 22.10: a gate is see-through and glowing, and through it the world it leads to shows faintly — a panorama of
		 * that world (tools/gen_portal_views.py) seen as through a window: each point of the sphere shows what lies in the
		 * direction from the eye through it, so the view shifts as one walks round the gate. Over it a thin swirl of the
		 * gate's colour, round it a glow.
		 */
		@Override public void render(VoidRift rift, float yaw, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
			float radius = rift.size() / 2f;
			int color = rift.color();
			net.minecraft.world.phys.Vec3 cam = this.entityRenderDispatcher.camera.getPosition();
			net.minecraft.world.phys.Vec3 centre = new net.minecraft.world.phys.Vec3(net.minecraft.util.Mth.lerp(partial, rift.xo, rift.getX()),
				net.minecraft.util.Mth.lerp(partial, rift.yo, rift.getY()) + radius, net.minecraft.util.Mth.lerp(partial, rift.zo, rift.getZ()));
			pose.pushPose();
			pose.translate(0, radius, 0);
			// the world beyond: a window onto its panorama
			VertexConsumer view = buffers.getBuffer(RenderType.entityTranslucentEmissive(viewOf(color)));
			for (int lat = 0; lat < 16; lat++) for (int lon = 0; lon < 32; lon++) window(view, pose.last(), radius * 0.97f, lat, lon, centre, cam);
			// the swirl of the gate, thin
			pose.pushPose();
			pose.mulPose(Axis.YP.rotationDegrees((rift.tickCount + partial) * 1.2f));
			VertexConsumer buffer = buffers.getBuffer(RenderType.entityTranslucentEmissive(SKIN));
			for (int lat = 0; lat < 12; lat++) for (int lon = 0; lon < 24; lon++) {
				vertex(buffer, pose.last(), radius, lat, lon, color, 70);
				vertex(buffer, pose.last(), radius, lat, lon + 1, color, 70);
				vertex(buffer, pose.last(), radius, lat + 1, lon + 1, color, 70);
				vertex(buffer, pose.last(), radius, lat + 1, lon, color, 70);
			}
			pose.popPose();
			// the glow: a ring facing the eye at the gate's edge, brightest on the edge, fading out
			glow(buffers.getBuffer(RenderType.lightning()), pose, radius, color, centre, cam, (rift.tickCount + partial) * 0.08f);
			pose.popPose();
			super.render(rift, yaw, partial, pose, buffers, light);
		}

		private static final ResourceLocation OVERWORLD_VIEW = StarMine.id("textures/entity/portal_view/overworld.png"),
			NETHER_VIEW = StarMine.id("textures/entity/portal_view/nether.png"), END_VIEW = StarMine.id("textures/entity/portal_view/end.png"),
			SKY_VIEW = StarMine.id("textures/entity/portal_view/stratosphere.png"), VOID_VIEW = StarMine.id("textures/entity/portal_view/void.png");

		/** The world a gate leads to, by its colour ({@link VoidRift#color}). */
		private static ResourceLocation viewOf(int color) {
			return switch (color & 0xFFFFFF) {
				case 0x44DD77 -> OVERWORLD_VIEW;
				case 0xFF6028 -> NETHER_VIEW;
				case 0xF3D45A -> END_VIEW;
				case 0x70CBFF -> SKY_VIEW;
				default -> VOID_VIEW;
			};
		}

		/** One quad of the window: its corners take the panorama's point in the direction from the eye through them. */
		private static void window(VertexConsumer buffer, PoseStack.Pose pose, float r, int lat, int lon, net.minecraft.world.phys.Vec3 centre,
			net.minecraft.world.phys.Vec3 cam) {
			int[][] corners = {{lat, lon}, {lat, lon + 1}, {lat + 1, lon + 1}, {lat + 1, lon}};
			float[][] uv = new float[4][];
			float[][] at = new float[4][];
			for (int i = 0; i < 4; i++) {
				double a = Math.PI * corners[i][0] / 16 - Math.PI / 2, b = Math.PI * 2 * corners[i][1] / 32;
				float x = (float) (Math.cos(a) * Math.cos(b)), y = (float) Math.sin(a), z = (float) (Math.cos(a) * Math.sin(b));
				at[i] = new float[] {x, y, z};
				double dx = centre.x + x * r - cam.x, dy = centre.y + y * r - cam.y, dz = centre.z + z * r - cam.z, len = Math.sqrt(dx * dx + dy * dy + dz * dz) + 1e-6;
				float u = (float) (Math.atan2(dz, dx) / (Math.PI * 2) + 0.5), v = (float) (0.5 - Math.asin(dy / len) / Math.PI);
				if (i > 0 && u - uv[0][0] > 0.5f) u -= 1; // the panorama's seam: corners of one quad stay on one side
				else if (i > 0 && uv[0][0] - u > 0.5f) u += 1;
				uv[i] = new float[] {u, v};
			}
			for (int i = 0; i < 4; i++)
				buffer.addVertex(pose, at[i][0] * r, at[i][1] * r, at[i][2] * r).setColor(255, 255, 255, 120).setUv(uv[i][0], uv[i][1])
					.setOverlay(OverlayTexture.NO_OVERLAY).setLight(net.minecraft.client.renderer.LightTexture.FULL_BRIGHT).setNormal(pose, at[i][0], at[i][1], at[i][2]);
		}

		/** A glowing ring round the gate's silhouette (additive), turned to the eye, pulsing a little. */
		private static void glow(VertexConsumer buffer, PoseStack pose, float r, int color, net.minecraft.world.phys.Vec3 centre, net.minecraft.world.phys.Vec3 cam,
			float time) {
			org.joml.Vector3f toEye = new org.joml.Vector3f((float) (cam.x - centre.x), (float) (cam.y - centre.y), (float) (cam.z - centre.z));
			if (toEye.lengthSquared() < 1e-4f) return;
			toEye.normalize();
			org.joml.Vector3f side = new org.joml.Vector3f(0, 1, 0).cross(toEye);
			if (side.lengthSquared() < 1e-4f) side.set(1, 0, 0);
			side.normalize();
			org.joml.Vector3f up = new org.joml.Vector3f(toEye).cross(side).normalize();
			float red = (color >> 16 & 255) / 255f, green = (color >> 8 & 255) / 255f, blue = (color & 255) / 255f;
			float pulse = 0.75f + 0.25f * (float) Math.sin(time * 2.0);
			float inner = r * 0.9f, outer = r * (1.12f + 0.03f * (float) Math.sin(time * 3.1));
			PoseStack.Pose last = pose.last();
			int segments = 48;
			for (int i = 0; i < segments; i++) {
				double a0 = Math.PI * 2 * i / segments, a1 = Math.PI * 2 * (i + 1) / segments;
				float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
				org.joml.Vector3f e0 = new org.joml.Vector3f(side).mul(c0).add(new org.joml.Vector3f(up).mul(s0));
				org.joml.Vector3f e1 = new org.joml.Vector3f(side).mul(c1).add(new org.joml.Vector3f(up).mul(s1));
				// inner edge bright, outer edge faded out
				buffer.addVertex(last, e0.x * inner, e0.y * inner, e0.z * inner).setColor(red, green, blue, 0.55f * pulse);
				buffer.addVertex(last, e0.x * outer, e0.y * outer, e0.z * outer).setColor(red, green, blue, 0f);
				buffer.addVertex(last, e1.x * outer, e1.y * outer, e1.z * outer).setColor(red, green, blue, 0f);
				buffer.addVertex(last, e1.x * inner, e1.y * inner, e1.z * inner).setColor(red, green, blue, 0.55f * pulse);
			}
		}

		private static void vertex(VertexConsumer buffer, PoseStack.Pose pose, float r, int lat, int lon, int color, int alpha) {
			double a = Math.PI * lat / 12 - Math.PI / 2, b = Math.PI * 2 * lon / 24;
			float x = (float)(Math.cos(a) * Math.cos(b)), y = (float)Math.sin(a), z = (float)(Math.cos(a) * Math.sin(b));
			buffer.addVertex(pose, x * r, y * r, z * r).setColor(color >> 16 & 255, color >> 8 & 255, color & 255, alpha)
				.setUv(lon / 24f, lat / 12f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(net.minecraft.client.renderer.LightTexture.FULL_BRIGHT)
				.setNormal(pose, x, y, z);
		}
		@Override public ResourceLocation getTextureLocation(VoidRift rift) { return SKIN; }
	}
}
