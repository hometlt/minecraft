package dev.starmine.client;

import dev.starmine.StarMine;
import dev.starmine.entity.ZergKind;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import dev.starmine.block.StarMineBlocks;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraft.server.packs.PackType;

/** Client side: StarMine model loading, zerg, missile and creep tumor renderers, the creep layer's render type. */
public final class StarMineClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new StarMineModels());
		for (ZergKind kind : ZergKind.values()) EntityRendererRegistry.register(StarMine.type(kind), ZergRenderer::new);
		EntityRendererRegistry.register(StarMine.SPIT, ThrownItemRenderer::new);
		BlockEntityRenderers.register(StarMineBlocks.CREEP_TUMOR_ENTITY, CreepTumorRenderer::new);
		BlockRenderLayerMap.INSTANCE.putBlocks(RenderType.cutout(), StarMineBlocks.CREEP, StarMineBlocks.CREEP_TUMOR);
	}
}
