package com.example.gtmaddons.stats;

import com.example.gtmaddons.PvpCategory;
import com.example.gtmaddons.rating.Ratings;
import com.example.gtmaddons.stats.PlayerStats.PlayerDetail;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Ranks players for one PvP category by their Overall rating, highest first.
 * The ratings are worked out here from each player's stats, exactly as on the
 * stats pages (see Ratings). Players whose Overall can't be rated yet (not enough
 * shots, swaps or combos) go below everyone who can be, in the order of their
 * Aim rating and then their fights, and have no rank number.
 */
public final class Leaderboard {
	private Leaderboard() {}

	/** rank is 1-based for rated players and 0 for the unranked ones below them. */
	public record Entry(int rank, PlayerDetail detail, Ratings.Result ratings) {
		public boolean ranked() {
			return rank > 0;
		}
	}

	public static List<Entry> rank(List<PlayerDetail> players, PvpCategory category) {
		List<Entry> entries = new ArrayList<>();
		for (PlayerDetail player : players) entries.add(new Entry(0, player, Ratings.compute(player, category)));

		Comparator<Entry> byOverall = Comparator.comparing((Entry e) -> e.ratings().overall(), Comparator.nullsLast(Comparator.reverseOrder()));
		entries.sort(byOverall
				.thenComparing(e -> e.ratings().aim(), Comparator.nullsLast(Comparator.reverseOrder()))
				.thenComparing(Comparator.comparingInt((Entry e) -> e.detail().fights()).reversed())
				.thenComparing(e -> String.valueOf(e.detail().name()).toLowerCase(Locale.ROOT)));

		List<Entry> out = new ArrayList<>();
		int rank = 0;
		for (Entry e : entries) {
			out.add(new Entry(e.ratings().overall() != null ? ++rank : 0, e.detail(), e.ratings()));
		}
		return out;
	}
}
