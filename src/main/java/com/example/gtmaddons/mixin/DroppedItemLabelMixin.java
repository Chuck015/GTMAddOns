package com.example.gtmaddons.mixin;

import com.example.gtmaddons.DroppedItemLabels;
import com.example.gtmaddons.DroppedLabelState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.ItemEntityRenderer;
import net.minecraft.client.render.entity.state.ItemEntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.ItemEntity;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
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

	@Inject(method = "updateRenderState(Lnet/minecraft/entity/ItemEntity;Lnet/minecraft/client/render/entity/state/ItemEntityRenderState;F)V",
			at = @At("TAIL"), require = 0)
	private void gtmaddons$label(ItemEntity item, ItemEntityRenderState state, float tickProgress, CallbackInfo ci) {
		((DroppedLabelState) (Object) state).gtmaddons$setLabel(DroppedItemLabels.labelFor(item));
	}

	@Inject(method = "render(Lnet/minecraft/client/render/entity/state/ItemEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V",
			at = @At("TAIL"), require = 0)
	private void gtmaddons$draw(ItemEntityRenderState state, MatrixStack matrices, OrderedRenderCommandQueue queue, CameraRenderState camera, CallbackInfo ci) {
		Text label = ((DroppedLabelState) (Object) state).gtmaddons$getLabel();
		if (label == null) return;
		TextRenderer font = MinecraftClient.getInstance().textRenderer;
		OrderedText text = label.asOrderedText();
		matrices.push();
		matrices.translate(0.0, state.height + 0.5, 0.0);
		matrices.multiply(camera.orientation);
		matrices.scale(0.025f, -0.025f, 0.025f);
		queue.submitText(matrices, -font.getWidth(text) / 2.0f, 0.0f, text, false, TextRenderer.TextLayerType.NORMAL,
				LightmapTextureManager.MAX_LIGHT_COORDINATE, 0xFFFFFFFF, 0x40000000, 0);
		matrices.pop();
	}
}
