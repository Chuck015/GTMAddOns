package com.example.gtmaddons;

import net.minecraft.text.Text;

/** Added to an item's render state by ItemEntityRenderStateMixin: the name label to draw above it (null = none). */
public interface DroppedLabelState {
	Text gtmaddons$getLabel();

	void gtmaddons$setLabel(Text label);
}
