package com.example.gtmaddons.rating;

import com.example.gtmaddons.PvpCategory;
import com.example.gtmaddons.stats.PlayerStats.AirSwapStat;
import com.example.gtmaddons.stats.PlayerStats.Averages;
import com.example.gtmaddons.stats.PlayerStats.ComboStat;
import com.example.gtmaddons.stats.PlayerStats.GunStat;
import com.example.gtmaddons.stats.PlayerStats.MovementGunStat;
import com.example.gtmaddons.stats.PlayerStats.PlayerDetail;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aim, Movement and Overall ratings (0-100) for one PvP category, from a
 * player's stats for that kind of fight - the groundwork for PvP ratings.
 * All the numbers that shape them are in RatingWeights; a player at every
 * baseline scores 50.
 *
 *   Aim:      per gun used in this category, points per shot -
 *             headshots worth HEADSHOT_POINTS, body shots BODY_SHOT_POINTS,
 *             misses nothing - against that gun's baseline in this category
 *             (the median player = 50, 2x the baseline about 85, 0.5x about
 *             15), with few shots pulled toward 50. Guns are averaged, weighted
 *             by how much the gun counts (impact) and how many shots (capped).
 *   Movement: Wing - swap score and momentum kept (see Overall).
 *             Air - swap score and momentum kept, like Wing, on the same swap bounds.
 *             Ground - speed right after movement gun
 *             shots, from MOVEMENT_FLOOR_BPS (sprinting) = 0 to
 *             each gun's own ceiling = 100 (RatingWeights.movementCeiling).
 *   Melee:    JP's second rating in Movement's place (and a part of Air's
 *             Overall) - enemy combos you
 *             broke, your combos you kept, and your share of all combos
 *             (50 = even), each as a %, averaged by weight. JP calls Aim
 *             "Gun aim" (see aimLabel, secondLabel).
 *   Overall:  Wing - the composite (see RatingWeights):
 *               swap      success rate x (1 / average swap time), scored as
 *                         an effective swap time (average / success rate)
 *                         from BEST_SWAP_MS = 100 to WORST_SWAP_MS = 0
 *               momentum  % of your speed kept through the swap
 *               aim       as above, weighted more the more of your kills
 *                         come from long-range guns (snipers, Musket)
 *               kd        K/D, KD_BASELINE = 50, twice it = 100
 *             shown once swap and aim both have enough data. Ground - the
 *             same with movement in place of swap and momentum. Air - Air
 *             swap score, aim, melee (Air combos) and K/D, shown once swap
 *             and aim have enough data (no momentum: it's only recorded for
 *             Wing swaps). JP - gun aim (long-range weighted), melee and
 *             K/D, with K/D weighted JP.KD; shown once K/D and gun
 *             aim or melee have enough data.
 *
 * A rating is null when there isn't enough data for it yet. breakdown()
 * lists what went into each, for checking and tuning.
 */
public final class Ratings {

	/**
	 * movement is the category's second rating: Movement, or Melee for JP
	 * (see secondLabel). parts are what Overall is made of, in order - a part
	 * with a null score has no data yet and was left out.
	 */
	public record Result(Double aim, Double movement, Double overall, List<Part> parts, List<String> breakdown,
			boolean aimAssumed, boolean movementAssumed) {
		/** A rating worked out from real data (nothing assumed). */
		public Result(Double aim, Double movement, Double overall, List<Part> parts, List<String> breakdown) {
			this(aim, movement, overall, parts, breakdown, false, false);
		}
	}

	/** One part of a rating: its name, score (null = no data, left out) and weight. */
	public record Part(String name, Double score, double weight) {}

	/** Set while rating a single fight (see computeForFight): parts need only one sample, then are blended toward 50. */
	private static final ThreadLocal<Boolean> LENIENT = ThreadLocal.withInitial(() -> false);

	/** How much data a part needs: its normal minimum, or just one sample when rating a single fight. */
	private static int need(int min) {
		return LENIENT.get() ? 1 : min;
	}

	/**
	 * A part with fewer samples than it takes to count in full (its FULL_ amount) is pulled toward 50 as if the
	 * missing samples had scored exactly 50, so a few lucky swaps can't score 100 and a full amount is unchanged.
	 */
	private static double shrink(double score, long samples, int full) {
		if (samples >= full) return score;
		return (score * samples + RatingWeights.SCORE_AT_BASELINE * (full - samples)) / full;
	}

	private Ratings() {}

