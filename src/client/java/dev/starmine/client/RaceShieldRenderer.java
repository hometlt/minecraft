package dev.starmine.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.starmine.StarMine;
import dev.starmine.entity.UnitKind;
import dev.starmine.gear.RaceTools;
import java.util.Locale;
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ShieldModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/** Owner 19.10: the races' shields, drawn as vanilla's shield in the race's look (`textures/entity/shield/<race>.png`). */
final class RaceShieldRenderer {
	private static ShieldModel model;

	private RaceShieldRenderer() {
	}

	static void register() {
		for (UnitKind.Race race : UnitKind.Race.values()) {
			Item shield = RaceTools.item(race, "shield");
			ResourceLocation texture = StarMine.id("textures/entity/shield/" + race.name().toLowerCase(Locale.ROOT) + ".png");
			BuiltinItemRendererRegistry.INSTANCE.register(shield, (stack, mode, pose, buffers, light, overlay) -> render(stack, texture, pose, buffers, light, overlay));
			ItemProperties.register(shield, ResourceLocation.withDefaultNamespace("blocking"),
				(stack, level, entity, seed) -> entity != null && entity.isUsingItem() && entity.getUseItem() == stack ? 1.0f : 0.0f);
		}
	}

	private static void render(ItemStack stack, ResourceLocation texture, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
		if (model == null) model = new ShieldModel(Minecraft.getInstance().getEntityModels().bakeLayer(ModelLayers.SHIELD));
		pose.pushPose();
		pose.scale(1.0f, -1.0f, -1.0f);
		VertexConsumer vertices = ItemRenderer.getFoilBufferDirect(buffers, model.renderType(texture), true, stack.hasFoil());
		model.handle().render(pose, vertices, light, overlay);
		model.plate().render(pose, vertices, light, overlay);
		pose.popPose();
	}
}
