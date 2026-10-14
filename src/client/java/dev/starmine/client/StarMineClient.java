package dev.starmine.client;

import dev.starmine.StarMine;
import dev.starmine.entity.UnitKind;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import dev.starmine.block.StarMineBlocks;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraft.server.packs.PackType;

/**
 * Client side: StarMine model loading, unit, missile and creep tumor renderers, the creep layer's render
 * type; the terran base's resource panel on the HUD and the command view (key B by default).
 */
public final class StarMineClient implements ClientModInitializer {
	private static final net.minecraft.client.KeyMapping COMMAND = net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.registerKeyBinding(
		new net.minecraft.client.KeyMapping("key.starmine.command", com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM,
			org.lwjgl.glfw.GLFW.GLFW_KEY_B, "category.starmine"));

	@Override
	public void onInitializeClient() {
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(dev.starmine.colony.ColonyNetwork.BaseStatus.TYPE,
			(status, context) -> BaseStatusClient.receive(status));
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (COMMAND.consumeClick()) if (!CommandView.active()) CommandView.enter(mc);
			CommandView.tick(mc);
		});
		net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register((graphics, delta) -> {
			if (!CommandView.active()) BaseStatusClient.drawPanel(graphics, graphics.guiWidth());
		});
		net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.AFTER_ENTITIES.register(CommandView::render);
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
			BaseStatusClient.receive(dev.starmine.colony.ColonyNetwork.BaseStatus.NONE);
			CommandView.exit(mc);
		});
		ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new StarMineModels());
		for (UnitKind kind : UnitKind.values()) EntityRendererRegistry.register(StarMine.type(kind), UnitRenderer::new);
		EntityRendererRegistry.register(StarMine.SPIT, ThrownItemRenderer::new);
		BlockEntityRenderers.register(StarMineBlocks.CREEP_TUMOR_ENTITY, CreepTumorRenderer::new);
		BlockRenderLayerMap.INSTANCE.putBlocks(RenderType.cutout(), StarMineBlocks.CREEP, StarMineBlocks.CREEP_TUMOR,
			StarMineBlocks.CREEP_TENDRIL, StarMineBlocks.CREEP_PUSTULE, StarMineBlocks.CREEP_VESSELS, StarMineBlocks.CREEP_TENTACLE);
	}
}
