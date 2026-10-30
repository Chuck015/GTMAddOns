package com.example.gtmaddons.mixin;

import com.example.gtmaddons.ClickTracker;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every key or mouse button press outside a screen, the moment it arrives: ClickTracker times clicks from here. */
@Mixin(KeyMapping.class)
public abstract class KeyClickMixin {

	@Inject(method = "click", at = @At("HEAD"), require = 0)
	private static void gtmaddons$onClick(InputConstants.Key key, CallbackInfo ci) {
		ClickTracker.INSTANCE.onKey(key);
	}
}
