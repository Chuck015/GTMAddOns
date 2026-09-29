package com.example.gtmaddons.gui;

import com.example.gtmaddons.PvpCategory;
import com.example.gtmaddons.gui.InsightScreen.Insight;
import com.example.gtmaddons.gun.GunType;
import com.example.gtmaddons.rating.Ratings;
import com.example.gtmaddons.stats.PlayerStats.AirSwapStat;
import com.example.gtmaddons.stats.PlayerStats.Averages;
import com.example.gtmaddons.stats.PlayerStats.ComboStat;
import com.example.gtmaddons.stats.PlayerStats.GunStat;
import com.example.gtmaddons.stats.PlayerStats.MovementGunStat;
import com.example.gtmaddons.stats.PlayerStats.PlayerDetail;
import com.example.gtmaddons.stats.PlayerStats.RecentFight;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The stats page's Overview, styled after csstats.gg: two rows of dark cards
 * for one PvP category.
 *
 *   [ K/D ring ][ Rating ring ][ breakdown: big stat + pies ][ last fights ]
 *   [ Hit rate ][ Headshot % ][ momentum / swap time / melee / movement ][ most shots ][ gun stats ]
 *
 * The breakdown card depends on the category: swap success and swap time
 * together for Wing; each Air swap type's share for Air; melee (combos
 * broken and kept) for JP; headshot / body / miss for Ground. Guns are grouped by type
 * unless "Individual gun stats" is on.
 *
 * Every card is clickable: hovering one outlines it, and clicking opens its
 * insight page (see InsightScreen) - draw() says which card the mouse is on.
 */
final class StatsOverview {
	private StatsOverview() {}

	static final int ROW_A = 94;
	static final int ROW_B = 106;
	static final int GAP = 5;

	/** Height of the whole overview. */
	static int height() {
		return ROW_A + GAP + ROW_B;
	}

	/**
	 * Draws the overview at (x, y), w wide. Returns the insight page of the
	 * card the mouse is on (outlined), or null.
	 */
	static Insight draw(DrawContext context, TextRenderer font, PlayerDetail detail, PvpCategory category, boolean individualGuns,
			int x, int y, int w, int mouseX, int mouseY) {
		Data data = new Data(detail, category, individualGuns);
		Insight hovered = null;

		// Row A: two rings, the breakdown, the fights strip.
		int availA = w - 3 * GAP;
		int ringW = Math.round(availA * 0.18f), breakdownW = Math.round(availA * 0.30f);
		int fightsW = availA - 2 * ringW - breakdownW;
		int cx = x;
		kdCard(context, font, data, cx, y, ringW, ROW_A);
		hovered = hover(context, Insight.FIGHTS, cx, y, ringW, ROW_A, mouseX, mouseY, hovered);
		cx += ringW + GAP;
		ratingCard(context, font, Ratings.compute(detail, category), category, cx, y, ringW, ROW_A);
		hovered = hover(context, Insight.RATING, cx, y, ringW, ROW_A, mouseX, mouseY, hovered);
		cx += ringW + GAP;
		if (category == PvpCategory.WING) wingSwapCard(context, font, data, cx, y, breakdownW, ROW_A);
		else breakdownCard(context, font, data, category, cx, y, breakdownW, ROW_A);
		hovered = hover(context, breakdownInsight(category), cx, y, breakdownW, ROW_A, mouseX, mouseY, hovered);
		cx += breakdownW + GAP;
		fightsCard(context, font, data, cx, y, fightsW, ROW_A);
		hovered = hover(context, Insight.FIGHTS, cx, y, fightsW, ROW_A, mouseX, mouseY, hovered);

		// Row B: three stat cards, then the two gun cards (gun stats gets more room for its columns).
		int yb = y + ROW_A + GAP;
		int availB = w - 4 * GAP;
		int smallW = Math.round(availB * 0.18f);
		int listW = (availB - 3 * smallW) * 2 / 5;
		cx = x;
		hitCard(context, font, data, cx, yb, smallW, ROW_B);
		hovered = hover(context, Insight.GUNS, cx, yb, smallW, ROW_B, mouseX, mouseY, hovered);
		cx += smallW + GAP;
		headshotCard(context, font, data, cx, yb, smallW, ROW_B);
		hovered = hover(context, Insight.GUNS, cx, yb, smallW, ROW_B, mouseX, mouseY, hovered);
		cx += smallW + GAP;
		thirdCard(context, font, data, category, cx, yb, smallW, ROW_B);
		hovered = hover(context, thirdInsight(category), cx, yb, smallW, ROW_B, mouseX, mouseY, hovered);
		cx += smallW + GAP;
		mostShotsCard(context, font, data.guns, cx, yb, listW, ROW_B);
		hovered = hover(context, Insight.GUNS, cx, yb, listW, ROW_B, mouseX, mouseY, hovered);
		cx += listW + GAP;
		// The last card takes whatever width is left, so the row ends flush.
		gunStatsCard(context, font, data.guns, cx, yb, x + w - cx, ROW_B);
		hovered = hover(context, Insight.GUNS, cx, yb, x + w - cx, ROW_B, mouseX, mouseY, hovered);
		return hovered;
	}

