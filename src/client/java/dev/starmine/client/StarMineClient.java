package dev.starmine.client;

import dev.starmine.StarMine;
import dev.starmine.entity.ZergKind;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraft.server.packs.PackType;

/** Client side: StarMine model loading, zerg renderers and the missile renderer. */
public final class StarMineClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new StarMineModels());
		for (ZergKind kind : ZergKind.values()) EntityRendererRegistry.register(StarMine.type(kind), ZergRenderer::new);
		EntityRendererRegistry.register(StarMine.SPIT, ThrownItemRenderer::new);
	}
}
