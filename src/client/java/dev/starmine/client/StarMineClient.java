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
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(dev.starmine.colony.ColonyNetwork.BaseMarks.TYPE,
			(marks, context) -> BaseStatusClient.marks = marks.marks());
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(dev.starmine.colony.ColonyNetwork.UnitRoute.TYPE,
			(route, context) -> Orders.route(route.id(), route.nodes()));
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(dev.starmine.colony.ColonyNetwork.AddOnSites.TYPE,
			(sites, context) -> AddOnRenderer.sites = sites.sites());
		net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.AFTER_ENTITIES.register(AddOnRenderer::renderLeft);
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (COMMAND.consumeClick()) if (!CommandView.active()) CommandView.enterWithPanel(mc);
			PilotClient.tick(mc);
			CommandView.tick(mc);
			HeroPilot.tick(mc);
		});
		net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register((graphics, delta) -> {
			PilotClient.drawHud(graphics);
		});
		net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.AFTER_ENTITIES.register(CommandView::render);
		net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.AFTER_ENTITIES.register(CommandView::renderPlans);
		net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.AFTER_ENTITIES.register(BeamRenderer::render);
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(dev.starmine.entity.Fx.Beam.TYPE,
			(beam, context) -> BeamRenderer.receive(beam));
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
			BaseStatusClient.receive(dev.starmine.colony.ColonyNetwork.BaseStatus.NONE);
			CommandView.exit(mc);
			PilotClient.clear();
			BeamRenderer.clear();
		});
		PilotClient.register();
		Harness.register(); // dev-only, inert without -Dstarmine.harness
		StratosphereSky.register();
		VoidSky.register();
		VoidRenderers.register();
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
		EntityRendererRegistry.register(StarMine.SPIT, ShotRenderer::new);
		EntityRendererRegistry.register(StarMine.NUKE_MISSILE, NukeRenderer::new);
		EntityRendererRegistry.register(dev.starmine.world.VisionBlocker.TYPE, VisionBlockerRenderer::new);
		EntityRendererRegistry.register(dev.starmine.gear.Guns.MARSHAL, TownsfolkRenderer::new);
		EntityRendererRegistry.register(dev.starmine.gear.Guns.GHOST_AGENT, TownsfolkRenderer::new);
		EntityRendererRegistry.register(dev.starmine.gear.Guns.SHERIFF, TownsfolkRenderer::new);
		// owner 16.10: a weapon module looks like its unit's weapon in the hand (dev.starmine.possess.WeaponLook)
		net.minecraft.client.renderer.item.ItemProperties.register(dev.starmine.possess.ModuleItem.WEAPON, StarMine.id("weapon"), (stack, level, entity, seed) -> {
			UnitKind kind = dev.starmine.possess.ModuleItem.kindOf(stack);
			return kind == null ? 0 : dev.starmine.possess.WeaponLook.of(kind).property();
		});
		BlockEntityRenderers.register(StarMineBlocks.CREEP_TUMOR_ENTITY, CreepTumorRenderer::new);
		BlockEntityRenderers.register(dev.starmine.mineral.Minerals.CORE_ENTITY, MineralRenderer::new);
		BlockEntityRenderers.register(dev.starmine.world.Artifact.CORE, ArtifactRenderer::new); // owner 17.10: the artifact's beam
		BlockRenderLayerMap.INSTANCE.putBlock(dev.starmine.world.Artifact.PORTAL, RenderType.translucent());
		for (net.minecraft.world.level.block.Block part : dev.starmine.world.Artifact.PARTS.values()) BlockRenderLayerMap.INSTANCE.putBlock(part, RenderType.cutout());
		BlockRenderLayerMap.INSTANCE.putBlock(dev.starmine.vespene.Vespene.VESPENE, RenderType.translucent());
		// the liquids (owner 16.10): vespene and liquid creep, their own still and flowing textures
		net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry.INSTANCE.register(dev.starmine.vespene.Vespene.SOURCE,
			dev.starmine.vespene.Vespene.FLOWING, new net.fabricmc.fabric.api.client.render.fluid.v1.SimpleFluidRenderHandler(
				StarMine.id("block/vespene_still"), StarMine.id("block/vespene_flow")));
		net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry.INSTANCE.register(StarMineBlocks.LIQUID_CREEP_SOURCE,
			StarMineBlocks.LIQUID_CREEP_FLOWING, new net.fabricmc.fabric.api.client.render.fluid.v1.SimpleFluidRenderHandler(
				StarMine.id("block/liquid_creep_still"), StarMine.id("block/liquid_creep_flow")));
		// owner 17.10: terrazine of the Void's lakes
		net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry.INSTANCE.register(dev.starmine.vespene.Vespene.TERRAZINE_SOURCE,
			dev.starmine.vespene.Vespene.TERRAZINE_FLOWING, new net.fabricmc.fabric.api.client.render.fluid.v1.SimpleFluidRenderHandler(
				StarMine.id("block/terrazine_still"), StarMine.id("block/terrazine_flow")));
		BlockRenderLayerMap.INSTANCE.putFluids(RenderType.translucent(), dev.starmine.vespene.Vespene.TERRAZINE_SOURCE, dev.starmine.vespene.Vespene.TERRAZINE_FLOWING);
		BlockRenderLayerMap.INSTANCE.putBlock(dev.starmine.vespene.Vespene.TERRAZINE, RenderType.translucent());
		BlockRenderLayerMap.INSTANCE.putFluids(RenderType.translucent(), dev.starmine.vespene.Vespene.SOURCE, dev.starmine.vespene.Vespene.FLOWING,
			StarMineBlocks.LIQUID_CREEP_SOURCE, StarMineBlocks.LIQUID_CREEP_FLOWING);
		BlockRenderLayerMap.INSTANCE.putBlock(StarMineBlocks.FORCE_FIELD, RenderType.translucent());
		// a laid-over map's walk-through decor draws the block it looks like, in the chunk's mesh
		BlockRenderLayerMap.INSTANCE.putBlock(dev.starmine.world.MapDecor.BLOCK, RenderType.cutout());
		net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin.register(context -> context.modifyModelAfterBake().register((model, at) ->
			at.topLevelId() != null && at.topLevelId().id().equals(StarMine.id("map_decor")) ? new MapDecorModel(model) : model));
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