	/** What the aim rating is called: "Gun aim" in JP (next to Melee), "Aim" elsewhere. */
	public static String aimLabel(PvpCategory category) {
		return category == PvpCategory.JP ? "Gun aim" : "Aim";
	}

	/** What the second rating is called: "Melee" in JP, "Movement" elsewhere. */
	public static String secondLabel(PvpCategory category) {
		return category == PvpCategory.JP ? "Melee" : "Movement";
	}

	/** Short forms for the Overview's rating ring. */
	public static String aimShort(PvpCategory category) {
		return category == PvpCategory.JP ? "Gun" : "Aim";
	}

	public static String secondShort(PvpCategory category) {
		return category == PvpCategory.JP ? "Mel" : "Mv";
	}

	/**
	 * Ratings for a single fight (or a short run of fights) - unlike compute(), every rating is filled in:
	 *   - a part with only a little data (fewer than its normal minimum) still counts, blended toward 50 in
	 *     proportion to how much is missing;
	 *   - a part with no data at all (no shots, no swaps, no combos) is assumed to be 50, and flagged
	 *     (Result.aimAssumed / movementAssumed) so screens can show it as an estimate;
	 *   - Overall is always worked out, from the real parts plus those assumed ones.
	 * The aggregate views (Personal Stats, the leaderboard) still use compute() and need real data.
	 */
	public static Result computeForFight(PlayerDetail detail, PvpCategory category) {
		LENIENT.set(true);
		try {
			Result r = compute(detail, category);
			boolean aimMissing = r.aim() == null, movementMissing = r.movement() == null;
			List<Part> parts = new ArrayList<>();
			for (Part p : r.parts()) {
				// The parts each category's Overall is built on (see wing, ground, air, jp): none may stay empty.
				boolean required = switch (category) {
					case WING, AIR -> p.name().equals("Swap") || p.name().equals("Aim");
					case GROUND -> p.name().equals("Movement") || p.name().equals("Aim");
					case JP -> p.name().equals("Gun aim") || p.name().equals("Melee");
				};
				parts.add(p.score() == null && required ? new Part(p.name(), RatingWeights.SCORE_AT_BASELINE, p.weight()) : p);
			}
			Double aim = aimMissing ? Double.valueOf(RatingWeights.SCORE_AT_BASELINE) : r.aim();
			Double movement = movementMissing ? Double.valueOf(RatingWeights.SCORE_AT_BASELINE) : r.movement();
			Double overall = weighted(parts);
			List<String> breakdown = new ArrayList<>(r.breakdown());
			if (aimMissing) breakdown.add("  " + aimLabel(category).toLowerCase(java.util.Locale.ROOT) + ": no shots with a gun in this fight - assumed 50");
			if (movementMissing) breakdown.add("  " + secondLabel(category).toLowerCase(java.util.Locale.ROOT) + ": no data in this fight - assumed 50");
			return new Result(aim, movement, overall, parts, breakdown, aimMissing, movementMissing);
		} finally {
			LENIENT.set(false);
		}
	}

	/** Ratings from `detail`, which should be the player's fights of this category. */
	public static Result compute(PlayerDetail detail, PvpCategory category) {
		List<String> breakdown = new ArrayList<>();
		Double aim = aim(detail, category, breakdown);
		if (category == PvpCategory.JP) return jp(detail, category, aim, breakdown);
		// Melee counts inside Aim for Air only (JP keeps Gun aim and Melee apart; Wing and Ground have no melee rating).
		if (category == PvpCategory.AIR) aim = withMelee(detail, category, aim, breakdown);
		if (category == PvpCategory.WING) return wing(detail, category, aim, breakdown);
		if (category == PvpCategory.GROUND) return ground(detail, category, aim, breakdown);
		return air(detail, category, aim, breakdown);
	}

	// ---- Aim ----

