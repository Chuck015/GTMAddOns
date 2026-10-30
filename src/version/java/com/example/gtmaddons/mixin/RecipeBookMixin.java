package com.example.gtmaddons.mixin;

import com.example.gtmaddons.RecipeBookHider;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * "Hide recipes": while the setting is on and the survival inventory is open, the recipe book still opens and closes (its button and the
 * inventory shifting are untouched) but its panel is not drawn, its tooltips are not shown and clicks in it do nothing (see RecipeBookHider).
 * require = 0: if a hook can't attach, that part is simply not applied.
 */
@Mixin(RecipeBookComponent.class)
public abstract class RecipeBookMixin {

	@Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0)
	private void gtmaddons$hideRender(GuiGraphics context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		if (RecipeBookHider.hides()) ci.cancel();
	}

	@Inject(method = "renderTooltip", at = @At("HEAD"), cancellable = true, require = 0)
	private void gtmaddons$hideTooltip(GuiGraphics context, int x, int y, Slot slot, CallbackInfo ci) {
		if (RecipeBookHider.hides()) ci.cancel();
	}

	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true, require = 0)
	private void gtmaddons$hideClicks(MouseButtonEvent click, boolean doubled, CallbackInfoReturnable<Boolean> cir) {
		if (RecipeBookHider.hides()) cir.setReturnValue(false);
	}
}
