package dev.starmine.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.OptionalDouble;
import net.minecraft.client.renderer.RenderType;

/**
 * Lines drawn through blocks (no depth test): the silhouettes of creatures a detector senses behind a
 * wall in the command view. The game's glowing outline is a post effect that shader packs (Iris) may
 * leave out; these lines are plain geometry every pipeline draws.
 */
abstract class SeeThrough extends RenderType {
	static final RenderType LINES = create("starmine_see_through_lines", DefaultVertexFormat.POSITION_COLOR_NORMAL, VertexFormat.Mode.LINES, 1536,
		false, false, CompositeState.builder()
			.setShaderState(RENDERTYPE_LINES_SHADER)
			.setLineState(new LineStateShard(OptionalDouble.of(2.5)))
			.setLayeringState(VIEW_OFFSET_Z_LAYERING)
			.setTransparencyState(TRANSLUCENT_TRANSPARENCY)
			.setWriteMaskState(COLOR_WRITE)
			.setCullState(NO_CULL)
			.setDepthTestState(NO_DEPTH_TEST)
			.createCompositeState(false));

	private SeeThrough(String name, VertexFormat format, VertexFormat.Mode mode, int size, boolean crumbling, boolean sort, Runnable setup, Runnable clear) {
		super(name, format, mode, size, crumbling, sort, setup, clear);
	}
}