	/** The breakdown card's page: Wing swaps, Air swaps, melee (JP), or guns (Ground's shot breakdown). */
	private static Insight breakdownInsight(PvpCategory category) {
		return switch (category) {
			case WING -> Insight.WING_SWAPS;
			case AIR -> Insight.AIR_SWAPS;
			case JP -> Insight.MELEE;
			case GROUND -> Insight.GUNS;
		};
	}

	/** The third card's page (see thirdCard): momentum is on the Wing swaps page. */
	private static Insight thirdInsight(PvpCategory category) {
		return switch (category) {
			case WING -> Insight.WING_SWAPS;
			case AIR -> Insight.AIR_SWAPS;
			case JP -> Insight.MELEE;
			case GROUND -> Insight.MOVEMENT;
		};
	}

	/** If the mouse is on the card, outlines it and returns its page; otherwise returns current. */
	private static Insight hover(DrawContext context, Insight insight, int x, int y, int w, int h, int mouseX, int mouseY, Insight current) {
		if (!inside(mouseX, mouseY, x, y, w, h)) return current;
		context.fill(x, y, x + w, y + 1, Ui.LABEL);
		context.fill(x, y + h - 1, x + w, y + h, Ui.LABEL);
		context.fill(x, y, x + 1, y + h, Ui.LABEL);
		context.fill(x + w - 1, y, x + w, y + h, Ui.LABEL);
		return insight;
	}

	// ---- Row A ----

	private static void kdCard(DrawContext context, TextRenderer font, Data d, int x, int y, int w, int h) {
		Ui.card(context, font, x, y, w, h, "K/D");
		if (d.detail.fights() == 0) {
			ringWithNumber(context, font, x, y, w, h, 0, Ui.TRACK, "-", "no fights");
			return;
		}
		double kd = PlayerScreen.kd(d.detail.kills(), d.detail.deaths());
		ringWithNumber(context, font, x, y, w, h, Math.min(1.0, kd / 2.0), Ui.gradient(kd / 2.0),
				String.format("%.2f", kd), d.detail.kills() + "K  " + d.detail.deaths() + "D");
	}

	private static void ratingCard(DrawContext context, TextRenderer font, Ratings.Result r, PvpCategory category, int x, int y, int w, int h) {
		Ui.card(context, font, x, y, w, h, "RATING");
		Double overall = r.overall();
		String second = r.movement() != null ? Ratings.secondShort(category) + " " + Math.round(r.movement()) : null;
		String sub = r.aim() != null ? Ratings.aimShort(category) + " " + Math.round(r.aim()) + (second != null ? "  " + second : "")
				: second != null ? second : "beta";
		ringWithNumber(context, font, x, y, w, h, overall != null ? overall / 100 : 0, overall != null ? Ui.gradient(overall / 100) : Ui.TRACK,
				overall != null ? String.valueOf(Math.round(overall)) : "-", sub);
	}

