package com.example.gtmaddons.gui;

import com.example.gtmaddons.MenuKey;
import com.example.gtmaddons.Settings;
import com.example.gtmaddons.compat.Gfx;
import com.example.gtmaddons.compat.Mc;
import com.example.gtmaddons.compat.ModScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Menu keybind's ⚙ page: click "Set key", press the key to use (Escape cancels), or Clear it. */
public class KeyCaptureScreen extends ModScreen {

	private static final int BUTTON_WIDTH = 200;
	private static final int ESCAPE = 256;

	private final Screen parent;
	private final Settings settings;
	private boolean listening = false;

	public KeyCaptureScreen(Screen parent, Settings settings) {
		super(Component.literal("GTMAddOns - Menu keybind"));
		this.parent = parent;
		this.settings = settings;
	}

	@Override
	protected void init() {
		int x = width / 2 - BUTTON_WIDTH / 2, y = height / 2 - 22;
		String label = listening ? "> Press a key <" : "Key: " + MenuKey.keyName(settings.menuKey);
		addRenderableWidget(ThemedButton.create(Component.literal(label), b -> {
			listening = true;
			rebuildWidgets();
		}).bounds(x, y, BUTTON_WIDTH, 20).build());
		addRenderableWidget(ThemedButton.create(Component.literal("Clear"), b -> {
			settings.menuKey = -1;
			settings.save();
			listening = false;
			rebuildWidgets();
		}).bounds(x, y + 22, BUTTON_WIDTH, 20).build());
		addRenderableWidget(ThemedButton.create(CommonComponents.GUI_BACK, b -> onClose()).bounds(x, y + 54, BUTTON_WIDTH, 20).build());
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (!listening) return super.keyPressed(event);
		listening = false;
		if (event.key() != ESCAPE) {
			settings.menuKey = event.key();
			settings.save();
		}
		rebuildWidgets();
		return true;
	}

	@Override
	public void onClose() {
		Mc.setScreen(minecraft, parent);
	}

	@Override
	public void draw(Gfx context, int mouseX, int mouseY, float deltaTicks) {
		context.fill(0, 0, width, height, Ui.PAGE);
		super.draw(context, mouseX, mouseY, deltaTicks);
		context.centeredText(font, title, width / 2, height / 2 - 44, 0xFFFFFFFF);
	}
}
