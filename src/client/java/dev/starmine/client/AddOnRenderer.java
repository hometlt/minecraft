package dev.starmine.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.starmine.ability.Modes;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Draws terran add-ons (SC2 tech lab and reactor models): one built on a barracks, factory or starport, beside it
 * as in SC2 (2 x 2 cells, to the east, half a cell to the south), and one left on the ground when its building
 * lifted off ({@link dev.starmine.ability.LiftOff}, the sites come from the server in
 * {@link dev.starmine.colony.ColonyNetwork.AddOnSites}).
 */
final class AddOnRenderer {
	private static final float WIDTH = 2 * UnitKind.CELL;
	/** Its centre from its building's: east by half of each, south by half a cell (SC2 places it so). */
	private static final float EAST = 3 * UnitKind.CELL / 2f + WIDTH / 2, SOUTH = UnitKind.CELL / 2f;
	private static final SkinnedModelDrawer DRAWER = new SkinnedModelDrawer();
	private static float[] palette = new float[0];
	/** x, y, z, mode of each add-on left on the ground in this dimension. */
	static int[] sites = new int[0];

	private AddOnRenderer() {
	}

	/** The add-on of a building that has one, beside it (the pose is at the building's feet). */
	static void attached(StarUnit unit, PoseStack pose, MultiBufferSource buffers, int light, float time) {
		int mode = unit.mode();
		if ((mode != Modes.TECHLAB && mode != Modes.REACTOR) || !dev.starmine.ability.Ability.BUILD_TECHLAB.users().contains(unit.kind)) return;
		draw(mode, pose, EAST, 0, SOUTH, buffers, light, time);
	}

	/** The add-ons standing on the ground without their building. */
	static void renderLeft(WorldRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || sites.length == 0 || context.consumers() == null || context.matrixStack() == null) return;
		Vec3 cam = context.camera().getPosition();
		float time = (mc.level.getGameTime() + context.tickCounter().getGameTimeDeltaPartialTick(false)) / 20f;
		for (int i = 0; i + 3 < sites.length; i += 4) {
			double x = sites[i] + 0.5 + EAST, y = sites[i + 1], z = sites[i + 2] + 0.5 + SOUTH;
			if (cam.distanceToSqr(x, y, z) > 160 * 160) continue;
			int light = LevelRenderer.getLightColor(mc.level, BlockPos.containing(x, y + 1, z));
			draw(sites[i + 3], context.matrixStack(), x - cam.x, y - cam.y, z - cam.z, context.consumers(), light, time);
		}
	}

	private static void draw(int mode, PoseStack pose, double x, double y, double z, MultiBufferSource buffers, int light, float time) {
		SkinnedModel model = StarMineModels.get(mode == Modes.REACTOR ? "reactor" : "techlab");
		if (model == null) return;
		SkinnedModel.Clip stand = model.clips.get("stand");
		if (stand == null && !model.clips.isEmpty()) stand = model.clips.values().iterator().next();
		if (stand == null) return;
		if (palette.length != model.slots * 12) palette = new float[model.slots * 12];
		model.pose(stand, time, palette);
		DRAWER.skin(model, palette);
		pose.pushPose();
		pose.translate(x, y, z);
		float size = Math.max(model.size[0], model.size[2]);
		if (size > 0) pose.scale(WIDTH / size, WIDTH / size, WIDTH / size);
		DRAWER.draw(model, pose.last(), buffers, light, OverlayTexture.NO_OVERLAY);
		pose.popPose();
	}
}
