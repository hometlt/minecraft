package dev.starmine.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.starmine.StarMine;
import dev.starmine.colony.PlatformBlock;
import dev.starmine.colony.PlatformBlockEntity;
import dev.starmine.entity.UnitKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.InventoryMenu;

/**
 * The armoured crate's top (owner 22.10): it opens from its top to every side while someone has it open, each race in
 * its own way — the zerg's an alien egg of four fleshy petals that peel outward, the terrans' an airlock of four steel
 * leaves swinging up and out, the protoss' a dome of force that fades and sinks as four golden fins spread. The base
 * under it is the block's own model (models/block/&lt;race&gt;_platform.json). Units: blocks (a pixel is 1/16).
 */
public final class PlatformRenderer implements BlockEntityRenderer<PlatformBlockEntity> {
	private static final ResourceLocation ZERG_FLESH = StarMine.id("block/zerg_flesh"), ZERG_GLOW = StarMine.id("block/glowing_zerg_flesh"),
		TERRAN_PANEL = StarMine.id("block/terran_armor_panel"), TERRAN_PLATING = StarMine.id("block/terran_armor_plating"),
		LAMP = ResourceLocation.withDefaultNamespace("block/redstone_lamp_on"), SHIELD = StarMine.id("block/psi_barrier"),
		GOLD = ResourceLocation.withDefaultNamespace("block/gold_block"), PROTOSS_PANEL = StarMine.id("block/protoss_armor_panel");
	private static final float PX = 1 / 16f;

	public PlatformRenderer(BlockEntityRendererProvider.Context context) {
	}

	private static TextureAtlasSprite sprite(ResourceLocation location) {
		return Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(location);
	}

	@Override
	public void render(PlatformBlockEntity crate, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
		if (!(crate.getBlockState().getBlock() instanceof PlatformBlock block)) return;
		float open = crate.openness(partialTick);
		open = 1 - (1 - open) * (1 - open); // eased: quick at first, then slow
		VertexConsumer solid = buffers.getBuffer(RenderType.entityCutoutNoCull(InventoryMenu.BLOCK_ATLAS));
		switch (block.race) {
			case ZERG -> zerg(pose, solid, light, open);
			case TERRAN -> terran(pose, solid, light, open);
			case PROTOSS -> protoss(pose, solid, buffers.getBuffer(RenderType.entityTranslucent(InventoryMenu.BLOCK_ATLAS)), light, open);
		}
	}

	/**
	 * Owner 22.10: a resource crate become its base's store (a building, dev.starmine.colony.Pedestal) is drawn as the crate it
	 * was: its block and its shut top, at the entity's feet.
	 */
	public static void drawCrate(UnitKind.Race race, PoseStack pose, MultiBufferSource buffers, int light) {
		var state = PlatformBlock.BLOCKS.get(race).defaultBlockState();
		pose.pushPose();
		pose.translate(-0.5, 0, -0.5);
		Minecraft.getInstance().getBlockRenderer().renderSingleBlock(state, pose, buffers, light, OverlayTexture.NO_OVERLAY);
		VertexConsumer solid = buffers.getBuffer(RenderType.entityCutoutNoCull(InventoryMenu.BLOCK_ATLAS));
		switch (race) {
			case ZERG -> zerg(pose, solid, light, 0);
			case TERRAN -> terran(pose, solid, light, 0);
			case PROTOSS -> protoss(pose, solid, buffers.getBuffer(RenderType.entityTranslucent(InventoryMenu.BLOCK_ATLAS)), light, 0);
		}
		pose.popPose();
	}

	/** An egg of four petals on the mound (5 px up): shut they lean in to a point, open they peel outward. */
	private static void zerg(PoseStack pose, VertexConsumer out, int light, float open) {
		TextureAtlasSprite flesh = sprite(ZERG_FLESH), glow = sprite(ZERG_GLOW);
		for (int side = 0; side < 4; side++) {
			pose.pushPose();
			pose.translate(0.5, 0, 0.5);
			pose.mulPose(Axis.YP.rotationDegrees(side * 90));
			pose.translate(0, 5 * PX, -4.5 * PX); // the hinge: the petal's foot on the mound's edge
			pose.mulPose(Axis.XP.rotationDegrees(Mth.lerp(open, -22, 105))); // in (shut) to out (open)
			box(pose, out, flesh, light, -4 * PX, 0, -1 * PX, 4 * PX, 6 * PX, 0.5f * PX, 1f);
			pose.translate(0, 6 * PX, 0); // the petal's tip, bent further in
			pose.mulPose(Axis.XP.rotationDegrees(Mth.lerp(open, -28, 10)));
			box(pose, out, flesh, light, -3 * PX, 0, -1 * PX, 3 * PX, 4 * PX, 0.5f * PX, 1f);
			pose.popPose();
		}
		// the egg's glowing heart, seen as it opens
		float s = 0.6f + 0.4f * open;
		pose.pushPose();
		pose.translate(0.5, 5 * PX, 0.5);
		pose.scale(s, s, s);
		box(pose, out, glow, light, -3 * PX, 0, -3 * PX, 3 * PX, 4 * PX, 3 * PX, 1f);
		pose.popPose();
	}

