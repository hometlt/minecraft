package dev.starmine.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.starmine.entity.StarUnit;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * What the player last ordered (owner 16.10: show where units go, as in SC2): for each unit sent somewhere,
 * its order's point. In the command view a line of arrows leads from the unit to it (green: move, red:
 * attack, yellow: gather, patrol, rally, a cast), a marker lies on the ground there — a ring that closes in
 * when the order is given, then a small cross while the units are on the way — and a dot on the minimap.
 * An order is forgotten when its unit gets there, dies, or after {@link #KEEP} ticks.
 */
final class Orders {
	static final int KEEP = 20 * 30, FLASH = 16;
	/** Blocks from its point at which a unit has arrived. */
	static final double THERE = 2.5;

	private record Order(Vec3 to, int colour, long since) {
	}

	private static final Map<Integer, Order> ORDERS = new HashMap<>();
	private static Vec3 lastPoint;
	private static int lastColour;
	private static long lastSince = -1000;

	private Orders() {
	}

	static int colour(String action) {
		return switch (action) {
			case "move" -> 0xFF40FF40;
			case "attack", "attack_move" -> 0xFFFF4040;
			case "stop", "hold" -> 0;
			default -> 0xFFFFD040;
		};
	}

	/** The player sent `action` to `ids` at `to`. */
	static void sent(int[] ids, String action, Vec3 to) {
		int colour = colour(action);
		Minecraft mc = Minecraft.getInstance();
		long now = mc.level == null ? 0 : mc.level.getGameTime();
		if (colour == 0) {
			for (int id : ids) ORDERS.remove(id);
			return;
		}
		if (to == null || to.lengthSqr() == 0) return;
		for (int id : ids) ORDERS.put(id, new Order(to, colour, now));
		lastPoint = to;
		lastColour = colour;
		lastSince = now;
	}

	/** Forgets orders done, stale or of the gone. */
	private static void prune(long now) {
		Minecraft mc = Minecraft.getInstance();
		ORDERS.entrySet().removeIf(entry -> {
			if (now - entry.getValue().since() > KEEP || mc.level == null || !(mc.level.getEntity(entry.getKey()) instanceof StarUnit unit)
				|| !unit.isAlive()) return true;
			Vec3 to = entry.getValue().to();
			return Mth.square(unit.getX() - to.x) + Mth.square(unit.getZ() - to.z) < THERE * THERE;
		});
	}

	/** The arrows and markers in the world (called with the pose at the world's origin). */
	static void render(PoseStack pose, VertexConsumer lines, float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		long now = mc.level.getGameTime();
		prune(now);
		PoseStack.Pose last = pose.last();
		Map<Vec3, Integer> marks = new HashMap<>();
		for (Map.Entry<Integer, Order> entry : ORDERS.entrySet()) {
			if (!(mc.level.getEntity(entry.getKey()) instanceof StarUnit unit) || !CommandView.selection.contains(unit.getId())) continue;
			Order order = entry.getValue();
			Vec3 from = unit.getPosition(partialTick).add(0, 0.15, 0), to = order.to().add(0, 0.15, 0);
			arrows(last, lines, from, to, order.colour(), now + partialTick);
			marks.put(order.to(), order.colour());
		}
		for (Map.Entry<Vec3, Integer> mark : marks.entrySet()) cross(last, lines, mark.getKey().add(0, 0.1, 0), mark.getValue());
		float flash = (now - lastSince + partialTick) / FLASH;
		if (lastPoint != null && flash >= 0 && flash < 1) ring(last, lines, lastPoint.add(0, 0.12, 0), 2.2 * (1 - flash) + 0.3, lastColour);
	}

	/** A dashed line of chevrons from a unit to its point, moving toward the point. */
	private static void arrows(PoseStack.Pose last, VertexConsumer lines, Vec3 from, Vec3 to, int colour, float time) {
		Vec3 way = to.subtract(from);
		double length = way.length();
		if (length < 0.5) return;
		Vec3 dir = way.scale(1 / length), side = new Vec3(-dir.z, 0, dir.x).normalize().scale(0.35);
		double offset = (time * 0.08) % 1.5;
		for (double t = offset; t < length - 0.6; t += 1.5) {
			Vec3 tip = from.add(dir.scale(t + 0.5)), back = from.add(dir.scale(t));
			line(last, lines, back.add(side), tip, colour);
			line(last, lines, back.subtract(side), tip, colour);
		}
	}

	private static void cross(PoseStack.Pose last, VertexConsumer lines, Vec3 at, int colour) {
		line(last, lines, at.add(-0.6, 0, -0.6), at.add(0.6, 0, 0.6), colour);
		line(last, lines, at.add(-0.6, 0, 0.6), at.add(0.6, 0, -0.6), colour);
	}

	private static void ring(PoseStack.Pose last, VertexConsumer lines, Vec3 at, double r, int colour) {
		int segments = 24;
		for (int i = 0; i < segments; i++) {
			double a = i * Math.PI * 2 / segments, b = (i + 1) * Math.PI * 2 / segments;
			line(last, lines, at.add(Math.cos(a) * r, 0, Math.sin(a) * r), at.add(Math.cos(b) * r, 0, Math.sin(b) * r), colour);
		}
	}

	private static void line(PoseStack.Pose last, VertexConsumer lines, Vec3 a, Vec3 b, int colour) {
		Vec3 n = b.subtract(a).normalize();
		lines.addVertex(last, (float) a.x, (float) a.y, (float) a.z).setColor(colour).setNormal(last, (float) n.x, (float) n.y, (float) n.z);
		lines.addVertex(last, (float) b.x, (float) b.y, (float) b.z).setColor(colour).setNormal(last, (float) n.x, (float) n.y, (float) n.z);
	}

	/** The points of the selection's orders on the minimap: `screen` maps a world point to the minimap. */
	static void minimap(GuiGraphics graphics, java.util.function.BiFunction<Double, Double, int[]> screen) {
		java.util.Set<Vec3> drawn = new java.util.HashSet<>();
		for (Map.Entry<Integer, Order> entry : ORDERS.entrySet()) {
			if (!CommandView.selection.contains(entry.getKey()) || !drawn.add(entry.getValue().to())) continue;
			int[] at = screen.apply(entry.getValue().to().x, entry.getValue().to().z);
			graphics.fill(at[0] - 2, at[1], at[0] + 3, at[1] + 1, entry.getValue().colour());
			graphics.fill(at[0], at[1] - 2, at[0] + 1, at[1] + 3, entry.getValue().colour());
		}
	}
}
