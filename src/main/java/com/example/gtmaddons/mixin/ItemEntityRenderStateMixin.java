package com.example.gtmaddons.mixin;

import com.example.gtmaddons.DroppedLabelState;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Carries the dropped-item name label from the renderer's state update to its draw call (see DroppedItemLabelMixin). */
@Mixin(ItemEntityRenderState.class)
public abstract class ItemEntityRenderStateMixin implements DroppedLabelState {
	@Unique
	private Component gtmaddons$label;

	@Override
	public Component gtmaddons$getLabel() {
		return gtmaddons$label;
	}

	@Override
	public void gtmaddons$setLabel(Component label) {
		gtmaddons$label = label;
	}
}
