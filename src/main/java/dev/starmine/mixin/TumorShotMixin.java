package dev.starmine.mixin;

import dev.starmine.block.StarMineBlocks;
import dev.starmine.creep.CreepTumors;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Owner 17.10: an arrow, a trident or any thrown thing striking a creep tumor wounds it ({@link CreepTumors#hurt}). */
@Mixin(Projectile.class)
abstract class TumorShotMixin {
	@Inject(method = "onHitBlock", at = @At("HEAD"))
	private void starmine$hitTumor(BlockHitResult hit, CallbackInfo info) {
		Projectile self = (Projectile) (Object) this;
		if (!(self.level() instanceof ServerLevel level)) return;
		// the tumor has nothing to collide with: the shot strikes the block it grows on, by the face it sits on
		net.minecraft.core.BlockPos pos = hit.getBlockPos(), beside = pos.relative(hit.getDirection());
		net.minecraft.core.BlockPos tumor = level.getBlockState(pos).is(StarMineBlocks.CREEP_TUMOR) ? pos
			: level.getBlockState(beside).is(StarMineBlocks.CREEP_TUMOR) ? beside : null;
		if (tumor == null) return;
		double damage = self instanceof AbstractArrow arrow ? arrow.getBaseDamage() * Math.max(1, self.getDeltaMovement().length()) : 4;
		CreepTumors.hurt(level, tumor, damage);
	}
}
