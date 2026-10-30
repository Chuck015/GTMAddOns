package com.example.gtmaddons;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.util.RandomSource;

/**
 * Sounds set above 100% in Settings > Sounds. Minecraft clamps a sound's volume to 100%, so the part above it is played as extra copies
 * of the same sound at the same place and pitch, on top of each other (250% = the sound once at 100% plus copies worth 150% more).
 * Looping sounds are left alone. The copies are marked so they are not boosted again.
 */
public final class LouderSounds {

	private static final int MAX_COPIES = 3;
	private static boolean copying = false;

	private LouderSounds() {}

	/** Called as a sound starts to play. */
	public static void boost(SoundInstance sound) {
		if (copying || sound.isLooping()) return;
		float extra = GunSounds.INSTANCE.boostFor(sound.getIdentifier(), sound.getX(), sound.getY(), sound.getZ());
		if (extra <= 0.0f) return;
		float own = Math.min(1.0f, sound.getVolume());
		copying = true;
		try {
			int copies = 0;
			for (float left = extra; left > 0.001f && copies < MAX_COPIES; left -= 1.0f, copies++) {
				SoundInstance copy = new SimpleSoundInstance(sound.getIdentifier(), sound.getSource(), Math.min(1.0f, left) * own, sound.getPitch(), RandomSource.create(),
						false, 0, sound.getAttenuation(), sound.getX(), sound.getY(), sound.getZ(), sound.isRelative());
				Minecraft.getInstance().getSoundManager().play(copy);
			}
		} finally {
			copying = false;
		}
	}
}
