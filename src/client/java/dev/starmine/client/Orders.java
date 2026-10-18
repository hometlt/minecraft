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
 * An order is forgotten when its unit gets there, dies, or after {@link #KEEP} ticks. Once the server has
 * planned a unit's way ({@link #route}, owner 18.10) the arrows follow that way, round cliffs and buildings.
 */
final class Orders {
	static final int KEEP = 20 * 30, FLASH = 16;
	/** Blocks from its point at which a unit has arrived. */
	static final double THERE = 2.5;

	private record Order(Vec3 to, int colour, long since) {
	}

	private static final Map<Integer, Order> ORDERS = new HashMap<>();
	/** The planned way of a unit to its order point: node middles, from where it stood. */
	private static final Map<Integer, java.util.List<Vec3>> ROUTES = new HashMap<>();
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
		for (int id : ids) ROUTES.remove(id); // the old way is gone with the old order
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

	/** The server planned this unit's way (block x y z per node). */
	static void route(int id, int[] nodes) {
		java.util.List<Vec3> points = new java.util.ArrayList<>();
		for (int i = 0; i + 2 < nodes.length; i += 3) points.add(new Vec3(nodes[i] + 0.5, nodes[i + 1], nodes[i + 2] + 0.5));
		if (points.size() > 1) ROUTES.put(id, points);
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
		ROUTES.keySet().retainAll(ORDERS.keySet());
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
			java.util.List<Vec3> route = ROUTES.get(entry.getKey());
			if (route == null) arrows(last, lines, from, to, order.colour(), now + partialTick);
			else arrows(last, lines, ahead(route, from, to), order.colour(), now + partialTick);
			marks.put(order.to(), order.colour());
		}
		for (Map.Entry<Vec3, Integer> mark : marks.entrySet()) cross(last, lines, mark.getKey().add(0, 0.1, 0), mark.getValue());
		float flash = (now - lastSince + partialTick) / FLASH;
		if (lastPoint != null && flash >= 0 && flash < 1) ring(last, lines, lastPoint.add(0, 0.12, 0), 2.2 * (1 - flash) + 0.3, lastColour);
	}

	/** The rest of a planned way: from the unit, past the node it is nearest, on to the order point. */
	private static java.util.List<Vec3> ahead(java.util.List<Vec3> route, Vec3 from, Vec3 to) {
		int nearest = 0;
		for (int i = 1; i < route.size(); i++)
			if (route.get(i).distanceToSqr(from) < route.get(nearest).distanceToSqr(from)) nearest = i;
		java.util.List<Vec3> points = new java.util.ArrayList<>();
		points.add(from);
		for (int i = nearest + 1; i < route.size(); i++) points.add(route.get(i).add(0, 0.15, 0));
		if (points.get(points.size() - 1).distanceToSqr(to) > 1) points.add(to);
		return points;
	}

	/** A dashed line of chevrons from a unit to its point, moving toward the point. */
	private static void arrows(PoseStack.Pose last, VertexConsumer lines, Vec3 from, Vec3 to, int colour, float time) {
		arrows(last, lines, java.util.List.of(from, to), colour, time);
	}

	/** Chevrons a step apart along a line through these points, moving along it. */
	private static void arrows(PoseStack.Pose last, VertexConsumer lines, java.util.List<Vec3> points, int colour, float time) {
		double at = (time * 0.08) % 1.5; // where the next chevron's back lies, along the whole line
		for (int i = 0; i + 1 < points.size(); i++) {
			Vec3 from = points.get(i), way = points.get(i + 1).subtract(from);
			double length = way.length();
			if (length < 1e-3) continue;
			Vec3 dir = way.scale(1 / length), side = new Vec3(-dir.z, 0, dir.x);
			side = side.lengthSqr() < 1e-6 ? new Vec3(0.35, 0, 0) : side.normalize().scale(0.35);
			boolean lastPart = i + 2 == points.size();
			for (; at < length - (lastPart ? 0.6 : 0); at += 1.5) {
				Vec3 back = from.add(dir.scale(at)), tip = from.add(dir.scale(Math.min(at + 0.5, length)));
				line(last, lines, back.add(side), tip, colour);
				line(last, lines, back.subtract(side), tip, colour);
			}
			at -= length;
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