	/** An airlock of four steel leaves over the box (6 px up): flat and shut, they swing up and out. */
	private static void terran(PoseStack pose, VertexConsumer out, int light, float open) {
		TextureAtlasSprite panel = sprite(TERRAN_PANEL), plating = sprite(TERRAN_PLATING), lamp = sprite(LAMP);
		for (int side = 0; side < 4; side++) {
			pose.pushPose();
			pose.translate(0.5, 0, 0.5);
			pose.mulPose(Axis.YP.rotationDegrees(side * 90));
			pose.translate(0, 6 * PX + side * 0.002f, -6.5 * PX); // the hinge on the box's edge (leaves a hair apart: no flicker)
			pose.mulPose(Axis.XP.rotationDegrees(-open * 118));
			// a leaf: wide at the hinge, narrowing to the middle (two steps)
			box(pose, out, panel, light, -6.5f * PX, 0, 0, 6.5f * PX, 1.2f * PX, 3.5f * PX, 1f);
			box(pose, out, plating, light, -3.5f * PX, 0, 3.5f * PX, 3.5f * PX, 1.2f * PX, 6.5f * PX, 1f);
			pose.popPose();
		}
		// the warning lamp inside, lit when it opens
		pose.pushPose();
		pose.translate(0.5, 6 * PX, 0.5);
		box(pose, out, lamp, light, -1 * PX, -1 * PX, -1 * PX, 1 * PX, open * 2 * PX, 1 * PX, 1f);
		pose.popPose();
	}

	/** A dome of force over the plinth (5 px up) with four gold fins: the dome fades and sinks, the fins spread. */
	private static void protoss(PoseStack pose, VertexConsumer out, VertexConsumer glass, int light, float open) {
		TextureAtlasSprite gold = sprite(GOLD), shield = sprite(SHIELD), panel = sprite(PROTOSS_PANEL);
		for (int side = 0; side < 4; side++) {
			pose.pushPose();
			pose.translate(0.5, 0, 0.5);
			pose.mulPose(Axis.YP.rotationDegrees(side * 90 + 45));
			pose.translate(0, 5 * PX, -6.2 * PX);
			pose.mulPose(Axis.XP.rotationDegrees(Mth.lerp(open, -18, 40)));
			box(pose, out, gold, light, -1 * PX, 0, -0.6f * PX, 1 * PX, 8 * PX, 0.6f * PX, 1f);
			pose.popPose();
		}
		// the khaydarin core, rising as the dome sinks
		pose.pushPose();
		pose.translate(0.5, 5 * PX + open * 2 * PX, 0.5);
		box(pose, out, panel, light, -2 * PX, 0, -2 * PX, 2 * PX, 2 * PX, 2 * PX, 1f);
		pose.popPose();
		float alpha = 0.75f * (1 - open);
		if (alpha < 0.02f) return;
		float h = 8 * (1 - open);
		pose.pushPose();
		pose.translate(0.5, 5 * PX, 0.5);
		box(pose, glass, shield, 0xF000F0, -5 * PX, 0, -5 * PX, 5 * PX, h * PX, 5 * PX, alpha);
		pose.popPose();
	}

	/** A box from (x0, y0, z0) to (x1, y1, z1) in the pose's space, each face the sprite stretched over it. */
	private static void box(PoseStack pose, VertexConsumer out, TextureAtlasSprite sprite, int light, float x0, float y0, float z0, float x1, float y1,
		float z1, float alpha) {
		PoseStack.Pose last = pose.last();
		float u0 = sprite.getU0(), u1 = sprite.getU1(), v0 = sprite.getV0(), v1 = sprite.getV1();
		// north (-z), south (+z), west (-x), east (+x), down, up
		quad(out, last, light, alpha, u0, v0, u1, v1, 0, 0, -1, x1, y1, z0, x0, y1, z0, x0, y0, z0, x1, y0, z0);
		quad(out, last, light, alpha, u0, v0, u1, v1, 0, 0, 1, x0, y1, z1, x1, y1, z1, x1, y0, z1, x0, y0, z1);
		quad(out, last, light, alpha, u0, v0, u1, v1, -1, 0, 0, x0, y1, z0, x0, y1, z1, x0, y0, z1, x0, y0, z0);
		quad(out, last, light, alpha, u0, v0, u1, v1, 1, 0, 0, x1, y1, z1, x1, y1, z0, x1, y0, z0, x1, y0, z1);
		quad(out, last, light, alpha, u0, v0, u1, v1, 0, -1, 0, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0);
		quad(out, last, light, alpha, u0, v0, u1, v1, 0, 1, 0, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1);
	}

	private static void quad(VertexConsumer out, PoseStack.Pose last, int light, float alpha, float u0, float v0, float u1, float v1,
		float nx, float ny, float nz, float... corners) {
		float[][] uv = {{u0, v0}, {u1, v0}, {u1, v1}, {u0, v1}};
		for (int i = 0; i < 4; i++)
			out.addVertex(last, corners[i * 3], corners[i * 3 + 1], corners[i * 3 + 2]).setColor(1f, 1f, 1f, alpha).setUv(uv[i][0], uv[i][1])
				.setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(last, nx, ny, nz);
	}
}