	/** A ring filling most of the card, with a big number in the middle and a small line under it. */
	private static void ringWithNumber(DrawContext context, TextRenderer font, int x, int y, int w, int h,
			double fraction, int color, String number, String sub) {
		int top = y + 17;
		int size = Math.min(w - 10, h - 21);
		int r = size / 2, cx = x + w / 2, cy = top + r;
		int thickness = Math.max(2, r / 9);
		Ui.ring(context, cx, cy, r, thickness, fraction, color);
		int inner = 2 * (r - thickness) - 8;
		float scale = Math.max(1f, Math.min(2.6f, inner / (float) Math.max(1, font.getWidth(number))));
		Ui.bigText(context, font, number, cx, cy - Math.round(5 * scale), scale, Ui.TEXT, true);
		if (sub != null && r > 18) {
			String s = Ui.fit(font, sub, inner);
			context.drawTextWithShadow(font, s, cx - font.getWidth(s) / 2, cy + Math.round(4 * scale) + 1, Ui.MUTED);
		}
	}

	/** The category's breakdown: a big headline stat, then up to four pies with labels. */
	private static void breakdownCard(DrawContext context, TextRenderer font, Data d, PvpCategory category, int x, int y, int w, int h) {
		List<Pie> pies = new ArrayList<>();
		String title, prefix, big;
		switch (category) {
			case AIR -> {
				title = "AIR SWAPS";
				List<AirSwapStat> stats = d.detail.airSwaps() != null ? d.detail.airSwaps() : List.of();
				int attempts = 0, successes = 0;
				for (AirSwapStat s : stats) {
					attempts += s.total() - s.cancels();
					successes += s.successes();
				}
				prefix = "Swaps";
				big = pct(attempts > 0 ? (double) successes / attempts : Double.NaN);
				String[][] types = { { "JETPACK", "JP" }, { "WINGSUIT", "Wing" }, { "JP_TO_WING", "JP>W" }, { "WING_TO_JP", "W>JP" } };
				for (String[] type : types) {
					AirSwapStat s = stats.stream().filter(a -> type[0].equals(a.swapType())).findFirst().orElse(null);
					pies.add(new Pie(type[1], s != null && successes > 0 ? (double) s.successes() / successes : Double.NaN, Ui.BLUE,
							s != null ? Format.seconds(s.avgMs()) : "-"));
				}
			}
			case JP -> {
				title = "MELEE";
				ComboStat c = d.combos;
				double broke = c != null && c.enemyCombos() > 0 ? (double) c.enemyBroken() / c.enemyCombos() : Double.NaN;
				double kept = c != null && c.ownCombos() > 0 ? 1 - (double) c.ownBroken() / c.ownCombos() : Double.NaN;
				prefix = "Breaks";
				big = pct(broke);
				pies.add(new Pie("You broke", broke, Ui.BLUE, c != null ? c.enemyBroken() + "/" + c.enemyCombos() : "-"));
				pies.add(new Pie("Kept yours", kept, Ui.ORANGE, c != null ? (c.ownCombos() - c.ownBroken()) + "/" + c.ownCombos() : "-"));
				long firstHits = c != null ? c.ownFirstHits() + c.enemyFirstHits() : 0;
				pies.add(new Pie("First hit", firstHits > 0 ? (double) c.ownFirstHits() / firstHits : Double.NaN, Ui.BLUE,
						firstHits > 0 ? c.ownFirstHits() + "/" + firstHits : "-"));
			}
			default -> {
				title = "SHOT BREAKDOWN";
				prefix = "Hit";
				big = pct(d.shots > 0 ? (double) d.hits / d.shots : Double.NaN);
				pies.add(new Pie("Head", d.shots > 0 ? (double) d.headshots / d.shots : Double.NaN, Ui.BLUE, String.valueOf(d.headshots)));
				pies.add(new Pie("Body", d.shots > 0 ? (double) (d.hits - d.headshots) / d.shots : Double.NaN, Ui.BLUE, String.valueOf(d.hits - d.headshots)));
				pies.add(new Pie("Miss", d.shots > 0 ? (double) (d.shots - d.hits) / d.shots : Double.NaN, Ui.ORANGE, String.valueOf(d.shots - d.hits)));
			}
		}
		Ui.card(context, font, x, y, w, h, title);
		// "Swaps 87%" - small gray prefix, big white number.
		context.drawTextWithShadow(font, prefix, x + 8, y + 26, Ui.LABEL);
		Ui.bigText(context, font, big, x + 12 + font.getWidth(prefix), y + 20, 2f, Ui.TEXT, false);
		context.fill(x + 6, y + 42, x + w - 6, y + 43, Ui.CARD_BORDER);

		pies(context, font, pies, x + 6, y, w - 12);
	}

