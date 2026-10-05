package com.example.gtmaddons.gui;

import com.example.gtmaddons.AdminMode;
import com.example.gtmaddons.PvpCategory;
import com.example.gtmaddons.gui.InsightScreen.Insight;
import com.example.gtmaddons.rating.Ratings;
import com.example.gtmaddons.stats.FightStats;
import com.example.gtmaddons.stats.PlayerStats.FightData;
import com.example.gtmaddons.stats.PlayerStats.FightHistory;
import com.example.gtmaddons.stats.PlayerStats.GunRow;
import com.example.gtmaddons.stats.PlayerStats.PlayerDetail;
import com.example.gtmaddons.stats.PlayerStats.SwapRow;
import com.example.gtmaddons.stats.StatsClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A player's fight log (from their stats page): every fight in their last
 * 100, newest first - when, who against, win or loss, how long, which PvP -
 * with the ratings they had in it. Scrolls with the mouse wheel.
 *
 * The ratings are the ones from that fight's own numbers. A single fight rarely has
 * enough shots, swaps or combos for every rating, so some show "~" (no data, 50
 * assumed), and K/D is just a win or a loss.
 *
 * Click a row for the ratings page behind it. Closes if you get combat-tagged.
 */
public class FightLogScreen extends Screen {

	private static final int ROW = 13;
	private static final int TABLE_WIDTH = 390;
	private static final int TABLE_TOP = 62;

	private final Screen parent;
	private FightHistory history;
	private final boolean individualGuns;
	private final StatsClient stats;
	/** Told the new history after an admin deletes a fight, so the page behind can refresh. */
	private final Consumer<FightHistory> onHistoryChanged;
	private String notice = null;
	/** null = every PvP. */
	private PvpCategory filter;
	private List<Row> rows = List.of();
	private int scroll = 0;

	private record Row(int index, FightData fight, Ratings.Result ratings) {}

	public FightLogScreen(Screen parent, FightHistory history, PvpCategory filter, boolean individualGuns,
			StatsClient stats, Consumer<FightHistory> onHistoryChanged) {
		super(Text.literal("Fight log - " + history.name()));
		this.parent = parent;
		this.history = history;
		this.filter = filter;
		this.individualGuns = individualGuns;
		this.stats = stats;
		this.onHistoryChanged = onHistoryChanged;
		rebuild();
	}

	/** Works out every shown fight's ratings (cheap: at most 100 fights). */
	private void rebuild() {
		List<FightData> all = history.fights();
		List<Row> out = new ArrayList<>();
		for (int i = 0; i < all.size(); i++) {
			FightData fight = all.get(i);
			if (filter != null && !filter.name().equals(fight.category())) continue;
			Ratings.Result ratings = FightStats.ratingsForFight(history, fight);
			out.add(new Row(i, fight, ratings));
		}
		rows = out;
		scroll = 0;
	}

	@Override
	protected void init() {
		// PvP filter chips.
		int chip = 44, gap = 3;
		PvpCategory[] categories = PvpCategory.values();
		int total = (categories.length + 1) * (chip + gap) - gap;
		int x = (width - total) / 2, y = 22;

		ButtonWidget all = addDrawableChild(ButtonWidget.builder(Text.literal("All"), b -> setFilter(null))
				.dimensions(x, y, chip, 20).build());
		all.active = filter != null;
		x += chip + gap;
		for (PvpCategory category : categories) {
			ButtonWidget button = addDrawableChild(ButtonWidget.builder(Text.literal(category.label), b -> setFilter(category))
					.dimensions(x, y, chip, 20).build());
			button.active = filter != category;
			x += chip + gap;
		}


		addDrawableChild(ButtonWidget.builder(ScreenTexts.BACK, b -> close())
				.dimensions(width / 2 - 75, height - 28, 150, 20).build());
	}

	private void setFilter(PvpCategory category) {
		filter = category;
		rebuild();
		clearAndInit();
	}

	@Override
	public void close() {
		client.setScreen(parent);
	}

	@Override
	public void tick() {
		CombatLock.closeIfInCombat(client);
	}

	/** In admin mode there is a red X column after the ratings. */
	private int tableWidth() {
		return TABLE_WIDTH + (AdminMode.isOn() ? 18 : 0);
	}

	private int left() {
		return (width - tableWidth()) / 2;
	}

	private int tableBottom() {
		return height - 44;
	}

	private int visibleHeight() {
		return tableBottom() - TABLE_TOP;
	}

