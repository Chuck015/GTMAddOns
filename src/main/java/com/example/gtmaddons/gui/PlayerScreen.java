package com.example.gtmaddons.gui;

import com.example.gtmaddons.AdminMode;
import com.example.gtmaddons.GTMAddOnsClient;
import com.example.gtmaddons.PvpCategory;
import com.example.gtmaddons.gui.InsightScreen.Insight;
import com.example.gtmaddons.stats.FightFilter;
import com.example.gtmaddons.stats.FightStats;
import com.example.gtmaddons.stats.PlayerStats.FightHistory;
import com.example.gtmaddons.stats.PlayerStats.PlayerDetail;
import com.example.gtmaddons.stats.StatsClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.HashMap;
import java.util.Map;

/**
 * One player's profile, with a tab per PvP category, shown as the
 * csstats-style Overview (see StatsOverview). Each category has its own
 * accent color, shown on its tab.
 *
 * Every card on the Overview is clickable and opens an insight page for
 * that stat (see InsightScreen) with the numbers behind it.
 *
 * All stats come from the player's fights (combat tag to kill or death).
 * The buttons above Back switch between their last 25, 50 or 100 fights
 * (loaded from the backend the first time each is picked), and between
 * guns grouped by type (Rifle, Sniper, SMG...) and every gun on its own.
 * The subtitle shows how many fights and the kills and deaths.
 *
 * Opens on the tab that was open last (for Personal Stats, mod != null,
 * saved in Settings). Closes itself if you get combat-tagged.
 */
public class PlayerScreen extends Screen {

	private static final int TABS_Y = 44;
	private static final int TAB_WIDTH = 60;
	private static final int TAB_GAP = 4;
	/** How many of the player's last fights each view button shows; the first is what's passed in. */
	private static final int[] FIGHT_VIEWS = { 25, 50, 100 };

	private final Screen parent;
	private final StatsClient stats;
	private final GTMAddOnsClient mod;
	private final String uuid;
	/** Views loaded so far, by key(category, fights). */
	private final Map<String, PlayerDetail> views = new HashMap<>();
	/** What's on screen, and which view it is; blank while the picked view loads. */
	private PlayerDetail detail;
	private String shownKey;
	/** The chosen 25 / 50 / 100 (remembered, see FightViews); the detail passed in was loaded for it. */
	private int viewFights = FightViews.get();
	/** "Loading..." or an error, shown instead of the subtitle. */
	private String status = null;
	private PvpCategory selected;
	/** Tab last opened on another player's page (Personal Stats saves its own in Settings). */
	private static PvpCategory lastViewedTab = PvpCategory.WING;
	/** The player's raw last 100 fights, loaded the first time the fight log or a filter is opened. */
	private FightHistory history = null;
	/** A custom selection (Filter...), or null for the 25 / 50 / 100 quick views. While set, viewFights is -1. */
	private FightFilter filter = null;
	/** Set when an admin change (a deleted fight) means what's shown is out of date; redone on the next tick. */
	private boolean needsRefresh = false;
	/** Told after an admin deletes data here, so the page behind (the leaderboard) can reload. */
	private Runnable onAdminChange = () -> {};
	/** Every gun on its own instead of grouped by type (Rifle, Sniper, SMG...). */
	private static boolean showIndividualGuns = false;
	/** How far the Overview is scrolled down, when it doesn't fit. */
	private int scroll = 0;
	/** The insight page of the card under the mouse, as of the last frame. */
	private Insight hovered = null;

	/**
	 * The tab a stats page opens on - whichever was open last. Load the
	 * player's last 25 fights of this category to pass in as detail.
	 */
	/** Makes the next page opened on another player start on this tab (the leaderboard's). */
	public static void rememberTab(PvpCategory tab) {
		lastViewedTab = tab;
	}

	public static PvpCategory openingTab(GTMAddOnsClient mod) {
		return mod != null ? mod.getStatsTab() : lastViewedTab;
	}

	/**
	 * detail is the player's last 25 fights of openingTab(mod). With mod set,
	 * this is your own Personal Stats page.
	 */
	public PlayerScreen(Screen parent, PlayerDetail detail, StatsClient stats, GTMAddOnsClient mod) {
		super(Text.literal(mod != null ? "Personal Stats - " + detail.name() : detail.name()));
		this.parent = parent;
		this.stats = stats;
		this.mod = mod;
		this.uuid = detail.uuid();
		this.selected = openingTab(mod);
		this.detail = detail;
		this.shownKey = key(selected, viewFights);
		views.put(shownKey, detail);
	}

