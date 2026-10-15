package dev.starmine.client;

import net.minecraft.client.Minecraft;
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
		dev.starmine.colony.CommandPanel.openView = () -> {
			Minecraft mc = Minecraft.getInstance();
			if (!CommandView.active()) mc.execute(() -> CommandView.enter(mc));
		};
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(dev.starmine.colony.ColonyNetwork.BaseStatus.TYPE,
			(status, context) -> BaseStatusClient.receive(status));
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (COMMAND.consumeClick()) if (!CommandView.active()) CommandView.enterWithPanel(mc);
			PilotClient.tick(mc);
			CommandView.tick(mc);
		});
		net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register((graphics, delta) -> {
			PilotClient.drawHud(graphics);
		});
		net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.AFTER_ENTITIES.register(CommandView::render);
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
			BaseStatusClient.receive(dev.starmine.colony.ColonyNetwork.BaseStatus.NONE);
			CommandView.exit(mc);
			PilotClient.clear();
		});
		PilotClient.register();
		// modules (dev.starmine.possess): tinted by the race of their unit
		java.util.List<net.minecraft.world.item.Item> modules = new java.util.ArrayList<>(java.util.List.of(dev.starmine.possess.ModuleItem.WEAPON,
			dev.starmine.possess.ModuleItem.WEAPON_UPGRADE, dev.starmine.possess.ModuleItem.ARMOR_UPGRADE));
		for (dev.starmine.ability.Ability ability : dev.starmine.ability.Ability.values()) modules.add(dev.starmine.possess.ModuleItem.of(ability));
		net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry.ITEM.register((stack, tint) -> {
			UnitKind kind = dev.starmine.possess.ModuleItem.kindOf(stack);
			UnitKind.Race race = kind != null ? kind.race : stack.getItem() instanceof dev.starmine.possess.ModuleItem module && module.ability != null
				? module.ability.users().get(0).race : UnitKind.Race.TERRAN;
			return switch (race) {
				case ZERG -> 0xFFC070E0;
				case TERRAN -> 0xFF70B0FF;
				case PROTOSS -> 0xFFFFD050;
			};
		}, modules.toArray(net.minecraft.world.item.Item[]::new));
		ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new StarMineModels());
		for (UnitKind kind : UnitKind.values()) EntityRendererRegistry.register(StarMine.type(kind), UnitRenderer::new);
		EntityRendererRegistry.register(StarMine.SPIT, ThrownItemRenderer::new);
		BlockEntityRenderers.register(StarMineBlocks.CREEP_TUMOR_ENTITY, CreepTumorRenderer::new);
		BlockRenderLayerMap.INSTANCE.putBlock(dev.starmine.vespene.Vespene.VESPENE, RenderType.translucent());
		BlockRenderLayerMap.INSTANCE.putBlock(StarMineBlocks.FORCE_FIELD, RenderType.translucent());
		// a spawn egg shows its SC2 icon (textures/item/sc2/<kind>.png, imported by Modkit) when there is one,
		// drawn untinted; without it the plain two-colour egg
		net.fabricmc.fabric.api.client.model.loading.v1.PreparableModelLoadingPlugin.register(
			(manager, executor) -> java.util.concurrent.CompletableFuture.supplyAsync(() -> {
				java.util.Set<net.minecraft.resources.ResourceLocation> eggs = new java.util.HashSet<>();
				for (UnitKind kind : UnitKind.values())
					if (manager.getResource(StarMine.id("textures/item/sc2/" + kind.id + ".png")).isPresent()) eggs.add(StarMine.id("item/" + kind.id + "_spawn_egg"));
				return eggs;
			}, executor),
			(eggs, context) -> context.modifyModelOnLoad().register((model, at) -> {
				net.minecraft.resources.ResourceLocation id = at.resourceId();
				if (id == null || !eggs.contains(id)) return model;
				String kind = id.getPath().substring("item/".length(), id.getPath().length() - "_spawn_egg".length());
				return net.minecraft.client.renderer.block.model.BlockModel.fromString(
					"{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"starmine:item/sc2/" + kind + "\"}}");
			}));
		for (UnitKind kind : UnitKind.values())
			net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry.ITEM.register((stack, tint) ->
				StarMineModels.hasIcon(kind) ? -1 : net.minecraft.util.FastColor.ARGB32.opaque(tint == 0 ? kind.eggBase : kind.eggSpots), StarMine.egg(kind));
		BlockRenderLayerMap.INSTANCE.putBlocks(RenderType.cutout(), StarMineBlocks.CREEP, StarMineBlocks.CREEP_TUMOR,
			StarMineBlocks.CREEP_TENDRIL, StarMineBlocks.CREEP_PUSTULE, StarMineBlocks.CREEP_VESSELS, StarMineBlocks.CREEP_TENTACLE);
	}
}