	private static Double aim(PlayerDetail detail, PvpCategory category, List<String> breakdown) {
		// One row per gun used in this category: {shots, hits, headshots}.
		Map<String, long[]> guns = new LinkedHashMap<>();
		if (detail.guns() != null) {
			for (GunStat g : detail.guns()) {
				if (!category.name().equals(g.category())) continue;
				long[] t = guns.computeIfAbsent(g.gun(), k -> new long[3]);
				t[0] += g.shots();
				t[1] += g.hits();
				t[2] += g.headshots();
			}
		}
		breakdown.add("Aim - per gun: score (impact, shots, HS / body / miss)");
		double weighted = 0, totalWeight = 0;
		List<Map.Entry<String, long[]>> sorted = new ArrayList<>(guns.entrySet());
		sorted.sort(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[0]).reversed());
		for (Map.Entry<String, long[]> e : sorted) {
			String gun = e.getKey();
			long shots = e.getValue()[0], hits = e.getValue()[1], headshots = e.getValue()[2];
			Double gunScore = gunAim(category, gun, shots, hits, headshots);
			if (gunScore == null) continue;
			double score = gunScore;
			double hs = (double) headshots / shots;
			double body = (double) (hits - headshots) / shots;
			double miss = 1.0 - (double) hits / shots;
			double impact = RatingWeights.impact(gun);
			double weight = impact * Math.min(shots, RatingWeights.SHOT_WEIGHT_CAP);
			weighted += score * weight;
			totalWeight += weight;
			breakdown.add(String.format("  %s: %.0f (%.1fx, %d shots, %.0f%% / %.0f%% / %.0f%%)",
					gun, score, impact, shots, hs * 100, body * 100, miss * 100));
		}
		if (totalWeight == 0) {
			breakdown.add("  not enough shots yet (" + RatingWeights.MIN_SHOTS_PER_GUN + " with a gun)");
			return null;
		}
		return weighted / totalWeight;
	}

	/**
	 * One gun's aim score in a PvP category (see RatingWeights section 1): points per shot
	 * against the baseline (50 = the baseline), on a smooth curve, with few shots pulled
	 * toward 50. Null under MIN_SHOTS_PER_GUN.
	 */
	public static Double gunAim(PvpCategory category, String gun, long shots, long hits, long headshots) {
		if (shots < need(RatingWeights.MIN_SHOTS_PER_GUN)) return null;
		double baseline = RatingWeights.baseline(category, gun);
		double points = (double) headshots * RatingWeights.HEADSHOT_POINTS
				+ (double) (hits - headshots) * RatingWeights.BODY_SHOT_POINTS;
		// Pull toward the baseline: as if AIM_PRIOR_SHOTS more shots were fired exactly at it.
		double perShot = (points + baseline * RatingWeights.AIM_PRIOR_SHOTS) / (shots + RatingWeights.AIM_PRIOR_SHOTS);
		double curved = Math.pow(perShot / baseline, RatingWeights.AIM_CURVE);
		return clamp(100.0 * curved / (1.0 + curved));
	}

	/**
	 * Aim with melee in it: gun aim (weight 1) and the melee score (AIM_MELEE_WEIGHT), whichever have data. Melee is
	 * 50 = as good as the enemy (see meleeScore).
	 */
	private static Double withMelee(PlayerDetail detail, PvpCategory category, Double gunAim, List<String> breakdown) {
		Double melee = meleeScore(detail, category, RatingWeights.AIM_MELEE_WEIGHT, breakdown);
		if (melee == null) return gunAim;
		Double aim = weighted(List.of(new Part("Gun aim", gunAim, 1.0), new Part("Melee", melee, RatingWeights.AIM_MELEE_WEIGHT)));
		breakdown.add(String.format("  aim with melee: %.0f (gun aim %s at 1.00, melee %.0f at %.2f)", aim,
				gunAim != null ? String.format("%.0f", gunAim) : "-", melee, RatingWeights.AIM_MELEE_WEIGHT));
		return aim;
	}

	// ---- Wing: the composite ----

	private static Result wing(PlayerDetail detail, PvpCategory category, Double aim, List<String> breakdown) {
		breakdown.add("Wing rating - part: score (weight)");
		int attempts = detail.total() - detail.cancels();
		Averages avg = detail.avg();
		Double swap = null;
		if (detail.successes() < need(RatingWeights.MIN_SWAPS) || avg == null || avg.totalMs() == null) {
			breakdown.add("  swap: not enough swaps yet (" + RatingWeights.MIN_SWAPS + " successful)");
		} else {
			swap = shrink(swapScore(detail.successes(), attempts, avg.totalMs(), RatingWeights.BEST_SWAP_MS, RatingWeights.WORST_SWAP_MS, RatingWeights.Wing.SWAP, breakdown),
					detail.successes(), RatingWeights.FULL_SWAPS);
		}
		Double momentum = momentumScore(avg, breakdown);
		Double kd = kdScore(detail, RatingWeights.Wing.KD, breakdown);
		double aimWeight = aimWeight(detail, category, RatingWeights.Wing.AIM, breakdown);

		Part swapPart = new Part("Swap", swap, RatingWeights.Wing.SWAP);
		Part momentumPart = new Part("Momentum", momentum, RatingWeights.Wing.MOMENTUM);
		Double movement = weighted(swapPart, momentumPart);
		List<Part> parts = List.of(swapPart, momentumPart, new Part("Aim", aim, aimWeight), new Part("K/D", kd, RatingWeights.Wing.KD));
		Double overall = swap != null && aim != null ? weighted(parts) : null;
		if (aim != null) breakdown.add(String.format("  aim: %.0f (%.2f)", aim, aimWeight));
		if (overall == null) breakdown.add("  overall needs both swap and aim");
		return new Result(aim, movement, overall, parts, breakdown);
	}

	// ---- Ground: the composite, with movement guns for movement ----

	private static Result ground(PlayerDetail detail, PvpCategory category, Double aim, List<String> breakdown) {
		breakdown.add("Ground rating - part: score (weight)");
		Double movement = movementGunScore(detail, breakdown);
		Double kd = kdScore(detail, RatingWeights.Ground.KD, breakdown);
		double aimWeight = aimWeight(detail, category, RatingWeights.Ground.AIM, breakdown);
		List<Part> parts = List.of(new Part("Movement", movement, RatingWeights.Ground.MOVEMENT), new Part("Aim", aim, aimWeight),
				new Part("K/D", kd, RatingWeights.Ground.KD));
		Double overall = movement != null && aim != null ? weighted(parts) : null;
		if (aim != null) breakdown.add(String.format("  aim: %.0f (%.2f)", aim, aimWeight));
		if (overall == null) breakdown.add("  overall needs both movement and aim");
		return new Result(aim, movement, overall, parts, breakdown);
	}

	/**
	 * Speed right after movement gun shots: each gun's average scored from
	 * MOVEMENT_FLOOR_BPS (sprinting, the shot added nothing) = 0 to
	 * that gun's own ceiling = 100 (Heavy Revolver > Sawed-off > Pump), then averaged by shots. Null under
	 * MIN_MOVEMENT_SHOTS.
	 */
	static Double movementGunScore(PlayerDetail detail, List<String> breakdown) {
		List<MovementGunStat> guns = detail.movementGuns() != null ? detail.movementGuns() : List.of();
		long shots = guns.stream().filter(g -> g.avgBps() != null).mapToLong(MovementGunStat::shots).sum();
		if (shots < need(RatingWeights.MIN_MOVEMENT_SHOTS)) {
			breakdown.add(String.format("  movement: not enough movement gun shots yet (%d of %d)", shots, RatingWeights.MIN_MOVEMENT_SHOTS));
			return null;
		}
		double sum = 0;
		List<String> perGun = new ArrayList<>();
		for (MovementGunStat g : guns) {
			if (g.avgBps() == null) continue;
			double score = RatingWeights.movementScore(g.gun(), g.avgBps());
			sum += score * g.shots();
			perGun.add(String.format("    %s: %.0f (%d shots, avg %.1f b/s)", g.gun(), score, g.shots(), g.avgBps()));
		}
		double score = shrink(sum / shots, shots, RatingWeights.FULL_MOVEMENT_SHOTS);
		breakdown.add(String.format("  movement: %.0f (%.2f)", score, RatingWeights.Ground.MOVEMENT));
		breakdown.addAll(perGun);
		return score;
	}

	// ---- JP: gun aim, melee and (heavily) K/D ----

	private static Result jp(PlayerDetail detail, PvpCategory category, Double gunAim, List<String> breakdown) {
		breakdown.add("JP rating - part: score (weight)");
		Double melee = meleeScore(detail, category, RatingWeights.JP.MELEE, breakdown);
		Double kd = kdScore(detail, RatingWeights.JP.KD, breakdown);
		double aimWeight = aimWeight(detail, category, RatingWeights.JP.AIM, breakdown);
		List<Part> parts = List.of(new Part("Gun aim", gunAim, aimWeight), new Part("Melee", melee, RatingWeights.JP.MELEE),
				new Part("K/D", kd, RatingWeights.JP.KD));
		Double overall = kd != null && (gunAim != null || melee != null) ? weighted(parts) : null;
		if (gunAim != null) breakdown.add(String.format("  gun aim: %.0f (%.2f)", gunAim, aimWeight));
		if (overall == null) breakdown.add("  overall needs K/D and gun aim or melee");
		return new Result(gunAim, melee, overall, parts, breakdown);
	}

	/**
	 * Melee from this category's combos: the % of enemy combos you broke, the
	 * % of your combos you kept, and your share of all combos (50 = as many as
	 * the enemy), averaged by weight. Null under MIN_COMBOS.
	 */
	static Double meleeScore(PlayerDetail detail, PvpCategory category, double weight, List<String> breakdown) {
		ComboStat c = null;
		if (detail.combos() != null) {
			for (ComboStat s : detail.combos()) if (category.name().equals(s.category())) c = s;
		}
		long total = c != null ? c.enemyCombos() + c.ownCombos() : 0;
		if (c == null || total < need(RatingWeights.MIN_COMBOS)) {
			breakdown.add(String.format("  melee: not enough combos yet (%d of %d)", total, RatingWeights.MIN_COMBOS));
			return null;
		}
		Double broke = c.enemyCombos() > 0 ? 100.0 * c.enemyBroken() / c.enemyCombos() : null;
		Double kept = c.ownCombos() > 0 ? 100.0 * (c.ownCombos() - c.ownBroken()) / c.ownCombos() : null;
		double share = 100.0 * c.ownCombos() / total;
		double score = shrink(weighted(List.of(new Part("Broke", broke, RatingWeights.MELEE_BROKE_WEIGHT),
				new Part("Kept", kept, RatingWeights.MELEE_KEPT_WEIGHT), new Part("Share", share, RatingWeights.MELEE_SHARE_WEIGHT))),
				total, RatingWeights.FULL_COMBOS);
		breakdown.add(String.format("  melee: %.0f (%.2f)", score, weight));
		breakdown.add(broke != null ? String.format("    broke: %.0f (%d of %d enemy combos)", broke, c.enemyBroken(), c.enemyCombos())
				: "    broke: no enemy combos");
		breakdown.add(kept != null ? String.format("    kept: %.0f (%d of %d own combos)", kept, c.ownCombos() - c.ownBroken(), c.ownCombos())
				: "    kept: no own combos");
		breakdown.add(String.format("    share: %.0f (%d own, %d enemy)", share, c.ownCombos(), c.enemyCombos()));
		return score;
	}

	/**
	 * Speed and reliability together: success rate x (1 / average swap time),
	 * scored as the effective swap time it works out to (average / success
	 * rate) - bestMs scores 100, worstMs 0. Used by Wing; Air can use it with
	 * the AIR_ bounds.
	 */
	static double swapScore(int successes, int attempts, double avgMs, double bestMs, double worstMs, double weight, List<String> breakdown) {
		double successRate = attempts > 0 ? (double) successes / attempts : 1.0;
		double effectiveMs = avgMs / Math.max(0.01, successRate);
		double score = clamp(100.0 * (worstMs - effectiveMs) / (worstMs - bestMs));
		breakdown.add(String.format("  swap: %.0f (%.0f%% success, avg %.3fs = %.3fs effective) (%.2f)",
				score, successRate * 100, avgMs / 1000, effectiveMs / 1000, weight));
		return score;
	}

	/** % of your speed kept through Wing swaps into an empty hotbar slot, or null without any. */
	static Double momentumScore(Averages avg, List<String> breakdown) {
		if (avg == null || avg.speedBeforeBps() == null || avg.speedAfterBps() == null || avg.speedBeforeBps() <= 0) {
			breakdown.add("  momentum: no swaps into an empty slot yet");
			return null;
		}
		double score = clamp(100.0 * avg.speedAfterBps() / avg.speedBeforeBps());
		breakdown.add(String.format("  momentum: %.0f (%.1f -> %.1f b/s) (%.2f)", score, avg.speedBeforeBps(), avg.speedAfterBps(),
				RatingWeights.Wing.MOMENTUM));
		return score;
	}

	/** K/D against KD_BASELINE (50 there, 100 at twice it), or null with no fights. weight is only for the breakdown. */
	static Double kdScore(PlayerDetail detail, double weight, List<String> breakdown) {
		if (detail.fights() == 0) {
			breakdown.add("  kd: no fights yet");
			return null;
		}
		double kd = detail.deaths() > 0 ? (double) detail.kills() / detail.deaths() : detail.kills();
		double score = clamp(RatingWeights.SCORE_AT_BASELINE * kd / RatingWeights.KD_BASELINE);
		breakdown.add(String.format("  kd: %.0f (K/D %.2f) (%.2f)", score, kd, weight));
		return score;
	}

	/**
	 * aim weight = baseWeight x (1 + LONG_RANGE_AIM_BONUS x long-range kill share): the
	 * more of your kills come from long-range guns in this category, the
	 * more aim counts. Kills are the view's kills (or gun kills, if more).
	 */
	static double aimWeight(PlayerDetail detail, PvpCategory category, double baseWeight, List<String> breakdown) {
		long gunKills = 0, longRange = 0;
		if (detail.guns() != null) {
			for (GunStat g : detail.guns()) {
				if (!category.name().equals(g.category())) continue;
				gunKills += g.kills();
				if (RatingWeights.isLongRange(g.gun())) longRange += g.kills();
			}
		}
		long kills = Math.max(detail.kills(), gunKills);
		double fraction = kills > 0 ? (double) longRange / kills : 0.0;
		breakdown.add(String.format("  long-range kills: %d of %d (%.0f%%)", longRange, kills, fraction * 100));
		return baseWeight * (1 + RatingWeights.LONG_RANGE_AIM_BONUS * fraction);
	}

	private static Double weighted(Part... parts) {
		return weighted(List.of(parts));
	}

	/** Weighted average of the parts that have a score; null if none do. */
	private static Double weighted(List<Part> parts) {
		double sum = 0, weights = 0;
		for (Part p : parts) {
			if (p.score() == null) continue;
			sum += p.score() * p.weight();
			weights += p.weight();
		}
		return weights > 0 ? sum / weights : null;
	}

	// ---- Air: everything it tracks ----

	/**
	 * Air: the Air swap score (all four swap types together, on the AIR_
	 * bounds), melee from Air combos, gun aim with the long-range weight, and
	 * K/D. Movement is the swap score. There's no momentum part: momentum is
	 * only recorded for Wing swaps.
	 */
	private static Result air(PlayerDetail detail, PvpCategory category, Double aim, List<String> breakdown) {
		breakdown.add("Air rating - part: score (weight)");
		List<AirSwapStat> stats = detail.airSwaps() != null ? detail.airSwaps() : List.of();
		int attempts = 0, successes = 0;
		double totalMs = 0;
		for (AirSwapStat s : stats) {
			attempts += s.total() - s.cancels();
			successes += s.successes();
			if (s.avgMs() != null) totalMs += s.avgMs() * s.successes();
		}
		Double swap = null;
		if (successes < need(RatingWeights.MIN_SWAPS)) {
			breakdown.add("  swap: not enough swaps yet (" + RatingWeights.MIN_SWAPS + " successful)");
		} else {
			swap = shrink(swapScore(successes, attempts, totalMs / successes, RatingWeights.BEST_SWAP_MS, RatingWeights.WORST_SWAP_MS, RatingWeights.Air.SWAP, breakdown),
					successes, RatingWeights.FULL_SWAPS);
		}
		// Momentum, over all Air swap types together: speed after / speed before, weighted by the swaps that have both.
		double before = 0, after = 0;
		int moving = 0;
		for (AirSwapStat s : stats) {
			if (s.speedBeforeBps() == null || s.speedAfterBps() == null || s.momentumSwaps() <= 0) continue;
			before += s.speedBeforeBps() * s.momentumSwaps();
			after += s.speedAfterBps() * s.momentumSwaps();
			moving += s.momentumSwaps();
		}
		Double momentum = null;
		if (moving > 0 && before > 0) {
			momentum = clamp(100.0 * after / before);
			breakdown.add(String.format("  momentum: %.0f (%.1f -> %.1f b/s over %d swaps) (%.2f)", momentum, before / moving, after / moving, moving,
					RatingWeights.Air.MOMENTUM));
		} else {
			breakdown.add("  momentum: no wingsuit swaps into an empty slot yet");
		}
		Part swapPart = new Part("Swap", swap, RatingWeights.Air.SWAP);
		Part momentumPart = new Part("Momentum", momentum, RatingWeights.Air.MOMENTUM);
		Double movement = weighted(swapPart, momentumPart);
		Double kd = kdScore(detail, RatingWeights.Air.KD, breakdown);
		double aimWeight = aimWeight(detail, category, RatingWeights.Air.AIM, breakdown);
		List<Part> parts = List.of(swapPart, momentumPart, new Part("Aim", aim, aimWeight), new Part("K/D", kd, RatingWeights.Air.KD));
		Double overall = swap != null && aim != null ? weighted(parts) : null;
		if (aim != null) breakdown.add(String.format("  aim: %.0f (%.2f)", aim, aimWeight));
		if (overall == null) breakdown.add("  overall needs both swap and aim");
		return new Result(aim, movement, overall, parts, breakdown);
	}

	private static double clamp(double value) {
		return Math.max(0, Math.min(100, value));
	}
}
