package com.example.gtmaddons.gui;

import com.example.gtmaddons.PvpCategory;
import com.example.gtmaddons.gun.GunType;
import com.example.gtmaddons.rating.RatingWeights;
import com.example.gtmaddons.rating.Ratings;
import com.example.gtmaddons.stats.PlayerStats.AirSwapStat;
import com.example.gtmaddons.stats.PlayerStats.Averages;
import com.example.gtmaddons.stats.PlayerStats.ComboStat;
import com.example.gtmaddons.stats.PlayerStats.GunStat;
import com.example.gtmaddons.stats.PlayerStats.MovementGunStat;
import com.example.gtmaddons.stats.PlayerStats.PlayerDetail;
import com.example.gtmaddons.stats.PlayerStats.RecentFight;
import com.example.gtmaddons.stats.PlayerStats.RecentSwap;
import com.example.gtmaddons.gui.Panels.Gap;
import com.example.gtmaddons.gui.Panels.Meter;
import com.example.gtmaddons.gui.Panels.Note;
import com.example.gtmaddons.gui.Panels.Row;
import com.example.gtmaddons.gui.Panels.Section;
import com.example.gtmaddons.gui.Panels.Stat;
import com.example.gtmaddons.gui.Panels.TableRow;
import com.example.gtmaddons.gui.Panels.TimeRow;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deep insights into one kind of stat, opened by clicking a card on the
 * stats Overview (see StatsOverview). Same player, PvP category and fights
 * as the page it came from; the buttons along the top switch between the
 * category's insight pages, and the panels scroll with the mouse wheel.
 *
 * The pages show numbers and labels only, with no explanatory text. Closes
 * itself if you get combat-tagged.
 */
public class InsightScreen extends Screen {

	/** The insight pages, and which PvP categories have each. */
	public enum Insight {
		RATING("Ratings"),
		FIGHTS("Fights"),
		WING_SWAPS("Swaps"),
		AIR_SWAPS("Air swaps"),
		MELEE("Melee"),
		MOVEMENT("Movement"),
		GUNS("Guns");

		public final String label;

		Insight(String label) {
			this.label = label;
		}

		static List<Insight> of(PvpCategory category) {
			return switch (category) {
				case WING -> List.of(RATING, FIGHTS, WING_SWAPS, GUNS);
				case AIR -> List.of(RATING, FIGHTS, AIR_SWAPS, MELEE, GUNS);
				case JP -> List.of(RATING, FIGHTS, MELEE, GUNS);
				case GROUND -> List.of(RATING, FIGHTS, MOVEMENT, GUNS);
			};
		}
	}

	private static final int TABS_Y = 32;
	private static final int TAB_WIDTH = 66;
	private static final int TAB_GAP = 4;
	private static final int CONTENT_Y = 60;

	private final Screen parent;
	private final PlayerDetail detail;
	private final PvpCategory category;
	private final Text subtitle;
	private final boolean individualGuns;
	private Insight insight;
	private List<Section> left = List.of(), right = List.of();
	private int scroll = 0;

	/** subtitle is the stats page's (which fights these are, and the K/D). */
	public InsightScreen(Screen parent, PlayerDetail detail, PvpCategory category, Insight insight, Text subtitle, boolean individualGuns) {
		super(Text.literal(detail.name() + " · " + category.label + " PvP"));
		this.parent = parent;
		this.detail = detail;
		this.category = category;
		this.insight = insight;
		this.subtitle = subtitle;
		this.individualGuns = individualGuns;
	}

	@Override
	public void tick() {
		CombatLock.closeIfInCombat(client);
	}

	@Override
	protected void init() {
		List<Insight> pages = Insight.of(category);
		int tabsLeft = tabsLeft(pages.size());
		for (int i = 0; i < pages.size(); i++) {
			Insight page = pages.get(i);
			ButtonWidget tab = addDrawableChild(ButtonWidget.builder(Text.literal(page.label), b -> {
				insight = page;
				scroll = 0;
				clearAndInit();
			}).dimensions(tabsLeft + i * (TAB_WIDTH + TAB_GAP), TABS_Y, TAB_WIDTH, 20).build());
			tab.active = page != insight;
		}
		addDrawableChild(ButtonWidget.builder(ScreenTexts.BACK, b -> close())
				.dimensions(width / 2 - 75, height - 28, 150, 20).build());
		build();
	}

