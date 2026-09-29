package com.example.gtmaddons.gui;

import com.example.gtmaddons.AdminMode;
import com.example.gtmaddons.GTMAddOnsClient;
import com.example.gtmaddons.PvpCategory;
import com.example.gtmaddons.rating.Ratings;
import com.example.gtmaddons.stats.FightFilter;
import com.example.gtmaddons.stats.FightFilter.Opponent;
import com.example.gtmaddons.stats.Leaderboard;
import com.example.gtmaddons.stats.PlayerStats.LeaderboardData;
import com.example.gtmaddons.stats.PlayerStats.OpponentCount;
import com.example.gtmaddons.stats.PlayerStats.PlayerDetail;
import com.example.gtmaddons.stats.StatsClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Player Stats: a leaderboard of ratings. One PvP category at a time (Ground,
 * Wing, JP, Air - the tabs), everyone ranked by their Overall rating, highest
 * at the top; players who can't be rated yet are listed below without a rank
 * (see Leaderboard). Ratings are the same Aim / Movement (Melee on JP) / Overall
 * as on a player's page, over each player's newest N fights of that PvP.
 *
 * The same filters as Personal Stats: 25 / 50 / 100 fights, or Filter... for any
 * number from 1 to 100 and only fights against chosen opponents (each player's
 * fights against them). Click a player to open their page on this tab.
 *
 * In Admin mode every row also has a red X that deletes all of that player's stats
 * data from the server (after a confirmation) - for when someone has boosted.
 */
public class PlayersScreen extends Screen {

	public static final int DEFAULT_FIGHTS = 25;
	/** The quick views, as on a player's page. */
	private static final int[] VIEWS = { 25, 50, 100 };

	private static final int TABS_Y = 32;
	private static final int TAB_WIDTH = 60;
	private static final int TAB_GAP = 4;
	private static final int VIEWS_Y = 56;
	private static final int TABLE_TOP = 96;
	private static final int ROW_HEIGHT = 20;
	private static final int DELETE_WIDTH = 18;

	private final Screen parent;
	private final StatsClient stats;
	private final GTMAddOnsClient mod;
	private PvpCategory category;
	/** null while a request is loading. */
	private LeaderboardData board;
	private List<Leaderboard.Entry> entries;
	/** The quick view in use (25 / 50 / 100), or -1 while a custom filter is on. */
	private int viewFights;
	private FightFilter filter = null;
	private final Map<String, LeaderboardData> cache = new HashMap<>();
	private int page = 0;
	/** Loading / error text, and a note about the last admin delete. */
	private String status = null;
	private String notice = null;
	/** An admin deleted something on a player's page opened from here: reload when we're back. */
	private boolean stale = false;

	/** parent is where Back returns to (e.g. the main menu), or null to close. `first` is already loaded. */
	public PlayersScreen(Screen parent, StatsClient stats, GTMAddOnsClient mod, PvpCategory category, LeaderboardData first) {
		super(Text.literal("GTMAddOns - Player Stats"));
		this.parent = parent;
		this.stats = stats;
		this.mod = mod;
		this.category = category;
		this.viewFights = first.fights();
		setBoard(first);
		cache.put(requestKey(), first);
	}

	private void setBoard(LeaderboardData data) {
		board = data;
		entries = Leaderboard.rank(data.players(), category);
		page = 0;
	}

	private int effectiveFights() {
		return filter != null ? filter.limit() : viewFights;
	}

	private String requestKey() {
		return category.name() + "|" + effectiveFights() + "|" + (filter != null ? String.join(",", filter.opponents().stream().sorted().toList()) : "");
	}

	@Override
	public void close() {
		client.setScreen(parent);
	}

	@Override
	public void tick() {
		CombatLock.closeIfInCombat(client);
		if (stale) {
			stale = false;
			cache.clear();
			load();
		}
	}

	private int rowWidth() {
		return Math.min(380, width - 24);
	}

	private int rowsPerPage() {
		return Math.max(3, (height - TABLE_TOP - 38) / ROW_HEIGHT);
	}

