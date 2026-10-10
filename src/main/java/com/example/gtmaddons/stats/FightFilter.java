package com.example.gtmaddons.stats;

import com.example.gtmaddons.PvpCategory;
import com.example.gtmaddons.stats.PlayerStats.FightData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A custom selection of a player's fights for the stats pages: how many
 * (1 to 500) and, optionally, only fights against certain opponents.
 *
 * Applied to one PvP tab's fights, newest first:
 *   1. only fights of that PvP category;
 *   2. only fights against the chosen opponents (none chosen = everyone);
 *   3. the newest `limit` of those.
 * So "last 25, against Alice and Bob" is Alice's and Bob's 25 most recent
 * fights together, not the last 25 fights narrowed down afterwards.
 *
 * Opponents are matched ignoring case; a fight with no known opponent
 * (GTM didn't say who killed you) matches the empty key.
 */
public record FightFilter(int limit, Set<String> opponents) {

	public static final int MAX_FIGHTS = 500;

	public FightFilter {
		limit = Math.max(1, Math.min(MAX_FIGHTS, limit));
		opponents = Set.copyOf(opponents);
	}

	/** Every opponent, the newest `limit` fights. */
	public static FightFilter last(int limit) {
		return new FightFilter(limit, Set.of());
	}

	public boolean hasOpponents() {
		return !opponents.isEmpty();
	}

	/** The key a fight's opponent is filtered by: lowercase, or "" if unknown. */
	public static String key(String opponent) {
		return opponent == null || opponent.isBlank() ? "" : opponent.toLowerCase(Locale.ROOT);
	}

	/** The fights this selection picks from `all` (a player's fights, newest first), for one PvP tab. */
	public List<FightData> apply(List<FightData> all, PvpCategory category) {
		List<FightData> out = new ArrayList<>();
		for (FightData fight : all) {
			if (out.size() >= limit) break;
			if (category != null && !category.name().equals(fight.category())) continue;
			if (hasOpponents() && !opponents.contains(key(fight.opponent()))) continue;
			out.add(fight);
		}
		return out;
	}

	/** One opponent in the chooser: how it's filtered, how it's shown, and how many fights against them. */
	public record Opponent(String key, String name, int inCategory, int total) {}

	/**
	 * Everyone the player has fought, most fights in this PvP tab first (then
	 * most in total, then by name). Counts are over `all`.
	 */
	public static List<Opponent> opponents(List<FightData> all, PvpCategory category) {
		Map<String, String> names = new LinkedHashMap<>();
		Map<String, int[]> counts = new LinkedHashMap<>();
		for (FightData fight : all) {
			String key = key(fight.opponent());
			names.putIfAbsent(key, key.isEmpty() ? "(unknown)" : fight.opponent());
			int[] c = counts.computeIfAbsent(key, k -> new int[2]);
			c[1]++;
			if (category == null || category.name().equals(fight.category())) c[0]++;
		}
		List<Opponent> out = new ArrayList<>();
		for (Map.Entry<String, int[]> e : counts.entrySet()) {
			out.add(new Opponent(e.getKey(), names.get(e.getKey()), e.getValue()[0], e.getValue()[1]));
		}
		out.sort(Comparator.comparingInt(Opponent::inCategory).reversed()
				.thenComparing(Comparator.comparingInt(Opponent::total).reversed())
				.thenComparing(o -> o.name().toLowerCase(Locale.ROOT)));
		return out;
	}

	/** "vs Alice, Bob" (or "vs Alice, Bob and 3 more"), or "" when every opponent is included. */
	public String describeOpponents(List<FightData> all) {
		if (!hasOpponents()) return "";
		Map<String, String> names = new LinkedHashMap<>();
		for (Opponent o : opponents(all, null)) names.put(o.key(), o.name());
		List<String> shown = new ArrayList<>();
		for (String key : opponents.stream().sorted().toList()) shown.add(names.getOrDefault(key, key.isEmpty() ? "(unknown)" : key));
		if (shown.size() <= 3) return "vs " + String.join(", ", shown);
		return "vs " + String.join(", ", shown.subList(0, 3)) + " and " + (shown.size() - 3) + " more";
	}
}
