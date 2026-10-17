package dev.starmine.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.starmine.entity.Fx;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Weapon beams ({@link Fx}): glowing strips from a weapon to where it hits, turned to face the camera, drawn with
 * additive light (seen at any distance, the command view too). A tracer is a thin streak with a bright bolt
 * running along it; a laser a hot core in a coloured glow; a beam a thick pulsing ray that lasts while it fires.
 * Each fades out over its last ticks.
 */
public final class BeamRenderer {
	private record Live(Fx.Beam beam, long born) {
	}

	private static final List<Live> BEAMS = new ArrayList<>();

	private BeamRenderer() {
	}

	static void receive(Fx.Beam beam) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		synchronized (BEAMS) {
			if (BEAMS.size() > 512) BEAMS.remove(0);
			BEAMS.add(new Live(beam, mc.level.getGameTime()));
		}
	}

	static void clear() {
		synchronized (BEAMS) {
			BEAMS.clear();
		}
	}

	static void render(WorldRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || context.consumers() == null || context.matrixStack() == null) return;
		float partial = context.tickCounter().getGameTimeDeltaPartialTick(false);
		double now = mc.level.getGameTime() + partial;
		Vec3 cam = context.camera().getPosition();
		PoseStack pose = context.matrixStack();
		VertexConsumer out = context.consumers().getBuffer(RenderType.lightning());
		pose.pushPose();
		pose.translate(-cam.x, -cam.y, -cam.z);
		Matrix4f matrix = pose.last().pose();
		synchronized (BEAMS) {
			BEAMS.removeIf(live -> now - live.born > live.beam.ticks() + 1);
			for (Live live : BEAMS) {
				Fx.Beam beam = live.beam;
				double age = (now - live.born) / Math.max(1, beam.ticks());
				float fade = (float) Math.max(0, Math.min(1, (1 - age) * 2.5));
				if (fade <= 0) continue;
				int rgb = beam.color();
				float r = (rgb >> 16 & 255) / 255f, g = (rgb >> 8 & 255) / 255f, b = (rgb & 255) / 255f;
				float width = beam.width();
				switch (beam.look()) {
					case TRACER -> {
						strip(out, matrix, cam, beam.from(), beam.to(), width * 0.5f, r, g, b, 0.35f * fade);
						// the bolt: a bright short streak running from the muzzle to the target
						double t = Math.min(1, age * 1.6), length = Math.min(0.35, 1.5 / Math.max(1, beam.from().distanceTo(beam.to())));
						Vec3 head = beam.from().lerp(beam.to(), t), tail = beam.from().lerp(beam.to(), Math.max(0, t - length));
						strip(out, matrix, cam, tail, head, width, 1, 1, 0.9f, 0.9f * fade);
					}
					case LASER -> {
						strip(out, matrix, cam, beam.from(), beam.to(), width * 2.2f, r, g, b, 0.5f * fade);
						strip(out, matrix, cam, beam.from(), beam.to(), width * 0.5f, r * 0.4f + 0.6f, g * 0.4f + 0.5f, b * 0.4f + 0.4f, 0.6f * fade);
					}
					case BEAM -> {
						float pulse = 0.8f + 0.2f * (float) Math.sin(now * 1.7);
						strip(out, matrix, cam, beam.from(), beam.to(), width * 2.0f * pulse, r, g, b, 0.45f * fade);
						strip(out, matrix, cam, beam.from(), beam.to(), width * 0.8f, r, g, b, 0.6f * fade);
						strip(out, matrix, cam, beam.from(), beam.to(), width * 0.25f, r * 0.3f + 0.7f, g * 0.3f + 0.7f, b * 0.3f + 0.7f, 0.6f * fade);
					}
				}
			}
		}
		pose.popPose();
	}

	/** A strip of `width` from a to b facing the camera. */
	private static void strip(VertexConsumer out, Matrix4f matrix, Vec3 cam, Vec3 a, Vec3 b, float width, float r, float g, float bl, float alpha) {
		Vec3 along = b.subtract(a);
		if (along.lengthSqr() < 1e-6) return;
		Vec3 toCam = cam.subtract(a.lerp(b, 0.5));
		Vec3 side = along.cross(toCam);
		if (side.lengthSqr() < 1e-6) side = along.cross(new Vec3(0, 1, 0));
		if (side.lengthSqr() < 1e-6) return;
		side = side.normalize().scale(width / 2);
		vertex(out, matrix, a.add(side), r, g, bl, alpha);
		vertex(out, matrix, a.subtract(side), r, g, bl, alpha);
		vertex(out, matrix, b.subtract(side), r, g, bl, alpha);
		vertex(out, matrix, b.add(side), r, g, bl, alpha);
		vertex(out, matrix, b.add(side), r, g, bl, alpha); // and its back: seen from either side
		vertex(out, matrix, b.subtract(side), r, g, bl, alpha);
		vertex(out, matrix, a.subtract(side), r, g, bl, alpha);
		vertex(out, matrix, a.add(side), r, g, bl, alpha);
	}

	private static void vertex(VertexConsumer out, Matrix4f matrix, Vec3 at, float r, float g, float b, float a) {
		out.addVertex(matrix, (float) at.x, (float) at.y, (float) at.z).setColor(r, g, b, a);
	}
}
