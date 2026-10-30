package com.example.gtmaddons.compat;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Base of ThemedButton: a vanilla button whose look is drawn in draw(). */
public abstract class ModButton extends Button {

	protected ModButton(int x, int y, int width, int height, Component message, OnPress onPress, CreateNarration narration) {
		super(x, y, width, height, message, onPress, narration);
	}

	@Override
	protected final void renderContents(GuiGraphics context, int mouseX, int mouseY, float deltaTicks) {
		draw(new Gfx(context), mouseX, mouseY, deltaTicks);
	}

	protected abstract void draw(Gfx context, int mouseX, int mouseY, float deltaTicks);
}
