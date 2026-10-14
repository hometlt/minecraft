package dev.starmine.mixin.client;

import dev.starmine.client.CommandView;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Fog of war in the terran command view: entities nobody of the base sees are not drawn ({@link CommandView#visible}). */
@Mixin(EntityRenderDispatcher.class)
abstract class HideInFogMixin {
	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
	private void starmine$hideInFog(Entity entity, Frustum frustum, double x, double y, double z, CallbackInfoReturnable<Boolean> result) {
		if (CommandView.active() && !CommandView.visible(entity)) result.setReturnValue(false);
	}
}
