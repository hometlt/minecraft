package dev.starmine.client;

import dev.starmine.world.AmunSky;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Owner 19.10: the red sky of Amun's waking ({@link AmunSky}): the sky and the fog of the player's world turn blood red
 * for the time the server says, fading in and out ({@link dev.starmine.mixin.client.AmunSkyMixin}).
 */
public final class AmunSkyClient {
	private static final int FADE = 40;
	private static final Vec3 RED = new Vec3(0.55, 0.04, 0.03);
	private static long start = Long.MIN_VALUE, end = Long.MIN_VALUE;

	private AmunSkyClient() {
	}

	static void register() {
		ClientPlayNetworking.registerGlobalReceiver(AmunSky.Red.TYPE, (red, context) -> {
			long now = now();
			start = now;
			end = now + red.ticks();
		});
	}

	private static long now() {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft.level == null ? 0 : minecraft.level.getGameTime();
	}

	/** How red it is now, 0-1. */
	public static float strength(float partialTick) {
		if (end == Long.MIN_VALUE) return 0;
		double t = now() + partialTick;
		if (t >= end || t < start) return 0;
		return (float) Mth.clamp(Math.min((t - start) / FADE, (end - t) / FADE), 0, 1) * 0.85f;
	}

	/** A colour turned towards blood red as much as the sky is red now. */
	public static Vec3 tint(Vec3 colour, float partialTick) {
		float k = strength(partialTick);
		return k <= 0 ? colour : colour.lerp(RED, k);
	}
}
