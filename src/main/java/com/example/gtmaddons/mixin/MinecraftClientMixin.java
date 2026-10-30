package com.example.gtmaddons.mixin;

import com.example.gtmaddons.TrajectoryLines;
import com.example.gtmaddons.gun.DevLogger;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every left click (attack), for dev mode's swing checks (see
 * SwingDebugger): the state just before it's handled, and what it did.
 * Every right click (use item) too, for the shot trajectory line.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftClientMixin {

	/** Ticks left of vanilla's click block after swinging at air (0 = none). */
	@Shadow
	protected int missTime;

	@Inject(method = "startAttack", at = @At("HEAD"))
	private void gtmaddons$beforeAttack(CallbackInfoReturnable<Boolean> cir) {
		if (DevLogger.INSTANCE.isEnabled()) DevLogger.INSTANCE.beforeAttack((Minecraft) (Object) this, missTime);
	}

	@Inject(method = "startAttack", at = @At("RETURN"))
	private void gtmaddons$afterAttack(CallbackInfoReturnable<Boolean> cir) {
		if (DevLogger.INSTANCE.isEnabled()) DevLogger.INSTANCE.afterAttack((Minecraft) (Object) this);
	}

	/** Every right click (use item), for the shot trajectory line (see TrajectoryLines). */
	@Inject(method = "startUseItem", at = @At("HEAD"))
	private void gtmaddons$beforeItemUse(CallbackInfo ci) {
		TrajectoryLines.INSTANCE.onUse((Minecraft) (Object) this);
	}
}
