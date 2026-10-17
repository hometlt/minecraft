package dev.starmine.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.starmine.ability.Modes;
import dev.starmine.entity.StarUnit;
import net.minecraft.util.Mth;

/**
 * How a unit looks in its mode and while it changes (owner 17.10: units morph visibly and look different after).
 *
 * - A model with SC2 morph clips: "morph" plays forward into a mode and backward out of it; in the mode the model
 *   stands with "stand_<mode>" (e.g. "stand_sieged"), or holds the last frame of "morph". A model of its own for the
 *   mode (`<kind>_<mode>.smm`, e.g. `siegetank_sieged`) is drawn instead of the unit's.
 * - Any model (imported or StarMine's own): its body eases into the mode's shape over the change — a sieged tank low
 *   and wide, a phasing prism spread flat, a liberator in defender mode nose down, a planetary fortress squat, an
 *   orbital command taller; a change into another kind (a hellion into a hellbat) shudders and gathers in.
 */
final class ModeLook {
	/** The body in a mode: widths and height scaled, tilted (degrees, nose down). */
	private record Shape(float wide, float tall, float pitch) {
		static final Shape SAME = new Shape(1, 1, 0);

		Shape lerp(Shape to, float t) {
			return new Shape(Mth.lerp(t, wide, to.wide), Mth.lerp(t, tall, to.tall), Mth.lerp(t, pitch, to.pitch));
		}
	}

	private ModeLook() {
	}

	/** The mode's name in clip and model names. */
	static String name(int mode) {
		return switch (mode) {
			case Modes.SIEGED -> "sieged";
			case Modes.BURROWED -> "burrowed";
			case Modes.LANDED -> "landed";
			case Modes.HELLBAT -> "hellbat";
			case Modes.PHASING -> "phasing";
			case Modes.PULSAR -> "pulsar";
			case Modes.HIGH_IMPACT -> "high_impact";
			case Modes.LOWERED -> "lowered";
			case Modes.WARPGATE -> "warpgate";
			case Modes.TECHLAB -> "techlab";
			case Modes.REACTOR -> "reactor";
			case Modes.ORBITAL -> "orbital";
			case Modes.PLANETARY -> "planetary";
			case Modes.AIRBORNE -> "airborne";
			case Modes.DEFENDER -> "defender";
			case Modes.GREATER -> "greater";
			default -> "normal";
		};
	}

	private static Shape shape(int mode) {
		return switch (mode) {
			case Modes.SIEGED -> new Shape(1.18f, 0.8f, 0);
			case Modes.PHASING -> new Shape(1.35f, 0.65f, 0);
			case Modes.DEFENDER -> new Shape(1.05f, 1f, 24);
			case Modes.GREATER -> new Shape(1.06f, 1.3f, 0); // the greater spire: taller, its spikes out
			case Modes.HIGH_IMPACT -> new Shape(1.05f, 1.08f, 0);
			case Modes.PULSAR -> new Shape(1f, 1f, -8);
			case Modes.WARPGATE -> new Shape(1.08f, 0.92f, 0);
			case Modes.ORBITAL -> new Shape(0.97f, 1.12f, 0);
			case Modes.PLANETARY -> new Shape(1.12f, 0.88f, 0);
			case Modes.AIRBORNE -> new Shape(1f, 1f, 4);
			default -> Shape.SAME;
		};
	}

	/** The model drawn for its mode: its own for the mode when the assets carry one. */
	static SkinnedModel model(StarUnit unit, SkinnedModel base, String id) {
		int mode = unit.mode();
		if (mode == Modes.NORMAL) return base;
		SkinnedModel own = StarMineModels.get(id + "_" + name(mode));
		return own != null ? own : base;
	}

	/**
	 * The pose of its mode (after the stand clip is posed into `palette`): the morph clip during a change, the
	 * mode's stand after; false when the model has no clips for it (the shape alone then shows it).
	 */
	static boolean pose(SkinnedModel model, StarUnit unit, float partialTick, float time, float[] palette) {
		SkinnedModel.Clip morph = model.clips.get("morph");
		int to = unit.changingTo();
		if (to >= 0 && morph != null) { // forward into a mode, backward out of it
			float progress = unit.changeProgress(partialTick);
			float at = to == Modes.NORMAL ? 1 - progress : progress;
			model.pose(morph, at * morph.seconds(model.fps), palette);
			return true;
		}
		int mode = unit.mode();
		if (mode == Modes.NORMAL) return false;
		SkinnedModel.Clip stand = model.clips.get("stand_" + name(mode));
		if (stand != null) {
			model.pose(stand, time, palette);
			return true;
		}
		if (morph != null) {
			model.pose(morph, morph.seconds(model.fps), palette); // held at the end of its morph
			return true;
		}
		return false;
	}

	/** Whether the model shows the mode itself (its clips): then the shape is left alone. */
	static boolean clipped(SkinnedModel model, StarUnit unit) {
		return model != null && (model.clips.containsKey("morph") || model.clips.containsKey("stand_" + name(unit.mode())));
	}

	/** Shapes the body for its mode, easing over a change; a change into another kind shudders and gathers in. */
	static void shape(StarUnit unit, float partialTick, PoseStack pose, boolean clipped) {
		float progress = unit.changeProgress(partialTick);
		if (unit.changingInto() != null) {
			float shudder = (float) Math.sin((unit.tickCount + partialTick) * 1.7) * 0.06f * (1 - progress);
			float gather = 1 - 0.25f * progress * progress;
			pose.scale(gather + shudder, gather - shudder, gather + shudder);
			return;
		}
		if (clipped) return;
		Shape now = shape(unit.mode());
		int to = unit.changingTo();
		if (to >= 0) now = now.lerp(shape(to), smooth(progress));
		if (now.pitch != 0) pose.mulPose(Axis.XP.rotationDegrees(now.pitch));
		if (now.wide != 1 || now.tall != 1) pose.scale(now.wide, now.tall, now.wide);
	}

	/** How deep a burrowing unit or a lowering depot is, easing over the change (0..1 of how far it goes down). */
	static float sunk(StarUnit unit, float partialTick, int mode) {
		float progress = smooth(unit.changeProgress(partialTick));
		int to = unit.changingTo();
		if (to == mode) return progress;
		if (unit.mode() == mode) return to >= 0 ? 1 - progress : 1;
		return 0;
	}

	private static float smooth(float t) {
		return t * t * (3 - 2 * t);
	}
}
