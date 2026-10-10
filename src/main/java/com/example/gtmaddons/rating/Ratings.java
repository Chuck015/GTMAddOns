package com.example.gtmaddons.rating;

import com.example.gtmaddons.PvpCategory;
import com.example.gtmaddons.stats.PlayerStats.AirSwapStat;
import com.example.gtmaddons.stats.PlayerStats.Averages;
import com.example.gtmaddons.stats.PlayerStats.ComboStat;
import com.example.gtmaddons.stats.PlayerStats.GunStat;
import com.example.gtmaddons.stats.PlayerStats.MovementGunStat;
import com.example.gtmaddons.stats.PlayerStats.OpponentOutcome;
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
 *               swap speed        average swap time, from the owner's table
 *                                 (100 ms = 100 ... 700 ms = 0)
 *               swap reliability  success rate, from the owner's table
 *                                 (98% = 100 ... 50% = 0)
 *               momentum  % of your speed kept through the swap
 *               aim       as above, weighted more the more of your kills
 *                         come from long-range guns (snipers, Musket)
 *               kd        K/D, KD_BASELINE = 50, twice it = 100
 *             shown once swap and aim both have enough data. Ground - the
 *             same with movement in place of swap and momentum. Air - Air
 *             swap score, aim, melee (Air combos) and K/D, shown once swap
 *             and aim have enough data; Air is rated like Wing throughout
 *             (the same weights, shot impacts and netted shots). JP - gun aim (long-range weighted), melee and
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
			boolean aimAssumed, boolean movementAssumed, Double skill, Double performance) {
		/** A rating worked out from real data (nothing assumed). */
		public Result(Double aim, Double movement, Double overall, List<Part> parts, List<String> breakdown) {
			this(aim, movement, overall, parts, breakdown, false, false, null, null);
		}

		public Result(Double aim, Double movement, Double overall, List<Part> parts, List<String> breakdown, boolean aimAssumed, boolean movementAssumed) {
			this(aim, movement, overall, parts, breakdown, aimAssumed, movementAssumed, null, null);
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
					case WING, AIR -> p.name().startsWith("Swap") || p.name().equals("Aim");
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
				long[] t = guns.computeIfAbsent(g.gun(), k -> new long[5]);
				t[0] += g.shots();
				t[1] += g.hits();
				t[2] += g.headshots();
				t[3] += g.netShots();
				t[4] += g.netHeadshots();
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
			double impact = RatingWeights.aimImpact(category, gun, shots, headshots, e.getValue()[3], e.getValue()[4]);
			double weight = impact * Math.min(shots, RatingWeights.SHOT_WEIGHT_CAP);
			weighted += score * weight;
			totalWeight += weight;
			breakdown.add(String.format("  %s: %.0f (impact %.1f, %d shots, %.0f%% / %.0f%% / %.0f%%)",
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
		if (RatingWeights.isNetLauncher(gun)) {
			// Slows opponents rather than killing: judged by hit rate against a low ceiling, gently (see RatingWeights.NET_LAUNCHER_HIT_CEILING).
			double rate = (hits + RatingWeights.NET_LAUNCHER_PRIOR_RATE * RatingWeights.AIM_PRIOR_SHOTS) / (shots + RatingWeights.AIM_PRIOR_SHOTS);
			return clamp(100.0 * Math.pow(Math.min(1.0, rate / RatingWeights.NET_LAUNCHER_HIT_CEILING), RatingWeights.NET_LAUNCHER_HIT_EXPONENT));
		}
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
		Double swapSpeed = null, swapReliability = null;
		if (detail.successes() < need(RatingWeights.MIN_SWAPS) || avg == null || avg.totalMs() == null) {
			breakdown.add("  swap: not enough swaps yet (" + RatingWeights.MIN_SWAPS + " successful)");
		} else {
			double[] scores = swapScores(detail.successes(), attempts, avg.totalMs(), RatingWeights.Wing.SWAP_SPEED, RatingWeights.Wing.SWAP_RELIABILITY, breakdown);
			swapSpeed = shrink(scores[0], detail.successes(), RatingWeights.FULL_SWAPS);
			swapReliability = shrink(scores[1], detail.successes(), RatingWeights.FULL_SWAPS);
		}
		Double momentum = momentumScore(avg, breakdown);
		Double kd = kdScore(detail, RatingWeights.Wing.KD, breakdown);
		double aimWeight = aimWeight(detail, category, RatingWeights.Wing.AIM, breakdown);

		Part speedPart = new Part("Swap speed", swapSpeed, RatingWeights.Wing.SWAP_SPEED);
		Part reliabilityPart = new Part("Swap reliability", swapReliability, RatingWeights.Wing.SWAP_RELIABILITY);
		Part momentumPart = new Part("Momentum", momentum, RatingWeights.Wing.MOMENTUM);
		Double movement = weighted(speedPart, reliabilityPart, momentumPart);
		List<Part> parts = List.of(speedPart, reliabilityPart, momentumPart, new Part("Aim", aim, aimWeight), new Part("K/D", kd, RatingWeights.Wing.KD));
		Double overall = swapSpeed != null && aim != null ? weighted(parts) : null;
		if (aim != null) breakdown.add(String.format("  aim: %.0f (%.2f)", aim, aimWeight));
		if (overall == null) breakdown.add("  overall needs both swap and aim");

		Double[] extra = skillAndPerformance(detail, category, swapSkillParts(swapSpeed, swapReliability, momentum, aim), swapSpeed != null && aim != null, "swaps and aim", breakdown);
		return new Result(aim, movement, overall, parts, breakdown, false, false, extra[0], extra[1]);
	}

	/**
	 * Skill and Performance, the same for every PvP (RatingWeights.Skill / Performance): Skill is the category's mechanical parts only
	 * (no results); Performance is 40% Skill, 35% K/D, 15% kills per fight (the win rate) and 10% survival time (how long the fights
	 * you died in lasted). ready says whether the mechanical parts have enough data (the same rule as the category's Overall, minus K/D).
	 * Returns {skill, performance}, either null without enough data.
	 */
	private static Double[] skillAndPerformance(PlayerDetail detail, PvpCategory category, List<Part> skillParts, boolean ready, String needs, List<String> breakdown) {
		Double skill = ready ? weighted(skillParts) : null;
		// The Results rating counts kills by who you killed (RatingWeights.winWeight); deaths in full. Overall's K/D is not adjusted.
		double wonKills = weightedKills(detail, category);
		Double kd = null, killsPerFight = null;
		if (detail.fights() > 0) {
			double ratio = detail.deaths() > 0 ? wonKills / detail.deaths() : wonKills;
			kd = clamp(RatingWeights.SCORE_AT_BASELINE * ratio / RatingWeights.KD_BASELINE);
			double decided = wonKills + detail.deaths();
			killsPerFight = decided > 0 ? RatingWeights.winRateScore(wonKills / decided) : null;
		}
		Double survival = null;
		if (detail.deathMs() != null && detail.deaths() > 0) {
			survival = shrink(RatingWeights.survivalScore(detail.deathMs() / 1000.0), detail.deaths(), RatingWeights.FULL_DEATHS);
		}
		List<Part> performanceParts = List.of(new Part("Mechanics", skill, RatingWeights.Performance.MECHANICS),
				new Part("K/D", kd, RatingWeights.Performance.KD), new Part("Kills per fight", killsPerFight, RatingWeights.Performance.KILLS_PER_FIGHT),
				new Part("Survival time", survival, RatingWeights.Performance.SURVIVAL));
		Double performance = skill != null ? weighted(performanceParts) : null;
		breakdown.add("Mechanics rating (no results) - part: score (weight)");
		for (Part p : skillParts) breakdown.add(String.format("  %s: %s (%.2f)", p.name().toLowerCase(java.util.Locale.ROOT), p.score() != null ? String.format("%.0f", p.score()) : "-", p.weight()));
		breakdown.add(skill != null ? String.format("  mechanics: %.0f", skill) : "  mechanics needs " + needs);
		breakdown.add("Results rating - part: score (weight)");
		for (Part p : performanceParts) breakdown.add(String.format("  %s: %s (%.2f)", p.name().toLowerCase(java.util.Locale.ROOT), p.score() != null ? String.format("%.0f", p.score()) : "-", p.weight()));
		if (detail.fights() > 0) breakdown.add(String.format("    kills per fight: %d of %d fights (%.0f%%)", detail.kills(), detail.fights(), 100.0 * detail.kills() / detail.fights()));
		if (Math.abs(wonKills - detail.kills()) > 1e-9) {
			breakdown.add(String.format("    opponent adjustment: your %d kills count as %.1f (a kill on a Ground opponent counts %.2f)", detail.kills(), wonKills, RatingWeights.EASY_OPPONENT_WIN_WEIGHT));
		}
		if (detail.deathMs() != null) breakdown.add(String.format("    survival: %.1f s on average in the %d fights you died in", detail.deathMs() / 1000.0, detail.deaths()));
		breakdown.add(performance != null ? String.format("  results: %.0f", performance) : "  results needs the mechanics rating");
		return new Double[] { skill, performance };
	}

	/** Your kills counted by the opponent's PvP (RatingWeights.winWeight); all of them in full where the backend sent no breakdown. */
	private static double weightedKills(PlayerDetail detail, PvpCategory category) {
		if (detail.byOpponent() == null || detail.byOpponent().isEmpty()) return detail.kills();
		double sum = 0;
		long counted = 0;
		for (OpponentOutcome o : detail.byOpponent()) {
			sum += o.kills() * RatingWeights.winWeight(category, o.category() == null || o.category().isEmpty() ? null : o.category());
			counted += o.kills();
		}
		return sum + Math.max(0, detail.kills() - counted);
	}

	/** The mechanical parts of the two swap PvPs (Wing and Air share them, see RatingWeights.Air). */
	private static List<Part> swapSkillParts(Double swapSpeed, Double swapReliability, Double momentum, Double aim) {
		return List.of(new Part("Swap speed", swapSpeed, RatingWeights.Skill.SWAP_SPEED),
				new Part("Swap reliability", swapReliability, RatingWeights.Skill.SWAP_RELIABILITY),
				new Part("Momentum", momentum, RatingWeights.Skill.MOMENTUM), new Part("Aim", aim, RatingWeights.Skill.AIM));
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
		Double[] extra = skillAndPerformance(detail, category, List.of(new Part("Movement", movement, RatingWeights.Skill.GROUND_MOVEMENT), new Part("Aim", aim, RatingWeights.Skill.AIM)),
				movement != null && aim != null, "movement and aim", breakdown);
		return new Result(aim, movement, overall, parts, breakdown, false, false, extra[0], extra[1]);
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
		Double[] extra = skillAndPerformance(detail, category, List.of(new Part("Gun aim", gunAim, RatingWeights.Skill.AIM), new Part("Melee", melee, RatingWeights.Skill.MELEE)),
				gunAim != null || melee != null, "gun aim or melee", breakdown);
		return new Result(gunAim, melee, overall, parts, breakdown, false, false, extra[0], extra[1]);
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
	 * The two swap stats: {speed, reliability}, each 0-100 from its own table (RatingWeights.swapSpeedScore from the average swap time,
	 * swapReliabilityScore from the success rate = successes / attempts, canceled swaps not counted as attempts). The weights are
	 * only for the breakdown lines.
	 */
	static double[] swapScores(int successes, int attempts, double avgMs, double speedWeight, double reliabilityWeight, List<String> breakdown) {
		double successRate = attempts > 0 ? (double) successes / attempts : 1.0;
		double speed = RatingWeights.swapSpeedScore(avgMs);
		double reliability = RatingWeights.swapReliabilityScore(successRate);
		breakdown.add(String.format("  swap speed: %.0f (average %.3fs) (%.2f)", speed, avgMs / 1000, speedWeight));
		breakdown.add(String.format("  swap reliability: %.0f (%.0f%% success, %d of %d) (%.2f)", reliability, successRate * 100, successes, attempts, reliabilityWeight));
		return new double[] { speed, reliability };
	}

	/** % of your speed kept through Wing swaps into an empty hotbar slot, or null without any. */
	static Double momentumScore(Averages avg, List<String> breakdown) {
		if (avg == null || avg.speedBeforeBps() == null || avg.speedAfterBps() == null || avg.speedBeforeBps() <= 0) {
			breakdown.add("  momentum: no swaps into an empty slot yet");
			return null;
		}
		double score = clamp(100.0 * (avg.speedAfterBps() / avg.speedBeforeBps()) / RatingWeights.MOMENTUM_CEILING);
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
	 * Air, rated like Wing (RatingWeights.Air takes Wing's weights): swap speed and reliability over all four Air swap types
	 * together, momentum through wingsuit swaps into an empty slot, aim (with melee from Air combos in it) and K/D.
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
		Double swapSpeed = null, swapReliability = null;
		if (successes < need(RatingWeights.MIN_SWAPS)) {
			breakdown.add("  swap: not enough swaps yet (" + RatingWeights.MIN_SWAPS + " successful)");
		} else {
			double[] scores = swapScores(successes, attempts, totalMs / successes, RatingWeights.Air.SWAP_SPEED, RatingWeights.Air.SWAP_RELIABILITY, breakdown);
			swapSpeed = shrink(scores[0], successes, RatingWeights.FULL_SWAPS);
			swapReliability = shrink(scores[1], successes, RatingWeights.FULL_SWAPS);
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
			momentum = clamp(100.0 * (after / before) / RatingWeights.MOMENTUM_CEILING);
			breakdown.add(String.format("  momentum: %.0f (%.1f -> %.1f b/s over %d swaps) (%.2f)", momentum, before / moving, after / moving, moving,
					RatingWeights.Air.MOMENTUM));
		} else {
			breakdown.add("  momentum: no wingsuit swaps into an empty slot yet");
		}
		Part speedPart = new Part("Swap speed", swapSpeed, RatingWeights.Air.SWAP_SPEED);
		Part reliabilityPart = new Part("Swap reliability", swapReliability, RatingWeights.Air.SWAP_RELIABILITY);
		Part momentumPart = new Part("Momentum", momentum, RatingWeights.Air.MOMENTUM);
		Double movement = weighted(speedPart, reliabilityPart, momentumPart);
		Double kd = kdScore(detail, RatingWeights.Air.KD, breakdown);
		double aimWeight = aimWeight(detail, category, RatingWeights.Air.AIM, breakdown);
		List<Part> parts = List.of(speedPart, reliabilityPart, momentumPart, new Part("Aim", aim, aimWeight), new Part("K/D", kd, RatingWeights.Air.KD));
		Double overall = swapSpeed != null && aim != null ? weighted(parts) : null;
		if (aim != null) breakdown.add(String.format("  aim: %.0f (%.2f)", aim, aimWeight));
		if (overall == null) breakdown.add("  overall needs both swap and aim");
		Double[] extra = skillAndPerformance(detail, category, swapSkillParts(swapSpeed, swapReliability, momentum, aim), swapSpeed != null && aim != null, "swaps and aim", breakdown);
		return new Result(aim, movement, overall, parts, breakdown, false, false, extra[0], extra[1]);
	}

	private static double clamp(double value) {
		return Math.max(0, Math.min(100, value));
	}
}
