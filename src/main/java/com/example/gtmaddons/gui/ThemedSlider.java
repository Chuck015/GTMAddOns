package com.example.gtmaddons.gui;

import com.example.gtmaddons.compat.Gfx;
import com.example.gtmaddons.compat.ModSlider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;

/** A vanilla slider drawn in the mod's look (see ThemedButton): a dark card, the filled part in the accent, a bright handle. */
public abstract class ThemedSlider extends ModSlider {

	private final int sliderAccent = ThemedButton.accent;

	protected ThemedSlider(int x, int y, int width, int height, Component message, double value) {
		super(x, y, width, height, message, value);
	}

	@Override
	protected void draw(Gfx context, int mouseX, int mouseY, float deltaTicks) {
		Font font = Minecraft.getInstance().font;
		int x = getX(), y = getY(), w = getWidth(), h = getHeight();
		boolean lit = active && isHoveredOrFocused();
		ThemedButton.frame(context, x, y, w, h, active, lit, sliderAccent);
		int handle = x + 2 + (int) Math.round(value * (w - 8));
		// The part up to the handle, in a dim accent, then the handle itself.
		context.fill(x + 1, y + 1, handle, y + h - 1, (sliderAccent & 0x00FFFFFF) | 0x44000000);
		context.fill(handle, y + 2, handle + 4, y + h - 2, active ? sliderAccent : Ui.MUTED);
		context.centeredText(font, Ui.fit(font, getMessage().getString(), w - 8), x + w / 2, y + (h - 8) / 2, active ? Ui.TEXT : Ui.MUTED);
	}
}
