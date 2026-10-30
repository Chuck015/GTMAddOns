package com.example.gtmaddons;

/**
 * The "Fish cat" setting: every player's head is drawn as a fish cat (FishHeadLayer, hooked in by PlayerFishHeadMixin).
 * It only changes what is drawn - the player's pose, size and hitbox are never touched.
 */
public final class FishHeads {

	private static Settings settings;

	private FishHeads() {}

	public static void init(Settings modSettings) {
		settings = modSettings;
	}

	public static boolean on() {
		return settings != null && settings.fishHeads;
	}
}
