package com.example.gtmaddons.mixin;

import com.example.gtmaddons.GunSounds;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Applies the Gun Sounds volume settings (see GunSounds): scales a sound's
 * volume when it starts playing, and whenever Minecraft recomputes it
 * (moving sounds, volume slider changes). At 0 the sound isn't played.
 *
 * require = 0: if another client (e.g. Lunar) has changed this code so a
 * hook can't attach, gun sound volumes just don't apply - the game still starts.
 */
@Mixin(SoundEngine.class)
public abstract class SoundSystemMixin {

	@ModifyExpressionValue(
			method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)Lnet/minecraft/client/sounds/SoundEngine$PlayResult;",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/resources/sounds/SoundInstance;getVolume()F"),
			require = 0)
	private float gtmaddons$scaleOnPlay(float volume, @Local(argsOnly = true) SoundInstance sound) {
		return volume * Math.min(1.0f, GunSounds.INSTANCE.volumeFor(sound.getIdentifier(), sound.getX(), sound.getY(), sound.getZ()));
	}

	/** Part of a sound set above 100% is played as extra copies when it starts (see LouderSounds). */
	@Inject(method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)Lnet/minecraft/client/sounds/SoundEngine$PlayResult;", at = @At("HEAD"), require = 0)
	private void gtmaddons$louder(SoundInstance sound, CallbackInfoReturnable<?> cir) {
		com.example.gtmaddons.LouderSounds.boost(sound);
	}

	@ModifyReturnValue(method = "calculateVolume(Lnet/minecraft/client/resources/sounds/SoundInstance;)F", at = @At("RETURN"), require = 0)
	private float gtmaddons$scaleOnUpdate(float volume, SoundInstance sound) {
		return volume * Math.min(1.0f, GunSounds.INSTANCE.volumeFor(sound.getIdentifier(), sound.getX(), sound.getY(), sound.getZ()));
	}
}
