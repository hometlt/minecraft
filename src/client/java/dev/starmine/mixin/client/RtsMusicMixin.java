package dev.starmine.mixin.client;

import dev.starmine.client.CommandView;
import net.minecraft.client.sounds.MusicManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** SC2 race music owns the music channel while the RTS view is open. */
@Mixin(MusicManager.class)
abstract class RtsMusicMixin {
	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void starmine$rtsMusic(CallbackInfo ci) { if (CommandView.active()) ci.cancel(); }
}
