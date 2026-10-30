package com.example.gtmaddons.mixin;

import com.example.gtmaddons.FishHeadLayer;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** "Fish cat": adds the fish cat to every player renderer. The real head is hidden by PlayerModelFishHeadMixin. */
@Mixin(AvatarRenderer.class)
public abstract class PlayerFishHeadMixin {

	@SuppressWarnings("unchecked")
	@Inject(method = "<init>", at = @At("RETURN"), require = 0)
	private void gtmaddons$addFish(EntityRendererProvider.Context context, boolean slim, CallbackInfo ci) {
		((LivingEntityRendererInvoker) this).gtmaddons$addLayer(new FishHeadLayer((RenderLayerParent<AvatarRenderState, PlayerModel>) (Object) this));
	}
}
