package com.example.gtmaddons;

import com.example.gtmaddons.compat.Mc;
import com.example.gtmaddons.gui.MainScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;

/**
 * Misc > "Menu keybind": a key that opens the GTMAddOns menu (/gao) when no screen is open. The key is Settings.menuKey (a GLFW key
 * code, -1 = none), chosen on its ⚙ page (KeyCaptureScreen). Checked once a frame; a press is the key going down.
 */
public final class MenuKey {

	private static Settings settings;
	private static boolean wasDown = false;

	private MenuKey() {}

	public static void init(Settings modSettings) {
		settings = modSettings;
	}

	public static void onFrame(Minecraft client, GTMAddOnsClient mod) {
		if (settings == null || !settings.menuKeyOn || settings.menuKey < 0) {
			wasDown = false;
			return;
		}
		boolean down = InputConstants.isKeyDown(client.getWindow(), settings.menuKey);
		if (down && !wasDown && Mc.screen(client) == null && client.player != null) Mc.setScreen(client, new MainScreen(mod));
		wasDown = down;
	}

	/** The key's name for the settings ("None" without one). */
	public static String keyName(int key) {
		return key < 0 ? "None" : InputConstants.Type.KEYSYM.getOrCreate(key).getDisplayName().getString();
	}
}