	private int tabsLeft() {
		int count = PvpCategory.values().length;
		return (width - (count * TAB_WIDTH + (count - 1) * TAB_GAP)) / 2;
	}

	// ---- Loading ----

	/** Shows the leaderboard for the picked tab and filter, from the cache or the backend. */
	private void load() {
		String wanted = requestKey();
		LeaderboardData cached = cache.get(wanted);
		if (cached != null) {
			setBoard(cached);
			status = null;
			clearAndInit();
			return;
		}
		board = null;
		entries = List.of();
		status = "Loading the " + category.label + " leaderboard...";
		clearAndInit();
		PvpCategory asked = category;
		stats.fetchLeaderboard(asked, effectiveFights(), filter != null ? filter.opponents() : Set.of()).whenComplete((result, error) -> client.execute(() -> {
			// A newer pick replaced this one while it loaded.
			if (!wanted.equals(requestKey())) return;
			if (error != null) {
				String why = Format.error(error);
				status = "Couldn't load the leaderboard: " + why + (why.contains("404") ? " (the stats server needs updating)" : "");
			} else {
				cache.put(wanted, result);
				setBoard(result);
				status = null;
			}
			clearAndInit();
		}));
	}

	private void selectTab(PvpCategory picked) {
		category = picked;
		mod.setLeaderboardTab(picked);
		notice = null;
		load();
	}

	private void selectView(int fights) {
		filter = null;
		viewFights = fights;
		notice = null;
		load();
	}

	private void openFilter() {
		if (board == null) return;
		List<Opponent> opponents = new ArrayList<>();
		for (OpponentCount o : board.opponents()) opponents.add(new Opponent(o.key(), o.name(), o.fights(), o.fights()));
		FightFilter current = filter != null ? filter : FightFilter.last(viewFights);
		client.setScreen(new FightFilterScreen(this, category.label, opponents, current, null, picked -> {
			filter = picked;
			viewFights = -1;
			notice = null;
			load();
		}));
	}

	// ---- Rows ----

	@Override
	protected void init() {
		// One tab per kind of PvP; the selected one is greyed out and underlined.
		PvpCategory[] categories = PvpCategory.values();
		for (int i = 0; i < categories.length; i++) {
			PvpCategory tab = categories[i];
			ButtonWidget button = addDrawableChild(ButtonWidget.builder(Text.literal(tab.label), b -> selectTab(tab))
					.dimensions(tabsLeft() + i * (TAB_WIDTH + TAB_GAP), TABS_Y, TAB_WIDTH, 20).build());
			button.active = tab != category;
		}

		// [25 fights][50 fights][100 fights][Filter...]
		int viewWidth = 58, gap = 4, filterWidth = 84;
		int rowWidth = VIEWS.length * (viewWidth + gap) + filterWidth;
		int x = (width - rowWidth) / 2;
		for (int i = 0; i < VIEWS.length; i++) {
			int fights = VIEWS[i];
			ButtonWidget view = addDrawableChild(ButtonWidget.builder(Text.literal(fights + " fights"), b -> selectView(fights))
					.dimensions(x + i * (viewWidth + gap), VIEWS_Y, viewWidth, 20)
					.build());
			view.active = filter != null || viewFights != fights;
		}
		ButtonWidget filterButton = addDrawableChild(ButtonWidget.builder(Text.literal(filter != null ? "Filter: ON" : "Filter..."), b -> openFilter())
				.dimensions(x + rowWidth - filterWidth, VIEWS_Y, filterWidth, 20)
				.build());
		filterButton.active = board != null;

		// Each row is a blank button (for the click and hover); render() draws the columns on top of it.
		int left = (width - rowWidth()) / 2;
		int perPage = rowsPerPage();
		int start = page * perPage, end = Math.min(entries.size(), start + perPage);
		boolean admin = AdminMode.isOn();
		for (int i = start; i < end; i++) {
			Leaderboard.Entry entry = entries.get(i);
			int y = TABLE_TOP + (i - start) * ROW_HEIGHT;
			addDrawableChild(ButtonWidget.builder(Text.empty(), b -> openPlayer(entry.detail()))
					.dimensions(left, y, rowWidth() - (admin ? DELETE_WIDTH + 2 : 0), 20)
					.narrationSupplier(narration -> Text.literal(entry.detail().name()))
					.build());
			if (admin) {
				addDrawableChild(ButtonWidget.builder(Text.literal("✖").formatted(Formatting.RED), b -> confirmDelete(entry.detail()))
						.dimensions(left + rowWidth() - DELETE_WIDTH, y, DELETE_WIDTH, 20)
						.build());
			}
		}

		int bottom = height - 28;
		ButtonWidget prev = addDrawableChild(ButtonWidget.builder(Text.literal("< Prev"), b -> changePage(-1))
				.dimensions(width / 2 - 155, bottom, 70, 20).build());
		prev.active = page > 0;
		addDrawableChild(ButtonWidget.builder(parent != null ? ScreenTexts.BACK : ScreenTexts.DONE, b -> close())
				.dimensions(width / 2 - 75, bottom, 150, 20).build());
		ButtonWidget next = addDrawableChild(ButtonWidget.builder(Text.literal("Next >"), b -> changePage(1))
				.dimensions(width / 2 + 85, bottom, 70, 20).build());
		next.active = end < entries.size();
	}

