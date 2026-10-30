package com.example.gtmaddons.compat;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3x2fStack;

/** What the mod draws with: the game's GuiGraphics under the names the shared code uses (the 1.21.11 side of the version layer). */
public final class Gfx {

	private final GuiGraphics raw;

	public Gfx(GuiGraphics raw) {
		this.raw = raw;
	}

	public GuiGraphics raw() {
		return raw;
	}

	public void fill(int x1, int y1, int x2, int y2, int color) {
		raw.fill(x1, y1, x2, y2, color);
	}

	public void text(Font font, String text, int x, int y, int color) {
		raw.drawString(font, text, x, y, color);
	}

	public void text(Font font, Component text, int x, int y, int color) {
		raw.drawString(font, text, x, y, color);
	}

	public void centeredText(Font font, String text, int x, int y, int color) {
		raw.drawCenteredString(font, text, x, y, color);
	}

	public void centeredText(Font font, Component text, int x, int y, int color) {
		raw.drawCenteredString(font, text, x, y, color);
	}

	public void item(ItemStack stack, int x, int y) {
		raw.renderItem(stack, x, y);
	}

	public Matrix3x2fStack pose() {
		return raw.pose();
	}

	public void enableScissor(int x1, int y1, int x2, int y2) {
		raw.enableScissor(x1, y1, x2, y2);
	}

	public void disableScissor() {
		raw.disableScissor();
	}

	public int guiWidth() {
		return raw.guiWidth();
	}

	public int guiHeight() {
		return raw.guiHeight();
	}
}
