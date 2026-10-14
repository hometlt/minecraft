package dev.starmine.mixin;

import dev.starmine.possess.Pilots;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A player who plays as a unit has the unit's size in every pose (dev.starmine.possess.Pilots, both sides). */
@Mixin(Player.class)
abstract class PilotDimensionsMixin {
	@Inject(method = "getDefaultDimensions", at = @At("HEAD"), cancellable = true)
	private void starmine$pilotSize(Pose pose, CallbackInfoReturnable<EntityDimensions> result) {
		EntityDimensions dimensions = Pilots.dimensions((Player) (Object) this);
		if (dimensions != null) result.setReturnValue(dimensions);
	}
}
