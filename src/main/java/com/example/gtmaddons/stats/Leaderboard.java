package com.example.gtmaddons.stats;

import com.example.gtmaddons.PvpCategory;
import com.example.gtmaddons.rating.Ratings;
import com.example.gtmaddons.stats.PlayerStats.PlayerDetail;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Ranks players for one PvP category, highest first - by their Overall rating unless another column is picked
 * (Sort; the buttons above the leaderboard). The ratings are worked out here from each player's stats,
 * exactly as on the stats pages (see Ratings). Players who have no value in the chosen column (not enough shots,
 * swaps or combos to be rated) go below everyone who has, in the order of their Overall, their Aim rating and then
 * their fights, and have no rank number.
 */
public final class Leaderboard {
	private Leaderboard() {}

	/** rank is 1-based for rated players and 0 for the unranked ones below them. */
	public record Entry(int rank, PlayerDetail detail, Ratings.Result ratings) {
		public boolean ranked() {
			return rank > 0;
		}
	}

	/** What the list can be ranked by: the leaderboard's columns, the same for every PvP. */
	public enum Sort {
		PERFORMANCE(e -> e.ratings().performance()),
		SKILL(e -> e.ratings().skill()),
		OVERALL(e -> e.ratings().overall()),
		AIM(e -> e.ratings().aim()),
		MOVEMENT(e -> e.ratings().movement()),
		KD(e -> e.detail().fights() > 0 ? kd(e.detail()) : null),
		FIGHTS(e -> e.detail().fights() > 0 ? (double) e.detail().fights() : null);

		private final Function<Entry, Double> value;

		Sort(Function<Entry, Double> value) {
			this.value = value;
		}

		/** The three ratings that get a button above the leaderboard; the other columns are picked in Filter.... */
		public static List<Sort> mainButtons() {
			return List.of(PERFORMANCE, SKILL, OVERALL);
		}

		/** The leaderboard's columns, left to right (the same for every category). */
		public static List<Sort> available(PvpCategory category) {
			return List.of(PERFORMANCE, SKILL, OVERALL, AIM, MOVEMENT, KD, FIGHTS);
		}
	}

	private static double kd(PlayerDetail p) {
		return p.deaths() > 0 ? (double) p.kills() / p.deaths() : p.kills();
	}

	public static List<Entry> rank(List<PlayerDetail> players, PvpCategory category) {
		return rank(players, category, Sort.OVERALL);
	}

	public static List<Entry> rank(List<PlayerDetail> players, PvpCategory category, Sort sort) {
		List<Entry> entries = new ArrayList<>();
		for (PlayerDetail player : players) entries.add(new Entry(0, player, Ratings.compute(player, category)));

		Comparator<Double> highFirst = Comparator.nullsLast(Comparator.reverseOrder());
		entries.sort(Comparator.comparing((Entry e) -> sort.value.apply(e), highFirst)
				.thenComparing(e -> e.ratings().overall(), highFirst)
				.thenComparing(e -> e.ratings().aim(), highFirst)
				.thenComparing(Comparator.comparingInt((Entry e) -> e.detail().fights()).reversed())
				.thenComparing(e -> String.valueOf(e.detail().name()).toLowerCase(Locale.ROOT)));

		List<Entry> out = new ArrayList<>();
		int rank = 0;
		for (Entry e : entries) {
			out.add(new Entry(sort.value.apply(e) != null ? ++rank : 0, e.detail(), e.ratings()));
		}
		return out;
	}
}
