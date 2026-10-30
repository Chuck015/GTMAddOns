package com.example.gtmaddons;

import com.example.gtmaddons.compat.Mc;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

/**
 * Draws a "fish cat" where the player's head is, following the head's turns (the "Fish cat" setting): a teal scaled
 * blob with a cream cat face, puffy whisker cheeks and a pink nose, and coral fish parts - spiky ear fins, a dorsal fin,
 * pectoral fins, a forked tail, a belly fin and catfish barbels. The real head is hidden by PlayerModelFishHeadMixin.
 *
 * The parts are in HALF model pixels (drawn at half scale) so the texture has twice the usual detail; the face is at -z.
 * The texture is assets/gtmaddons/textures/entity/blobcat.png, packed to match this table. SCALE is what to tune for size.
 */
public final class FishHeadLayer extends RenderLayer<AvatarRenderState, PlayerModel> {

	private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("gtmaddons", "textures/entity/blobcat.png");
	/** 0.5 = one unit is half a model pixel. The blob is 24 units (12 px at 0.5) across; the head is an 8 px cube. */
	private static final float SCALE = 0.45f;

	private final ModelPart fish;

	public FishHeadLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
		super(parent);
		MeshDefinition mesh = new MeshDefinition();
		PartDefinition root = mesh.getRoot();
		root.addOrReplaceChild("core", CubeListBuilder.create().texOffs(81, 0).addBox(-10.0f, -8.0f, -10.0f, 20.0f, 16.0f, 20.0f), PartPose.offsetAndRotation(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f));
		root.addOrReplaceChild("vert", CubeListBuilder.create().texOffs(162, 0).addBox(-8.0f, -10.0f, -8.0f, 16.0f, 20.0f, 16.0f), PartPose.offsetAndRotation(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f));
		root.addOrReplaceChild("depth", CubeListBuilder.create().texOffs(0, 0).addBox(-8.0f, -8.0f, -12.0f, 16.0f, 16.0f, 24.0f), PartPose.offsetAndRotation(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f));
		root.addOrReplaceChild("side", CubeListBuilder.create().texOffs(0, 41).addBox(-12.0f, -8.0f, -8.0f, 24.0f, 16.0f, 16.0f), PartPose.offsetAndRotation(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f));
		root.addOrReplaceChild("stalk", CubeListBuilder.create().texOffs(156, 41).addBox(-3.0f, -3.0f, 12.0f, 6.0f, 6.0f, 6.0f), PartPose.offsetAndRotation(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f));
		root.addOrReplaceChild("cheek_l", CubeListBuilder.create().texOffs(215, 41).addBox(1.0f, 0.0f, -15.0f, 8.0f, 6.0f, 3.0f), PartPose.offsetAndRotation(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f));
		root.addOrReplaceChild("cheek_r", CubeListBuilder.create().texOffs(0, 74).addBox(-9.0f, 0.0f, -15.0f, 8.0f, 6.0f, 3.0f), PartPose.offsetAndRotation(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f));
		root.addOrReplaceChild("nose", CubeListBuilder.create().texOffs(95, 74).addBox(-1.0f, 0.0f, -16.0f, 2.0f, 2.0f, 4.0f), PartPose.offsetAndRotation(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f));
		root.addOrReplaceChild("ear_l", CubeListBuilder.create().texOffs(181, 41).addBox(-4.0f, -10.0f, 0.0f, 8.0f, 10.0f, 0.0f), PartPose.offsetAndRotation(6.0f, -9.0f, 0.0f, 0.0f, 0.0f, 0.4f));
		root.addOrReplaceChild("ear_r", CubeListBuilder.create().texOffs(198, 41).addBox(-4.0f, -10.0f, 0.0f, 8.0f, 10.0f, 0.0f), PartPose.offsetAndRotation(-6.0f, -9.0f, 0.0f, 0.0f, 0.0f, -0.4f));
		root.addOrReplaceChild("dorsal", CubeListBuilder.create().texOffs(106, 41).addBox(0.0f, -17.0f, -4.0f, 0.0f, 8.0f, 16.0f), PartPose.offsetAndRotation(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f));
		root.addOrReplaceChild("tail_fin", CubeListBuilder.create().texOffs(81, 41).addBox(0.0f, -10.0f, 17.0f, 0.0f, 20.0f, 12.0f), PartPose.offsetAndRotation(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f));
		root.addOrReplaceChild("belly_fin", CubeListBuilder.create().texOffs(139, 41).addBox(0.0f, 10.0f, -3.0f, 0.0f, 5.0f, 8.0f), PartPose.offsetAndRotation(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f));
		root.addOrReplaceChild("fin_l", CubeListBuilder.create().texOffs(23, 74).addBox(0.0f, -3.0f, 0.0f, 9.0f, 8.0f, 0.0f), PartPose.offsetAndRotation(12.0f, 1.0f, -2.0f, 0.0f, -0.6f, 0.25f));
		root.addOrReplaceChild("fin_r", CubeListBuilder.create().texOffs(42, 74).addBox(-9.0f, -3.0f, 0.0f, 9.0f, 8.0f, 0.0f), PartPose.offsetAndRotation(-12.0f, 1.0f, -2.0f, 0.0f, 0.6f, -0.25f));
		root.addOrReplaceChild("barbel_l", CubeListBuilder.create().texOffs(61, 74).addBox(0.0f, 0.0f, 0.0f, 8.0f, 7.0f, 0.0f), PartPose.offsetAndRotation(9.0f, 2.0f, -14.0f, 0.0f, -0.3f, 0.0f));
		root.addOrReplaceChild("barbel_r", CubeListBuilder.create().texOffs(78, 74).addBox(-8.0f, 0.0f, 0.0f, 8.0f, 7.0f, 0.0f), PartPose.offsetAndRotation(-9.0f, 2.0f, -14.0f, 0.0f, 0.3f, 0.0f));
		this.fish = LayerDefinition.create(mesh, 256, 128).bakeRoot();
	}

	@Override
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, AvatarRenderState state, float yRot, float xRot) {
		if (!FishHeads.on() || state.isInvisible || state.isSpectator) return;
		poseStack.pushPose();
		// To the head's pivot and turn with it, then up to the middle of the head.
		getParentModel().root().translateAndRotate(poseStack);
		getParentModel().translateToHead(poseStack);
		poseStack.translate(0.0f, -0.25f, 0.0f);
		poseStack.scale(SCALE, SCALE, SCALE);
		collector.submitModelPart(fish, poseStack, Mc.cutoutNoCull(TEXTURE), light, OverlayTexture.NO_OVERLAY, null);
		poseStack.popPose();
	}
}
