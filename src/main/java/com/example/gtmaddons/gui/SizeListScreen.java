package com.example.gtmaddons.gui;

import com.example.gtmaddons.compat.Gfx;
import com.example.gtmaddons.compat.Mc;
import com.example.gtmaddons.compat.ModScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Map;

/** A paged list of 0-100% sliders, one per name (Gun tracers: by category, by gun), saved as they move. */
public class SizeListScreen extends ModScreen {

	private static final int WIDTH = 220;
	private static final int ROW = 22;

	private final Screen parent;
	private final List<String> names;
	private final Map<String, Integer> values;
	private final Runnable save;
	private int page = 0;

	public SizeListScreen(Screen parent, String title, List<String> names, Map<String, Integer> values, Runnable save) {
		super(Component.literal("GTMAddOns - " + title));
		this.parent = parent;
		this.names = names;
		this.values = values;
		this.save = save;
	}

	private int perPage() {
		return Math.max(3, (height - 90) / ROW);
	}

	@Override
	protected void init() {
		int x = width / 2 - WIDTH / 2, y = 32;
		int pages = Math.max(1, (names.size() + perPage() - 1) / perPage());
		page = Math.min(page, pages - 1);
		for (int i = page * perPage(); i < Math.min(names.size(), (page + 1) * perPage()); i++) {
			String name = names.get(i);
			addRenderableWidget(new ThemedSlider(x, y, WIDTH, 20, Component.empty(), values.getOrDefault(name, 100) / 100.0) {
				{
					updateMessage();
				}

				@Override
				protected void updateMessage() {
					int percent = (int) Math.round(value * 100);
					setMessage(Component.literal(name + ": " + (percent == 0 ? "Hidden" : percent + "%")));
				}

				@Override
				protected void applyValue() {
					int percent = (int) Math.round(value * 100);
					if (percent == 100) values.remove(name);
					else values.put(name, percent);
					save.run();
				}
			});
			y += ROW;
		}
		int bottom = height - 30;
		if (pages > 1) {
			Button prev = ThemedButton.create(Component.literal("<"), b -> { page--; rebuildWidgets(); }).bounds(x, bottom - ROW, 40, 20).build();
			Button next = ThemedButton.create(Component.literal(">"), b -> { page++; rebuildWidgets(); }).bounds(x + WIDTH - 40, bottom - ROW, 40, 20).build();
			prev.active = page > 0;
			next.active = page < pages - 1;
			addRenderableWidget(prev);
			addRenderableWidget(next);
		}
		addRenderableWidget(ThemedButton.create(CommonComponents.GUI_BACK, b -> onClose()).bounds(x, bottom, WIDTH, 20).build());
	}

	@Override
	public void onClose() {
		Mc.setScreen(minecraft, parent);
	}

	@Override
	public void draw(Gfx context, int mouseX, int mouseY, float deltaTicks) {
		context.fill(0, 0, width, height, Ui.PAGE);
		super.draw(context, mouseX, mouseY, deltaTicks);
		context.centeredText(font, title, width / 2, 14, 0xFFFFFFFF);
	}
}
