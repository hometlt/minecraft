package dev.starmine.mixin;

import com.mojang.serialization.Lifecycle;
import java.util.List;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A world with the stratosphere is not "experimental" (owner 17.10: no warning at every load): the game calls a world
 * experimental when it has more dimensions than the vanilla three, or one it does not know; StarMine's own
 * dimensions are not counted and count as known.
 */
@Mixin(WorldDimensions.class)
abstract class StableDimensionMixin {
	@Redirect(method = "bake", at = @At(value = "INVOKE", target = "Ljava/util/List;size()I"))
	private int starmine$withoutOurs(List<?> dimensions) {
		// the entries are the game's own records of a key and a dimension; their text names the key
		return (int) dimensions.stream().filter(entry -> !String.valueOf(entry).contains("starmine:")).count();
	}

	@Inject(method = "checkStability", at = @At("HEAD"), cancellable = true)
	private static void starmine$stable(ResourceKey<LevelStem> key, LevelStem stem, CallbackInfoReturnable<Lifecycle> result) {
		if ("starmine".equals(key.location().getNamespace())) result.setReturnValue(Lifecycle.stable());
	}
}