	/** Pies side by side across width w from x, each with its label above and value and sub line below. */
	private static void pies(DrawContext context, TextRenderer font, List<Pie> pies, int x, int y, int w) {
		int slot = w / Math.max(1, pies.size());
		for (int i = 0; i < pies.size(); i++) {
			Pie p = pies.get(i);
			int px = x + slot * i + slot / 2;
			String label = Ui.fit(font, p.label(), slot - 2);
			context.drawTextWithShadow(font, label, px - font.getWidth(label) / 2, y + 47, Ui.TEXT);
			Ui.pie(context, px, y + 66, 8, Double.isNaN(p.fraction()) ? 0 : p.fraction(), p.color());
			String value = pct(p.fraction());
			context.drawTextWithShadow(font, value, px - font.getWidth(value) / 2, y + 76, Ui.TEXT);
			String sub = Ui.fit(font, p.sub(), slot - 2);
			context.drawTextWithShadow(font, sub, px - font.getWidth(sub) / 2, y + 85, Ui.MUTED);
		}
	}

	/**
	 * Wing: swap success and swap time in one card. Success % and the average
	 * swap time up top; below, success and mouse efficiency pies beside the
	 * time splits.
	 */
	private static void wingSwapCard(DrawContext context, TextRenderer font, Data d, int x, int y, int w, int h) {
		Ui.card(context, font, x, y, w, h, "SWAPS");
		int attempts = d.detail.total() - d.detail.cancels();
		double success = attempts > 0 ? (double) d.detail.successes() / attempts : Double.NaN;
		Averages avg = d.detail.avg();

		// "Success 87%" on the left, "Avg 0.412s" on the right; smaller if both don't fit at 2x.
		String rate = pct(success), time = avg != null ? Format.seconds(avg.totalMs()) : "-";
		String rateLabel = "Success", timeLabel = "Avg";
		int labels = font.getWidth(rateLabel) + font.getWidth(timeLabel) + 8 + 16 + 12;
		float scale = Ui.bigWidth(font, rate, 2f) + Ui.bigWidth(font, time, 2f) + labels <= w ? 2f : 1.5f;
		context.drawTextWithShadow(font, rateLabel, x + 8, y + 26, Ui.LABEL);
		Ui.bigText(context, font, rate, x + 12 + font.getWidth(rateLabel), y + 20, scale, Ui.TEXT, false);
		int timeW = Ui.bigWidth(font, time, scale);
		Ui.bigText(context, font, time, x + w - 8 - timeW, y + 20, scale, Ui.TEXT, false);
		context.drawTextWithShadow(font, timeLabel, x + w - 12 - timeW - font.getWidth(timeLabel), y + 26, Ui.LABEL);
		context.fill(x + 6, y + 42, x + w - 6, y + 43, Ui.CARD_BORDER);

		// Left half: success and mouse efficiency pies.
		int half = (w - 12) / 2;
		Double eff = avg != null ? avg.efficiency() : null;
		pies(context, font, List.of(
				new Pie("Success", success, Ui.BLUE, d.detail.successes() + "/" + attempts),
				new Pie("Mouse", eff != null ? eff / 100 : Double.NaN, Ui.BLUE, "efficiency")), x + 6, y, half);

		// Right half: the time splits.
		String[][] rows = {
				{ "FASTEST", Format.seconds(d.detail.bestMs()) },
				{ "REACH", avg != null ? Format.seconds(avg.reachMs()) : "-" },
				{ "TO HOTBAR", avg != null ? Format.seconds(avg.wingToHotbarMs()) : "-" },
				{ "SWAPS", String.valueOf(d.detail.total()) } };
		int left = x + 6 + half + 4, right = x + w - 7, ry = y + 48;
		for (String[] row : rows) {
			int valueW = font.getWidth(row[1]);
			context.drawTextWithShadow(font, Ui.fit(font, row[0], right - left - valueW - 4), left, ry, Ui.LABEL);
			Ui.textRight(context, font, row[1], right, ry, Ui.TEXT);
			ry += 11;
		}
	}

