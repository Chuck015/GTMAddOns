package com.example.gtmaddons;

/**
 * The "Inventory transparency" setting (0-100%), like Cobweb transparency but for your survival inventory screen: its
 * background texture (the grey panel and slot frames) is drawn that much see-through, and the dark shade Minecraft puts over
 * the game behind it fades out with it, so at 100% only the items, the slot highlight and your player model are drawn and
 * the game shows through. Only the picture changes; clicks and slots work as normal. Drawn by InventoryTransparencyMixin
 * (the texture) and InventoryBackgroundMixin (the shade, which also skips the menu blur while this is on).
 */
public final class InventoryTransparency {

	/** How dark the shade behind the inventory is at 0% of this setting (vanilla's in-game darkening is about this). */
	private static final int SHADE_ALPHA = 0x90;

	private static Settings settings;

	private InventoryTransparency() {}

	public static void init(Settings modSettings) {
		settings = modSettings;
	}

	/** 0 = drawn as normal, 1 = fully clear. */
	public static float amount() {
		return settings == null ? 0f : Math.max(0, Math.min(100, settings.inventoryTransparency)) / 100f;
	}

	/** White with the background texture's alpha, for the tinted draw call. */
	public static int tint() {
		return (Math.round(255 * (1 - amount())) << 24) | 0xFFFFFF;
	}

	/** The shade drawn behind the inventory in place of vanilla's (ARGB; alpha 0 = none). */
	public static int shade() {
		return (Math.round(SHADE_ALPHA * (1 - amount())) << 24) | 0x101010;
	}
}
