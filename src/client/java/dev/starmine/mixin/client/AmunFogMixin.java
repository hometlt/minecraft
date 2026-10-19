package dev.starmine.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.starmine.client.AmunSkyClient;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Owner 19.10: the fog reddens with the sky when Amun wakes — the Nether and the End have no sky but their fog. */
@Mixin(FogRenderer.class)
abstract class AmunFogMixin {
	@Shadow
	private static float fogRed;
	@Shadow
	private static float fogGreen;
	@Shadow
	private static float fogBlue;

	@Inject(method = "setupColor", at = @At("TAIL"))
	private static void starmine$redFog(Camera camera, float partialTick, ClientLevel level, int renderDistance, float darken, CallbackInfo info) {
		if (AmunSkyClient.strength(partialTick) <= 0) return;
		Vec3 red = AmunSkyClient.tint(new Vec3(fogRed, fogGreen, fogBlue), partialTick);
		fogRed = (float) red.x;
		fogGreen = (float) red.y;
		fogBlue = (float) red.z;
		RenderSystem.clearColor(fogRed, fogGreen, fogBlue, 0.0f);
	}
}
