package dev.starmine.mixin.client;

import dev.starmine.client.CommandView;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * In the command view, what a detector senses behind a wall is drawn as a glowing outline seen through blocks
 * ({@link CommandView#silhouette}); so is what a radar potion or the player's sensor tower senses ({@link dev.starmine.client.Radar}).
 */
@Mixin(Minecraft.class)
abstract class SilhouetteMixin {
	@Inject(method = "shouldEntityAppearGlowing", at = @At("HEAD"), cancellable = true)
	private void starmine$silhouette(Entity entity, CallbackInfoReturnable<Boolean> result) {
		if (CommandView.active() && CommandView.silhouette(entity) || dev.starmine.client.Radar.glows(entity)) result.setReturnValue(true);
	}
}
