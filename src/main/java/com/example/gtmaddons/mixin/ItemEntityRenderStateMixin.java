package com.example.gtmaddons.mixin;

import com.example.gtmaddons.DroppedLabelState;
import net.minecraft.client.render.entity.state.ItemEntityRenderState;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Carries the dropped-item name label from the renderer's state update to its draw call (see DroppedItemLabelMixin). */
@Mixin(ItemEntityRenderState.class)
public abstract class ItemEntityRenderStateMixin implements DroppedLabelState {
	@Unique
	private Text gtmaddons$label;

	@Override
	public Text gtmaddons$getLabel() {
		return gtmaddons$label;
	}

	@Override
	public void gtmaddons$setLabel(Text label) {
		gtmaddons$label = label;
	}
}
