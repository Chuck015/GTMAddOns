package com.example.gtmaddons.gui;

import com.example.gtmaddons.GTMAddOnsClient;
import com.example.gtmaddons.compat.Gfx;
import com.example.gtmaddons.compat.Mc;
import com.example.gtmaddons.compat.ModScreen;
import com.example.gtmaddons.gun.DevFilter;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * The dev mode / QA mode menu (Settings > Dev mode, Settings > QA mode), opened
 * once the backend has approved this account. Same options for both: a row of
 * tabs (Wing, JP, General like the PVP settings, then PVE) and, for the open
 * tab, one button per DevFilter to switch that output on or off (each mode
 * keeps its own choices), then All on / All off, and the mode's own on/off
 * button at the bottom. QA mode
 * shows the same things but never writes the log file.
 *
 * Choices apply at once, even while the mode is on.
 */
public class DevModeScreen extends ModScreen {

	private static final int ROW = 22;
	private static final int GAP = 4;
	private static final int BUTTON_WIDTH = 200;

	private final Screen parent;
	private final GTMAddOnsClient mod;
	private final boolean qa;
	private String status = null;

	public DevModeScreen(Screen parent, GTMAddOnsClient mod, boolean qa) {
		super(Component.literal("GTMAddOns - " + (qa ? "QA mode" : "Dev mode")));
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

	/** The tab open; remembered, so coming back shows the same one. */
	private static DevFilter.Group tab = DevFilter.Group.GENERAL;

	private int columns() {
		return width >= 480 ? 3 : 2;
	}

	private static int filtersIn(DevFilter.Group group) {
		int n = 0;
		for (DevFilter filter : DevFilter.values()) if (filter.group == group) n++;
		return n;
	}

	/** Rows of the biggest tab: every tab reserves this much, so the buttons below stay put when you switch. */
	private int rows() {
		int most = 1;
		for (DevFilter.Group group : DevFilter.Group.values()) most = Math.max(most, filtersIn(group));
		return (most + columns() - 1) / columns();
	}

	/** Tabs, filter grid, All on / All off, then the mode button and Back. */
	private int contentHeight() {
		return ROW + 6 + rows() * ROW + 8 + ROW + 8 + ROW * 2;
	}

	private int top() {
		return Math.max(34, (height - contentHeight()) / 2 + 6);
	}

	@Override
	protected void init() {
		int cols = columns();
		int colWidth = Math.min(150, (width - 30 - GAP * (cols - 1)) / cols);
		int gridWidth = cols * colWidth + GAP * (cols - 1);
		int left = (width - gridWidth) / 2;
		int top = top();

		// The tabs, side by side; the open one is greyed out.
		int chip = 62;
		int tx = width / 2 - (DevFilter.Group.values().length * (chip + GAP) - GAP) / 2;
		for (DevFilter.Group group : DevFilter.Group.values()) {
			Button button = ThemedButton.create(Component.literal(group.label), b -> {
				tab = group;
				rebuildWidgets();
			}).bounds(tx, top, chip, 20).build();
			button.active = group != tab;
			addRenderableWidget(button);
			tx += chip + GAP;
		}
		int gridTop = top + ROW + 6;

		int i = 0;
		for (DevFilter filter : DevFilter.values()) {
			if (filter.group != tab) continue;
			int x = left + (i % cols) * (colWidth + GAP);
			int y = gridTop + (i / cols) * ROW;
			i++;
			addRenderableWidget(ThemedButton.create(filterText(filter), b -> {
				mod.setDevFilterOn(qa, filter, !mod.isDevFilterOn(qa, filter));
				b.setMessage(filterText(filter));
			}).bounds(x, y, colWidth, 20).tooltip(Tooltip.create(Component.literal(filter.description))).build());
		}

		int y = gridTop + rows() * ROW + 8;
		int half = (BUTTON_WIDTH - GAP) / 2;
		int x = width / 2 - BUTTON_WIDTH / 2;
		addRenderableWidget(ThemedButton.create(Component.literal("All on"), b -> setAll(true))
				.bounds(x, y, half, 20).build());
		addRenderableWidget(ThemedButton.create(Component.literal("All off"), b -> setAll(false))
				.bounds(x + BUTTON_WIDTH - half, y, half, 20).build());

		y += ROW + 8;
		addRenderableWidget(ThemedButton.create(Component.literal(modeName() + ": ").append(onOff(modeOn())), b -> toggleMode())
				.bounds(x, y, BUTTON_WIDTH, 20)
				.tooltip(Tooltip.create(Component.literal(qa
						? "Show the selected things in chat. Nothing is saved to disk."
						: "Show the selected things in chat and save them to gtmaddons-dev.log")))
				.build());
		addRenderableWidget(ThemedButton.create(CommonComponents.GUI_BACK, b -> onClose())
				.bounds(x, y + ROW, BUTTON_WIDTH, 20).build());
	}

	private Component filterText(DevFilter filter) {
		return Component.literal(filter.label + ": ").append(onOff(mod.isDevFilterOn(qa, filter)));
	}

	private static Component onOff(boolean on) {
		return Component.literal(on ? "ON" : "OFF").withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED);
	}

	private void setAll(boolean on) {
		for (DevFilter filter : DevFilter.values()) mod.setDevFilterOn(qa, filter, on);
		rebuildWidgets();
	}

	/** Turning it off is immediate; turning it on asks for confirmation first. */
	private void toggleMode() {
		if (modeOn()) {
			if (qa) mod.disableQaMode(message -> status = message);
			else mod.disableDevMode(message -> status = message);
			rebuildWidgets();
			return;
		}
		Mc.setScreen(minecraft, new ConfirmScreen(yes -> {
			if (yes) {
				if (qa) mod.enableQaMode(message -> status = message);
				else mod.enableDevMode(message -> status = message);
			}
			Mc.setScreen(minecraft, this);
		},
				Component.literal("Enable " + modeName() + "?"),
				Component.literal("Please ensure you're on one of the dev servers when enabling this mode.\n\n"
						+ (qa ? "QA mode shows the same things as dev mode without saving a log.\n\n" : "")
						+ "Do you want to enable it?")));
	}

	@Override
	public void onClose() {
		Mc.setScreen(minecraft, parent);
	}

	@Override
	public void draw(Gfx context, int mouseX, int mouseY, float deltaTicks) {
		context.fill(0, 0, width, height, Ui.PAGE);
		super.draw(context, mouseX, mouseY, deltaTicks);
		int top = top();
		context.centeredText(font, title, width / 2, top - 26, Ui.TEXT);
		context.centeredText(font,
				Component.literal("Choose what to show, then turn " + modeName() + " on. Hover an option to see what it does."),
				width / 2, top - 14, Ui.MUTED);
		if (filtersIn(tab) == 0) {
			context.centeredText(font, Component.literal("Nothing here yet"), width / 2, top + ROW + 10, 0xFF888888);
		}
		if (status != null) {
			context.centeredText(font, Component.literal(status), width / 2, top + contentHeight() + 2, 0xFFFFFF55);
		}
	}
}
