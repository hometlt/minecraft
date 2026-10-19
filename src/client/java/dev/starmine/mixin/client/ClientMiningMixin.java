package dev.starmine.mixin.client;

import dev.starmine.client.ClientWear;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Owner 19.10: mining a block starts from the work already done on it (the server's saved wear, {@code BlockWear}). */
@Mixin(MultiPlayerGameMode.class)
abstract class ClientMiningMixin {
	@Shadow private BlockPos destroyBlockPos;
	@Shadow private float destroyProgress;
	@Shadow private boolean isDestroying;

	@Inject(method = "startDestroyBlock", at = @At("RETURN"))
	private void starmine$resume(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> info) {
		if (isDestroying && pos.equals(destroyBlockPos)) destroyProgress = Math.max(destroyProgress, ClientWear.get(pos));
	}
}
