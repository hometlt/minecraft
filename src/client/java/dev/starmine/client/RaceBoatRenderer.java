package dev.starmine.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.starmine.StarMine;
import dev.starmine.gear.RaceBoats;
import java.util.Locale;
import net.minecraft.client.model.BoatModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.vehicle.Boat;

/** Owner 19.10: a race's boat — vanilla's boat in the race's stuff (`textures/entity/boat/<race>.png`). */
final class RaceBoatRenderer extends EntityRenderer<RaceBoats.RaceBoat> {
	private final BoatModel model;

	RaceBoatRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 0.8f;
		this.model = new BoatModel(context.bakeLayer(ModelLayers.createBoatModelName(Boat.Type.OAK)));
	}

	@Override
	public void render(RaceBoats.RaceBoat boat, float yaw, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
		pose.pushPose();
		pose.translate(0, 0.375, 0);
		pose.mulPose(Axis.YP.rotationDegrees(180 - yaw));
		float hurt = boat.getHurtTime() - partial, damage = Math.max(0, boat.getDamage() - partial);
		if (hurt > 0) pose.mulPose(Axis.XP.rotationDegrees(Mth.sin(hurt) * hurt * damage / 10f * boat.getHurtDir()));
		// owner 19.10: the terran cabin of mineral glass; the diving zerg boat closed over in flesh
		net.minecraft.world.level.block.state.BlockState cover = boat.race == dev.starmine.entity.UnitKind.Race.TERRAN
			? dev.starmine.block.CraftBlocks.MINERAL_GLASS.defaultBlockState()
			: boat.race == dev.starmine.entity.UnitKind.Race.ZERG && boat.maneuver() == RaceBoats.DIVE ? dev.starmine.block.StarMineBlocks.ZERG_FLESH.defaultBlockState() : null;
		if (cover != null) {
			pose.pushPose();
			pose.translate(-0.6, 0.05, -0.8);
			pose.scale(1.2f, 1.1f, 1.6f);
			net.minecraft.client.Minecraft.getInstance().getBlockRenderer().renderSingleBlock(cover, pose, buffers, light, OverlayTexture.NO_OVERLAY);
			pose.popPose();
		}
		pose.scale(-1, -1, 1);
		pose.mulPose(Axis.YP.rotationDegrees(90));
		model.setupAnim(boat, partial, 0, -0.1f, 0, 0);
		VertexConsumer vertices = buffers.getBuffer(model.renderType(getTextureLocation(boat)));
		model.renderToBuffer(pose, vertices, light, OverlayTexture.NO_OVERLAY);
		if (!boat.isUnderWater()) model.waterPatch().render(pose, buffers.getBuffer(RenderType.waterMask()), light, OverlayTexture.NO_OVERLAY);
		pose.popPose();
		super.render(boat, yaw, partial, pose, buffers, light);
	}

	@Override
	public ResourceLocation getTextureLocation(RaceBoats.RaceBoat boat) {
		return StarMine.id("textures/entity/boat/" + boat.race.name().toLowerCase(Locale.ROOT) + ".png");
	}
}
