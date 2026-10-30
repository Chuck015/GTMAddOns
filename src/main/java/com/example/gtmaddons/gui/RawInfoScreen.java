package com.example.gtmaddons.gui;

import com.example.gtmaddons.compat.Gfx;
import com.example.gtmaddons.compat.Mc;
import com.example.gtmaddons.compat.ModScreen;
import com.example.gtmaddons.stats.StatsClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Raw info: everything the server has stored about a player, as plain lines (see RawInfo) - for your own Personal Stats and for every
 * other player's page. Scroll with the mouse wheel; Copy all puts the whole text on the clipboard.
 */
public class RawInfoScreen extends ModScreen {

	private static final int TOP = 34;
	private static final int LINE = 10;

	private final Screen parent;
	private final StatsClient stats;
	private final String uuid;
	private final String name;
	private List<String> lines = null;
	private String status = "Loading...";
	private final List<String> wrapped = new ArrayList<>();
	private int wrappedFor = -1;
	private int scroll = 0;

	public RawInfoScreen(Screen parent, StatsClient stats, String uuid, String name) {
		super(Component.literal("Raw info - " + name));
		this.parent = parent;
		this.stats = stats;
		this.uuid = uuid;
		this.name = name;
		stats.fetchRaw(uuid).whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
			if (error != null) {
				String why = Format.error(error);
				status = why.contains("404") ? "The server has nothing stored for this player." : "Couldn't load the raw info: " + why;
				return;
			}
			lines = result;
			status = null;
			wrappedFor = -1;
		}));
	}

	@Override
	protected void init() {
		int y = height - 28;
		int total = 90 + 40 + 40 + 100 + 3 * 4;
		int x = (width - total) / 2;
		addRenderableWidget(ThemedButton.create(Component.literal("Copy all"), b -> {
			if (lines != null) minecraft.keyboardHandler.setClipboard(String.join("\n", lines));
		}).bounds(x, y, 90, 20).build());
		addRenderableWidget(ThemedButton.create(Component.literal("Top"), b -> scroll = 0).bounds(x + 94, y, 40, 20).build());
		addRenderableWidget(ThemedButton.create(Component.literal("End"), b -> scroll = Integer.MAX_VALUE).bounds(x + 138, y, 40, 20).build());
		addRenderableWidget(ThemedButton.create(CommonComponents.GUI_BACK, b -> onClose()).bounds(x + 182, y, 100, 20).build());
		wrappedFor = -1;
	}

	private int visibleLines() {
		return Math.max(1, (height - TOP - 36) / LINE);
	}

	/** Cuts every line to the screen width, once per width. */
	private void wrapIfNeeded() {
		if (lines == null || wrappedFor == width) return;
		wrapped.clear();
		for (String line : lines) wrapped.addAll(Ui.chunks(font, line, width - 16));
		wrappedFor = width;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		int max = Math.max(0, wrapped.size() - visibleLines());
		scroll = Math.max(0, Math.min(max, (scroll == Integer.MAX_VALUE ? max : scroll) - (int) Math.signum(verticalAmount) * 6));
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
		context.centeredText(font, Component.literal(title.getString()), width / 2, 12, 0xFFFFFFFF);
		if (status != null) {
			context.centeredText(font, Component.literal(status), width / 2, TOP + 6, 0xFFFFFF55);
			return;
		}
		wrapIfNeeded();
		int visible = visibleLines();
		int max = Math.max(0, wrapped.size() - visible);
		scroll = Math.max(0, Math.min(max, scroll));
		int y = TOP;
		for (int i = scroll; i < wrapped.size() && i < scroll + visible; i++) {
			String line = wrapped.get(i);
			int color = line.startsWith("FIGHT") ? 0xFFFFFFFF : line.startsWith("PLAYER") || line.startsWith("COUNTS") || line.startsWith("RAW") ? 0xFF55FFFF : 0xFFB0B6C4;
			context.text(font, line, 8, y, color);
			y += LINE;
		}
		String note = wrapped.isEmpty() ? "" : (scroll + 1) + "-" + Math.min(wrapped.size(), scroll + visible) + " of " + wrapped.size() + " lines";
		context.text(font, note, 8, height - 42, 0xFF9AA0AE);
	}
}
