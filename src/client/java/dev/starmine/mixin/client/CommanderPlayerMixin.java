package dev.starmine.mixin.client;

import dev.starmine.client.CommandView;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The detached RTS camera must not disable the hero's input or movement packets. */
@Mixin(LocalPlayer.class)
abstract class CommanderPlayerMixin {
	@Inject(method = "isControlledCamera", at = @At("HEAD"), cancellable = true)
	private void starmine$controlHero(CallbackInfoReturnable<Boolean> cir) {
		if (CommandView.active()) cir.setReturnValue(true);
	}
}
