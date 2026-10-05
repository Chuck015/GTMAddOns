package com.example.gtmaddons.mixin;

import com.example.gtmaddons.OldSneaking;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.entity.PlayerLikeEntity;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * "Old sneaking": draws your own player model crouched while you fly and hold sneak. Only the picture changes - the
 * player's pose, size and hitbox are not touched (see OldSneaking). require = 0: if it can't attach, nothing changes.
 */
@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerSneakAnimationMixin {

	@Inject(method = "updateRenderState(Lnet/minecraft/entity/PlayerLikeEntity;Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;F)V",
			at = @At("RETURN"), require = 0)
	private void gtmaddons$sneakAnimation(PlayerLikeEntity entity, PlayerEntityRenderState state, float tickDelta, CallbackInfo ci) {
		if (OldSneaking.showsFor(entity, entity.isSneaking())) state.isInSneakingPose = true;
	}
}
