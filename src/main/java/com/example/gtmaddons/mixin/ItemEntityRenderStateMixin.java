package com.example.gtmaddons.mixin;

import com.example.gtmaddons.DroppedLabelState;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Carries the dropped-item name label and size from the renderer's state update to its draw call (see DroppedItemLabelMixin). */
@Mixin(ItemEntityRenderState.class)
public abstract class ItemEntityRenderStateMixin implements DroppedLabelState {
	@Unique
	private Component gtmaddons$label;
	@Unique
	private float gtmaddons$size = 1f;

	@Override
	public Component gtmaddons$getLabel() {
		return gtmaddons$label;
	}

	@Override
	public void gtmaddons$setLabel(Component label) {
		gtmaddons$label = label;
	}

	@Override
	public float gtmaddons$getSize() {
		return gtmaddons$size;
	}

	@Override
	public void gtmaddons$setSize(float size) {
		gtmaddons$size = size;
	}
}
