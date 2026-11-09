package com.example.gtmaddons.mixin;

import com.example.gtmaddons.DroppedItemLabels;
import com.example.gtmaddons.DroppedItemSizes;
import com.example.gtmaddons.DroppedLabelState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives a dropped item in plain view a name label (see DroppedItemLabels). The label is drawn here, in full white, as ordinary
 * depth-tested text - never through the see-through layer - so a wall in front of it hides it.
 */
@Mixin(ItemEntityRenderer.class)
public abstract class DroppedItemLabelMixin {

	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;F)V",
			at = @At("TAIL"), require = 0)
	private void gtmaddons$label(ItemEntity item, ItemEntityRenderState state, float tickProgress, CallbackInfo ci) {
		((DroppedLabelState) (Object) state).gtmaddons$setLabel(DroppedItemLabels.labelFor(item));
		((DroppedLabelState) (Object) state).gtmaddons$setSize(DroppedItemSizes.sizeFor(item));
	}

	/** Dropped item size: the item is drawn scaled around where it lies (DroppedItemSizes); undone in gtmaddons$draw. */
	@Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V",
			at = @At("HEAD"), require = 0)
	private void gtmaddons$grow(ItemEntityRenderState state, PoseStack matrices, SubmitNodeCollector queue, CameraRenderState camera, CallbackInfo ci) {
		float size = ((DroppedLabelState) (Object) state).gtmaddons$getSize();
		if (size == 1f) return;
		matrices.pushPose();
		matrices.scale(size, size, size);
	}

	@Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V",
			at = @At("RETURN"), require = 0)
	private void gtmaddons$draw(ItemEntityRenderState state, PoseStack matrices, SubmitNodeCollector queue, CameraRenderState camera, CallbackInfo ci) {
		float size = ((DroppedLabelState) (Object) state).gtmaddons$getSize();
		if (size != 1f) matrices.popPose();
		Component label = ((DroppedLabelState) (Object) state).gtmaddons$getLabel();
		if (label == null) return;
		Font font = Minecraft.getInstance().font;
		FormattedCharSequence text = label.getVisualOrderText();
		matrices.pushPose();
		matrices.translate(0.0, state.boundingBoxHeight * size + 0.5, 0.0);
		matrices.mulPose(camera.orientation);
		matrices.scale(0.025f, -0.025f, 0.025f);
		queue.submitText(matrices, -font.width(text) / 2.0f, 0.0f, text, false, Font.DisplayMode.NORMAL,
				LightTexture.FULL_BRIGHT, 0xFFFFFFFF, 0x40000000, 0);
		matrices.popPose();
	}
}
