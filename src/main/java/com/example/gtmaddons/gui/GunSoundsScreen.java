package com.example.gtmaddons.gui;

import com.example.gtmaddons.GunSounds.Category;
import com.example.gtmaddons.GunSounds;
import com.example.gtmaddons.compat.Gfx;
import com.example.gtmaddons.compat.Mc;
import com.example.gtmaddons.compat.ModScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Settings > Sounds: a volume slider per wingsuit/jetpack sound and per
 * gunshot sound. Guns that share a sound are listed together under it,
 * since they can't be told apart.
 */
public class GunSoundsScreen extends ModScreen {

	private static final int ROW_HEIGHT = 24;
	private static final int TOP = 44;
	private static final int SLIDER_WIDTH = 150;

	private final Screen parent;
	private List<Category> categories = List.of();
	private int page = 0;

	public GunSoundsScreen(Screen parent) {
		super(Component.literal("GTMAddOns - Sounds"));
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
			addRenderableWidget(new VolumeSlider(rowLeft + rowWidth() - SLIDER_WIDTH, y + 2, categories.get(i)));
		}

		int bottom = height - 28;
		Button prev = addRenderableWidget(ThemedButton.create(Component.literal("< Prev"), b -> changePage(-1))
				.bounds(width / 2 - 155, bottom, 70, 20).build());
		prev.active = page > 0;
		addRenderableWidget(ThemedButton.create(CommonComponents.GUI_BACK, b -> onClose())
				.bounds(width / 2 - 75, bottom, 150, 20).build());
		Button next = addRenderableWidget(ThemedButton.create(Component.literal("Next >"), b -> changePage(1))
				.bounds(width / 2 + 85, bottom, 70, 20).build());
		next.active = end < categories.size();
	}

	private void changePage(int delta) {
		page += delta;
		rebuildWidgets();
	}

	@Override
	public void onClose() {
		Mc.setScreen(minecraft, parent);
	}

	@Override
	public void draw(Gfx context, int mouseX, int mouseY, float deltaTicks) {
		context.fill(0, 0, width, height, Ui.PAGE);
		super.draw(context, mouseX, mouseY, deltaTicks);
		context.centeredText(font, title, width / 2, 12, 0xFFFFFFFF);

		if (categories.isEmpty()) {
			context.centeredText(font, Component.literal("No gun sounds heard yet."),
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
			String name = font.plainSubstrByWidth(category.name(), textWidth);
			context.text(font, name, rowLeft, y + 8, 0xFFFFFFFF);
		}
	}

	/** 0-250% volume for one sound, saved as it's dragged. */
	private static final class VolumeSlider extends ThemedSlider {
		private final Category category;

		VolumeSlider(int x, int y, Category category) {
			super(x, y, SLIDER_WIDTH, 20, Component.empty(), GunSounds.INSTANCE.volume(category) / GunSounds.MAX_VOLUME);
			this.category = category;
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			int percent = (int) Math.round(value * 100 * GunSounds.MAX_VOLUME);
			setMessage(Component.literal(percent == 0 ? "Volume: Muted" : "Volume: " + percent + "%"));
		}

		@Override
		protected void applyValue() {
			float volume = (float) value * GunSounds.MAX_VOLUME;
			// Close to 100% sits exactly on 100% (the default).
			GunSounds.INSTANCE.setVolume(category, Math.abs(volume - 1.0f) < 0.03f ? 1.0f : volume);
		}
	}
}
