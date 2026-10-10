package com.example.gtmaddons.mixin;

import com.example.gtmaddons.RecipeBookHider;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Click;
import net.minecraft.screen.slot.Slot;
import net.minecraft.client.gui.screen.recipebook.RecipeBookWidget;
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
@Mixin(RecipeBookWidget.class)
public abstract class RecipeBookMixin {

	@Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0)
	private void gtmaddons$hideRender(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		if (RecipeBookHider.hides()) ci.cancel();
	}

	@Inject(method = "drawTooltip", at = @At("HEAD"), cancellable = true, require = 0)
	private void gtmaddons$hideTooltip(DrawContext context, int x, int y, Slot slot, CallbackInfo ci) {
		if (RecipeBookHider.hides()) ci.cancel();
	}

	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true, require = 0)
	private void gtmaddons$hideClicks(Click click, boolean doubled, CallbackInfoReturnable<Boolean> cir) {
		if (RecipeBookHider.hides()) cir.setReturnValue(false);
	}
}
