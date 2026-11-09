package com.example.gtmaddons;

import net.minecraft.network.chat.Component;

/** Added to an item's render state by ItemEntityRenderStateMixin: the name label to draw above it (null = none) and its size (Dropped item size). */
public interface DroppedLabelState {
	Component gtmaddons$getLabel();

	void gtmaddons$setLabel(Component label);

	float gtmaddons$getSize();

	void gtmaddons$setSize(float size);
}
