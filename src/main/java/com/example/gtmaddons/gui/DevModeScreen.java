package com.example.gtmaddons.gui;

import com.example.gtmaddons.GTMAddOnsClient;
import com.example.gtmaddons.gun.DevFilter;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * The dev mode / QA mode menu (Settings > Dev mode, Settings > QA mode), opened
 * once the backend has approved this account. Same options for both: one
 * button per DevFilter to switch that output on or off (each mode keeps its
 * own choices), then the mode's own on/off button at the bottom. QA mode
 * shows the same things but never writes the log file.
 *
 * Choices apply at once, even while the mode is on.
 */
public class DevModeScreen extends Screen {

	private static final int ROW = 22;
	private static final int GAP = 4;
	private static final int BUTTON_WIDTH = 200;

	private final Screen parent;
	private final GTMAddOnsClient mod;
	private final boolean qa;
	private String status = null;

	public DevModeScreen(Screen parent, GTMAddOnsClient mod, boolean qa) {
		super(Text.literal("GTMAddOns - " + (qa ? "QA mode" : "Dev mode")));
		this.parent = parent;
		this.mod = mod;
		this.qa = qa;
	}

	private String modeName() {
		return qa ? "QA mode" : "Dev mode";
	}

	private boolean modeOn() {
		return qa ? mod.isQaModeOn() : mod.isDevModeOn();
	}

	private int columns() {
		return width >= 480 ? 3 : 2;
	}

	private int rows() {
		int n = DevFilter.values().length;
		return (n + columns() - 1) / columns();
	}

	/** Filter grid, All on / All off, then the mode button and Back. */
	private int contentHeight() {
		return rows() * ROW + 8 + ROW + 8 + ROW * 2;
	}

	private int top() {
		return Math.max(34, (height - contentHeight()) / 2 + 6);
	}

	@Override
	protected void init() {
		DevFilter[] filters = DevFilter.values();
		int cols = columns();
		int colWidth = Math.min(150, (width - 30 - GAP * (cols - 1)) / cols);
		int gridWidth = cols * colWidth + GAP * (cols - 1);
		int left = (width - gridWidth) / 2;
		int top = top();

		for (int i = 0; i < filters.length; i++) {
			DevFilter filter = filters[i];
			int x = left + (i % cols) * (colWidth + GAP);
			int y = top + (i / cols) * ROW;
			addDrawableChild(ButtonWidget.builder(filterText(filter), b -> {
				mod.setDevFilterOn(qa, filter, !mod.isDevFilterOn(qa, filter));
				b.setMessage(filterText(filter));
			}).dimensions(x, y, colWidth, 20).tooltip(Tooltip.of(Text.literal(filter.description))).build());
		}

		int y = top + rows() * ROW + 8;
		int half = (BUTTON_WIDTH - GAP) / 2;
		int x = width / 2 - BUTTON_WIDTH / 2;
		addDrawableChild(ButtonWidget.builder(Text.literal("All on"), b -> setAll(true))
				.dimensions(x, y, half, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("All off"), b -> setAll(false))
				.dimensions(x + BUTTON_WIDTH - half, y, half, 20).build());

		y += ROW + 8;
		addDrawableChild(ButtonWidget.builder(Text.literal(modeName() + ": ").append(onOff(modeOn())), b -> toggleMode())
				.dimensions(x, y, BUTTON_WIDTH, 20)
				.tooltip(Tooltip.of(Text.literal(qa
						? "Show the selected things in chat. Nothing is saved to disk."
						: "Show the selected things in chat and save them to gtmaddons-dev.log")))
				.build());
		addDrawableChild(ButtonWidget.builder(ScreenTexts.BACK, b -> close())
				.dimensions(x, y + ROW, BUTTON_WIDTH, 20).build());
	}

	private Text filterText(DevFilter filter) {
		return Text.literal(filter.label + ": ").append(onOff(mod.isDevFilterOn(qa, filter)));
	}

	private static Text onOff(boolean on) {
		return Text.literal(on ? "ON" : "OFF").formatted(on ? Formatting.GREEN : Formatting.RED);
	}

	private void setAll(boolean on) {
		for (DevFilter filter : DevFilter.values()) mod.setDevFilterOn(qa, filter, on);
		clearAndInit();
	}

	/** Turning it off is immediate; turning it on asks for confirmation first. */
	private void toggleMode() {
		if (modeOn()) {
			if (qa) mod.disableQaMode(message -> status = message);
			else mod.disableDevMode(message -> status = message);
			clearAndInit();
			return;
		}
		client.setScreen(new ConfirmScreen(yes -> {
			if (yes) {
				if (qa) mod.enableQaMode(message -> status = message);
				else mod.enableDevMode(message -> status = message);
			}
			client.setScreen(this);
		},
				Text.literal("Enable " + modeName() + "?"),
				Text.literal("Please ensure you're on one of the dev servers when enabling this mode.\n\n"
						+ (qa ? "QA mode shows the same things as dev mode without saving a log.\n\n" : "")
						+ "Do you want to enable it?")));
	}

	@Override
	public void close() {
		client.setScreen(parent);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		super.render(context, mouseX, mouseY, deltaTicks);
		int top = top();
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, top - 26, Ui.TEXT);
		context.drawCenteredTextWithShadow(textRenderer,
				Text.literal("Choose what to show, then turn " + modeName() + " on. Hover an option to see what it does."),
				width / 2, top - 14, Ui.MUTED);
		if (status != null) {
			context.drawCenteredTextWithShadow(textRenderer, Text.literal(status), width / 2, top + contentHeight() + 2, 0xFFFFFF55);
		}
	}
}
