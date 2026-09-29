package com.example.gtmaddons.mixin;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes where a container screen's background is drawn, so slot
 * positions (which are relative to it) can be turned into screen
 * positions for debug-mode mouse tracking.
 */
@Mixin(HandledScreen.class)
public interface HandledScreenAccessor {
	@Accessor("x")
	int gtmaddons$getX();

	@Accessor("y")
	int gtmaddons$getY();
}
