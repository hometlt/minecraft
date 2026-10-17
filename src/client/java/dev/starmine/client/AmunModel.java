package dev.starmine.client;

import dev.starmine.StarMine;
import dev.starmine.world.Amun;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.util.Mth;

/**
 * Amun's body (owner 17.10; StarMine's own, no SC2 model): a long dark body with spines down its back, a horned head
 * with burning eyes on a neck, two great wings of two joints that beat slowly, a tail of three segments that
 * sways, and claws hanging below. One noisy texture for the hide; the eyes map to its red corner, which glows.
 */
final class AmunModel extends HierarchicalModel<Amun> {
	static final ModelLayerLocation LAYER = new ModelLayerLocation(StarMine.id("amun"), "main");
	private final ModelPart root, neck, head, leftWing, leftTip, rightWing, rightTip, tail, tailMid, tailEnd;

	AmunModel(ModelPart root) {
		this.root = root;
		ModelPart body = root.getChild("body");
		neck = body.getChild("neck");
		head = neck.getChild("head");
		leftWing = body.getChild("left_wing");
		leftTip = leftWing.getChild("tip");
		rightWing = body.getChild("right_wing");
		rightTip = rightWing.getChild("tip");
		tail = body.getChild("tail");
		tailMid = tail.getChild("mid");
		tailEnd = tailMid.getChild("end");
	}

	static LayerDefinition createBodyLayer() {
		MeshDefinition mesh = new MeshDefinition();
		PartDefinition root = mesh.getRoot();
		PartDefinition body = root.addOrReplaceChild("body", CubeListBuilder.create().texOffs(0, 0).addBox(-8, -6, -16, 16, 12, 32)
			.texOffs(0, 0).addBox(-6, 6, -12, 12, 3, 22), PartPose.offset(0, 8, 0));
		for (int i = 0; i < 5; i++) // spines down the back
			body.addOrReplaceChild("spine" + i, CubeListBuilder.create().texOffs(0, 0).addBox(-1, -6 - (i % 2 == 0 ? 6 : 4), -1, 2, 6 + (i % 2 == 0 ? 6 : 4), 2),
				PartPose.offsetAndRotation(0, -6, -12 + i * 6, -0.35f, 0, 0));
		PartDefinition neck = body.addOrReplaceChild("neck", CubeListBuilder.create().texOffs(0, 0).addBox(-4, -4, -12, 8, 8, 12),
			PartPose.offsetAndRotation(0, -2, -16, -0.2f, 0, 0));
		PartDefinition head = neck.addOrReplaceChild("head", CubeListBuilder.create().texOffs(0, 0).addBox(-6, -5, -12, 12, 10, 12)
			.texOffs(0, 0).addBox(-4, 3, -14, 8, 4, 10) // the jaw
			.texOffs(116, 116).addBox(-5, -2, -12.2f, 3, 2, 1) // the eyes (the texture's red corner)
			.texOffs(116, 116).addBox(2, -2, -12.2f, 3, 2, 1), PartPose.offsetAndRotation(0, 0, -11, 0.2f, 0, 0));
		head.addOrReplaceChild("left_horn", CubeListBuilder.create().texOffs(0, 0).addBox(-1, -1, 0, 2, 2, 14), PartPose.offsetAndRotation(4, -5, -4, 0.5f, 0.35f, 0));
		head.addOrReplaceChild("right_horn", CubeListBuilder.create().texOffs(0, 0).addBox(-1, -1, 0, 2, 2, 14), PartPose.offsetAndRotation(-4, -5, -4, 0.5f, -0.35f, 0));
		for (int side = 0; side < 2; side++) {
			boolean left = side == 0;
			float s = left ? 1 : -1;
			PartDefinition wing = body.addOrReplaceChild(left ? "left_wing" : "right_wing", CubeListBuilder.create()
				.texOffs(0, 0).addBox(left ? 0 : -28, -1.5f, -1.5f, 28, 3, 3)
				.texOffs(0, 0).addBox(left ? 0 : -28, 0, 1.5f, 28, 0, 24, CubeDeformation.NONE), PartPose.offset(7 * s, -4, -8));
			wing.addOrReplaceChild("tip", CubeListBuilder.create()
				.texOffs(0, 0).addBox(left ? 0 : -26, -1, -1, 26, 2, 2)
				.texOffs(0, 0).addBox(left ? 0 : -26, 0, 1, 26, 0, 20, CubeDeformation.NONE), PartPose.offset(28 * s, 0, 0));
		}
		PartDefinition tail = body.addOrReplaceChild("tail", CubeListBuilder.create().texOffs(0, 0).addBox(-4, -4, 0, 8, 8, 14), PartPose.offset(0, -1, 16));
		PartDefinition mid = tail.addOrReplaceChild("mid", CubeListBuilder.create().texOffs(0, 0).addBox(-3, -3, 0, 6, 6, 14), PartPose.offset(0, 0, 14));
		mid.addOrReplaceChild("end", CubeListBuilder.create().texOffs(0, 0).addBox(-2, -2, 0, 4, 4, 14)
			.texOffs(0, 0).addBox(-5, -1, 10, 10, 2, 6), PartPose.offset(0, 0, 14));
		for (int i = 0; i < 4; i++) // claws hanging below
			body.addOrReplaceChild("claw" + i, CubeListBuilder.create().texOffs(0, 0).addBox(-1.5f, 0, -1.5f, 3, 9, 3),
				PartPose.offsetAndRotation(i % 2 == 0 ? 6 : -6, 8, i < 2 ? -8 : 8, 0.4f, 0, 0));
		return LayerDefinition.create(mesh, 128, 128);
	}

	@Override
	public ModelPart root() {
		return root;
	}

	@Override
	public void setupAnim(Amun amun, float limbSwing, float limbSpeed, float age, float headYaw, float headPitch) {
		float beat = age * 0.12f;
		leftWing.zRot = Mth.sin(beat) * 0.55f - 0.1f;
		rightWing.zRot = -leftWing.zRot;
		leftTip.zRot = Mth.sin(beat - 0.6f) * 0.45f;
		rightTip.zRot = -leftTip.zRot;
		tail.yRot = Mth.sin(age * 0.05f) * 0.25f;
		tailMid.yRot = Mth.sin(age * 0.05f - 0.7f) * 0.3f;
		tailEnd.yRot = Mth.sin(age * 0.05f - 1.4f) * 0.35f;
		neck.yRot = Mth.sin(age * 0.03f) * 0.15f;
		head.xRot = 0.2f + Mth.sin(age * 0.07f) * 0.08f;
	}
}
