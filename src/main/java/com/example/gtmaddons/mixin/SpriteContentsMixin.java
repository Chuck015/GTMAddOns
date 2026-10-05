package com.example.gtmaddons.mixin;

import com.example.gtmaddons.CobwebTransparency;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.SpriteContents;
import net.minecraft.client.texture.SpriteDimensions;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Thins the cobweb texture for the "Cobweb transparency" setting as its sprite is created (see CobwebTransparency). */
@Mixin(SpriteContents.class)
public abstract class SpriteContentsMixin {

	@Inject(method = "<init>(Lnet/minecraft/util/Identifier;Lnet/minecraft/client/texture/SpriteDimensions;Lnet/minecraft/client/texture/NativeImage;Ljava/util/Optional;Ljava/util/List;Ljava/util/Optional;)V", at = @At("RETURN"), require = 0)
	private void gtmaddons$cobweb(Identifier id, SpriteDimensions dimensions, NativeImage image, java.util.Optional<?> animation, java.util.List<?> metadata, java.util.Optional<?> other, CallbackInfo ci) {
		CobwebTransparency.thin(id, image);
	}

	/** The short constructor, in case the sprite is made through it rather than the one above (an image is only thinned once). */
	@Inject(method = "<init>(Lnet/minecraft/util/Identifier;Lnet/minecraft/client/texture/SpriteDimensions;Lnet/minecraft/client/texture/NativeImage;)V", at = @At("RETURN"), require = 0)
	private void gtmaddons$cobwebShort(Identifier id, SpriteDimensions dimensions, NativeImage image, CallbackInfo ci) {
		CobwebTransparency.thin(id, image);
	}
}
