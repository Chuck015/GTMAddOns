package com.example.gtmaddons;

/**
 * Admin mode (Settings > Admin mode): while it's on, the stats screens show buttons
 * that delete players' stats data from the server - a whole player, or a single fight -
 * for when someone has boosted their ratings. Only accounts in the backend's ADMIN_UUIDS
 * can turn it on, and the backend checks that again on every delete, so this flag only
 * decides whether the buttons are drawn. Like dev mode it isn't saved: it has to pass
 * the access check each time.
 */
public final class AdminMode {
	private static volatile boolean on = false;

	private AdminMode() {}

	public static boolean isOn() {
		return on;
	}

	public static void set(boolean value) {
		on = value;
	}
}
