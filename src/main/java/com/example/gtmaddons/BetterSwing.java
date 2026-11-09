package com.example.gtmaddons;

/**
 * The "Better swing" setting: with "Always swing" on, every swing of yours starts the animation over; and every swing plays at the
 * speed set on its ⚙ page. Vanilla only restarts
 * a swing once the current one is half done (3 of 6 ticks, 150 ms), so clicking faster than that attacks while the arm just carries
 * on: hits that seem to land with no swing. The speed scales how many ticks a swing takes (100% = the item's own, 6 for most).
 * Only your own arm on your screen changes; what is sent to the server, the attacks and their timing are untouched (BetterSwingMixin).
 */
public final class BetterSwing {

	private static Settings settings;

	private BetterSwing() {}

	public static void init(Settings modSettings) {
		settings = modSettings;
	}

	public static boolean on() {
		return settings != null && settings.betterSwing;
	}

	/** Whether every click restarts the swing (Better swing's "Always swing"). */
	public static boolean restart() {
		return on() && settings.betterSwingRestart;
	}

	/** A swing's length in ticks at the set speed, never under 1. */
	public static int duration(int ticks) {
		int speed = Math.max(10, settings.betterSwingSpeed);
		return Math.max(1, Math.round(ticks * 100f / speed));
	}
}
