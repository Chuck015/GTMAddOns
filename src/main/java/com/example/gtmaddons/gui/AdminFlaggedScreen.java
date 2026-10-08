package com.example.gtmaddons.gui;

import com.example.gtmaddons.GTMAddOnsClient;
import com.example.gtmaddons.stats.PlayerStats;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Admin mode > Flagged players: everyone who currently has a warning flag, most warnings first, one button each that opens their
 * Player info. The backend works the list out from all stored swaps and guns and keeps it for 15 minutes (GET /admin/flagged), so
 * it can be up to that old. Search player... opens Player info for any name.
 */
public class AdminFlaggedScreen extends Screen {

	private static final int WIDTH = 360;
	private static final int ROW = 22;

	private final Screen parent;
	private final GTMAddOnsClient mod;
	private List<PlayerStats.FlaggedPlayer> players = null;
	private long computedAt = 0L;
	private String status = "Loading the flagged players...";
	private boolean loading = false;
	private int page = 0;

	public AdminFlaggedScreen(Screen parent, GTMAddOnsClient mod) {
		super(Text.literal("GTMAddOns - Flagged players (admin)"));
		this.parent = parent;
		this.mod = mod;
	}

	private int rowsPerPage() {
		return Math.max(1, (height - 100) / ROW);
	}

	@Override
	protected void init() {
		if (players == null && !loading) load();
		int x = width / 2 - WIDTH / 2;
		addDrawableChild(ButtonWidget.builder(Text.literal("Search player..."), b -> client.setScreen(new AdminPlayerInfoScreen(this, mod)))
				.dimensions(width / 2 - 60, 30, 120, 20).build());
		int perPage = rowsPerPage();
		int start = page * perPage;
		int end = players == null ? 0 : Math.min(players.size(), start + perPage);
		for (int i = start; i < end; i++) {
			PlayerStats.FlaggedPlayer p = players.get(i);
			int warnings = 0;
			String first = "";
			for (PlayerStats.Flag flag : p.flags()) {
				if (!flag.level().equals("warn")) continue;
				if (warnings++ == 0) first = flag.text();
			}
			String label = p.name() + "  -  " + warnings + (warnings == 1 ? " flag" : " flags") + ": " + first;
			addDrawableChild(ButtonWidget.builder(Text.literal(Ui.fit(textRenderer, label, WIDTH - 12)), b -> client.setScreen(new AdminPlayerInfoScreen(this, mod, p.name())))
					.dimensions(x, 56 + (i - start) * ROW, WIDTH, 20).build());
		}
		ButtonWidget prev = addDrawableChild(ButtonWidget.builder(Text.literal("< Prev"), b -> {
			page--;
			clearAndInit();
		}).dimensions(width / 2 - 155, height - 28, 70, 20).build());
		prev.active = page > 0;
		addDrawableChild(ButtonWidget.builder(ScreenTexts.BACK, b -> close()).dimensions(width / 2 - 75, height - 28, 150, 20).build());
		ButtonWidget next = addDrawableChild(ButtonWidget.builder(Text.literal("Next >"), b -> {
			page++;
			clearAndInit();
		}).dimensions(width / 2 + 85, height - 28, 70, 20).build());
		next.active = players != null && end < players.size();
	}

	private void load() {
		loading = true;
		mod.fetchAdminFlagged().whenComplete((answer, error) -> client.execute(() -> {
			loading = false;
			if (client.currentScreen != this) return;
			if (error != null) {
				String why = Format.error(error);
				status = why.contains("403") ? "Only admin accounts can see the flagged players (HTTP 403)." : "Couldn't load the flagged players: " + why;
				return;
			}
			players = new ArrayList<>(answer.players() != null ? answer.players() : List.of());
			computedAt = answer.computedAt();
			status = players.isEmpty() ? "Nobody has a warning flag right now." : null;
			clearAndInit();
		}));
	}

	@Override
	public void close() {
		client.setScreen(parent);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		super.render(context, mouseX, mouseY, deltaTicks);
		context.drawCenteredTextWithShadow(textRenderer, Text.literal(title.getString()), width / 2, 14, 0xFFFFFFFF);
		if (status != null) {
			context.drawCenteredTextWithShadow(textRenderer, Text.literal(status), width / 2, 62, 0xFFFFFF55);
		}
		if (players != null && !players.isEmpty()) {
			String note = players.size() + (players.size() == 1 ? " player" : " players") + " flagged - list updated " + Math.max(0, (System.currentTimeMillis() - computedAt) / 60_000L) + " min ago (the server refreshes it every 15 min)";
			context.drawTextWithShadow(textRenderer, Ui.fit(textRenderer, note, width - 20), 10, height - 44, 0xFF9AA0AE);
		}
	}
}
