package dev.starmine.mixin;

import com.mojang.serialization.Decoder;
import com.mojang.serialization.Lifecycle;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.WritableRegistry;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.resources.Resource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * StarMine's own world generation entries (owner 17.10: the stratosphere's dimension, its biome) are stable: a mod's
 * data is not a "known pack", so the game took them for experimental and warned at every world load.
 */
@Mixin(RegistryDataLoader.class)
abstract class StableRegistriesMixin {
	@ModifyVariable(method = "loadElementFromResource", at = @At("HEAD"), argsOnly = true)
	private static RegistrationInfo starmine$stable(RegistrationInfo info, WritableRegistry<?> registry, Decoder<?> decoder,
			RegistryOps<?> ops, ResourceKey<?> key, Resource resource) {
		return "starmine".equals(key.location().getNamespace()) && info.lifecycle() != Lifecycle.stable()
			? new RegistrationInfo(info.knownPackInfo(), Lifecycle.stable()) : info;
	}
}
