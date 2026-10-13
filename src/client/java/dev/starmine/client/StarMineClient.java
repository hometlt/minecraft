package dev.starmine.client;

import dev.starmine.StarMine;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.server.packs.PackType;

/** Client side: StarMine model loading and mob renderers. */
public final class StarMineClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new StarMineModels());
		EntityRendererRegistry.register(StarMine.ZERGLING, ZerglingRenderer::new);
	}
}