	private record Pie(String label, double fraction, int color, String sub) {}

	/** The last fights as green (kill) and red (death) dots, newest first. The Fights page has the details. */
	private static void fightsCard(DrawContext context, TextRenderer font, Data d, int x, int y, int w, int h) {
		Ui.card(context, font, x, y, w, h, "FIGHTS");
		List<RecentFight> fights = d.detail.recentFights() != null ? d.detail.recentFights() : List.of();
		if (fights.isEmpty()) {
			context.drawTextWithShadow(font, "No fights yet", x + 8, y + 40, Ui.MUTED);
			return;
		}
		int n = Math.min(10, fights.size());
		int slot = (w - 12) / 10;
		int line = y + 34;
		context.fill(x + 6 + slot / 2, line, x + 6 + slot * (n - 1) + slot / 2 + 1, line + 1, Ui.CARD_BORDER);
		for (int i = 0; i < n; i++) {
			RecentFight f = fights.get(i);
			boolean kill = "KILL".equals(f.outcome());
			int fx = x + 6 + slot * i + slot / 2;
			Ui.dot(context, fx, line, 4, kill ? Ui.GOOD : Ui.BAD);
			String letter = kill ? "K" : "D";
			context.drawTextWithShadow(font, letter, fx - font.getWidth(letter) / 2 + 1, line + 8, kill ? Ui.GOOD : Ui.BAD);
			if (f.opponent() != null) Ui.bigText(context, font, Ui.fit(font, f.opponent(), (int) ((slot - 1) / 0.6f)), fx, line + 19, 0.6f, Ui.MUTED, true);
		}
		String summary = String.format("K/D %.2f over %d %s", PlayerScreen.kd(d.detail.kills(), d.detail.deaths()), d.detail.fights(),
				d.detail.fights() == 1 ? "fight" : "fights");
		context.drawTextWithShadow(font, summary, x + 8, y + h - 14, Ui.LABEL);
	}

	// ---- Row B ----

	private static void hitCard(DrawContext context, TextRenderer font, Data d, int x, int y, int w, int h) {
		Ui.card(context, font, x, y, w, h, "HIT RATE");
		iconAndBig(context, font, new ItemStack(Items.TARGET), pct(d.shots > 0 ? (double) d.hits / d.shots : Double.NaN), x, y, w);
		rows(context, font, x, y, w, new String[][] {
				{ "SHOTS", String.valueOf(d.shots) }, { "HITS", String.valueOf(d.hits) },
				{ "HEADSHOTS", String.valueOf(d.headshots) }, { "KILLS", String.valueOf(d.gunKills) } });
	}

	private static void headshotCard(DrawContext context, TextRenderer font, Data d, int x, int y, int w, int h) {
		Ui.card(context, font, x, y, w, h, "HS%");
		iconAndBig(context, font, new ItemStack(Items.SKELETON_SKULL), pct(d.hits > 0 ? (double) d.headshots / d.hits : Double.NaN), x, y, w);
		rows(context, font, x, y, w, new String[][] {
				{ "HEAD", String.valueOf(d.headshots) }, { "BODY", String.valueOf(d.hits - d.headshots) },
				{ "MISSED", String.valueOf(d.shots - d.hits) }, { "OF SHOTS", pct(d.shots > 0 ? (double) d.headshots / d.shots : Double.NaN) } });
	}

