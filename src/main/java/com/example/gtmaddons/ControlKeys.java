package com.example.gtmaddons;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;

/** Whether a Control key is held, read straight from the keyboard (for Control + drop, see DropStackMixin). */
public final class ControlKeys {

	private static final int LEFT_CONTROL = 341, RIGHT_CONTROL = 345;

	private ControlKeys() {}

	public static boolean down() {
		var window = Minecraft.getInstance().getWindow();
		return InputConstants.isKeyDown(window, LEFT_CONTROL) || InputConstants.isKeyDown(window, RIGHT_CONTROL);
	}
}