	private void changePage(int delta) {
		page += delta;
		clearAndInit();
	}

	private void openPlayer(PlayerDetail player) {
		status = "Loading " + player.name() + "...";
		PlayerScreen.rememberTab(category);
		stats.fetchPlayer(player.uuid(), 25, PlayerScreen.openingTab(null)).whenComplete((detail, error) -> client.execute(() -> {
			if (client.currentScreen != this) return;
			if (error != null) {
				status = "Couldn't load " + player.name() + ": " + Format.error(error);
			} else {
				status = null;
				PlayerScreen screen = new PlayerScreen(this, detail, stats, null);
				screen.setOnAdminChange(() -> stale = true);
				client.setScreen(screen);
			}
		}));
	}

	// ---- Admin ----

	private void confirmDelete(PlayerDetail player) {
		client.setScreen(new ConfirmScreen(yes -> {
			client.setScreen(this);
			if (yes) delete(player);
		},
				Text.literal("Delete " + player.name() + "'s data?").formatted(Formatting.RED),
				Text.literal("This deletes ALL of " + player.name() + "'s stats from the server: every fight, swap, gun and combo total, "
						+ "in every PvP - not just this leaderboard.\n\nIt cannot be undone.")));
	}

	private void delete(PlayerDetail player) {
		status = "Deleting " + player.name() + "'s data...";
		clearAndInit();
		stats.deletePlayerData(player.uuid()).whenComplete((result, error) -> client.execute(() -> {
			if (error != null) {
				status = "Couldn't delete: " + Format.error(error);
				clearAndInit();
				return;
			}
			notice = "Deleted " + player.name() + "'s data (" + result.deletedFights() + " fights)";
			cache.clear();
			load();
		}));
	}

	// ---- Drawing ----

	/** Right edges of the number columns (Overall, Aim, Movement, K/D, Fights), inwards from the row's right edge. */
	private int[] columnRights(int left) {
		int right = left + rowWidth() - 8 - (AdminMode.isOn() ? DELETE_WIDTH + 2 : 0);
		return new int[] { right - 158, right - 118, right - 80, right - 40, right };
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		int left = (width - rowWidth()) / 2;
		int perPage = rowsPerPage();
		int start = page * perPage, end = Math.min(entries.size(), start + perPage);

		// Panel behind the table, drawn before the row buttons.
		int panelBottom = TABLE_TOP + Math.max(1, end - start) * ROW_HEIGHT + 2;
		Ui.panel(context, left - 6, TABLE_TOP - 20, rowWidth() + 12, panelBottom - (TABLE_TOP - 20), Ui.accent(category));
		super.render(context, mouseX, mouseY, deltaTicks);

		// Underline the selected tab in its category's color.
		int tabX = tabsLeft() + category.ordinal() * (TAB_WIDTH + TAB_GAP);
		context.fill(tabX + 2, TABS_Y + 21, tabX + TAB_WIDTH - 2, TABS_Y + 23, Ui.accent(category));

		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 8, Ui.TEXT);
		context.drawCenteredTextWithShadow(textRenderer, Text.literal(status != null ? status : subtitle()), width / 2, 20,
				status != null ? Ui.OK : Ui.MUTED);

