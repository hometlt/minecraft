package dev.starmine.client;

import dev.starmine.entity.StarUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Bars over units in the command view (owner 16.10, as in SC2): over every damaged unit and over the
 * selected ones — shields (blue) over health (green, yellow, red as it falls) and energy (violet) under
 * them, as wide as the unit looks.
 */
final class Bars {
	/** Blocks from the camera beyond which no bars are drawn. */
	static final double RANGE = 96;

	private Bars() {
	}

	static void draw(GuiGraphics graphics, int width, int height) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
		float partial = mc.getTimer().getGameTimeDeltaPartialTick(false);
		for (Entity entity : mc.level.entitiesForRendering()) {
			// owner 17.10: any creature's health too — monsters, animals, the Void's foes — when hurt or looked at
			if (entity instanceof net.minecraft.world.entity.LivingEntity living && !(entity instanceof StarUnit) && living.isAlive()
				&& !living.isInvisible() && entity != mc.player && CommandView.visible(entity)) {
				boolean looked = CommandView.inspected != null && CommandView.inspected == entity.getId();
				if (!looked && living.getHealth() >= living.getMaxHealth()) continue;
				Vec3 at = living.getPosition(partial);
				if (at.distanceToSqr(camera) > RANGE * RANGE) continue;
				double[] top = CommandView.project(at.add(0, living.getBbHeight() + 0.4, 0), width, height);
				double[] side = CommandView.project(at.add(living.getBbWidth() / 2, living.getBbHeight() + 0.4, 0), width, height);
				if (top == null || side == null) continue;
				int half = (int) Math.max(6, Math.min(60, Math.hypot(side[0] - top[0], side[1] - top[1]) * 1.1));
				float health = living.getHealth() / living.getMaxHealth();
				bar(graphics, (int) top[0] - half, (int) top[1] - 3, half * 2, health, health > 0.6f ? 0xFF30E030 : health > 0.3f ? 0xFFE0D020 : 0xFFE03020);
				continue;
			}
			if (!(entity instanceof StarUnit unit) || !unit.isAlive() || unit.isInvisible()) continue;
			if (!CommandView.own(unit) && !CommandView.visible(unit)) continue;
			boolean selected = CommandView.selection.contains(unit.getId());
			boolean hurt = unit.getHealth() < unit.getMaxHealth() || unit.kind.shields > 0 && unit.shield() < unit.maxShield();
			if (!selected && !hurt) continue;
			Vec3 at = unit.getPosition(partial);
			if (at.distanceToSqr(camera) > RANGE * RANGE) continue;
			double[] top = CommandView.project(at.add(0, unit.getBbHeight() + 0.4, 0), width, height);
			double[] side = CommandView.project(at.add(unit.getBbWidth() / 2, unit.getBbHeight() + 0.4, 0), width, height);
			if (top == null || side == null) continue;
			int half = (int) Math.max(6, Math.min(40, Math.hypot(side[0] - top[0], side[1] - top[1]) * 1.1));
			int x = (int) top[0], y = (int) top[1];
			if (x < -half || x > width + half || y < 0 || y > height) continue;
			if (unit.kind.shields > 0) {
				bar(graphics, x - half, y - 6, half * 2, unit.shield() / (float) unit.maxShield(), 0xFF3080FF);
			}
			float health = unit.getHealth() / unit.getMaxHealth();
			bar(graphics, x - half, y - 3, half * 2, health, health > 0.6f ? 0xFF30E030 : health > 0.3f ? 0xFFE0D020 : 0xFFE03020);
			if (unit.maxEnergy() > 0) bar(graphics, x - half, y, half * 2, unit.energy() / unit.maxEnergy(), 0xFFC050FF);
		}
	}

	private static void bar(GuiGraphics graphics, int x, int y, int width, float fraction, int colour) {
		graphics.fill(x - 1, y - 1, x + width + 1, y + 2, 0xC0000000);
		graphics.fill(x, y, x + Math.round(width * Math.max(0, Math.min(1, fraction))), y + 1 + 1, colour);
	}
}
