package com.example.gtmaddons.mixin;

import com.example.gtmaddons.BlackOpsGlint;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Makes an item named Black Ops Boots show the enchantment glint while the setting is on (see BlackOpsGlint). */
@Mixin(ItemStack.class)
public abstract class ItemStackGlintMixin {

	@Inject(method = "hasFoil()Z", at = @At("HEAD"), cancellable = true, require = 0)
	private void gtmaddons$glint(CallbackInfoReturnable<Boolean> cir) {
		ItemStack stack = (ItemStack) (Object) this;
		if (!stack.isEmpty() && BlackOpsGlint.wants(stack.getHoverName().getString())) cir.setReturnValue(true);
	}
}
