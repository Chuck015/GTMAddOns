package com.example.gtmaddons.gui;

import com.example.gtmaddons.Settings;

/**
 * The 25 / 50 / 100 fights choice of the stats screens, remembered between menus: Personal Stats, the leaderboard and
 * other players' profiles all open on it, and picking one on any of them sets it for all (and for next time - it is
 * saved in Settings). A custom Filter... is not remembered.
 */
public final class FightViews {

	public static final int[] OPTIONS = { 25, 50, 100 };

	private static Settings settings;

	private FightViews() {}

	public static void init(Settings modSettings) {
		settings = modSettings;
	}

	/** The chosen number of fights; 25 until one is picked. */
	public static int get() {
		int saved = settings != null ? settings.statsFights : OPTIONS[0];
		for (int option : OPTIONS) if (option == saved) return saved;
		return OPTIONS[0];
	}

	public static void set(int fights) {
		if (settings == null || settings.statsFights == fights) return;
		for (int option : OPTIONS) {
			if (option == fights) {
				settings.statsFights = fights;
				settings.save();
				return;
			}
		}
	}
}
