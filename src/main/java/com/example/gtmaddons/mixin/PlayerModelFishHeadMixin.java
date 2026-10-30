package com.example.gtmaddons.mixin;

import com.example.gtmaddons.FishHeads;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * "Fish cat": hides the real head (with the hat layer, a child of it) while the setting is on. The player models are shared
 * between players, so the head is set visible again on every pose. Only what is drawn changes, not the pose, size or hitbox.
 */
@Mixin(PlayerModel.class)
public abstract class PlayerModelFishHeadMixin {

	@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("RETURN"), require = 0)
	private void gtmaddons$hideHead(AvatarRenderState state, CallbackInfo ci) {
		((HumanoidModel<?>) (Object) this).head.visible = !FishHeads.on() || state.isSpectator;
	}
}
