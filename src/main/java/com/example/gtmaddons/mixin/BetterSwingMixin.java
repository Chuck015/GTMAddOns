package com.example.gtmaddons.mixin;

import com.example.gtmaddons.BetterSwing;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** "Better swing": your swing restarts on every click and lasts as long as the set speed says (see BetterSwing). */
@Mixin(LivingEntity.class)
public abstract class BetterSwingMixin {

	@Inject(method = "swing(Lnet/minecraft/world/InteractionHand;Z)V", at = @At("HEAD"), require = 0)
	private void gtmaddons$restartSwing(InteractionHand hand, boolean updateSelf, CallbackInfo ci) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (BetterSwing.restart() && self == Minecraft.getInstance().player) self.swinging = false;
	}

	@Inject(method = "getCurrentSwingDuration()I", at = @At("RETURN"), cancellable = true, require = 0)
	private void gtmaddons$swingSpeed(CallbackInfoReturnable<Integer> cir) {
		if (BetterSwing.on() && (Object) this == Minecraft.getInstance().player) cir.setReturnValue(BetterSwing.duration(cir.getReturnValue()));
	}
}
