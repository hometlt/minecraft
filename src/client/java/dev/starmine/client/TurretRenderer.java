package dev.starmine.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.starmine.block.ArmorBlocks;
import dev.starmine.gear.Turrets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Owner 19.10: a turret drawn of blocks — its race's armour slab under a dispenser that turns to its foe. */
final class TurretRenderer extends EntityRenderer<Turrets.Turret> {
	TurretRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 0.45f;
	}

	@Override
	public void render(Turrets.Turret turret, float yaw, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
		var blocks = Minecraft.getInstance().getBlockRenderer();
		BlockState base = ArmorBlocks.BUILDING_BLOCKS.get(turret.kind.race).get("armor_slab").defaultBlockState();
		BlockState head = Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, Direction.SOUTH);
		int overlay = turret.hurtTime > 0 ? OverlayTexture.pack(0, 3) : OverlayTexture.NO_OVERLAY;
		pose.pushPose();
		pose.translate(-0.45, 0, -0.45);
		pose.scale(0.9f, 0.9f, 0.9f);
		blocks.renderSingleBlock(base, pose, buffers, light, overlay);
		pose.popPose();
		pose.pushPose();
		pose.translate(0, 0.45, 0);
		pose.mulPose(Axis.YP.rotationDegrees(-Mth.rotLerp(partial, turret.yHeadRotO, turret.yHeadRot)));
		pose.translate(-0.35, 0, -0.35);
		pose.scale(0.7f, 0.7f, 0.7f);
		blocks.renderSingleBlock(head, pose, buffers, light, overlay);
		pose.popPose();
		super.render(turret, yaw, partial, pose, buffers, light);
	}

	@Override
	public ResourceLocation getTextureLocation(Turrets.Turret turret) {
		return TextureAtlas.LOCATION_BLOCKS;
	}
}