	/** The row under the mouse, or -1. */
	private int rowAt(double mouseX, double mouseY) {
		if (mouseY < TABLE_TOP || mouseY >= tableBottom() || mouseX < left() || mouseX >= left() + tableWidth()) return -1;
		int i = ((int) mouseY - TABLE_TOP + scroll) / ROW;
		return i >= 0 && i < rows.size() ? i : -1;
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		context.fill(0, 0, width, height, Ui.PAGE);
		super.render(context, mouseX, mouseY, deltaTicks);
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 8, Ui.TEXT);

		int x0 = left();
		Ui.textRight(context, textRenderer, "#", x0 + 20, TABLE_TOP - 12, Ui.MUTED);
		context.drawTextWithShadow(textRenderer, "When", x0 + 26, TABLE_TOP - 12, Ui.MUTED);
		context.drawTextWithShadow(textRenderer, "Opponent", x0 + 88, TABLE_TOP - 12, Ui.MUTED);
		context.drawTextWithShadow(textRenderer, "W/L", x0 + 166, TABLE_TOP - 12, Ui.MUTED);
		Ui.textRight(context, textRenderer, "Time", x0 + 220, TABLE_TOP - 12, Ui.MUTED);
		context.drawTextWithShadow(textRenderer, "PvP", x0 + 228, TABLE_TOP - 12, Ui.MUTED);
		Ui.textRight(context, textRenderer, "Aim", x0 + 300, TABLE_TOP - 12, Ui.MUTED);
		Ui.textRight(context, textRenderer, "Mv", x0 + 338, TABLE_TOP - 12, Ui.MUTED);
		Ui.textRight(context, textRenderer, "Overall", x0 + 384, TABLE_TOP - 12, Ui.MUTED);

		int visible = visibleHeight();
		int full = rows.size() * ROW;
		scroll = Math.max(0, Math.min(scroll, Math.max(0, full - visible)));
		int hover = rowAt(mouseX, mouseY);

		context.enableScissor(x0 - 2, TABLE_TOP, x0 + tableWidth() + 2, tableBottom());
		for (int i = 0; i < rows.size(); i++) {
			int y = TABLE_TOP + i * ROW - scroll;
			if (y + ROW < TABLE_TOP || y > tableBottom()) continue;
			drawRow(context, rows.get(i), x0, y, i == hover);
		}
		context.disableScissor();

		if (full > visible) {
			int barH = Math.max(12, visible * visible / full);
			int barY = TABLE_TOP + (visible - barH) * scroll / (full - visible);
			context.fill(x0 + tableWidth() + 4, TABLE_TOP, x0 + tableWidth() + 6, tableBottom(), Ui.CARD);
			context.fill(x0 + tableWidth() + 4, barY, x0 + tableWidth() + 6, barY + barH, Ui.MUTED);
		}
		if (rows.isEmpty()) {
			context.drawCenteredTextWithShadow(textRenderer, Text.literal("No fights recorded yet."), width / 2, TABLE_TOP + 10, Ui.MUTED);
		}