	private int tabsLeft(int count) {
		return (width - (count * TAB_WIDTH + (count - 1) * TAB_GAP)) / 2;
	}

	@Override
	public void close() {
		client.setScreen(parent);
	}

	// ---- Drawing ----

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		context.fill(0, 0, width, height, Ui.PAGE);
		super.render(context, mouseX, mouseY, deltaTicks);
		int accent = Ui.accent(category);
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 8, Ui.TEXT);
		context.drawCenteredTextWithShadow(textRenderer, subtitle, width / 2, 19, Ui.MUTED);

		List<Insight> pages = Insight.of(category);
		int tabX = tabsLeft(pages.size()) + pages.indexOf(insight) * (TAB_WIDTH + TAB_GAP);
		context.fill(tabX + 2, TABS_Y + 21, tabX + TAB_WIDTH - 2, TABS_Y + 23, accent);

		int top = CONTENT_Y, bottom = height - 36;
		int visible = bottom - top, full = Math.max(Panels.height(left), Panels.height(right));
		scroll = Math.max(0, Math.min(scroll, full - visible));
		int columnW, leftX, rightX;
		if (right.isEmpty()) {
			columnW = Math.min(420, width - 40);
			leftX = (width - columnW) / 2;
			rightX = 0;
		} else {
			columnW = Math.min(300, (width - 30) / 2);
			leftX = width / 2 - columnW - 5;
			rightX = width / 2 + 5;
		}

		context.enableScissor(0, top, width, bottom);
		var matrices = context.getMatrices();
		matrices.pushMatrix();
		matrices.translate(0, -scroll);
		Panels.drawColumn(context, textRenderer, leftX, top, columnW, left, accent);
		if (!right.isEmpty()) Panels.drawColumn(context, textRenderer, rightX, top, columnW, right, accent);
		matrices.popMatrix();
		context.disableScissor();

		if (full > visible) {
			int barX = (right.isEmpty() ? leftX + columnW : rightX + columnW) + 4;
			int barH = Math.max(12, visible * visible / full);
			int barY = top + (visible - barH) * scroll / (full - visible);
			context.fill(barX, top, barX + 2, bottom, Ui.CARD);
			context.fill(barX, barY, barX + 2, barY + barH, Ui.MUTED);
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		scroll = Math.max(0, scroll - (int) Math.round(verticalAmount * 16));
		return true;
	}

	// ---- Pages ----

	private void build() {
		left = new ArrayList<>();
		right = new ArrayList<>();
		switch (insight) {
			case RATING -> ratingPage();
			case FIGHTS -> fightsPage();
			case WING_SWAPS -> wingSwapsPage();
			case AIR_SWAPS -> airSwapsPage();
			case MELEE -> meleePage();
			case MOVEMENT -> movementPage();
			case GUNS -> gunsPage();
		}
	}

	/**
	 * The three ratings, then what Overall is made of: each part's score,
	 * weight, share of Overall and how many Overall points it costs you
	 * (weight x (100 - score) / total weight) - the biggest one is where
	 * there's most to gain.
	 */
	private void ratingPage() {
		Ratings.Result r = Ratings.compute(detail, category);
		List<Row> ratings = new ArrayList<>();
		ratings.add(ratingRow(Ratings.aimLabel(category), r.aim()));
		ratings.add(ratingRow(Ratings.secondLabel(category), r.movement()));
		ratings.add(ratingRow("Overall", r.overall()));
		left.add(new Section(category.label + " ratings (beta)", ratings));

		List<Row> breakdown = new ArrayList<>();
		for (String line : r.breakdown()) breakdown.add(new Note(line, line.startsWith("  ") ? Ui.LABEL : Ui.TEXT));
		left.add(new Section("Everything that went in", breakdown));

		List<Row> parts = new ArrayList<>();
		parts.add(new TableRow(new String[] { "Part", "Score", "Weight", "Share", "Lost" }, Ui.MUTED, true));
		double totalWeight = 0;
		for (Ratings.Part p : r.parts()) if (p.score() != null) totalWeight += p.weight();
		Ratings.Part biggest = null;
		double biggestLost = 0;
		for (Ratings.Part p : r.parts()) {
			if (p.score() == null) {
				parts.add(new TableRow(new String[] { p.name(), "-", String.format("%.2f", p.weight()), "-", "-" }, Ui.MUTED, false));
				continue;
			}
			double lost = totalWeight > 0 ? p.weight() * (100 - p.score()) / totalWeight : 0;
			if (lost > biggestLost) {
				biggestLost = lost;
				biggest = p;
			}
			parts.add(new TableRow(new String[] { p.name(), String.format("%.0f", p.score()), String.format("%.2f", p.weight()),
					pct(totalWeight > 0 ? p.weight() / totalWeight : Double.NaN), String.format("%.1f", lost) }, Ui.TEXT, false));
		}
		parts.add(Gap.INSTANCE);
		if (r.overall() == null) {
			parts.add(new Note("Overall isn't shown yet - not enough data.", Ui.OK));
		} else if (biggest != null) {
			parts.add(new Note(String.format("Most to gain: %s (+%.1f if it were 100)", biggest.name(), biggestLost), Ui.GOOD));
		}
		right.add(new Section("What Overall is made of", parts));
	}

	private static Row ratingRow(String name, Double value) {
		if (value == null) return new Stat(name, "not enough data", Ui.MUTED);
		return new Meter(name, String.format("%.0f", value), Ui.rateColor(value), value / 100);
	}

	/** K/D and fights won, then the last few fights: streaks, fight lengths, opponents and a timeline. */
	private void fightsPage() {
		List<Row> record = new ArrayList<>();
		if (detail.fights() == 0) {
			record.add(new Note("No fights yet.", Ui.MUTED));
			left.add(new Section("Record", record));
			return;
		}
		double kd = PlayerScreen.kd(detail.kills(), detail.deaths());
		record.add(new Meter("K/D", String.format("%.2f", kd), PlayerScreen.kdColor(kd), kd / 2));
		double won = detail.kills() * 100.0 / detail.fights();
		record.add(new Meter("Fights won", String.format("%.0f%%", won), Ui.rateColor(won), won / 100));
		record.add(new Stat("Fights", String.valueOf(detail.fights()), Ui.TEXT));
		record.add(new Stat("Kills · deaths", detail.kills() + " · " + detail.deaths(), Ui.TEXT));
		left.add(new Section("Record", record));

		List<RecentFight> fights = detail.recentFights() != null ? detail.recentFights() : List.of();
		if (fights.isEmpty()) return;

		// Streaks: fights are newest first.
		boolean latestKill = "KILL".equals(fights.getFirst().outcome());
		int current = 0;
		while (current < fights.size() && "KILL".equals(fights.get(current).outcome()) == latestKill) current++;
		int bestStreak = 0, run = 0;
		long killMs = 0, deathMs = 0, quickestKill = Long.MAX_VALUE, longest = 0;
		int kills = 0, deaths = 0;
		Map<String, int[]> opponents = new LinkedHashMap<>();
		for (RecentFight f : fights) {
			boolean kill = "KILL".equals(f.outcome());
			long ms = Math.max(0, f.endedAt() - f.startedAt());
			run = kill ? run + 1 : 0;
			bestStreak = Math.max(bestStreak, run);
			longest = Math.max(longest, ms);
			if (kill) {
				kills++;
				killMs += ms;
				quickestKill = Math.min(quickestKill, ms);
			} else {
				deaths++;
				deathMs += ms;
			}
			int[] kd2 = opponents.computeIfAbsent(f.opponent() != null ? f.opponent() : "unknown", k -> new int[2]);
			kd2[kill ? 0 : 1]++;
		}
		List<Row> recent = new ArrayList<>();
		recent.add(new Stat("Current streak", current + " " + (latestKill ? (current == 1 ? "kill" : "kills") : (current == 1 ? "death" : "deaths")),
				latestKill ? Ui.GOOD : Ui.BAD));
		recent.add(new Stat("Best kill streak", String.valueOf(bestStreak), Ui.TEXT));
		recent.add(new Stat("Average fight", secs((killMs + deathMs) / (double) fights.size()), Ui.TEXT));
		recent.add(new Stat("Average fight you won", kills > 0 ? secs(killMs / (double) kills) : "-", Ui.TEXT));
		recent.add(new Stat("Average fight you lost", deaths > 0 ? secs(deathMs / (double) deaths) : "-", Ui.TEXT));
		recent.add(new Stat("Quickest kill", kills > 0 ? secs(quickestKill) : "-", Ui.GOOD));
		recent.add(new Stat("Longest fight", secs(longest), Ui.TEXT));
		left.add(new Section("Last " + fights.size() + " fights", recent));

		List<Row> vs = new ArrayList<>();
		vs.add(new TableRow(new String[] { "Opponent", "K", "D" }, Ui.MUTED, true));
		List<Map.Entry<String, int[]>> sorted = new ArrayList<>(opponents.entrySet());
		sorted.sort((a, b) -> Integer.compare(b.getValue()[0] + b.getValue()[1], a.getValue()[0] + a.getValue()[1]));
		for (Map.Entry<String, int[]> e : sorted) {
			int k = e.getValue()[0], d = e.getValue()[1];
			vs.add(new TableRow(new String[] { e.getKey(), String.valueOf(k), String.valueOf(d) },
					k > d ? Ui.GOOD : k < d ? Ui.BAD : Ui.TEXT, false));
		}
		right.add(new Section("Opponents (last " + fights.size() + ")", vs));

		List<Row> timeline = new ArrayList<>();
		for (RecentFight f : fights) {
			boolean kill = "KILL".equals(f.outcome());
			timeline.add(new TimeRow(Format.time(f.endedAt()),
					(f.opponent() != null ? f.opponent() : "unknown") + " · " + secs(Math.max(0, f.endedAt() - f.startedAt())),
					kill ? "Kill" : "Death", kill ? Ui.GOOD : Ui.BAD));
		}
		right.add(new Section("Timeline, newest first", timeline));
	}

	/** Wing swaps: reliability, each step of the average swap, momentum, mouse movement and the recent swaps. */
	private void wingSwapsPage() {
		List<Row> swaps = new ArrayList<>();
		if (detail.total() == 0) {
			swaps.add(new Note("No swaps yet.", Ui.MUTED));
			left.add(new Section("Wing swaps", swaps));
			return;
		}
		int attempts = detail.total() - detail.cancels();
		double success = attempts > 0 ? detail.successes() * 100.0 / attempts : 0;
		Averages avg = detail.avg();
		swaps.add(new Meter("Success rate", String.format("%.1f%%", success), Ui.rateColor(success), success / 100));
		swaps.add(new Stat("Swaps", String.valueOf(detail.total()), Ui.TEXT));
		swaps.add(new Stat("OK · failed · canceled", detail.successes() + " · " + detail.failures() + " · " + detail.cancels(), Ui.TEXT));
		swaps.add(new Stat("Canceled", pct(detail.total() > 0 ? (double) detail.cancels() / detail.total() : Double.NaN) + " of swaps", Ui.TEXT));
		swaps.add(new Stat("Fastest", Format.seconds(detail.bestMs()), Ui.GOOD));
		swaps.add(new Stat("Average", Format.seconds(avg.totalMs()), Ui.TEXT));
		if (avg.totalMs() != null && detail.bestMs() != null) {
			swaps.add(new Stat("Average over fastest", "+" + Format.seconds(avg.totalMs() - detail.bestMs()), Ui.TEXT));
		}
		if (avg.totalMs() != null && success > 0) {
			swaps.add(new Stat("Effective (average / success)", Format.seconds(avg.totalMs() / (success / 100)), Ui.TEXT));
		}
		left.add(new Section("Wing swaps", swaps));

		List<Row> steps = new ArrayList<>();
		Double total = avg.totalMs();
		steps.add(stepRow("Reach chest slot", avg.reachMs(), total));
		steps.add(stepRow("Chest slot → hotbar", avg.slotToHotbarMs(), total));
		steps.add(stepRow("Hotbar → close", avg.hotbarToCloseMs(), total));
		steps.add(new Stat("Wing to hotbar", Format.seconds(avg.wingToHotbarMs()), Ui.TEXT));
		steps.add(new Stat("Total", Format.seconds(total), Ui.TEXT));
		left.add(new Section("Average swap, step by step", steps));

		List<Row> momentum = new ArrayList<>();
		Double before = avg.speedBeforeBps(), after = avg.speedAfterBps();
		if (before == null || after == null) {
			momentum.add(new Note("No swaps into an empty hotbar slot yet.", Ui.MUTED));
		} else {
			double kept = before > 0 ? after / before * 100 : 100;
			momentum.add(new Meter("Momentum kept", String.format("%.0f%%", kept), Ui.rateColor(kept), kept / 100));
			momentum.add(new Stat("Speed before swap", Format.bps(before), Ui.TEXT));
			momentum.add(new Stat("Speed after swap", Format.bps(after), Ui.TEXT));
			momentum.add(new Stat("Speed lost", Format.bps(Math.max(0, before - after)), Ui.TEXT));
		}
		right.add(new Section("Momentum", momentum));

		List<Row> mouse = new ArrayList<>();
		mouse.add(new Stat("Total", Format.degrees(avg.mouseDeg()), Ui.TEXT));
		mouse.add(new Stat("Approach (needed)", Format.degrees(avg.approachDeg()) + " (" + Format.degrees(avg.neededDeg()) + ")", Ui.TEXT));
		Double efficiency = avg.efficiency();
		mouse.add(efficiency != null
				? new Meter("Efficiency", Format.percent(efficiency), Ui.rateColor(efficiency), efficiency / 100)
				: new Stat("Efficiency", "-", Ui.TEXT));
		mouse.add(new Stat("Backward", Format.degrees(avg.awayDeg()), Ui.TEXT));
		mouse.add(new Stat("After touching slot (peak)", Format.degrees(avg.overflickDeg()) + " (" + Format.degrees(avg.overflickPeakDeg()) + ")", Ui.TEXT));
		mouse.add(new Stat("After wing in hotbar", Format.degrees(avg.afterDeg()), Ui.TEXT));
		right.add(new Section("Average mouse movement", mouse));

		List<Row> recent = new ArrayList<>();
		if (detail.recent().isEmpty()) recent.add(new Note("None yet.", Ui.MUTED));
		for (RecentSwap swap : detail.recent()) {
			recent.add(switch (swap.result()) {
				case "SUCCESS" -> new TimeRow(Format.time(swap.ts()),
						String.format("wing %s · %s · %s", Format.seconds(swap.wingToHotbarMs()),
								Format.percent(swap.efficiency()), Format.degrees(swap.overflickDeg())),
						Format.seconds(swap.totalMs()), Ui.GOOD);
				case "FAILED" -> new TimeRow(Format.time(swap.ts()), "", "Failed", Ui.BAD);
				default -> new TimeRow(Format.time(swap.ts()), "", "Canceled", Ui.MUTED);
			});
		}
		right.add(new Section("Recent swaps", recent));
	}

	private static Row stepRow(String label, Double ms, Double total) {
		if (ms == null || total == null || total <= 0) return new Stat(label, Format.seconds(ms), Ui.TEXT);
		double share = ms / total;
		return new Meter(label, Format.seconds(ms) + " · " + pct(share), Ui.BLUE, share);
	}

	/** Air swaps: reliability, then each swap type's use, speed and consistency. */
	private void airSwapsPage() {
		List<AirSwapStat> stats = detail.airSwaps() != null ? detail.airSwaps() : List.of();
		List<Row> summary = new ArrayList<>();
		if (stats.isEmpty()) {
			summary.add(new Note("No swaps yet.", Ui.MUTED));
			left.add(new Section("Air swaps", summary));
			return;
		}
		int attempts = 0, successes = 0, cancels = 0;
		double totalMs = 0;
		Double fastest = null;
		for (AirSwapStat s : stats) {
			attempts += s.total() - s.cancels();
			successes += s.successes();
			cancels += s.cancels();
			if (s.avgMs() != null) totalMs += s.avgMs() * s.successes();
			if (s.bestMs() != null) fastest = fastest == null ? s.bestMs() : Math.min(fastest, s.bestMs());
		}
		double success = attempts > 0 ? successes * 100.0 / attempts : 0;
		summary.add(new Meter("Success rate", String.format("%.1f%%", success), Ui.rateColor(success), success / 100));
		summary.add(new Stat("Attempts · failed · canceled", attempts + " · " + (attempts - successes) + " · " + cancels, Ui.TEXT));
		summary.add(new Stat("Average", successes > 0 ? Format.seconds(totalMs / successes) : "-", Ui.TEXT));
		summary.add(new Stat("Fastest", Format.seconds(fastest), Ui.GOOD));
		if (successes > 0 && success > 0) {
			summary.add(new Stat("Effective (average / success)", Format.seconds(totalMs / successes / (success / 100)), Ui.TEXT));
		}
		left.add(new Section("Air swaps", summary));

		String[][] types = { { "JETPACK", "Jetpack" }, { "WINGSUIT", "Wingsuit" }, { "JP_TO_WING", "JP → Wing" }, { "WING_TO_JP", "Wing → JP" } };
		List<Row> use = new ArrayList<>();
		List<Row> speed = new ArrayList<>();
		speed.add(new TableRow(new String[] { "Type", "Swaps", "Avg", "Best", "Gap" }, Ui.MUTED, true));
		for (String[] type : types) {
			AirSwapStat s = stats.stream().filter(a -> type[0].equals(a.swapType())).findFirst().orElse(null);
			if (s == null) {
				use.add(new Stat(type[1], "none yet", Ui.MUTED));
				speed.add(new TableRow(new String[] { type[1], "0", "-", "-", "-" }, Ui.MUTED, false));
				continue;
			}
			double share = successes > 0 ? (double) s.successes() / successes : 0;
			use.add(new Meter(type[1], s.successes() + " · " + pct(share), Ui.BLUE, share));
			String gap = s.avgMs() != null && s.bestMs() != null ? String.format("+%.2f", (s.avgMs() - s.bestMs()) / 1000) : "-";
			speed.add(new TableRow(new String[] { type[1], String.valueOf(s.successes()), shortSeconds(s.avgMs()), shortSeconds(s.bestMs()), gap },
					Ui.TEXT, false));
		}
		right.add(new Section("Swap types used", use));
		right.add(new Section("Speed per type", speed));
	}

	/** Melee: combo share, breaks, first hits and the melee rating. */
	private void meleePage() {
		ComboStat c = null;
		if (detail.combos() != null) {
			for (ComboStat s : detail.combos()) if (category.name().equals(s.category())) c = s;
		}
		List<Row> combos = new ArrayList<>();
		if (c == null) {
			combos.add(new Note("No combos yet.", Ui.MUTED));
			left.add(new Section("Combos", combos));
				return;
		}
		long total = c.ownCombos() + c.enemyCombos();
		combos.add(share("Your share of combos", c.ownCombos(), total));
		combos.add(new Stat("Yours · theirs", c.ownCombos() + " · " + c.enemyCombos(), Ui.TEXT));
		left.add(new Section("Combos", combos));

		List<Row> breaks = new ArrayList<>();
		breaks.add(share("Enemy combos you broke", c.enemyBroken(), c.enemyCombos()));
		breaks.add(share("Your combos you kept", c.ownCombos() - c.ownBroken(), c.ownCombos()));
		breaks.add(new Stat("Your combos that got broken", c.ownBroken() + " of " + c.ownCombos(), Ui.TEXT));
		breaks.add(new Stat("Their combos that ran out", (c.enemyCombos() - c.enemyBroken()) + " of " + c.enemyCombos(), Ui.TEXT));
		left.add(new Section("Breaks", breaks));

		List<Row> first = new ArrayList<>();
		long firstHits = c.ownFirstHits() + c.enemyFirstHits();
		if (firstHits == 0) {
			first.add(new Note("No first hits yet.", Ui.MUTED));
		} else {
			first.add(share("First hits that were yours", c.ownFirstHits(), firstHits));
			first.add(new Stat("Yours · theirs", c.ownFirstHits() + " · " + c.enemyFirstHits(), Ui.TEXT));
			first.add(new Stat("Your combos from a first hit", pct(c.ownCombos() > 0 ? (double) c.ownFirstHits() / c.ownCombos() : Double.NaN), Ui.TEXT));
			first.add(new Stat("Their combos from a first hit", pct(c.enemyCombos() > 0 ? (double) c.enemyFirstHits() / c.enemyCombos() : Double.NaN), Ui.TEXT));
		}
		left.add(new Section("First hits", first));

		// The melee rating, part by part (see Ratings.meleeScore).
		List<Row> rating = new ArrayList<>();
		if (total < RatingWeights.MIN_COMBOS) {
			rating.add(new Note(String.format("Needs %d combos (%d so far).", RatingWeights.MIN_COMBOS, total), Ui.MUTED));
		} else {
			Double broke = c.enemyCombos() > 0 ? 100.0 * c.enemyBroken() / c.enemyCombos() : null;
			Double kept = c.ownCombos() > 0 ? 100.0 * (c.ownCombos() - c.ownBroken()) / c.ownCombos() : null;
			double shareScore = 100.0 * c.ownCombos() / total;
			double sum = shareScore * RatingWeights.MELEE_SHARE_WEIGHT, weights = RatingWeights.MELEE_SHARE_WEIGHT;
			if (broke != null) {
				sum += broke * RatingWeights.MELEE_BROKE_WEIGHT;
				weights += RatingWeights.MELEE_BROKE_WEIGHT;
			}
			if (kept != null) {
				sum += kept * RatingWeights.MELEE_KEPT_WEIGHT;
				weights += RatingWeights.MELEE_KEPT_WEIGHT;
			}
			rating.add(ratingRow("Melee", sum / weights));
			rating.add(new Stat("Broke", broke != null ? String.format("%.0f", broke) : "-", Ui.TEXT));
			rating.add(new Stat("Kept", kept != null ? String.format("%.0f", kept) : "-", Ui.TEXT));
			rating.add(new Stat("Share", String.format("%.0f", shareScore), Ui.TEXT));
		}
		right.add(new Section("Melee rating", rating));
	}

	/** Ground movement guns: speed right after each shot, scored like the rating. */
	private void movementPage() {
		List<MovementGunStat> stats = detail.movementGuns() != null ? detail.movementGuns() : List.of();
		List<Row> summary = new ArrayList<>();
		if (stats.isEmpty()) {
			summary.add(new Note("No movement gun shots yet.", Ui.MUTED));
			left.add(new Section("Movement guns", summary));
			return;
		}
		long shots = 0;
		double total = 0, best = 0;
		for (MovementGunStat s : stats) {
			shots += s.shots();
			if (s.avgBps() != null) total += s.avgBps() * s.shots();
			if (s.bestBps() != null) best = Math.max(best, s.bestBps());
		}
		Ratings.Result r = Ratings.compute(detail, category);
		summary.add(ratingRow("Movement rating", r.movement()));
		summary.add(new Stat("Average speed after a shot", Format.bps(shots > 0 ? total / shots : null), Ui.TEXT));
		summary.add(new Stat("Fastest", Format.bps(best), Ui.GOOD));
		summary.add(new Stat("Shots", String.valueOf(shots), Ui.TEXT));
		left.add(new Section("Movement guns", summary));

		for (String gun : new String[] { "Sawed-off Shotgun", "Pump Shotgun", "Heavy Revolver" }) {
			MovementGunStat s = stats.stream().filter(x -> gun.equalsIgnoreCase(x.gun())).findFirst().orElse(null);
			List<Row> rows = new ArrayList<>();
			if (s == null || s.avgBps() == null) {
				rows.add(new Note("No shots yet.", Ui.MUTED));
			} else {
				double score = Math.max(0, Math.min(100, 100.0 * (s.avgBps() - RatingWeights.MOVEMENT_FLOOR_BPS)
						/ (RatingWeights.MOVEMENT_CEILING_BPS - RatingWeights.MOVEMENT_FLOOR_BPS)));
				rows.add(new Meter("Score", String.format("%.0f", score), Ui.rateColor(score), score / 100));
				rows.add(new Stat("Average", Format.bps(s.avgBps()), Ui.TEXT));
				rows.add(new Stat("Fastest", Format.bps(s.bestBps()), Ui.GOOD));
				if (s.bestBps() != null) rows.add(new Stat("Fastest over average", "+" + Format.bps(s.bestBps() - s.avgBps()), Ui.TEXT));
				rows.add(new Stat("Shots", s.shots() + " · " + pct(shots > 0 ? (double) s.shots() / shots : Double.NaN) + " of all", Ui.TEXT));
			}
			right.add(new Section(gun, rows));
		}
	}

	/** Guns: accuracy overall, how shots land, every gun with its aim score, and guns by type. */
	private void gunsPage() {
		List<GunStat> guns = new ArrayList<>();
		if (detail.guns() != null) {
			for (GunStat g : detail.guns()) if (category.name().equals(g.category())) guns.add(g);
		}
		List<Row> all = new ArrayList<>();
		if (guns.isEmpty()) {
			all.add(new Note("No shots yet.", Ui.MUTED));
			left.add(new Section("All guns", all));
			return;
		}
		guns.sort((a, b) -> Long.compare(b.shots(), a.shots()));
		long shots = 0, hits = 0, headshots = 0, kills = 0;
		for (GunStat g : guns) {
			shots += g.shots();
			hits += g.hits();
			headshots += g.headshots();
			kills += g.kills();
		}
		Ratings.Result r = Ratings.compute(detail, category);
		all.add(ratingRow(Ratings.aimLabel(category) + " rating", r.aim()));
		double hitRate = shots > 0 ? hits * 100.0 / shots : 0;
		all.add(new Meter("Hit rate", String.format("%.0f%%", hitRate), Ui.accent(category), hitRate / 100));
		double hsRate = hits > 0 ? headshots * 100.0 / hits : 0;
		all.add(new Meter("Headshots, of hits", String.format("%.0f%%", hsRate), Ui.accent(category), hsRate / 100));
		all.add(new Stat("Shots · hits · headshots", shots + " · " + hits + " · " + headshots, Ui.TEXT));
		all.add(new Stat("Kills", String.valueOf(kills), Ui.TEXT));
		all.add(new Stat("Shots per kill", kills > 0 ? String.format("%.1f", (double) shots / kills) : "-", Ui.TEXT));
		all.add(new Stat("Hits per kill", kills > 0 ? String.format("%.1f", (double) hits / kills) : "-", Ui.TEXT));
		left.add(new Section("All guns", all));

		List<Row> outcome = new ArrayList<>();
		outcome.add(outcomeRow("Head", headshots, shots));
		outcome.add(outcomeRow("Body", hits - headshots, shots));
		outcome.add(outcomeRow("Miss", shots - hits, shots));
		left.add(new Section("Where shots land", outcome));

		List<Row> each = new ArrayList<>();
		each.add(new TableRow(new String[] { "Gun", "Shots", "Hit", "HS", "K", "Aim" }, Ui.MUTED, true));
		for (GunStat g : guns) {
			Double aim = Ratings.gunAim(category, g.gun(), g.shots(), g.hits(), g.headshots());
			each.add(new TableRow(new String[] { g.gun(), String.valueOf(g.shots()),
					pct(g.shots() > 0 ? (double) g.hits() / g.shots() : Double.NaN),
					pct(g.hits() > 0 ? (double) g.headshots() / g.hits() : Double.NaN),
					String.valueOf(g.kills()), aim != null ? String.format("%.0f", aim) : "-" }, Ui.TEXT, false));
		}
		right.add(new Section("Every gun", each));

		if (!individualGuns) {
			Map<GunType, long[]> byType = new EnumMap<>(GunType.class);
			for (GunStat g : guns) {
				GunType type = GunType.of(g.gun());
				if (type == null) continue;
				long[] t = byType.computeIfAbsent(type, k -> new long[4]);
				t[0] += g.shots();
				t[1] += g.hits();
				t[2] += g.headshots();
				t[3] += g.kills();
			}
			if (!byType.isEmpty()) {
				List<Row> types = new ArrayList<>();
				types.add(new TableRow(new String[] { "Type", "Shots", "Hit", "HS", "K" }, Ui.MUTED, true));
				byType.forEach((type, t) -> types.add(new TableRow(new String[] { type.label, String.valueOf(t[0]),
						pct(t[0] > 0 ? (double) t[1] / t[0] : Double.NaN), pct(t[1] > 0 ? (double) t[2] / t[1] : Double.NaN),
						String.valueOf(t[3]) }, Ui.TEXT, false)));
				right.add(new Section("By type", types));
			}
		}
	}

	private static Row outcomeRow(String label, long part, long shots) {
		double share = shots > 0 ? (double) part / shots : 0;
		return new Meter(label, part + " · " + pct(share), label.equals("Miss") ? Ui.ORANGE : Ui.BLUE, share);
	}

	// ---- Helpers ----

	/** part of whole as a meter, "62% (8/13)"; "-" with nothing to count. */
	private static Row share(String label, long part, long whole) {
		if (whole == 0) return new Stat(label, "-", Ui.MUTED);
		double pct = part * 100.0 / whole;
		return new Meter(label, String.format("%.0f%% (%d/%d)", pct, part, whole), Ui.rateColor(pct), pct / 100);
	}

	private static String pct(double fraction) {
		return Double.isNaN(fraction) ? "-" : Math.round(fraction * 100) + "%";
	}

	private static String secs(double ms) {
		return String.format("%.0fs", ms / 1000);
	}

	/** "0.41" - seconds for a narrow table column. */
	private static String shortSeconds(Double ms) {
		return ms == null ? "-" : String.format("%.2f", ms / 1000);
	}
}