	/**
	 * Momentum (Wing - its swap time is in the SWAPS card), swap time (Air),
	 * melee combos (JP), or speed after movement gun shots (Ground).
	 */
	private static void thirdCard(DrawContext context, TextRenderer font, Data d, PvpCategory category, int x, int y, int w, int h) {
		switch (category) {
			case WING -> {
				// Speed kept through Wing swaps into an empty hotbar slot (see SwapRecord).
				Ui.card(context, font, x, y, w, h, "MOMENTUM");
				Averages avg = d.detail.avg();
				Double before = avg != null ? avg.speedBeforeBps() : null, after = avg != null ? avg.speedAfterBps() : null;
				boolean has = before != null && after != null;
				iconAndBig(context, font, new ItemStack(Items.ELYTRA), has ? pct(before > 0 ? after / before : 1) : "-", x, y, w);
				rows(context, font, x, y, w, new String[][] {
						{ "BEFORE", Format.bps(before) }, { "AFTER", Format.bps(after) },
						{ "LOST", has ? Format.bps(Math.max(0, before - after)) : "-" } });
			}
			case AIR -> {
				Ui.card(context, font, x, y, w, h, "SWAP TIME");
				List<AirSwapStat> stats = d.detail.airSwaps() != null ? d.detail.airSwaps() : List.of();
				double total = 0;
				int successes = 0;
				for (AirSwapStat s : stats) {
					if (s.avgMs() != null) total += s.avgMs() * s.successes();
					successes += s.successes();
				}
				iconAndBig(context, font, new ItemStack(Items.CLOCK), successes > 0 ? Format.seconds(total / successes) : "-", x, y, w);
				rows(context, font, x, y, w, new String[][] {
						{ "JETPACK", airAvg(stats, "JETPACK") }, { "WINGSUIT", airAvg(stats, "WINGSUIT") },
						{ "JP > WING", airAvg(stats, "JP_TO_WING") }, { "WING > JP", airAvg(stats, "WING_TO_JP") } });
			}
			case JP -> {
				// The MELEE card above has the rates; this one has the counts.
				Ui.card(context, font, x, y, w, h, "MELEE STATS");
				ComboStat c = d.combos;
				iconAndBig(context, font, new ItemStack(Items.IRON_SWORD), c != null ? String.valueOf(c.ownCombos()) : "-", x, y, w);
				rows(context, font, x, y, w, new String[][] {
						{ "YOUR COMBOS", c != null ? String.valueOf(c.ownCombos()) : "-" },
						{ "GOT BROKEN", c != null ? String.valueOf(c.ownBroken()) : "-" },
						{ "THEIR COMBOS", c != null ? String.valueOf(c.enemyCombos()) : "-" },
						{ "YOU BROKE", c != null ? String.valueOf(c.enemyBroken()) : "-" } });
			}
			default -> {
				// Values are blocks/s; the unit is in the title so they fit the narrow card.
				Ui.card(context, font, x, y, w, h, "MOVEMENT B/S");
				List<MovementGunStat> stats = d.detail.movementGuns() != null ? d.detail.movementGuns() : List.of();
				long shots = 0;
				double total = 0, best = 0;
				for (MovementGunStat s : stats) {
					shots += s.shots();
					if (s.avgBps() != null) total += s.avgBps() * s.shots();
					if (s.bestBps() != null) best = Math.max(best, s.bestBps());
				}
				iconAndBig(context, font, new ItemStack(Items.FEATHER), shots > 0 ? String.format("%.1f", total / shots) : "-", x, y, w);
				rows(context, font, x, y, w, new String[][] {
						{ "SAWED-OFF", movementAvg(stats, "Sawed-off Shotgun") }, { "PUMP", movementAvg(stats, "Pump Shotgun") },
						{ "REVOLVER", movementAvg(stats, "Heavy Revolver") }, { "FASTEST", shots > 0 ? String.format("%.1f", best) : "-" } });
			}
		}
	}

