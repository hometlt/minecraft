package dev.starmine.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.starmine.entity.UnitKind;
import dev.starmine.gear.Townsfolk;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.npc.WanderingTrader;

/**
 * Owner 19.10: the traders of the other races drawn with their SC2 models — the protoss preserver ({@code protosstrader})
 * and Dehaka ({@code dehaka}) — standing, walking as they move, at units' size ({@link UnitKind#UNIT_SIZE}). Without the
 * imported models they are drawn as the other townsfolk.
 */
public final class AlienTraderRenderer extends TownsfolkRenderer {
	private final SkinnedModelDrawer drawer = new SkinnedModelDrawer();
	private float[] palette = new float[0], second = new float[0];

	public AlienTraderRenderer(EntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	public void render(WanderingTrader trader, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
		SkinnedModel model = trader instanceof Townsfolk folk ? StarMineModels.get(folk.role == Townsfolk.Role.DEHAKA ? "dehaka" : "protosstrader") : null;
		if (model == null || model.clips.isEmpty()) {
			super.render(trader, yaw, partialTick, pose, buffers, light);
			return;
		}
		int size = model.slots * 12;
		if (palette.length != size) {
			palette = new float[size];
			second = new float[size];
		}
		float time = (trader.tickCount + partialTick) / 20f;
		SkinnedModel.Clip stand = model.clips.getOrDefault("stand", model.clips.values().iterator().next());
		model.pose(stand, time, palette);
		SkinnedModel.Clip walk = model.clips.get("walk");
		float moving = Math.min(1f, trader.walkAnimation.speed(partialTick) * 2.5f);
		if (walk != null && moving > 0.05f) {
			model.pose(walk, time * 1.4f, second);
			SkinnedModel.blend(palette, second, moving);
		}
		drawer.skin(model, palette);
		pose.pushPose();
		pose.mulPose(Axis.YP.rotationDegrees(-Mth.rotLerp(partialTick, trader.yBodyRotO, trader.yBodyRot)));
		pose.scale(UnitKind.UNIT_SIZE, UnitKind.UNIT_SIZE, UnitKind.UNIT_SIZE);
		drawer.draw(model, pose.last(), buffers, light, OverlayTexture.pack(OverlayTexture.u(0f), OverlayTexture.v(trader.hurtTime > 0)));
		pose.popPose();
		if (this.shouldShowName(trader)) this.renderNameTag(trader, trader.getDisplayName(), pose, buffers, light, partialTick);
	}
}
