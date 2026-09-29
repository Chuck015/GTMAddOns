package com.example.gtmaddons.mixin;

import com.example.gtmaddons.TrajectoryLines;
import com.example.gtmaddons.gun.DevLogger;
import net.minecraft.client.MinecraftClient;
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
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {

	/** Ticks left of vanilla's click block after swinging at air (0 = none). */
	@Shadow
	protected int attackCooldown;

	@Inject(method = "doAttack", at = @At("HEAD"))
	private void gtmaddons$beforeAttack(CallbackInfoReturnable<Boolean> cir) {
		if (DevLogger.INSTANCE.isEnabled()) DevLogger.INSTANCE.beforeAttack((MinecraftClient) (Object) this, attackCooldown);
	}

	@Inject(method = "doAttack", at = @At("RETURN"))
	private void gtmaddons$afterAttack(CallbackInfoReturnable<Boolean> cir) {
		if (DevLogger.INSTANCE.isEnabled()) DevLogger.INSTANCE.afterAttack((MinecraftClient) (Object) this);
	}

	/** Every right click (use item), for the shot trajectory line (see TrajectoryLines). */
	@Inject(method = "doItemUse", at = @At("HEAD"))
	private void gtmaddons$beforeItemUse(CallbackInfo ci) {
		TrajectoryLines.INSTANCE.onUse((MinecraftClient) (Object) this);
	}
}
