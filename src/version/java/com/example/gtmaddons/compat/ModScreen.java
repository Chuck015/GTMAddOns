package com.example.gtmaddons.compat;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Base of the mod's screens: they draw in draw() and drawBackground() whatever the game version calls those (1.21.11: render, renderBackground). */
public abstract class ModScreen extends Screen {

	protected ModScreen(Component title) {
		super(title);
	}

	@Override
	public final void render(GuiGraphics context, int mouseX, int mouseY, float deltaTicks) {
		draw(new Gfx(context), mouseX, mouseY, deltaTicks);
	}

	@Override
	public final void renderBackground(GuiGraphics context, int mouseX, int mouseY, float deltaTicks) {
		drawBackground(new Gfx(context), mouseX, mouseY, deltaTicks);
	}

	public void draw(Gfx context, int mouseX, int mouseY, float deltaTicks) {
		super.render(context.raw(), mouseX, mouseY, deltaTicks);
	}

	public void drawBackground(Gfx context, int mouseX, int mouseY, float deltaTicks) {
		super.renderBackground(context.raw(), mouseX, mouseY, deltaTicks);
	}
}
