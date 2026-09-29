package com.example.gtmaddons.mixin;

import com.example.gtmaddons.GunSounds;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Applies the Gun Sounds volume settings (see GunSounds): scales a sound's
 * volume when it starts playing, and whenever Minecraft recomputes it
 * (moving sounds, volume slider changes). At 0 the sound isn't played.
 *
 * require = 0: if another client (e.g. Lunar) has changed this code so a
 * hook can't attach, gun sound volumes just don't apply - the game still starts.
 */
@Mixin(SoundSystem.class)
public abstract class SoundSystemMixin {

	@ModifyExpressionValue(
			method = "play(Lnet/minecraft/client/sound/SoundInstance;)Lnet/minecraft/client/sound/SoundSystem$PlayResult;",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/sound/SoundInstance;getVolume()F"),
			require = 0)
	private float gtmaddons$scaleOnPlay(float volume, @Local(argsOnly = true) SoundInstance sound) {
		return volume * GunSounds.INSTANCE.volumeFor(sound.getId());
	}

	@ModifyReturnValue(method = "getAdjustedVolume(Lnet/minecraft/client/sound/SoundInstance;)F", at = @At("RETURN"), require = 0)
	private float gtmaddons$scaleOnUpdate(float volume, SoundInstance sound) {
		return volume * GunSounds.INSTANCE.volumeFor(sound.getId());
	}
}
