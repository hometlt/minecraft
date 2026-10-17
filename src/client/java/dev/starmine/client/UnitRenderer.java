package dev.starmine.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.starmine.StarMine;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Draws any zerg from its StarMine model with CPU linear-blend skinning.
 *
 * Clip choice: death while dying; an attack clip for its length after a swing; otherwise stand
 * cross-faded into walk by movement speed (flying kinds by their velocity). A death that only exists
 * as a separate SC2 model (gore burst, ragdoll pieces) draws that model instead of the unit and
 * nothing once its clip has ended; with no death at all the body sinks into the ground. Each model
 * part is drawn with its own texture; triangles go out as quads whose last corner repeats the third.
 */
public class UnitRenderer extends EntityRenderer<StarUnit> {
	private float[] palette = new float[0], second = new float[0];
	private final SkinnedModelDrawer drawer = new SkinnedModelDrawer();

	private final ItemRenderer items;

	public UnitRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.items = context.getItemRenderer();
		this.shadowRadius = 0.5f;
	}

	/** Block light every building has at least (owner 16.10: buildings glow a little, even in the dark). */
	public static final int BUILDING_GLOW = 9;

	@Override
	protected int getBlockLightLevel(StarUnit entity, net.minecraft.core.BlockPos pos) {
		int light = super.getBlockLightLevel(entity, pos);
		return entity.kind.structure() ? Math.max(light, BUILDING_GLOW) : light;
	}

	/**
	 * Whose model a unit is drawn with: its own when the assets carry it (the heroes since 17.10: Kerrigan,
	 * Raynor, Artanis from the SC2 campaign), else its stand-in {@link UnitKind#look()}, tinted.
	 */
	static UnitKind shown(UnitKind kind) {
		return kind.look() != kind && StarMineModels.get(kind.id) != null ? kind : kind.look();
	}

	@Override
	public ResourceLocation getTextureLocation(StarUnit entity) {
		return StarMine.id("textures/entity/sc2/" + shown(entity.kind).id + ".png");
	}

	@Override
	public void render(StarUnit entity, float entityYaw, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int packedLight) {
		if (entity.isInvisible()) return; // a worker inside a vespene building
		// a changeling in disguise looks like what it copied, except to its own side (dev.starmine.ability.Changeling)
		if (entity.kind == UnitKind.CHANGELING && !entity.disguise().isEmpty() && entity.deathTime == 0
			&& !(net.minecraft.client.Minecraft.getInstance().player != null && dev.starmine.faction.Faction.allied(entity, net.minecraft.client.Minecraft.getInstance().player))
			&& Disguises.render(entity, partialTick, poseStack, buffers, packedLight)) return;
		SkinnedModel model = StarMineModels.get(shown(entity.kind).id);
		if (model != null && entity.deathTime == 0) model = ModeLook.model(entity, model, shown(entity.kind).id); // its own model for the mode, if any
		float sink = 0f, fall = 0f, shake = 0f;
		boolean visible = model != null;
		boolean imported = model != null;
		if (entity.deathTime > 0 && (model == null || !model.clips.containsKey("death"))) {
			float dying = (entity.deathTime - 1 + partialTick) / 20f;
			SkinnedModel burst = model == null ? null : StarMineModels.get(shown(entity.kind).id + "_death");
			if (burst != null) {
				model = burst;
				SkinnedModel.Clip clip = burst.clips.get("death");
				visible = clip != null && (clip.loop || dying <= clip.seconds(burst.fps));
			} else if (entity.kind.structure()) { // a building without its SC2 death: it shakes and sinks into its ruin
				sink = dying / (entity.kind.deathTicks() / 20f) * entity.kind.height * 0.8f;
				shake = 0.08f * entity.kind.width / 3;
			} else { // a unit without its SC2 death: it falls over, then sinks
				fall = Math.min(1f, dying / 0.45f);
				sink = Math.max(0f, dying - 0.8f) / Math.max(0.1f, entity.kind.deathTicks() / 20f - 0.8f) * entity.kind.height * 0.6f;
			}
		}
		float drop = groundGap(entity, partialTick); // a wide body resting on a ledge: its feet drawn on the ground under it
		if (drop > 0f) sink += drop;
		if (entity.kind == UnitKind.SUPPLY_DEPOT && entity.deathTime == 0) // a lowered depot: its roof level with the ground (it eases down and up)
			sink = Math.max(sink, entity.kind.height * 0.85f * ModeLook.sunk(entity, partialTick, dev.starmine.ability.Modes.LOWERED));
		if (entity.deathTime == 0) // burrowed (dev.starmine.ability.Modes): only the back shows above the ground; it digs in and comes up over its change
			sink = Math.max(sink, entity.kind.height * 0.8f * ModeLook.sunk(entity, partialTick, dev.starmine.ability.Modes.BURROWED));
		if (visible) {
			this.shadowRadius = entity.kind.flying ? entity.kind.width * 0.3f : entity.kind.width * 0.5f;
			choosePose(model, entity, partialTick);
			drawer.skin(model, palette);
			poseStack.pushPose();
			if (sink > 0f) poseStack.translate(0f, -sink, 0f);
			float bodyYaw = Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot);
			poseStack.mulPose(Axis.YP.rotationDegrees(-bodyYaw));
			if (entity.deathTime == 0) ModeLook.shape(entity, partialTick, poseStack, ModeLook.clipped(model, entity)); // its mode's look, and the morph
			if (shake > 0f) poseStack.translate((Math.random() - 0.5) * shake, 0, (Math.random() - 0.5) * shake);
			if (fall > 0f) { // over onto its side (a flyer nose first), round its feet
				float half = entity.kind.flying ? 0 : entity.kind.width / 2;
				if (entity.kind.flying) poseStack.mulPose(Axis.XP.rotationDegrees(35f * fall));
				else {
					poseStack.translate(half, 0, 0);
					poseStack.mulPose(Axis.ZP.rotationDegrees(-85f * fall * fall));
					poseStack.translate(-half, 0, 0);
				}
			}
			float scale = entity.kind.modelScale();
			if (scale != 1f) poseStack.scale(scale, scale, scale);
			int overlay = OverlayTexture.pack(OverlayTexture.u(0f), OverlayTexture.v(entity.hurtTime > 0 && entity.deathTime == 0));
			// cloaked (dev.starmine.ability.Cloak): see-through for its own side, a faint shimmer for others unless a detector reveals it
			var player = net.minecraft.client.Minecraft.getInstance().player;
			if (dev.starmine.ability.ProtossAbilities.SHADE.equals(entity.disguise())) // an adept's shade: see-through, violet
				drawer.drawTranslucent(model, poseStack.last(), buffers, packedLight, overlay, 0x90B080FF);
			else if (entity.cloaked() && entity.deathTime == 0 && !entity.revealed()) {
				boolean ours = player != null && dev.starmine.faction.Faction.allied(entity, player);
				drawer.drawTranslucent(model, poseStack.last(), buffers, packedLight, overlay, ours ? 0x70FFFFFF : 0x14FFFFFF);
			} else if (shown(entity.kind) != entity.kind && entity.kind.tint() != 0xFFFFFFFF) drawer.drawTinted(model, poseStack.last(), buffers, packedLight, overlay, entity.kind.tint());
			else drawer.draw(model, poseStack.last(), buffers, packedLight, overlay);
			if (entity.kind == UnitKind.DRONE && entity.deathTime == 0) drawCarried(entity, model, poseStack, buffers, packedLight);
			poseStack.popPose();
		}
		if (!imported) { // owner 17.10: no imported SC2 model — StarMine's own stand-in (FallbackModels), not an outline
			this.shadowRadius = entity.kind.flying ? entity.kind.width * 0.3f : entity.kind.width * 0.5f;
			poseStack.pushPose();
			if (sink > 0f) poseStack.translate(0f, -sink, 0f);
			poseStack.mulPose(Axis.YP.rotationDegrees(-Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot)));
			if (entity.deathTime == 0) ModeLook.shape(entity, partialTick, poseStack, false);
			if (shake > 0f) poseStack.translate((Math.random() - 0.5) * shake, 0, (Math.random() - 0.5) * shake);
			if (fall > 0f) {
				float half = entity.kind.flying ? 0 : entity.kind.width / 2;
				if (entity.kind.flying) poseStack.mulPose(Axis.XP.rotationDegrees(35f * fall));
				else {
					poseStack.translate(half, 0, 0);
					poseStack.mulPose(Axis.ZP.rotationDegrees(-85f * fall * fall));
					poseStack.translate(-half, 0, 0);
				}
			}
			int overlay = OverlayTexture.pack(OverlayTexture.u(0f), OverlayTexture.v(entity.hurtTime > 0 && entity.deathTime == 0));
			var player = net.minecraft.client.Minecraft.getInstance().player;
			int alpha = 255;
			if (entity.cloaked() && entity.deathTime == 0 && !entity.revealed())
				alpha = player != null && dev.starmine.faction.Faction.allied(entity, player) ? 0x70 : 0x14;
			if (dev.starmine.ability.ProtossAbilities.SHADE.equals(entity.disguise())) alpha = 0x90; // an adept's shade
			FallbackModels.draw(entity, partialTick, poseStack, buffers, packedLight, overlay, alpha);
			poseStack.popPose();
		}
		super.render(entity, entityYaw, partialTick, poseStack, buffers, packedLight);
	}

	/**
	 * How far the ground under a walker's middle lies below its feet (owner 17.10: ground units seemed to fly over
	 * blocks): a wide body stands on the edge of a step or a ditch with its middle over air, so it is drawn lowered
	 * onto the ground under its middle, by up to a block and a half. Not flyers, buildings or the dead.
	 */
	private static float groundGap(StarUnit entity, float partialTick) {
		if (entity.kind.flying || entity.kind.structure() || !entity.onGround() || entity.deathTime > 0 || entity.isPassenger()) return 0f;
		var level = entity.level();
		double x = Mth.lerp(partialTick, entity.xo, entity.getX()), y = Mth.lerp(partialTick, entity.yo, entity.getY()), z = Mth.lerp(partialTick, entity.zo, entity.getZ());
		net.minecraft.core.BlockPos.MutableBlockPos at = new net.minecraft.core.BlockPos.MutableBlockPos();
		for (int down = 0; down <= 2; down++) {
			at.set(Mth.floor(x), Mth.floor(y - 0.01) - down, Mth.floor(z));
			var shape = level.getBlockState(at).getCollisionShape(level, at);
			if (shape.isEmpty()) continue;
			double top = at.getY() + shape.max(net.minecraft.core.Direction.Axis.Y);
			return (float) Math.max(0, Math.min(1.5, y - top));
		}
		return 1.5f;
	}

	/** The drone's valuable slot held in front of it: one small block per unit, up to 8 (2x2x2). */
	private void drawCarried(StarUnit drone, SkinnedModel model, PoseStack poseStack, MultiBufferSource buffers, int light) {
		ItemStack carried = drone.carried();
		if (carried.isEmpty()) return;
		int cubes = Math.min(8, carried.getCount());
		ItemStack one = carried.copyWithCount(1);
		for (int i = 0; i < cubes; i++) {
			poseStack.pushPose();
			poseStack.translate(((i & 1) - 0.5) * 0.17, model.size[1] * 0.25 + (i >> 2) * 0.17, model.size[2] * 0.36 + ((i >> 1) & 1) * 0.17);
			poseStack.scale(0.17f, 0.17f, 0.17f);
			items.renderStatic(one, ItemDisplayContext.FIXED, light, OverlayTexture.NO_OVERLAY, poseStack, buffers, drone.level(), drone.getId() + i);
			poseStack.popPose();
		}
	}

	private void choosePose(SkinnedModel model, StarUnit entity, float partialTick) {
		int size = model.slots * 12;
		if (palette.length != size) {
			palette = new float[size];
			second = new float[size];
		}
		float time = (entity.tickCount + partialTick) / 20f;
		SkinnedModel.Clip death = model.clips.get("death");
		if (entity.deathTime > 0 && death != null) {
			model.pose(death, (entity.deathTime - 1 + partialTick) / 20f, palette);
			return;
		}
		if (entity.deathTime > 0 && model.clips.isEmpty()) return;
		SkinnedModel.Clip stand = model.clips.get("stand");
		if (stand == null) stand = model.clips.values().iterator().next();
		if (stand == null) return;
		model.pose(stand, time, palette);
		ModeLook.pose(model, entity, partialTick, time, palette); // a morph under way, or the mode's stand
		SkinnedModel.Clip walk = model.clips.get("walk");
		float moving = entity.kind.flying
			? (float) entity.getDeltaMovement().horizontalDistance() * 6f
			: entity.walkAnimation.speed(partialTick) * 2.5f;
		moving = Math.min(1f, moving);
		if (walk != null && moving > 0.01f) {
			model.pose(walk, time * 1.4f, second);
			SkinnedModel.blend(palette, second, moving);
		}
		int attacks = model.attacks.size();
		if (attacks > 0) {
			SkinnedModel.Clip attack = model.attacks.get(Math.floorMod(entity.attackVariant, attacks));
			float since = (entity.tickCount - entity.attackStartTick + partialTick) / 20f;
			float length = attack.seconds(model.fps);
			if (since >= 0 && since < length) {
				model.pose(attack, since, second);
				SkinnedModel.blend(palette, second, Math.min(1f, Math.min(since, length - since) / 0.08f));
			}
		}
	}

}
