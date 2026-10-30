package com.example.gtmaddons.mixin;

import com.example.gtmaddons.OldSneaking;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * "Old sneaking": draws your own player model crouched while you fly and hold sneak. Only the picture changes - the
 * player's pose, size and hitbox are not touched (see OldSneaking). require = 0: if it can't attach, nothing changes.
 */
@Mixin(AvatarRenderer.class)
public abstract class PlayerSneakAnimationMixin {

	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
			at = @At("RETURN"), require = 0)
	private void gtmaddons$sneakAnimation(Avatar entity, AvatarRenderState state, float tickDelta, CallbackInfo ci) {
		if (OldSneaking.showsFor(entity, entity.isShiftKeyDown())) state.isCrouching = true;
	}
}
