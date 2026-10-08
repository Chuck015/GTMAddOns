package com.example.gtmaddons.gui;

import com.example.gtmaddons.PvpCategory;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

/** Shared look for the stats screens: panels, bars and colors. All colors are ARGB. */
final class Ui {
	private Ui() {}

	static final int TEXT = 0xFFFFFFFF;
	static final int LABEL = 0xFFB8B8C0;
	static final int MUTED = 0xFF7C7C86;
	static final int GOOD = 0xFF55FF55;
	static final int OK = 0xFFFFDD55;
	static final int BAD = 0xFFFF5555;

	private static final int BAR_TRACK = 0xFF26262E;

	/** Each PvP category's accent color, used for its tab, panel headers and bars. */
	static int accent(PvpCategory category) {
		return switch (category) {
			case GROUND -> 0xFF7BD66B;
			case WING -> 0xFF3D8BE8;  // blue
			case JP -> 0xFFE8453D;    // red
			case AIR -> 0xFF9B55E0;   // purple, between the two
		};
	}

	/** A dark panel with a thin border and an accent strip along the top. */
	static void panel(DrawContext context, int x, int y, int w, int h, int accent) {
		context.fill(x, y, x + w, y + h, CARD);
		context.fill(x, y, x + w, y + 1, CARD_BORDER);
		context.fill(x, y + h - 1, x + w, y + h, CARD_BORDER);
		context.fill(x, y, x + 1, y + h, CARD_BORDER);
		context.fill(x + w - 1, y, x + w, y + h, CARD_BORDER);
		context.fill(x, y, x + w, y + 2, accent);
	}

	/** A thin progress bar; fraction is clamped to 0-1. */
	static void bar(DrawContext context, int x, int y, int w, int h, double fraction, int color) {
		context.fill(x, y, x + w, y + h, BAR_TRACK);
		int filled = (int) Math.round(w * Math.max(0.0, Math.min(1.0, fraction)));
		if (filled > 0) context.fill(x, y, x + filled, y + h, color);
	}

	static void textRight(DrawContext context, TextRenderer font, String text, int right, int y, int color) {
		context.drawTextWithShadow(font, text, right - font.getWidth(text), y, color);
	}

