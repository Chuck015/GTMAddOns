package com.example.gtmaddons;

import java.io.BufferedInputStream;
import java.io.InputStream;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;

/**
 * The Credits button's audio (assets/gtmaddons/credits.wav, 22.05 kHz mono). Played with Java's own audio output rather than
 * Minecraft's sound system, which only reads .ogg files; its loudness follows the game's Master volume. Press again to stop.
 */
public final class CreditsAudio {

	public static final CreditsAudio INSTANCE = new CreditsAudio();
	private static final String RESOURCE = "/assets/gtmaddons/credits.wav";

	private Clip clip;

	private CreditsAudio() {}

	public boolean isPlaying() {
		return clip != null && clip.isRunning();
	}

	/** Plays the credits from the start at this volume (0-1, the game's Master volume), or stops them if they are playing. */
	public void toggle(float volume) {
		if (isPlaying()) {
			stop();
			return;
		}
		stop();
		try (InputStream raw = CreditsAudio.class.getResourceAsStream(RESOURCE)) {
			if (raw == null) return;
			AudioInputStream stream = AudioSystem.getAudioInputStream(new BufferedInputStream(raw));
			Clip c = AudioSystem.getClip();
			c.open(stream);
			if (c.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
				FloatControl gain = (FloatControl) c.getControl(FloatControl.Type.MASTER_GAIN);
				float db = volume <= 0.0001f ? gain.getMinimum() : (float) (20.0 * Math.log10(volume));
				gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), db)));
			}
			c.start();
			clip = c;
		} catch (Exception | LinkageError e) {
			// No audio device, or the file could not be read: the button simply does nothing.
			clip = null;
		}
	}

	public void stop() {
		if (clip != null) {
			clip.stop();
			clip.close();
			clip = null;
		}
	}
}
