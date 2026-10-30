package com.example.gtmaddons.mixin;

import com.example.gtmaddons.MouseTracker;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells MouseTracker how far the mouse turned the player each frame (the game hands every mouse movement to this method). */
@Mixin(Entity.class)
public abstract class EntityLookMixin {

	@Inject(method = "turn", at = @At("HEAD"), require = 0)
	private void gtmaddons$mouse(double cursorDeltaX, double cursorDeltaY, CallbackInfo ci) {
		MouseTracker.INSTANCE.onMouseTurn((Entity) (Object) this, cursorDeltaX, cursorDeltaY);
	}
}
