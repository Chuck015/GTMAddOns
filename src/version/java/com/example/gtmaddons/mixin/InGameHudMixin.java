package com.example.gtmaddons.mixin;

import com.example.gtmaddons.ComboTracker;
import com.example.gtmaddons.gun.DevLogger;
import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sees every action-bar message as it's set. Server action bars (like GTM's
 * combo display) don't go through the chat event, so this is where dev mode
 * logs them, and where GTM's frozen "Combo'd" and "Target recovering"
 * timers are hidden in favor of the live ones (see ComboTracker).
 */
@Mixin(Gui.class)
public abstract class InGameHudMixin {

	@Inject(method = "setOverlayMessage", at = @At("HEAD"), cancellable = true)
	private void gtmaddons$onActionBar(Component message, boolean tinted, CallbackInfo ci) {
		DevLogger.INSTANCE.onActionBar(message);
		if (ComboTracker.INSTANCE.onActionBar(message)) ci.cancel();
	}
}
