package com.example.gtmaddons.gui;

import com.example.gtmaddons.GTMAddOnsClient;
import com.example.gtmaddons.compat.Gfx;
import com.example.gtmaddons.compat.Mc;
import com.example.gtmaddons.compat.ModScreen;
import com.example.gtmaddons.stats.PlayerStats;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Admin mode > Stat flags: everyone who currently has a warning flag, most warnings first, one button each that opens their
 * Player info. The backend works the list out from all stored swaps and guns and keeps it for 15 minutes (GET /admin/flagged), so
 * it can be up to that old. Search player... opens Player info for any name.
 */
public class AdminFlaggedScreen extends ModScreen {

	private static final int WIDTH = 360;
	private static final int ROW = 22;

	private final Screen parent;
	private final GTMAddOnsClient mod;
	private List<PlayerStats.FlaggedPlayer> players = null;
	private long computedAt = 0L;
	private String status = "Loading the stat flags...";
	private boolean loading = false;
	private int page = 0;

	public AdminFlaggedScreen(Screen parent, GTMAddOnsClient mod) {
		super(Component.literal("GTMAddOns - Stat flags (admin)"));
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
		addRenderableWidget(ThemedButton.create(Component.literal("Search player..."), b -> Mc.setScreen(minecraft, new AdminPlayerInfoScreen(this, mod)))
				.bounds(width / 2 - 60, 30, 120, 20).build());
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
			addRenderableWidget(ThemedButton.create(Component.literal(Ui.fit(font, label, WIDTH - 12)), b -> Mc.setScreen(minecraft, new AdminPlayerInfoScreen(this, mod, p.name())))
					.bounds(x, 56 + (i - start) * ROW, WIDTH, 20).build());
		}
		Button prev = addRenderableWidget(ThemedButton.create(Component.literal("< Prev"), b -> {
			page--;
			rebuildWidgets();
		}).bounds(width / 2 - 155, height - 28, 70, 20).build());
		prev.active = page > 0;
		addRenderableWidget(ThemedButton.create(CommonComponents.GUI_BACK, b -> onClose()).bounds(width / 2 - 75, height - 28, 150, 20).build());
		Button next = addRenderableWidget(ThemedButton.create(Component.literal("Next >"), b -> {
			page++;
			rebuildWidgets();
		}).bounds(width / 2 + 85, height - 28, 70, 20).build());
		next.active = players != null && end < players.size();
	}

	private void load() {
		loading = true;
		mod.fetchAdminFlagged().whenComplete((answer, error) -> minecraft.execute(() -> {
			loading = false;
			if (Mc.screen(minecraft) != this) return;
			if (error != null) {
				String why = Format.error(error);
				status = why.contains("403") ? "Only admin accounts can see the stat flags (HTTP 403)." : "Couldn't load the stat flags: " + why;
				return;
			}
			players = new ArrayList<>(answer.players() != null ? answer.players() : List.of());
			computedAt = answer.computedAt();
			status = players.isEmpty() ? "Nobody has a warning flag right now." : null;
			rebuildWidgets();
		}));
	}

	@Override
	public void onClose() {
		Mc.setScreen(minecraft, parent);
	}

	@Override
	public void draw(Gfx context, int mouseX, int mouseY, float deltaTicks) {
		context.fill(0, 0, width, height, Ui.PAGE);
		super.draw(context, mouseX, mouseY, deltaTicks);
		context.centeredText(font, Component.literal(title.getString()), width / 2, 14, 0xFFFFFFFF);
		if (status != null) {
			context.centeredText(font, Component.literal(status), width / 2, 62, 0xFFFFFF55);
		}
		if (players != null && !players.isEmpty()) {
			String note = players.size() + (players.size() == 1 ? " player" : " players") + " flagged - list updated " + Math.max(0, (System.currentTimeMillis() - computedAt) / 60_000L) + " min ago (the server refreshes it every 15 min)";
			context.text(font, Ui.fit(font, note, width - 20), 10, height - 44, 0xFF9AA0AE);
		}
	}
}