		int[] rights = columnRights(left);
		String[] headings = { "Overall", Ratings.aimShort(category), Ratings.secondShort(category), "K/D", "Fights" };
		int headY = TABLE_TOP - 13;
		Ui.textRight(context, textRenderer, "#", left + 22, headY, Ui.MUTED);
		context.drawTextWithShadow(textRenderer, "Player", left + 28, headY, Ui.MUTED);
		for (int i = 0; i < headings.length; i++) Ui.textRight(context, textRenderer, headings[i], rights[i], headY, Ui.MUTED);

		for (int i = start; i < end; i++) drawRow(context, entries.get(i), left, TABLE_TOP + (i - start) * ROW_HEIGHT + 6, rights);

		if (board != null && entries.isEmpty()) {
			context.drawCenteredTextWithShadow(textRenderer,
					Text.literal(filter != null ? "No " + category.label + " fights match that filter." : "No " + category.label + " fights recorded yet."),
					width / 2, TABLE_TOP + 6, Ui.MUTED);
		}
		if (notice != null) context.drawCenteredTextWithShadow(textRenderer, Text.literal(notice), width / 2, height - 42, Ui.GOOD);
		else if (AdminMode.isOn()) {
			context.drawCenteredTextWithShadow(textRenderer, Text.literal("ADMIN MODE"), width / 2, height - 42, Ui.BAD);
		}
	}

	private void drawRow(DrawContext context, Leaderboard.Entry entry, int left, int y, int[] rights) {
		PlayerDetail p = entry.detail();
		boolean ranked = entry.ranked();
		Ui.textRight(context, textRenderer, ranked ? String.valueOf(entry.rank()) : "-", left + 22, y, Ui.MUTED);
		context.drawTextWithShadow(textRenderer, Ui.fit(textRenderer, p.name(), rights[0] - 44 - (left + 28)), left + 28, y, ranked ? Ui.TEXT : Ui.MUTED);

		Ratings.Result r = entry.ratings();
		rating(context, r.overall(), rights[0], y);
		rating(context, r.aim(), rights[1], y);
		rating(context, r.movement(), rights[2], y);
		double kd = PlayerScreen.kd(p.kills(), p.deaths());
		Ui.textRight(context, textRenderer, String.format("%.2f", kd), rights[3], y, PlayerScreen.kdColor(kd));
		Ui.textRight(context, textRenderer, String.valueOf(p.fights()), rights[4], y, Ui.LABEL);
	}

	private void rating(DrawContext context, Double value, int right, int y) {
		if (value == null) Ui.textRight(context, textRenderer, "-", right, y, Ui.MUTED);
		else Ui.textRight(context, textRenderer, String.valueOf(Math.round(value)), right, y, Ui.rateColor(value));
	}

	/** "Last 25 fights  ·  12 players  ·  page 1 of 2", or the filter in words. */
	private String subtitle() {
		if (board == null) return "";
		int pages = Math.max(1, (entries.size() + rowsPerPage() - 1) / rowsPerPage());
		String scope = "each player's last " + effectiveFights() + " fights";
		if (filter != null && filter.hasOpponents()) {
			Map<String, String> names = new HashMap<>();
			for (OpponentCount o : board.opponents()) names.put(o.key(), o.name());
			List<String> shown = new ArrayList<>();
			for (String key : filter.opponents().stream().sorted().toList()) shown.add(names.getOrDefault(key, key));
			scope += " vs " + (shown.size() <= 3 ? String.join(", ", shown) : String.join(", ", shown.subList(0, 3)) + " and " + (shown.size() - 3) + " more");
		}
		return category.label + " leaderboard  ·  " + scope + "  ·  " + entries.size() + (entries.size() == 1 ? " player" : " players")
				+ "  ·  page " + (page + 1) + " of " + pages;
	}
}
