package com.example.gtmaddons.gui;

import com.example.gtmaddons.stats.StatsClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Raw info: everything the server has stored about a player, as plain lines (see RawInfo) - for your own Personal Stats and for every
 * other player's page. Scroll with the mouse wheel; Copy all puts the whole text on the clipboard.
 */
public class RawInfoScreen extends Screen {

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
		super(Text.literal("Raw info - " + name));
		this.parent = parent;
		this.stats = stats;
		this.uuid = uuid;
		this.name = name;
		stats.fetchRaw(uuid).whenComplete((result, error) -> net.minecraft.client.MinecraftClient.getInstance().execute(() -> {
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
		addDrawableChild(ButtonWidget.builder(Text.literal("Copy all"), b -> {
			if (lines != null) client.keyboard.setClipboard(String.join("\n", lines));
		}).dimensions(x, y, 90, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Top"), b -> scroll = 0).dimensions(x + 94, y, 40, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("End"), b -> scroll = Integer.MAX_VALUE).dimensions(x + 138, y, 40, 20).build());
		addDrawableChild(ButtonWidget.builder(ScreenTexts.BACK, b -> close()).dimensions(x + 182, y, 100, 20).build());
		wrappedFor = -1;
	}

	private int visibleLines() {
		return Math.max(1, (height - TOP - 36) / LINE);
	}

	/** Cuts every line to the screen width, once per width. */
	private void wrapIfNeeded() {
		if (lines == null || wrappedFor == width) return;
		wrapped.clear();
		for (String line : lines) wrapped.addAll(Ui.chunks(textRenderer, line, width - 16));
		wrappedFor = width;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		int max = Math.max(0, wrapped.size() - visibleLines());
		scroll = Math.max(0, Math.min(max, (scroll == Integer.MAX_VALUE ? max : scroll) - (int) Math.signum(verticalAmount) * 6));
		return true;
	}

	@Override
	public void close() {
		client.setScreen(parent);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		super.render(context, mouseX, mouseY, deltaTicks);
		context.drawCenteredTextWithShadow(textRenderer, Text.literal(title.getString()), width / 2, 12, 0xFFFFFFFF);
		if (status != null) {
			context.drawCenteredTextWithShadow(textRenderer, Text.literal(status), width / 2, TOP + 6, 0xFFFFFF55);
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
			context.drawTextWithShadow(textRenderer, line, 8, y, color);
			y += LINE;
		}
		String note = wrapped.isEmpty() ? "" : (scroll + 1) + "-" + Math.min(wrapped.size(), scroll + visible) + " of " + wrapped.size() + " lines";
		context.drawTextWithShadow(textRenderer, note, 8, height - 42, 0xFF9AA0AE);
	}
}
