package com.example.gtmaddons.gui;

import com.example.gtmaddons.compat.Gfx;
import com.example.gtmaddons.compat.ModButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.components.Tooltip;

/**
 * The mod's button: a dark card like the stats pages' (Ui.CARD with a thin border) instead of Minecraft's stone button. Hovered or
 * focused it lights up in its accent. A label ending in ": ON" / ": OFF" is drawn as the label with an on/off pill on the right.
 * Given a subtitle and at least 30 px of height it is a tile: an accent strip on the left, a title and a grey line under it.
 *
 * Built like a vanilla button: ThemedButton.create(text, onPress).bounds(x, y, w, h).build().
 */
public class ThemedButton extends ModButton {

	/** The accent of the buttons built from now on; a screen sets it at the top of init() (back to Ui.BLUE by default). */
	static int accent = Ui.BLUE;

	static final int HOVER = 0xFF252833;
	static final int DISABLED = 0xFF15161B;
	static final int ON_PILL = 0xFF2E7D46;
	static final int OFF_PILL = 0xFF3A3D48;

	private final int buttonAccent;
	private final String subtitle;

	protected ThemedButton(int x, int y, int width, int height, Component message, OnPress onPress, CreateNarration narration, int accent, String subtitle) {
		super(x, y, width, height, message, onPress, narration);
		this.buttonAccent = accent;
		this.subtitle = subtitle;
	}

	public static Builder create(Component message, OnPress onPress) {
		return new Builder(message, onPress);
	}

	@Override
	protected void draw(Gfx context, int mouseX, int mouseY, float deltaTicks) {
		Font font = Minecraft.getInstance().font;
		int x = getX(), y = getY(), w = getWidth(), h = getHeight();
		boolean lit = active && isHoveredOrFocused();
		frame(context, x, y, w, h, active, lit, buttonAccent);
		int textColor = active ? Ui.TEXT : Ui.MUTED;

		if (subtitle != null && h >= 30) {
			context.fill(x, y, x + 3, y + h, active ? buttonAccent : Ui.CARD_BORDER);
			int textY = y + h / 2 - 9;
			context.text(font, Component.literal(Ui.fit(font, getMessage().getString(), w - 16)), x + 10, textY, textColor);
			context.text(font, Ui.fit(font, subtitle, w - 16), x + 10, textY + 11, active ? Ui.LABEL : Ui.MUTED);
			return;
		}

		String plain = getMessage().getString();
		Boolean on = plain.endsWith(": ON") ? Boolean.TRUE : plain.endsWith(": OFF") ? Boolean.FALSE : null;
		int textY = y + (h - 8) / 2;
		if (on != null) {
			String label = plain.substring(0, plain.lastIndexOf(": "));
			String state = on ? "ON" : "OFF";
			int pillW = font.width("OFF") + 8, pillX = x + w - pillW - 4;
			context.text(font, Ui.fit(font, label, pillX - x - 10), x + 6, textY, textColor);
			context.fill(pillX, y + 4, pillX + pillW, y + h - 4, !active ? DISABLED : on ? ON_PILL : OFF_PILL);
			context.centeredText(font, state, pillX + pillW / 2, textY, active ? (on ? Ui.GOOD : Ui.LABEL) : Ui.MUTED);
			return;
		}
		if (font.width(getMessage()) > w - 8) {
			context.centeredText(font, Ui.fit(font, plain, w - 8), x + w / 2, textY, textColor);
		} else {
			context.centeredText(font, getMessage(), x + w / 2, textY, textColor);
		}
	}

	/** The card behind a button or slider: border in the accent when lit, a 2 px accent strip on the left. */
	static void frame(Gfx context, int x, int y, int w, int h, boolean active, boolean lit, int accent) {
		context.fill(x, y, x + w, y + h, !active ? DISABLED : lit ? HOVER : Ui.CARD);
		int border = lit ? accent : Ui.CARD_BORDER;
		context.fill(x, y, x + w, y + 1, border);
		context.fill(x, y + h - 1, x + w, y + h, border);
		context.fill(x, y, x + 1, y + h, border);
		context.fill(x + w - 1, y, x + w, y + h, border);
		if (lit) context.fill(x, y, x + 2, y + h, accent);
	}

	/** The same builder methods as the vanilla button's, plus accent and subtitle. */
	public static final class Builder {
		private final Component message;
		private final OnPress onPress;
		private Tooltip tooltip;
		private CreateNarration narration = DEFAULT_NARRATION;
		private int x, y, width = 150, height = 20;
		private int accent = ThemedButton.accent;
		private String subtitle;

		private Builder(Component message, OnPress onPress) {
			this.message = message;
			this.onPress = onPress;
		}

		public Builder pos(int x, int y) {
			this.x = x;
			this.y = y;
			return this;
		}

		public Builder width(int width) {
			this.width = width;
			return this;
		}

		public Builder size(int width, int height) {
			this.width = width;
			this.height = height;
			return this;
		}

		public Builder bounds(int x, int y, int width, int height) {
			return pos(x, y).size(width, height);
		}

		public Builder tooltip(Tooltip tooltip) {
			this.tooltip = tooltip;
			return this;
		}

		public Builder createNarration(CreateNarration narration) {
			this.narration = narration;
			return this;
		}

		public Builder accent(int accent) {
			this.accent = accent;
			return this;
		}

		public Builder subtitle(String subtitle) {
			this.subtitle = subtitle;
			return this;
		}

		public ThemedButton build() {
			ThemedButton button = new ThemedButton(x, y, width, height, message, onPress, narration, accent, subtitle);
			button.setTooltip(tooltip);
			return button;
		}
	}
}
