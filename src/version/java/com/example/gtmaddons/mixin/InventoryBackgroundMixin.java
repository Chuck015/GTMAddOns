package com.example.gtmaddons.mixin;

import com.example.gtmaddons.InventoryTransparency;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Behind the survival inventory, with "Inventory transparency" on: a lighter shade (none at 100%) instead of vanilla's blur and darkening. */
@Mixin(Screen.class)
public abstract class InventoryBackgroundMixin {

	@Inject(method = "renderBackground(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("HEAD"), cancellable = true, require = 0)
	private void gtmaddons$shade(GuiGraphics context, int mouseX, int mouseY, float deltaTicks, CallbackInfo ci) {
		Screen self = (Screen) (Object) this;
		if (!(self instanceof InventoryScreen) || InventoryTransparency.amount() <= 0f) return;
		int shade = InventoryTransparency.shade();
		if ((shade >>> 24) > 0) context.fill(0, 0, self.width, self.height, shade);
		ci.cancel();
	}
}
