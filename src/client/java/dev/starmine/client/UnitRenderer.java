package dev.starmine.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.starmine.StarMine;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
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
public class UnitRenderer extends EntityRenderer<StarUnit> {
	private float[] palette = new float[0], second = new float[0];
	private final SkinnedModelDrawer drawer = new SkinnedModelDrawer();

	private final ItemRenderer items;

	public UnitRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.items = context.getItemRenderer();
		this.shadowRadius = 0.5f;
	}

	@Override
	public ResourceLocation getTextureLocation(StarUnit entity) {
		return StarMine.id("textures/entity/sc2/" + entity.kind.id + ".png");
	}

	@Override
	public void render(StarUnit entity, float entityYaw, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int packedLight) {
		if (entity.isInvisible()) return; // a worker inside a vespene building
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
			this.shadowRadius = entity.kind.flying ? entity.kind.width * 0.3f : entity.kind.width * 0.5f;
			choosePose(model, entity, partialTick);
			drawer.skin(model, palette);
			poseStack.pushPose();
			if (sink > 0f) poseStack.translate(0f, -sink, 0f);
			float bodyYaw = Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot);
			poseStack.mulPose(Axis.YP.rotationDegrees(-bodyYaw));
			float scale = entity.kind.modelScale();
			if (scale != 1f) poseStack.scale(scale, scale, scale);
			int overlay = OverlayTexture.pack(OverlayTexture.u(0f), OverlayTexture.v(entity.hurtTime > 0 && entity.deathTime == 0));
			drawer.draw(model, poseStack.last(), buffers, packedLight, overlay);
			if (entity.kind == UnitKind.DRONE && entity.deathTime == 0) drawCarried(entity, model, poseStack, buffers, packedLight);
			poseStack.popPose();
		}
		super.render(entity, entityYaw, partialTick, poseStack, buffers, packedLight);
	}

	/** The drone's valuable slot held in front of it: one small block per unit, up to 8 (2x2x2). */
	private void drawCarried(StarUnit drone, SkinnedModel model, PoseStack poseStack, MultiBufferSource buffers, int light) {
		ItemStack carried = drone.carried();
		if (carried.isEmpty()) return;
		int cubes = Math.min(8, carried.getCount());
		ItemStack one = carried.copyWithCount(1);
		for (int i = 0; i < cubes; i++) {
			poseStack.pushPose();
			poseStack.translate(((i & 1) - 0.5) * 0.17, model.size[1] * 0.25 + (i >> 2) * 0.17, model.size[2] * 0.36 + ((i >> 1) & 1) * 0.17);
			poseStack.scale(0.17f, 0.17f, 0.17f);
			items.renderStatic(one, ItemDisplayContext.FIXED, light, OverlayTexture.NO_OVERLAY, poseStack, buffers, drone.level(), drone.getId() + i);
			poseStack.popPose();
		}
	}

	private void choosePose(SkinnedModel model, StarUnit entity, float partialTick) {
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

}
