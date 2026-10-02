package com.example.gtmaddons;

import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Settings > Better near: GTM answers /near with every nearby player on
 * one long line -
 *
 *   [GTM] Players nearby:  VIP> Divine ✯ 1_loyal (27b),  [23] Patax23 (42b), ...
 *
 * This rewrites that message in place (it replaces GTM's line in chat, so
 * there's no copy) as one compact message, closest first, so the nearest
 * players can be read without opening chat:
 *
 *   [GTM] Near: Stayzz_ (4b), IPandamix (8b), Ealox (12b), ...
 *
 * Each player is just their name (rank, level and tags are dropped), bold
 * and in their rank's color (see rankColor), then their distance in gray
 * brackets. Any message that doesn't parse is left exactly as it was.
 */
public final class NearList {

	private static final String HEADER = "Players nearby:";
	/** One player: everything up to " (NNb)". */
	private static final Pattern ENTRY = Pattern.compile("\\s*(.+?)\\s*\\((\\d+)b\\)\\s*(?:,|$)");

	/** A reply counts as asked for if the player ran any command (/near, an alias...) within this long before it. */
	private static final long ASKED_WINDOW_NANOS = 15_000_000_000L;
	private static volatile long lastCommandNanos = System.nanoTime() - 2 * ASKED_WINDOW_NANOS;

	private NearList() {}

	/** Any command the player sends: a /near reply that follows is one they asked for. */
	public static void onCommand() {
		lastCommandNanos = System.nanoTime();
	}

	/**
	 * A /near reply the player didn't ask for: the server sent it with no command run just before.
	 * Those are stale or someone else's, so they are hidden instead of shown as if current.
	 */
	public static boolean isUnsolicitedReply(Text message) {
		String plain = message.getString();
		if (!plain.contains("[GTM]")) return false;
		if (!plain.contains(HEADER.substring(0, HEADER.length() - 1)) && !plain.contains("No nearby players found")) return false;
		return System.nanoTime() - lastCommandNanos > ASKED_WINDOW_NANOS;
	}

	/** One styled run of the original message. */
	private record Run(String text, Style style) {}

	private record Player(int start, int end, int distance) {}

	/** GTM's /near reply as a sorted list, or the message unchanged if it isn't one. */
	public static Text reformat(Text message) {
		List<Run> runs = new ArrayList<>();
		message.visit((Style style, String part) -> {
			if (!part.isEmpty()) runs.add(new Run(part, style));
			return Optional.empty();
		}, Style.EMPTY);
		StringBuilder all = new StringBuilder();
		for (Run run : runs) all.append(run.text());
		String plain = all.toString();

		int header = plain.indexOf(HEADER);
		if (header < 0 || !plain.substring(0, header).contains("[GTM]")) return message;

		List<Player> players = new ArrayList<>();
		Matcher m = ENTRY.matcher(plain);
		int pos = header + HEADER.length();
		while (pos < plain.length() && m.find(pos) && m.start() == pos) {
			players.add(new Player(m.start(1), m.end(1), Integer.parseInt(m.group(2))));
			pos = m.end();
		}
		// Anything left over means the format isn't what we expect: leave it alone.
		if (players.isEmpty() || !plain.substring(pos).isBlank()) return message;
		players.sort(Comparator.comparingInt(Player::distance));

		// "[GTM] " as GTM styled it, then everyone on one line, closest first.
		MutableText out = slice(runs, plain.indexOf("[GTM]"), header).copy();
		out.append(Text.literal("Near: ").formatted(Formatting.GRAY));
		for (int i = 0; i < players.size(); i++) {
			Player p = players.get(i);
			if (i > 0) out.append(Text.literal(", ").formatted(Formatting.DARK_GRAY));
			out.append(name(runs, plain, p));
			out.append(Text.literal(" (" + p.distance() + "b)").formatted(Formatting.GRAY));
		}
		return out;
	}

	/**
	 * GTM's rank colors (the rank is the word ending in ">", e.g. "VIP>"):
	 * &c Supreme, &5 Sponsor, &b Elite, &a Premium, &6 VIP.
	 */
	private static Formatting rankColor(String rank) {
		return switch (rank.toUpperCase(java.util.Locale.ROOT)) {
			case "SUPREME" -> Formatting.RED;
			case "SPONSOR" -> Formatting.DARK_PURPLE;
			case "ELITE" -> Formatting.AQUA;
			case "PREMIUM" -> Formatting.GREEN;
			case "VIP" -> Formatting.GOLD;
			default -> null;
		};
	}

	/**
	 * Just the player's name - the last word of the entry, dropping the rank
	 * ("SUPREME>"), level ("[112]") and tags ("Divine ✯") before it - in bold,
	 * in their rank's color. With no known rank it keeps the name's own color.
	 * Anything else on the name (like hover text) is kept.
	 */
	private static Text name(List<Run> runs, String plain, Player p) {
		String entry = plain.substring(p.start(), p.end());
		String[] words = entry.trim().split("\\s+");
		String name = words[words.length - 1];
		int nameStart = p.start() + entry.lastIndexOf(name);

		Style nameStyle = styleAt(runs, nameStart);
		Formatting rank = words[0].endsWith(">") ? rankColor(words[0].substring(0, words[0].length() - 1)) : null;
		TextColor color = rank != null ? TextColor.fromFormatting(rank) : nameStyle.getColor();
		return Text.literal(name).setStyle(nameStyle.withColor(color).withBold(true));
	}

	/** The style of the run the character at index is in. */
	private static Style styleAt(List<Run> runs, int index) {
		int at = 0;
		for (Run run : runs) {
			at += run.text().length();
			if (index < at) return run.style();
		}
		return Style.EMPTY;
	}

	/** The part of the message from start to end, keeping each run's style. */
	private static MutableText slice(List<Run> runs, int start, int end) {
		MutableText out = Text.empty();
		int at = 0;
		for (Run run : runs) {
			int runStart = at, runEnd = at + run.text().length();
			at = runEnd;
			int from = Math.max(start, runStart), to = Math.min(end, runEnd);
			if (from < to) out.append(Text.literal(run.text().substring(from - runStart, to - runStart)).setStyle(run.style()));
		}
		return out;
	}
}