	/** Word-wraps text to lines no wider than width (a single long word stays on its own line). */
	static java.util.List<String> wrap(TextRenderer font, String text, int width) {
		java.util.List<String> lines = new java.util.ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			String tryLine = line.length() == 0 ? word : line + " " + word;
			if (line.length() > 0 && font.getWidth(tryLine) > width) {
				lines.add(line.toString());
				line = new StringBuilder(word);
			} else {
				line = new StringBuilder(tryLine);
			}
		}
		if (line.length() > 0) lines.add(line.toString());
		return lines;
	}

	/** Cuts a line into pieces no wider than width (at any character, so long unbroken text such as JSON fits too). */
	static java.util.List<String> chunks(TextRenderer font, String text, int width) {
		java.util.List<String> out = new java.util.ArrayList<>();
		String rest = text;
		while (!rest.isEmpty()) {
			String head = font.trimToWidth(rest, Math.max(1, width));
			if (head.isEmpty()) head = rest.substring(0, 1);
			out.add(head);
			rest = rest.substring(head.length());
		}
		if (out.isEmpty()) out.add("");
		return out;
	}

	/** Cuts text to fit width, ending in "..." if it had to. */
	static String fit(TextRenderer font, String text, int width) {
		if (font.getWidth(text) <= width) return text;
		String dots = "...";
		return font.trimToWidth(text, Math.max(0, width - font.getWidth(dots))) + dots;
	}

	/**
	 * Color for a rating score (0-100, 50 = the average player): red, through yellow at 50, to green. Ratings use
	 * this everywhere (leaderboard, fight log, insight pages, the overview ring); rateColor is for percentages.
	 */
	static int scoreColor(double score) {
		return gradient(score / 100.0);
	}

	/**
	 * What a typical player (the median of the players with data, 2026-10-02) gets in each PvP category: the share of
	 * shots that hit, and the share of hits that are headshots. Hit rates are low in GTM; these set where yellow is.
	 */
	static double typicalHitRate(PvpCategory category) {
		return switch (category) {
			case GROUND -> 0.184;
			case WING -> 0.166;
			case AIR -> 0.110;
			case JP -> 0.110;
		};
	}

	static double typicalHeadshotShare(PvpCategory category) {
		return switch (category) {
			case GROUND -> 0.126;
			case WING -> 0.106;
			case AIR -> 0.166;
			case JP -> 0.196;
		};
	}

	/** Color for a value against what a typical player gets: red at 0, yellow at typical, green at twice typical. White with no data. */
	static int relativeColor(double value, double typical) {
		return Double.isNaN(value) || typical <= 0 ? TEXT : gradient(value / typical / 2.0);
	}

	/** Color for a swap time: green at the best time the ratings use, red at the worst. */
	static int swapTimeColor(double ms, double bestMs, double worstMs) {
		return scoreColor(Math.max(0.0, Math.min(100.0, 100.0 * (worstMs - ms) / (worstMs - bestMs))));
	}

	/** Green / yellow / red for a success-style percentage. */
	static int rateColor(double percent) {
		return percent >= 75 ? GOOD : percent >= 50 ? OK : BAD;
	}

	// ---- csstats-style overview pieces ----

	/** The overview's page background, cards and bars (dark navy, like csstats). */
	static final int PAGE = 0xE60E1014;
	static final int CARD = 0xFF1B1D24;
	static final int CARD_BORDER = 0xFF262933;
	static final int TRACK = 0xFF2C303B;
	static final int BLUE = 0xFF3F7DF0;
	static final int ORANGE = 0xFFF0A53F;

	/** The accent the overview is drawing its cards in (0 = none); set by StatsOverview.draw. */
	static int cardAccent = 0;

	/** A flat dark card with a thin border, an accent strip along the top, and its title in bold caps in the accent. */
	static void card(DrawContext context, TextRenderer font, int x, int y, int w, int h, String title) {
		context.fill(x, y, x + w, y + h, CARD);
		context.fill(x, y, x + w, y + 1, CARD_BORDER);
		context.fill(x, y + h - 1, x + w, y + h, CARD_BORDER);
		context.fill(x, y, x + 1, y + h, CARD_BORDER);
		context.fill(x + w - 1, y, x + w, y + h, CARD_BORDER);
		if (cardAccent != 0) context.fill(x, y, x + w, y + 2, cardAccent);
		context.drawTextWithShadow(font, net.minecraft.text.Text.literal(title).formatted(net.minecraft.util.Formatting.BOLD), x + 6, y + 6, cardAccent != 0 ? cardAccent : TEXT);
	}

	/** Text drawn scale times bigger; x is its left edge, or its center if centered. */
	static void bigText(DrawContext context, TextRenderer font, String text, int x, int y, float scale, int color, boolean centered) {
		var matrices = context.getMatrices();
		matrices.pushMatrix();
		matrices.translate(x, y);
		matrices.scale(scale, scale);
		context.drawTextWithShadow(font, text, centered ? -font.getWidth(text) / 2 : 0, 0, color);
		matrices.popMatrix();
	}

	/** How wide text is at a scale. */
	static int bigWidth(TextRenderer font, String text, float scale) {
		return Math.round(font.getWidth(text) * scale);
	}

	/**
	 * A ring gauge centered on (cx, cy): the first fraction of it (clockwise
	 * from the top) in color, the rest in the track color.
	 */
	static void ring(DrawContext context, int cx, int cy, int radius, int thickness, double fraction, int color) {
		arc(context, cx, cy, radius, radius - thickness, fraction, color, TRACK);
	}

	/** A small pie chart: fraction of it (clockwise from the top) in color. */
	static void pie(DrawContext context, int cx, int cy, int radius, double fraction, int color) {
		arc(context, cx, cy, radius, -1, fraction, color, TRACK);
	}

	/** A filled dot. */
	static void dot(DrawContext context, int cx, int cy, int radius, int color) {
		arc(context, cx, cy, radius, -1, 1.0, color, color);
	}

	/**
	 * Draws the pixels between two radii, colored by angle - one fill per
	 * run of same-colored pixels in a row, so rings stay cheap to draw.
	 */
	private static void arc(DrawContext context, int cx, int cy, int outer, int inner, double fraction, int color, int track) {
		double f = Math.max(0.0, Math.min(1.0, fraction));
		for (int dy = -outer; dy < outer; dy++) {
			int runStart = 0, runColor = 0;
			for (int dx = -outer; dx <= outer; dx++) {
				int c = 0;
				if (dx < outer) {
					double px = dx + 0.5, py = dy + 0.5, d = Math.hypot(px, py);
					if (d <= outer && d > inner) {
						double angle = Math.atan2(px, -py);
						if (angle < 0) angle += Math.PI * 2;
						c = angle / (Math.PI * 2) <= f ? color : track;
					}
				}
				if (c != runColor) {
					if (runColor != 0) context.fill(cx + runStart, cy + dy, cx + dx, cy + dy + 1, runColor);
					runStart = dx;
					runColor = c;
				}
			}
		}
	}

	/** Red through yellow to green as fraction goes from 0 to 1. */
	static int gradient(double fraction) {
		double f = Math.max(0.0, Math.min(1.0, fraction));
		int from = f < 0.5 ? BAD : OK, to = f < 0.5 ? OK : GOOD;
		double t = f < 0.5 ? f * 2 : (f - 0.5) * 2;
		int r = (int) (((from >> 16) & 0xFF) * (1 - t) + ((to >> 16) & 0xFF) * t);
		int g = (int) (((from >> 8) & 0xFF) * (1 - t) + ((to >> 8) & 0xFF) * t);
		int b = (int) ((from & 0xFF) * (1 - t) + (to & 0xFF) * t);
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}
}
