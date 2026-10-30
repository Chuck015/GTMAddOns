package com.example.gtmaddons.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes where a container screen's background is drawn, so slot
 * positions (which are relative to it) can be turned into screen
 * positions for debug-mode mouse tracking.
 */
@Mixin(AbstractContainerScreen.class)
public interface HandledScreenAccessor {
	@Accessor("leftPos")
	int gtmaddons$getX();

	@Accessor("topPos")
	int gtmaddons$getY();
}