	private static String movementAvg(List<MovementGunStat> stats, String gun) {
		return stats.stream().filter(s -> gun.equalsIgnoreCase(s.gun()) && s.avgBps() != null).findFirst()
				.map(s -> String.format("%.1f", s.avgBps())).orElse("-");
	}

	private static String airAvg(List<AirSwapStat> stats, String type) {
		return stats.stream().filter(a -> type.equals(a.swapType())).findFirst().map(a -> Format.seconds(a.avgMs())).orElse("-");
	}

	/** Most shots (bars by shot count) or gun HS% (bars by headshot % of hits), top five. */
	private static void mostShotsCard(DrawContext context, TextRenderer font, List<GunTotal> guns, int x, int y, int w, int h) {
		Ui.card(context, font, x, y, w, h, "MOST SHOTS");
		List<GunTotal> rows = mostShotsFirst(guns);
		if (rows.isEmpty()) {
			context.drawTextWithShadow(font, "No shots yet", x + 8, y + 24, Ui.MUTED);
			return;
		}
		long maxShots = rows.getFirst().shots;
		int ry = y + 22;
		for (GunTotal g : rows.subList(0, Math.min(5, rows.size()))) {
			String value = String.valueOf(g.shots);
			int valueW = font.getWidth(value);
			context.drawTextWithShadow(font, Ui.fit(font, g.name, w - 18 - valueW), x + 8, ry, Ui.TEXT);
			Ui.textRight(context, font, value, x + w - 8, ry, Ui.TEXT);
			int barW = (int) Math.round((w - 16) * Math.max(0, Math.min(1, (double) g.shots / Math.max(1, maxShots))));
			context.fill(x + 8, ry + 10, x + 8 + Math.max(1, barW), ry + 12, Ui.BLUE);
			ry += 16;
		}
	}

	/** Width a gun name gets before columns are dropped to make room. */
	private static final int MIN_NAME_W = 45;

	/**
	 * Every gun's stats as a table, most shots first: shots, hit %, headshot %
	 * (of hits) and kills. On a narrow card, kills and then shots (also in
	 * MOST SHOTS) are dropped so the names stay readable.
	 */
	private static void gunStatsCard(DrawContext context, TextRenderer font, List<GunTotal> guns, int x, int y, int w, int h) {
		Ui.card(context, font, x, y, w, h, "GUN STATS");
		List<GunTotal> rows = mostShotsFirst(guns);
		if (rows.isEmpty()) {
			context.drawTextWithShadow(font, "No shots yet", x + 8, y + 24, Ui.MUTED);
			return;
		}
		// Columns from the right, each as wide as its widest likely value plus a gap.
		boolean showKills = true, showShots = true;
		int killsW = font.getWidth("999") + 6, pctW = font.getWidth("100%") + 6, shotsW = font.getWidth("99999") + 6;
		int left = x + 7, right = x + w - 7;
		if (right - left - 2 * pctW - killsW - shotsW < MIN_NAME_W) showKills = false;
		if (right - left - 2 * pctW - shotsW < MIN_NAME_W) showShots = false;
		int hsRight = showKills ? right - killsW : right, hitRight = hsRight - pctW, shotsRight = hitRight - pctW;
		int nameW = (showShots ? shotsRight - shotsW : shotsRight) - left;

		int ry = y + 22;
		if (showShots) Ui.textRight(context, font, "SHOTS", shotsRight, ry, Ui.MUTED);
		Ui.textRight(context, font, "HIT", hitRight, ry, Ui.MUTED);
		Ui.textRight(context, font, "HS", hsRight, ry, Ui.MUTED);
		if (showKills) Ui.textRight(context, font, "K", right, ry, Ui.MUTED);
		ry += 12;
		for (GunTotal g : rows.subList(0, Math.min(5, rows.size()))) {
			context.drawTextWithShadow(font, Ui.fit(font, g.name, nameW), left, ry, Ui.TEXT);
			if (showShots) Ui.textRight(context, font, String.valueOf(g.shots), shotsRight, ry, Ui.TEXT);
			Ui.textRight(context, font, pct(g.shots > 0 ? (double) g.hits / g.shots : Double.NaN), hitRight, ry, Ui.TEXT);
			Ui.textRight(context, font, pct(g.hits > 0 ? g.hsRate() : Double.NaN), hsRight, ry, Ui.TEXT);
			if (showKills) Ui.textRight(context, font, String.valueOf(g.kills), right, ry, Ui.TEXT);
			ry += 13;
		}
	}

