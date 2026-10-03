package com.example.gtmaddons.gui;

import com.example.gtmaddons.GTMAddOnsClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;

/**
 * Settings > PVP > Swap timer: a master ON/OFF switch, then which swap displays to show -
 * the taskbar timer (the result text after each swap, isSwapTimerShown), the corner text
 * (Last Swap, isCornerSwapTextOn) or both. The two saved settings are unchanged; OFF just
 * turns both off, and ON brings back the last choice.
 */
public class SwapTimerScreen extends Screen {

	/** The three ways to show the swap displays when the timer is on. */
	public enum Mode {
		TIMER("Taskbar timer only", true, false),
		CORNER("Text only", false, true),
		BOTH("Taskbar timer + corner text", true, true);

		final String label;
		final boolean timer;
		final boolean corner;

		Mode(String label, boolean timer, boolean corner) {
			this.label = label;
			this.timer = timer;
			this.corner = corner;
		}
	}

	private static final int BUTTON_WIDTH = 200;
	private static final int ROW = 24;

	/** The mode used when the switch is turned back on; follows the settings while one is on. */
	private static Mode remembered = Mode.BOTH;

	private final Screen parent;
	private final GTMAddOnsClient mod;

	public SwapTimerScreen(Screen parent, GTMAddOnsClient mod) {
		super(Text.literal("GTMAddOns - Swap timer"));
		this.parent = parent;
		this.mod = mod;
	}

	private boolean enabled() {
		return mod.isSwapTimerShown() || mod.isCornerSwapTextOn();
	}

	private void apply(Mode mode) {
		remembered = mode;
		mod.setSwapTimerShown(mode.timer);
		mod.setCornerSwapTextOn(mode.corner);
	}

	private int top() {
		return Math.max(34, (height - ROW * 5) / 2 + 6);
	}

	@Override
	protected void init() {
		int x = width / 2 - BUTTON_WIDTH / 2;
		int y = top();
		for (Mode mode : Mode.values()) {
			if (mode.timer == mod.isSwapTimerShown() && mode.corner == mod.isCornerSwapTextOn()) remembered = mode;
		}
		boolean on = enabled();
		addDrawableChild(ButtonWidget.builder(Text.literal("Timer: " + (on ? "ON" : "OFF")), b -> {
			if (enabled()) {
				mod.setSwapTimerShown(false);
				mod.setCornerSwapTextOn(false);
			} else {
				apply(remembered);
			}
			clearAndInit();
		}).dimensions(x, y, BUTTON_WIDTH, 20).build());
		y += ROW + 4;
		for (Mode mode : Mode.values()) {
			boolean selected = on && mode == remembered;
			addDrawableChild(ButtonWidget.builder(Text.literal((selected ? "> " : "") + mode.label + (selected ? " <" : "")), b -> {
				apply(mode);
				clearAndInit();
			}).dimensions(x, y, BUTTON_WIDTH, 20).build());
			y += ROW;
		}
		addDrawableChild(ButtonWidget.builder(ScreenTexts.BACK, b -> close())
				.dimensions(x, y + 4, BUTTON_WIDTH, 20).build());
	}

	@Override
	public void close() {
		client.setScreen(parent);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		super.render(context, mouseX, mouseY, deltaTicks);
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, top() - 16, 0xFFFFFFFF);
	}
}
