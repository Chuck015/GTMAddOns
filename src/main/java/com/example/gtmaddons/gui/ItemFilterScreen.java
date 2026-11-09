package com.example.gtmaddons.gui;

import com.example.gtmaddons.Settings;
import com.example.gtmaddons.compat.Gfx;
import com.example.gtmaddons.compat.Mc;
import com.example.gtmaddons.compat.ModScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Locale;

/**
 * A mod's item filter (Dropped item names, Dropped item size): the item names it applies to. Type a name (or part of one) and Add, or add the item in your hand;
 * each entry has a ✕ to remove it. With the mod's "Only listed items" off it applies to every item; with it on only to items whose
 * name contains one of these (ignoring case).
 */
public class ItemFilterScreen extends ModScreen {

	private static final int WIDTH = 220;
	private static final int ROW = 22;
	private static final int MAX_NAME = 48;

	private final Screen parent;
	private final Settings settings;
	private final List<String> items;
	private final java.util.function.BooleanSupplier filterOn;
	private final String onText, offText;
	private EditBox input;
	private int page = 0;

	/** onText / offText: the line at the bottom while the mod's "Only listed items" is on / off. */
	public ItemFilterScreen(Screen parent, Settings settings, String title, List<String> items, java.util.function.BooleanSupplier filterOn,
			String onText, String offText) {
		super(Component.literal("GTMAddOns - " + title));
		this.parent = parent;
		this.settings = settings;
		this.items = items;
		this.filterOn = filterOn;
		this.onText = onText;
		this.offText = offText;
	}

	private int perPage() {
		return Math.max(3, (height - 150) / ROW);
	}

	@Override
	protected void init() {
		int x = width / 2 - WIDTH / 2, y = 32;
		String typed = input != null ? input.getValue() : "";
		input = new EditBox(font, x, y, WIDTH - 54, 20, Component.literal("Item name"));
		input.setMaxLength(MAX_NAME);
		input.setValue(typed);
		addRenderableWidget(input);
		addRenderableWidget(ThemedButton.create(Component.literal("Add"), b -> add(input.getValue())).bounds(x + WIDTH - 50, y, 50, 20).build());
		y += ROW;
		addRenderableWidget(ThemedButton.create(Component.literal("Add the item in your hand"), b -> {
			ItemStack held = minecraft.player != null ? minecraft.player.getMainHandItem() : ItemStack.EMPTY;
			if (!held.isEmpty()) add(held.getHoverName().getString());
		}).bounds(x, y, WIDTH, 20).build());
		y += ROW + 6;

		int pages = Math.max(1, (items.size() + perPage() - 1) / perPage());
		page = Math.min(page, pages - 1);
		for (int i = page * perPage(); i < Math.min(items.size(), (page + 1) * perPage()); i++) {
			String name = items.get(i);
			Button label = ThemedButton.create(Component.literal(name), b -> { }).bounds(x, y, WIDTH - 24, 20).build();
			label.active = false;
			addRenderableWidget(label);
			addRenderableWidget(ThemedButton.create(Component.literal("✕"), b -> {
				items.remove(name);
				settings.save();
				rebuildWidgets();
			}).bounds(x + WIDTH - 20, y, 20, 20).build());
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

	private void add(String name) {
		String clean = name.replaceAll("§.", "").trim();
		if (clean.isEmpty()) return;
		if (clean.length() > MAX_NAME) clean = clean.substring(0, MAX_NAME);
		String key = clean.toLowerCase(Locale.ROOT);
		if (items.stream().noneMatch(s -> s.toLowerCase(Locale.ROOT).equals(key))) {
			items.add(clean);
			settings.save();
		}
		input.setValue("");
		rebuildWidgets();
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		if (input != null && input.isFocused() && (event.key() == 257 || event.key() == 335)) {
			add(input.getValue());
			return true;
		}
		return super.keyPressed(event);
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
		if (items.isEmpty()) context.centeredText(font, Component.literal("No items yet"), width / 2, 32 + 2 * ROW + 12, Ui.MUTED);
		String mode = filterOn.getAsBoolean() ? onText : offText;
		context.centeredText(font, Component.literal(mode), width / 2, height - 46, Ui.LABEL);
	}
}