	private static String key(PvpCategory category, int fights) {
		return category.name() + "|" + fights;
	}

	@Override
	public void tick() {
		CombatLock.closeIfInCombat(client);
		if (needsRefresh) {
			needsRefresh = false;
			show();
		}
	}

	/** Called after an admin deletes data from this page. */
	public void setOnAdminChange(Runnable onAdminChange) {
		this.onAdminChange = onAdminChange;
	}

	@Override
	protected void init() {
		addViewControls();
		// One tab per kind of PvP; the selected one is greyed out and underlined.
		PvpCategory[] categories = PvpCategory.values();
		int tabsLeft = tabsLeft();
		for (int i = 0; i < categories.length; i++) {
			PvpCategory category = categories[i];
			ButtonWidget tab = addDrawableChild(ButtonWidget.builder(Text.literal(category.label), b -> selectTab(category))
					.dimensions(tabsLeft + i * (TAB_WIDTH + TAB_GAP), TABS_Y, TAB_WIDTH, 20).build());
			tab.active = category != selected;
		}
		addDrawableChild(ButtonWidget.builder(ScreenTexts.BACK, b -> close())
				.dimensions(width / 2 - 75, height - 28, 150, 20).build());
		if (AdminMode.isOn()) {
			addDrawableChild(ButtonWidget.builder(Text.literal("Admin: delete data").formatted(Formatting.RED), b -> confirmDeleteAll())
					.dimensions(width - 122, 4, 118, 16)
					.build());
		}
	}

	// ---- Admin mode ----

	private void confirmDeleteAll() {
		client.setScreen(new ConfirmScreen(yes -> {
			client.setScreen(this);
			if (yes) deleteAll();
		},
				Text.literal("Delete " + detail.name() + "'s data?").formatted(Formatting.RED),
				Text.literal("This deletes ALL of " + detail.name() + "'s stats from the server: every fight, swap, gun and combo total, in every PvP.\n\nIt cannot be undone.")));
	}

	private void deleteAll() {
		String name = detail.name();
		status = "Deleting " + name + "'s data...";
		stats.deletePlayerData(uuid).whenComplete((result, error) -> client.execute(() -> {
			if (error != null) {
				status = "Couldn't delete: " + Format.error(error);
				return;
			}
			views.clear();
			history = null;
			onAdminChange.run();
			show();
			status = "Deleted " + name + "'s data (" + result.deletedFights() + " fights)";
		}));
	}

	private int tabsLeft() {
		int count = PvpCategory.values().length;
		return (width - (count * TAB_WIDTH + (count - 1) * TAB_GAP)) / 2;
	}

