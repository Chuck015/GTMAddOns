package com.example.gtmaddons.compat;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

/** Base of ThemedSlider: a vanilla slider whose look is drawn in draw(). */
public abstract class ModSlider extends AbstractSliderButton {

	protected ModSlider(int x, int y, int width, int height, Component message, double value) {
		super(x, y, width, height, message, value);
	}

	@Override
	public final void renderWidget(GuiGraphics context, int mouseX, int mouseY, float deltaTicks) {
		draw(new Gfx(context), mouseX, mouseY, deltaTicks);
	}

	protected abstract void draw(Gfx context, int mouseX, int mouseY, float deltaTicks);
}
