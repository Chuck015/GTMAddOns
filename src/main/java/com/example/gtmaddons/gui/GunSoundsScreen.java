package com.example.gtmaddons.gui;

import com.example.gtmaddons.GunSounds;
import com.example.gtmaddons.GunSounds.Category;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;

import java.util.List;

/**
 * Settings > Sounds: a volume slider per wingsuit/jetpack sound and per
 * gunshot sound. Guns that share a sound are listed together under it,
 * since they can't be told apart.
 */
public class GunSoundsScreen extends Screen {

	private static final int ROW_HEIGHT = 24;
	private static final int TOP = 44;
	private static final int SLIDER_WIDTH = 150;

	private final Screen parent;
	private List<Category> categories = List.of();
	private int page = 0;

	public GunSoundsScreen(Screen parent) {
		super(Text.literal("GTMAddOns - Sounds"));
		this.parent = parent;
	}

	private int rowsPerPage() {
		return Math.max(1, (height - TOP - 40) / ROW_HEIGHT);
	}

	private int rowWidth() {
		return Math.min(400, width - 40);
	}

	@Override
	protected void init() {
		categories = GunSounds.INSTANCE.categories();
		int perPage = rowsPerPage();
		page = Math.min(page, Math.max(0, (categories.size() - 1) / perPage));
		int rowLeft = (width - rowWidth()) / 2;

		int start = page * perPage;
		int end = Math.min(categories.size(), start + perPage);
		for (int i = start; i < end; i++) {
			int y = TOP + (i - start) * ROW_HEIGHT;
			addDrawableChild(new VolumeSlider(rowLeft + rowWidth() - SLIDER_WIDTH, y + 2, categories.get(i)));
		}

		int bottom = height - 28;
		ButtonWidget prev = addDrawableChild(ButtonWidget.builder(Text.literal("< Prev"), b -> changePage(-1))
				.dimensions(width / 2 - 155, bottom, 70, 20).build());
		prev.active = page > 0;
		addDrawableChild(ButtonWidget.builder(ScreenTexts.BACK, b -> close())
				.dimensions(width / 2 - 75, bottom, 150, 20).build());
		ButtonWidget next = addDrawableChild(ButtonWidget.builder(Text.literal("Next >"), b -> changePage(1))
				.dimensions(width / 2 + 85, bottom, 70, 20).build());
		next.active = end < categories.size();
	}

	private void changePage(int delta) {
		page += delta;
		clearAndInit();
	}

	@Override
	public void close() {
		client.setScreen(parent);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		super.render(context, mouseX, mouseY, deltaTicks);
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 12, 0xFFFFFFFF);

		if (categories.isEmpty()) {
			context.drawCenteredTextWithShadow(textRenderer, Text.literal("No gun sounds heard yet."),
					width / 2, height / 2, 0xFFAAAAAA);
			return;
		}

		int perPage = rowsPerPage();
		int rowLeft = (width - rowWidth()) / 2;
		int start = page * perPage;
		int end = Math.min(categories.size(), start + perPage);
		for (int i = start; i < end; i++) {
			Category category = categories.get(i);
			int y = TOP + (i - start) * ROW_HEIGHT;
			int textWidth = rowWidth() - SLIDER_WIDTH - 8;
			String name = textRenderer.trimToWidth(category.name(), textWidth);
			context.drawTextWithShadow(textRenderer, name, rowLeft, y + 8, 0xFFFFFFFF);
		}
	}

	/** 0-100% volume for one sound, saved as it's dragged. */
	private static final class VolumeSlider extends SliderWidget {
		private final Category category;

		VolumeSlider(int x, int y, Category category) {
			super(x, y, SLIDER_WIDTH, 20, Text.empty(), GunSounds.INSTANCE.volume(category));
			this.category = category;
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			int percent = (int) Math.round(value * 100);
			setMessage(Text.literal(percent == 0 ? "Volume: Muted" : "Volume: " + percent + "%"));
		}

		@Override
		protected void applyValue() {
			GunSounds.INSTANCE.setVolume(category, (float) value);
		}
	}
}
