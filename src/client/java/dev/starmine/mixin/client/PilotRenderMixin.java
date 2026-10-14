package dev.starmine.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.starmine.client.PilotClient;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A player who plays as a unit is drawn as that unit (dev.starmine.client.PilotClient). */
@Mixin(PlayerRenderer.class)
abstract class PilotRenderMixin {
	@Inject(method = "render(Lnet/minecraft/client/player/AbstractClientPlayer;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
		at = @At("HEAD"), cancellable = true)
	private void starmine$asUnit(AbstractClientPlayer player, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, CallbackInfo info) {
		if (PilotClient.render(player, yaw, partialTick, pose, buffers, light)) info.cancel();
	}
}
