package com.example.gtmaddons.gui;

import com.example.gtmaddons.GTMAddOnsClient;
import com.example.gtmaddons.compat.Gfx;
import com.example.gtmaddons.compat.Mc;
import com.example.gtmaddons.compat.ModScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Admin mode's ⚙ page: its tools (Player info, Notify players). Only reachable while admin mode is on. */
public class AdminToolsScreen extends ModScreen {

	private static final int BUTTON_WIDTH = 200;

	private final Screen parent;
	private final GTMAddOnsClient mod;

	public AdminToolsScreen(Screen parent, GTMAddOnsClient mod) {
		super(Component.literal("GTMAddOns - Admin mode"));
		this.parent = parent;
		this.mod = mod;
	}

	@Override
	protected void init() {
		int x = width / 2 - BUTTON_WIDTH / 2, y = height / 2 - 34;
		addRenderableWidget(ThemedButton.create(Component.literal("Player info..."), b -> Mc.setScreen(minecraft, new AdminPlayerInfoScreen(this, mod)))
				.bounds(x, y, BUTTON_WIDTH, 20).build());
		addRenderableWidget(ThemedButton.create(Component.literal("Notify players..."), b -> Mc.setScreen(minecraft, new AdminNoticeScreen(this, mod)))
				.bounds(x, y + 22, BUTTON_WIDTH, 20).build());
		addRenderableWidget(ThemedButton.create(CommonComponents.GUI_BACK, b -> onClose()).bounds(x, y + 56, BUTTON_WIDTH, 20).build());
	}

	@Override
	public void onClose() {
		Mc.setScreen(minecraft, parent);
	}

	@Override
	public void draw(Gfx context, int mouseX, int mouseY, float deltaTicks) {
		context.fill(0, 0, width, height, Ui.PAGE);
		super.draw(context, mouseX, mouseY, deltaTicks);
		context.centeredText(font, title, width / 2, height / 2 - 56, 0xFFFFFFFF);
	}
}