	private static List<GunTotal> mostShotsFirst(List<GunTotal> guns) {
		List<GunTotal> rows = new ArrayList<>(guns);
		rows.sort((a, b) -> Long.compare(b.shots, a.shots));
		return rows;
	}

	/** An item icon with a big value beside it, like csstats' trophy + "58%". */
	private static void iconAndBig(DrawContext context, TextRenderer font, ItemStack icon, String value, int x, int y, int w) {
		context.drawItem(icon, x + 6, y + 20);
		float scale = Math.min(2f, (w - 32) / (float) Math.max(1, font.getWidth(value)));
		Ui.bigText(context, font, value, x + 26, y + 22, Math.max(1f, scale), Ui.TEXT, false);
		context.fill(x + 6, y + 42, x + w - 6, y + 43, Ui.CARD_BORDER);
	}

	/** Bold label on the left, value on the right, like csstats' PLAYED / WON / LOST. */
	private static void rows(DrawContext context, TextRenderer font, int x, int y, int w, String[][] rows) {
		int ry = y + 50;
		for (String[] row : rows) {
			int valueW = font.getWidth(row[1]);
			String label = Ui.fit(font, row[0], w - 18 - valueW);
			context.drawTextWithShadow(font, Text.literal(label).formatted(Formatting.BOLD), x + 7, ry, Ui.TEXT);
			Ui.textRight(context, font, row[1], x + w - 7, ry, Ui.TEXT);
			ry += 13;
		}
	}

	// ---- Data ----

	/** One gun (or gun type) row: its totals. */
	private record GunTotal(String name, long shots, long hits, long headshots, long kills) {
		double hsRate() {
			return hits > 0 ? (double) headshots / hits : 0;
		}
	}

	/** The numbers the cards need, for one category. */
	private static final class Data {
		final PlayerDetail detail;
		final ComboStat combos;
		final List<GunTotal> guns = new ArrayList<>();
		long shots, hits, headshots, gunKills;

		Data(PlayerDetail detail, PvpCategory category, boolean individualGuns) {
			this.detail = detail;
			ComboStat combo = null;
			if (detail.combos() != null) {
				for (ComboStat c : detail.combos()) if (category.name().equals(c.category())) combo = c;
			}
			this.combos = combo;
			Map<String, long[]> byName = new LinkedHashMap<>();
			if (detail.guns() != null) {
				for (GunStat g : detail.guns()) {
					if (!category.name().equals(g.category())) continue;
					shots += g.shots();
					hits += g.hits();
					headshots += g.headshots();
					gunKills += g.kills();
					GunType type = individualGuns ? null : GunType.of(g.gun());
					long[] t = byName.computeIfAbsent(type != null ? type.label : g.gun(), k -> new long[4]);
					t[0] += g.shots();
					t[1] += g.hits();
					t[2] += g.headshots();
					t[3] += g.kills();
				}
			}
			byName.forEach((name, t) -> guns.add(new GunTotal(name, t[0], t[1], t[2], t[3])));
		}
	}

	// ---- Helpers ----

	private static String pct(double fraction) {
		return Double.isNaN(fraction) ? "-" : Math.round(fraction * 100) + "%";
	}

	private static boolean inside(int mx, int my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}
}
