package dev.starmine.mixin;

import dev.starmine.gear.RaceTools;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Owner 19.10: a protoss tool's energy shield takes its wear while it lasts ({@link RaceTools#absorb}). */
@Mixin(ItemStack.class)
abstract class ItemStackWearMixin {
	@Inject(method = "hurtAndBreak(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/server/level/ServerPlayer;Ljava/util/function/Consumer;)V",
		at = @At("HEAD"), cancellable = true)
	private void starmine$shield(int amount, ServerLevel level, ServerPlayer player, Consumer<Item> onBreak, CallbackInfo info) {
		if (amount > 0 && RaceTools.absorb((ItemStack) (Object) this, level)) info.cancel();
	}
}
