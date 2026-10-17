package dev.starmine.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.starmine.StarMine;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * The world's relief under a laid-over map (owner 17.10: under the map, the world's own ground with
 * its caves, never a gap or a sea). The overworld's `depth`, `factor` and `jaggedness` density
 * functions (data/minecraft/worldgen/density_function/overworld/) are this function around their
 * own (`fallback`): over a map's columns the depth is that of a surface at the map's ground
 * ({@link MapOverlay#relief}: the vanilla gradient, 1/128 per block, crossing zero just above the
 * top block), the factor {@link #FACTOR} (a steep one: the noise moves the surface a block or so)
 * and no jaggedness (no peaks), each blended with the world's own by the column's weight (full on
 * the map, falling to none at its edge, smoothed again here). The world's noise router, caves, aquifers and ores are untouched, so they
 * work on that ground as on any; the map's land is laid on it by {@link MapOverlay}. With no map
 * loaded each function is its fallback.
 */
public record MapOverlayDensity(Role role, DensityFunction fallback) implements DensityFunction {
	static final double FACTOR = 40;

	public enum Role implements StringRepresentable {
		DEPTH, FACTOR, JAGGEDNESS;

		static final Codec<Role> CODEC = StringRepresentable.fromEnum(Role::values);

		@Override
		public String getSerializedName() {
			return name().toLowerCase(java.util.Locale.ROOT);
		}
	}

	static final MapCodec<MapOverlayDensity> DATA = RecordCodecBuilder.mapCodec(instance -> instance.group(
		Role.CODEC.fieldOf("role").forGetter(MapOverlayDensity::role),
		DensityFunction.HOLDER_HELPER_CODEC.fieldOf("fallback").forGetter(MapOverlayDensity::fallback)
	).apply(instance, MapOverlayDensity::new));
	static final KeyDispatchDataCodec<MapOverlayDensity> CODEC = KeyDispatchDataCodec.of(DATA);

	@Override
	public double compute(FunctionContext context) {
		double own = fallback.compute(context);
		double[] relief = MapOverlay.relief(context.blockX(), context.blockZ());
		if (relief == null) return own;
		double t = relief[1], weight = t * t * (3 - 2 * t);
		double ours = switch (role) {
			case DEPTH -> (relief[0] + 1 - context.blockY()) / 128.0;
			case FACTOR -> FACTOR;
			case JAGGEDNESS -> 0;
		};
		return own + (ours - own) * weight;
	}

	@Override
	public void fillArray(double[] array, ContextProvider provider) {
		provider.fillAllDirectly(array, this);
	}

	@Override
	public DensityFunction mapAll(Visitor visitor) {
		return visitor.apply(new MapOverlayDensity(role, fallback.mapAll(visitor)));
	}

	@Override
	public double minValue() {
		return Math.min(fallback.minValue(), switch (role) {
			case DEPTH -> -4;
			case FACTOR -> FACTOR;
			case JAGGEDNESS -> 0;
		});
	}

	@Override
	public double maxValue() {
		return Math.max(fallback.maxValue(), switch (role) {
			case DEPTH -> 4;
			case FACTOR -> FACTOR;
			case JAGGEDNESS -> 0;
		});
	}

	@Override
	public KeyDispatchDataCodec<? extends DensityFunction> codec() {
		return CODEC;
	}

	static void load() {
		Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE, StarMine.id("map_overlay"), DATA);
	}
}