	/**
	 * Above the Back button, two rows:
	 *   [25 fights][50 fights][100 fights]  [Filter...]
	 *   [Fight log]  [Individual gun stats: OFF]
	 * Filter picks a custom number of fights (1-100) and opponents; while
	 * one is on, none of the three quick views is selected.
	 */
	private void addViewControls() {
		int viewWidth = 58, gap = 4, filterWidth = 84;
		int rowWidth = FIGHT_VIEWS.length * (viewWidth + gap) + filterWidth;
		int x = (width - rowWidth) / 2;
		int y = height - 76;

		for (int i = 0; i < FIGHT_VIEWS.length; i++) {
			int fights = FIGHT_VIEWS[i];
			ButtonWidget view = addDrawableChild(ButtonWidget.builder(Text.literal(fights + " fights"), b -> selectView(fights))
					.dimensions(x + i * (viewWidth + gap), y, viewWidth, 20)
					.build());
			view.active = filter != null || viewFights != fights;
		}
		addDrawableChild(ButtonWidget.builder(Text.literal(filter != null ? "Filter: ON" : "Filter..."), b -> withHistory(this::openFilter))
				.dimensions(x + rowWidth - filterWidth, y, filterWidth, 20)
				.build());

		y = height - 52;
		int logWidth = 80, gunsWidth = 140;
		int row2 = logWidth + gap + gunsWidth;
		x = (width - row2) / 2;
		addDrawableChild(ButtonWidget.builder(Text.literal("Fight log"), b -> withHistory(this::openLog))
				.dimensions(x, y, logWidth, 20)
				.build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Individual gun stats: " + (showIndividualGuns ? "ON" : "OFF")), b -> {
			showIndividualGuns = !showIndividualGuns;
			clearAndInit();
		}).dimensions(x + logWidth + gap, y, gunsWidth, 20)
				.build());
	}

	private void selectView(int fights) {
		filter = null;
		viewFights = fights;
		FightViews.set(fights);
		show();
	}

	// ---- Fight log and custom filter (need the player's raw fights, loaded once) ----

	/** Runs `then` once this player's raw fights are loaded (loading them first if need be). */
	private void withHistory(Runnable then) {
		if (history != null) {
			then.run();
			return;
		}
		status = "Loading fights...";
		stats.fetchFights(uuid).whenComplete((result, error) -> client.execute(() -> {
			if (client.currentScreen != this) return;
			if (error != null) {
				String why = Format.error(error);
				status = "Couldn't load fights: " + why + (why.contains("404") ? " (the stats server needs updating)" : "");
				return;
			}
			history = result;
			status = null;
			then.run();
		}));
	}

	private void openFilter() {
		FightFilter current = filter != null ? filter : FightFilter.last(viewFights > 0 ? viewFights : FIGHT_VIEWS[0]);
		client.setScreen(new FightFilterScreen(this, selected.label, FightFilter.opponents(history.fights(), selected), current,
				picked -> picked.apply(history.fights(), selected).size(), this::applyFilter));
	}

	private void applyFilter(FightFilter picked) {
		filter = picked;
		viewFights = -1;
		show();
	}

	private void openLog() {
		client.setScreen(new FightLogScreen(this, history, selected, showIndividualGuns, stats, updated -> {
			// An admin deleted a fight: what's on this page is out of date.
			history = updated;
			views.clear();
			needsRefresh = true;
			onAdminChange.run();
		}));
	}

	private void selectTab(PvpCategory category) {
		selected = category;
		if (mod != null) mod.setStatsTab(category);
		else lastViewedTab = category;
		show();
	}

	/**
	 * Shows the picked tab's last `viewFights` fights of that PvP, loading
	 * them from the backend the first time. Until they arrive the page is
	 * blank apart from "Loading...", so another tab's stats never show.
	 */
	private void show() {
		// A custom selection is worked out here from the raw fights - no loading.
		if (filter != null && history != null) {
			detail = FightStats.detail(history, filter.apply(history.fights(), selected));
			shownKey = key(selected, viewFights);
			status = null;
			clearAndInit();
			return;
		}
		String wanted = key(selected, viewFights);
		PlayerDetail loaded = views.get(wanted);
		if (loaded != null) {
			detail = loaded;
			shownKey = wanted;
			status = null;
			clearAndInit();
			return;
		}
		int fights = viewFights;
		PvpCategory category = selected;
		status = "Loading the last " + fights + " " + category.label + " fights...";
		clearAndInit();
		stats.fetchPlayer(uuid, fights, category).whenComplete((result, error) -> client.execute(() -> {
			if (client.currentScreen != this) return;
			boolean stillWanted = key(selected, viewFights).equals(wanted);
			if (error != null) {
				if (stillWanted) status = "Couldn't load the last " + fights + " " + category.label + " fights: " + Format.error(error);
				return;
			}
			views.put(wanted, result);
			// Only show it if that view is still the one picked.
			if (stillWanted) {
				detail = result;
				shownKey = wanted;
				status = null;
			}
		}));
	}

	/** Whether what's loaded is the tab and fights picked (false while another loads). */
	private boolean showingPicked() {
		return key(selected, viewFights).equals(shownKey);
	}

	@Override
	public void close() {
		client.setScreen(parent);
	}

	// ---- Drawing ----

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		// A dark page behind everything, like csstats.
		context.fill(0, 0, width, height, Ui.PAGE);
		super.render(context, mouseX, mouseY, deltaTicks);
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 8, Ui.TEXT);

		// Underline the selected tab in its category's color.
		int tabX = tabsLeft() + selected.ordinal() * (TAB_WIDTH + TAB_GAP);
		context.fill(tabX + 2, TABS_Y + 21, tabX + TAB_WIDTH - 2, TABS_Y + 23, Ui.accent(selected));

		hovered = null;
		if (status != null) context.drawCenteredTextWithShadow(textRenderer, Text.literal(status), width / 2, 19, Ui.OK);
		// Another tab's (or fight count's) stats never show while the picked one loads.
		if (!showingPicked()) return;
		if (status == null) context.drawCenteredTextWithShadow(textRenderer, subtitle(), width / 2, 19, Ui.MUTED);
		drawOverview(context, mouseX, mouseY);
	}

	/**
	 * The csstats-style Overview (see StatsOverview) below the tabs. If it
	 * doesn't fit above the buttons it scrolls with the mouse wheel, with a
	 * thin scrollbar on the right. Remembers which card the mouse is on.
	 */
	private void drawOverview(DrawContext context, int mouseX, int mouseY) {
		int top = TABS_Y + 28, bottom = height - 82;
		int w = Math.min(width - 16, 660), x = (width - w) / 2;
		int visible = bottom - top, full = StatsOverview.height();
		scroll = Math.max(0, Math.min(scroll, full - visible));
		boolean mouseIn = mouseY >= top && mouseY < bottom;

		context.enableScissor(x - 1, top, x + w + 1, bottom);
		var matrices = context.getMatrices();
		matrices.pushMatrix();
		matrices.translate(0, -scroll);
		hovered = StatsOverview.draw(context, textRenderer, detail, selected, showIndividualGuns,
				x, top, w, mouseX, mouseIn ? mouseY + scroll : Integer.MIN_VALUE);
		matrices.popMatrix();
		context.disableScissor();

		if (full > visible) {
			int barH = Math.max(12, visible * visible / full);
			int barY = top + (visible - barH) * scroll / (full - visible);
			context.fill(x + w + 3, top, x + w + 5, bottom, Ui.CARD);
			context.fill(x + w + 3, barY, x + w + 5, barY + barH, Ui.MUTED);
		}
	}

	/** Clicking a card opens its insight page. */
	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (super.mouseClicked(click, doubled)) return true;
		if (click.button() != 0 || hovered == null || !showingPicked()) return false;
		client.setScreen(new InsightScreen(this, detail, selected, hovered, subtitle(), showIndividualGuns));
		return true;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		scroll = Math.max(0, scroll - (int) Math.round(verticalAmount * 16));
		return true;
	}

	/**
	 * Which fights these stats are from, and how they went - the K/D is one
	 * of the core stats, e.g. "Last 25 JP fights · K/D 1.78 · 16 kills · 9 deaths".
	 */
	private Text subtitle() {
		String opponents = filter != null && history != null ? filter.describeOpponents(history.fights()) : "";
		if (detail.fights() == 0) {
			return Text.literal(filter != null
					? "No " + selected.label + " fights match" + (opponents.isEmpty() ? "" : " " + opponents)
					: "No " + selected.label + " fights yet");
		}
		MutableText out;
		if (filter != null) {
			// A custom selection: how many of up to how many, and against whom.
			out = Text.literal(detail.fights() + " " + selected.label + (detail.fights() == 1 ? " fight" : " fights")
					+ (opponents.isEmpty() ? "" : " " + opponents) + " (max " + filter.limit() + ")");
		} else {
			out = Text.literal(detail.fights() == 1
					? "Last " + selected.label + " fight"
					: "Last " + detail.fights() + " " + selected.label + " fights");
		}
		double kd = kd(detail.kills(), detail.deaths());
		out.append(Text.literal("  ·  "));
		out.append(Text.literal(String.format("K/D %.2f", kd)).withColor(kdColor(kd)).formatted(Formatting.BOLD));
		out.append(Text.literal(String.format("  ·  %d %s  ·  %d %s", detail.kills(), detail.kills() == 1 ? "kill" : "kills",
				detail.deaths(), detail.deaths() == 1 ? "death" : "deaths")));
		return out;
	}

	/** Kills per death; with no deaths it's just the kills. */
	static double kd(long kills, long deaths) {
		return deaths > 0 ? (double) kills / deaths : kills;
	}

	/** Green from 1.0 (more kills than deaths), yellow from 0.7, red below. */
	static int kdColor(double kd) {
		return kd >= 1.0 ? Ui.GOOD : kd >= 0.7 ? Ui.OK : Ui.BAD;
	}
}