		if (notice != null) context.drawCenteredTextWithShadow(textRenderer, Text.literal(notice), width / 2, height - 40, notice.startsWith("Couldn") ? Ui.BAD : Ui.GOOD);
		else context.drawCenteredTextWithShadow(textRenderer, Text.literal(footer(hover)), width / 2, height - 40, Ui.MUTED);
	}

	private void drawRow(DrawContext context, Row row, int x0, int y, boolean hovered) {
		FightData f = row.fight();
		if (hovered) context.fill(x0 - 2, y - 1, x0 + tableWidth() + 2, y + ROW - 1, 0x40FFFFFF);
		PvpCategory category = PvpCategory.fromName(f.category());
		int ty = y + 1;

		Ui.textRight(context, textRenderer, String.valueOf(row.index() + 1), x0 + 20, ty, Ui.MUTED);
		context.drawTextWithShadow(textRenderer, Format.time(f.endedAt()), x0 + 26, ty, Ui.LABEL);
		context.drawTextWithShadow(textRenderer, Ui.fit(textRenderer, f.opponent() != null ? f.opponent() : "(unknown)", 72), x0 + 88, ty,
				f.opponent() != null ? Ui.TEXT : Ui.MUTED);
		context.drawTextWithShadow(textRenderer, f.won() ? "W" : "L", x0 + 170, ty, f.won() ? Ui.GOOD : Ui.BAD);
		Ui.textRight(context, textRenderer, length(f), x0 + 220, ty, Ui.LABEL);
		context.drawTextWithShadow(textRenderer, category != null ? category.label : "?", x0 + 228, ty,
				category != null ? Ui.accent(category) : Ui.MUTED);

		Ratings.Result r = row.ratings();
		rating(context, r != null ? r.aim() : null, r != null && r.aimAssumed(), x0 + 300, ty);
		rating(context, r != null ? r.movement() : null, r != null && r.movementAssumed(), x0 + 338, ty);
		rating(context, r != null ? r.overall() : null, r != null && r.aimAssumed() && r.movementAssumed(), x0 + 384, ty);
		if (AdminMode.isOn()) context.drawTextWithShadow(textRenderer, Text.literal("\u2716").formatted(Formatting.RED), x0 + TABLE_WIDTH + 4, ty, Ui.BAD);
	}

	/** assumed: no data for it in the fight, so it is shown as an estimate (dim, with a ~). */
	private void rating(DrawContext context, Double value, boolean assumed, int right, int y) {
		if (value == null) Ui.textRight(context, textRenderer, "-", right, y, Ui.MUTED);
		else if (assumed) Ui.textRight(context, textRenderer, "~" + Math.round(value), right, y, Ui.MUTED);
		else Ui.textRight(context, textRenderer, String.valueOf(Math.round(value)), right, y, Ui.scoreColor(value));
	}

	private static String length(FightData f) {
		long seconds = Math.max(0, (f.endedAt() - f.startedAt()) / 1000);
		return String.format("%d:%02d", seconds / 60, seconds % 60);
	}

	/** Says what a dim ~ rating means, for fights that have one. */
	private static String assumedNote(Ratings.Result r) {
		return r != null && (r.aimAssumed() || r.movementAssumed()) ? "  ·  ~ = no data, 50 assumed" : "";
	}

	/** One line under the table: about the hovered fight, or how many fights there are. */
	private String footer(int hover) {
		if (hover < 0) return rows.size() + (rows.size() == 1 ? " fight" : " fights");
		FightData f = rows.get(hover).fight();
		int shots = 0;
		for (GunRow g : f.gunRows()) shots += g.shots();
		int swaps = 0;
		for (SwapRow s : f.swapRows()) if ("SUCCESS".equals(s.result())) swaps++;
		return (f.won() ? "Won" : "Lost") + " vs " + (f.opponent() != null ? f.opponent() : "an unknown opponent")
				+ "  ·  " + length(f) + "  ·  " + shots + " shots  ·  " + swaps + " swaps" + assumedNote(rows.get(hover).ratings());
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (super.mouseClicked(click, doubled)) return true;
		if (click.button() != 0) return false;
		int i = rowAt(click.x(), click.y());
		if (i < 0) return false;
		// The red X column (admin mode) deletes the fight; the rest of the row opens its ratings.
		if (AdminMode.isOn() && click.x() >= left() + TABLE_WIDTH) confirmDelete(rows.get(i));
		else openRatings(rows.get(i));
		return true;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		scroll = Math.max(0, scroll - (int) Math.round(verticalAmount * ROW * 2));
		return true;
	}

	// ---- Admin: delete one fight ----

	private void confirmDelete(Row row) {
		FightData fight = row.fight();
		if (fight.fightKey() == null) {
			notice = "Couldn't delete: the stats server needs updating (no fight ids)";
			return;
		}
		String vs = fight.opponent() != null ? " vs " + fight.opponent() : "";
		client.setScreen(new ConfirmScreen(yes -> {
			client.setScreen(this);
			if (yes) delete(fight);
		},
				Text.literal("Delete this fight?").formatted(Formatting.RED),
				Text.literal("This deletes the " + (fight.won() ? "win" : "loss") + vs + " (" + Format.time(fight.endedAt()) + ") from "
						+ history.name() + "'s stats on the server: its swaps, shots and combos too.\n\nIt cannot be undone.")));
	}

	private void delete(FightData fight) {
		notice = "Deleting...";
		stats.deleteFight(history.uuid(), fight.fightKey()).whenComplete((result, error) -> client.execute(() -> {
			if (error != null) {
				notice = "Couldn't delete: " + Format.error(error);
				return;
			}
			List<FightData> remaining = new ArrayList<>(history.fights());
			remaining.remove(fight);
			history = new FightHistory(history.uuid(), history.name(), history.firstSeen(), history.lastSeen(), remaining);
			notice = "Deleted the fight";
			rebuild();
			onHistoryChanged.accept(history);
		}));
	}

	/** The ratings page (what went into each rating) for this row's numbers. */
	private void openRatings(Row row) {
		FightData fight = row.fight();
		PvpCategory category = PvpCategory.fromName(fight.category());
		if (category == null) return;
		PlayerDetail detail = FightStats.detail(history, List.of(fight));
		String vs = fight.opponent() != null ? " vs " + fight.opponent() : "";
		Text subtitle = Text.literal((fight.won() ? "Won" : "Lost") + vs + " (" + Format.time(fight.endedAt()) + ")  ·  this fight alone");
		client.setScreen(new InsightScreen(this, detail, category, Insight.RATING, subtitle, individualGuns));
	}
}
