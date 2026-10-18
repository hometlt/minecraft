package dev.starmine.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The server takes a player's blow or use on a StarCraft unit as far as the client's crosshair reaches it: its
 * hitbox grown by its {@link dev.starmine.entity.UnitKind#reachPad} (the model drawn wider than the hitbox), as
 * {@link dev.starmine.entity.StarUnit#getPickRadius} grows it on the client. Without it the server refused the
 * blows the crosshair found (measured by dev.starmine.client.Harness).
 */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class UnitReachMixin {
	@Redirect(method = "handleInteract", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getBoundingBox()Lnet/minecraft/world/phys/AABB;"))
	private AABB starmine$reachedBox(Entity target) {
		AABB box = target.getBoundingBox();
		return target instanceof dev.starmine.entity.StarUnit unit ? box.inflate(unit.kind.reachPad()) : box;
	}
}
