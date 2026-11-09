package com.example.gtmaddons;

/** The Wing mod "Armor hotkey lock": hotbar / offhand swap keys do nothing over the helmet, leggings and boots slots of your inventory (ChestHotkeyMixin). */
public final class ChestHotkeys {

	private static Settings settings;

	private ChestHotkeys() {}

	public static void init(Settings modSettings) {
		settings = modSettings;
	}

	public static boolean on() {
		return settings != null && settings.chestHotkeysOnly;
	}
}
