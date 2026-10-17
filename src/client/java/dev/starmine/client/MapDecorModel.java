package dev.starmine.client;

import java.util.function.Supplier;
import net.fabricmc.fabric.api.renderer.v1.model.ForwardingBakedModel;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The model of walk-through map decor ({@link dev.starmine.world.MapDecor}): in the chunk's mesh it
 * emits the quads of the block the decor looks like (its block entity's render data), culled and lit
 * as that block would be. Without that data it draws nothing.
 */
final class MapDecorModel extends ForwardingBakedModel {
	MapDecorModel(BakedModel wrapped) {
		this.wrapped = wrapped;
	}

	@Override
	public boolean isVanillaAdapter() {
		return false;
	}

	@Override
	public void emitBlockQuads(BlockAndTintGetter view, BlockState state, BlockPos pos, Supplier<RandomSource> random, RenderContext context) {
		if (view.getBlockEntityRenderData(pos) instanceof BlockState look)
			Minecraft.getInstance().getBlockRenderer().getBlockModel(look).emitBlockQuads(view, look, pos, random, context);
	}
}
