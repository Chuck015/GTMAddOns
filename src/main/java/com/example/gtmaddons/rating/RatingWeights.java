package com.example.gtmaddons.rating;

import com.example.gtmaddons.PvpCategory;
import com.example.gtmaddons.gun.GunType;

import java.util.Locale;
import java.util.Map;

/**
 * ============================================================================
 *  RATING TUNING SHEET - every adjustable number behind the ratings.
 * ============================================================================
 *
 *  Ratings (see Ratings) read everything from here, so balancing never needs
 *  a change to the logic. These are FIRST GUESSES - change them as we learn
 *  what good and bad players' stats look like.
 *
 *  How to read the numbers:
 *    - Every rating is 0-100. A player exactly at a baseline scores 50.
 *    - WEIGHTS are relative: only their ratio inside one rating matters.
 *      2.0 next to 1.0 = "counts twice as much". 0 = left out entirely.
 *    - MIN_ values are how much data a part needs before it is rated at all;
 *      below them the part shows "not enough ... yet". FULL_ values are how much
 *      it takes to count in full: between MIN_ and FULL_ the part is pulled toward
 *      50 in proportion (at MIN_ it is mostly 50), so little data gives a cautious
 *      rating instead of a lucky one.
 *    - BEST / CEILING values score 100, WORST / FLOOR values score 0, and
 *      everything in between is a straight line.
 *
 *  After changing anything: rebuild and reinstall the jar, then hover the
 *  rating line on a stats page - the breakdown shows every part and weight.
 *
 *  Sections:
 *    1. Aim             - points per shot, per gun
 *    2. Swaps           - Wing and Air swap score
 *    3. Ground movement - speed after movement gun shots
 *    4. Melee           - combos (JP, Air)
 *    5. K/D
 *    6. Long-range aim bonus
 *    7. Overall         - how the parts are mixed, per PvP category
 */
public final class RatingWeights {
	private RatingWeights() {}

	/** The score a player exactly at a baseline gets (twice the baseline = 100). */
	public static final double SCORE_AT_BASELINE = 50.0;

	// ========================================================================
	//  1. AIM
	//     Per gun: points per shot, compared to the baseline for that gun in
	//     that PvP category. A player exactly at the baseline scores 50.
	//
	//       ratio = points per shot / baseline
	//       score = 100 x ratio^AIM_CURVE / (1 + ratio^AIM_CURVE)
	//
	//     so 0.5x the baseline scores about 15, 1.5x about 73 and 2x about 85,
	//     and nobody hits a hard 0 or 100. Guns with few shots are pulled
	//     toward 50 (see AIM_PRIOR_SHOTS), so a lucky 25 shots can't score 95.
	// ========================================================================

	/**
	 * Points a single shot earns. A miss earns 0. Body shots are worth 0.75 of a
	 * headshot: headshot counts were too low before 2026-10-01 (the killing shot
	 * of a fight was dropped, and it is usually a headshot), and a small headshot
	 * premium keeps the rating mostly about hitting. Revisit once a few weeks of
	 * fixed data are in.
	 */
	public static final double HEADSHOT_POINTS = 1.0;
	public static final double BODY_SHOT_POINTS = 0.75;

	/** Shots a gun needs (in that category) before it counts toward aim. */
	public static final int MIN_SHOTS_PER_GUN = 10;
	/** Past this many shots, more shots don't make a gun count for more. */
	public static final int SHOT_WEIGHT_CAP = 300;

	/** How steep the score is around 50: higher spreads players out more (1 = gentle, 3 = steep). */
	public static final double AIM_CURVE = 2.5;
	/** A gun's points per shot count as if the player had also fired this many shots at exactly the baseline. */
	public static final int AIM_PRIOR_SHOTS = 50;

	/**
	 * Points per shot that scores 50 for a typical gun, per PvP category: the median of
	 * the players with data (2026-10-02: 13 players, about 30,000 shots; shots per player
	 * 90 to 3,700). Aim in Air and JP is lower because hitting while flying or in a
	 * jetpack fight is harder. Recalibrate with the saved query when there are more players.
	 */
	private static final Map<PvpCategory, Double> CATEGORY_BASELINE = Map.of(
			PvpCategory.GROUND, 0.145,
			PvpCategory.WING,   0.130,
			PvpCategory.AIR,    0.088,
			PvpCategory.JP,     0.087);
	private static final double DEFAULT_CATEGORY_BASELINE = 0.110;

	/**
	 * Per kind of gun:
	 *   impact - how much the gun counts in the aim average. Harder, more
	 *            skill-based guns count more; splash or utility less.
	 *   factor - how hard the gun is to land shots with, against a rifle or SMG
	 *            (1.0). The gun's baseline = the category's baseline x factor, so the
	 *            same accuracy scores higher with a harder gun. From the data: snipers
	 *            land far more shots per trigger pull (1.5x), machine guns and
	 *            shotguns far fewer (shotguns are mostly used to move).
	 */
	private static final Map<GunType, Gun> BY_TYPE = Map.of(
			//                            impact  factor
			GunType.SNIPER,      new Gun(  1.3,    1.5  ),
			GunType.RIFLE,       new Gun(  1.2,    1.0  ),
			GunType.SMG,         new Gun(  1.0,    1.0  ),
			GunType.SHOTGUN,     new Gun(  1.0,    0.30 ),
			GunType.MACHINE_GUN, new Gun(  1.0,    0.65 ),
			GunType.PISTOL,      new Gun(  0.9,    1.2  ),
			GunType.LAUNCHER,    new Gun(  0.5,    1.0  ));

	/**
	 * Single guns that don't fit their type (or have none), by name in
	 * lowercase. These win over BY_TYPE.
	 */
	private static final Map<String, Gun> BY_NAME = Map.of(
			//                            impact  factor
			"net launcher",      new Gun(  0.4,    1.0  ),
			"musket",            new Gun(  1.1,    1.5  ));

	/** Any gun not listed above. */
	private static final Gun DEFAULT_GUN = new Gun(1.0, 1.0);

	// ========================================================================
	//  1b. SHOT IMPACT (Wing aim)
	//      How much a landed shot matters, 1 (lowest) to 100 (highest), from the
	//      owner's guidelines for wingsuit guns. Body shot and headshot have their
	//      own numbers. They only set how much each gun counts inside the Wing aim
	//      average (weight = average impact per shot x shots, shots capped at
	//      SHOT_WEIGHT_CAP); how good you are with a gun is still points per shot
	//      against that gun's baseline. Guns that aren't listed use the numbers of
	//      their type (SHOT_IMPACT_BY_TYPE, provisional) until they are given.
	// ========================================================================

	/** Who you are fighting: the impact numbers are per matchup. Only wing vs wing has numbers so far; the others use it until they are given. */
	public enum Matchup { WING_VS_WING, WING_VS_JP, WING_VS_AIR }

	/** One gun's impact for a body shot and for a headshot (1-100). */
	public record ShotImpact(double body, double head) {}

	private static final Map<String, ShotImpact> WING_VS_WING_IMPACT = Map.ofEntries(
			//                                        body  head
			Map.entry("heavy sniper",    new ShotImpact( 80, 100)),
			Map.entry("sniper rifle",    new ShotImpact( 70,  90)),
			Map.entry("net launcher",    new ShotImpact( 20,  20)), // it controls rather than kills; the shots it sets up are amplified instead
			Map.entry("advanced rifle",  new ShotImpact(  5,  10)),
			Map.entry("special carbine", new ShotImpact(  7,  12)),
			Map.entry("carbine rifle",   new ShotImpact(  4,   9)));

	/** Per matchup, by gun name in lowercase. */
	private static final Map<Matchup, Map<String, ShotImpact>> SHOT_IMPACT = Map.of(
			Matchup.WING_VS_WING, WING_VS_WING_IMPACT,
			Matchup.WING_VS_JP, Map.of(),
			Matchup.WING_VS_AIR, Map.of());

	/** Provisional numbers for guns that aren't listed: like the listed guns of their type. */
	private static final Map<GunType, ShotImpact> SHOT_IMPACT_BY_TYPE = Map.of(
			GunType.SNIPER,      new ShotImpact(70, 90),
			GunType.RIFLE,       new ShotImpact( 5, 10),
			GunType.SMG,         new ShotImpact( 5, 10),
			GunType.MACHINE_GUN, new ShotImpact( 4,  9),
			GunType.PISTOL,      new ShotImpact( 4,  9),
			GunType.SHOTGUN,     new ShotImpact( 4,  9),
			GunType.LAUNCHER,    new ShotImpact(80, 80));
	private static final ShotImpact DEFAULT_SHOT_IMPACT = new ShotImpact(6, 12);

	/**
	 * How much more a shot counts when it was fired at a player caught in a Net Launcher net (the 25 ticks after a Net Launcher hit on a wingsuit
	 * player): the follow-up the net sets up is what gives the Net Launcher its value, so the Net Launcher's own impact is low and these shots
	 * are multiplied. 3.0 is a first guess.
	 */
	public static final double NETTED_SHOT_MULTIPLIER = 3.0;

	/** The impact numbers of a gun in a matchup: its own if listed, else its type's. */
	public static ShotImpact shotImpact(Matchup matchup, String gun) {
		String name = gun.toLowerCase(Locale.ROOT);
		ShotImpact own = SHOT_IMPACT.get(matchup).get(name);
		if (own == null) own = SHOT_IMPACT.get(Matchup.WING_VS_WING).get(name); // matchups without numbers yet
		if (own != null) return own;
		if (name.contains("musket")) return SHOT_IMPACT_BY_TYPE.get(GunType.SNIPER);
		GunType type = GunType.of(name);
		return type != null ? SHOT_IMPACT_BY_TYPE.getOrDefault(type, DEFAULT_SHOT_IMPACT) : DEFAULT_SHOT_IMPACT;
	}

	/**
	 * How much a gun counts in the aim average: its average impact per shot (shots that weren't headshots count as body shots) in
	 * Wing and in Air (rated like Wing: the same guns, and nets land on wingsuits there too); the gun's older impact in Ground and JP
	 * (the numbers above are for wingsuit guns).
	 */
	public static double aimImpact(PvpCategory category, String gun, long shots, long headshots, long netShots, long netHeadshots) {
		if ((category != PvpCategory.WING && category != PvpCategory.AIR) || shots <= 0) return impact(gun);
		ShotImpact impact = shotImpact(Matchup.WING_VS_WING, gun);
		netShots = Math.max(0, Math.min(netShots, shots));
		netHeadshots = Math.max(0, Math.min(netHeadshots, Math.min(netShots, headshots)));
		long plainShots = shots - netShots, plainHeadshots = headshots - netHeadshots;
		double plain = plainHeadshots * impact.head() + (plainShots - plainHeadshots) * impact.body();
		double netted = netHeadshots * impact.head() + (netShots - netHeadshots) * impact.body();
		return (plain + NETTED_SHOT_MULTIPLIER * netted) / shots;
	}

	/**
	 * Net Launcher: it is mostly used to slow opponents down, so a low hit rate is normal. Its aim score is its hit rate against this ceiling
	 * (15-20% was the owner's range): at NET_LAUNCHER_HIT_CEILING or more it scores 100, below it the score falls as (rate / ceiling)^
	 * NET_LAUNCHER_HIT_EXPONENT - an exponent under 1 makes low rates cost less than a straight line would. Few shots are pulled toward
	 * NET_LAUNCHER_PRIOR_RATE like every gun is pulled toward its baseline.
	 */
	public static final double NET_LAUNCHER_HIT_CEILING = 0.175;
	public static final double NET_LAUNCHER_HIT_EXPONENT = 0.6;
	public static final double NET_LAUNCHER_PRIOR_RATE = 0.10;

	public static boolean isNetLauncher(String gun) {
		return gun != null && gun.toLowerCase(Locale.ROOT).contains("net launcher");
	}

	// ========================================================================
	//  2. SWAPS (Wing, Air)
	//     Two separate stats, each scored from its own table (the owner's
	//     numbers, 2026-10-09): SPEED from the average swap time and
	//     RELIABILITY from the success rate. Between the listed points the
	//     score changes in a straight line, so every millisecond and every
	//     percent counts. Wing and Air use the same tables.
	// ========================================================================

	/** Successful swaps needed before the swap score is rated, and for it to count in full. */
	public static final int MIN_SWAPS = 3;
	public static final int FULL_SWAPS = 5;

	/** The average swap time (ms) that scores 100 and the one that scores 0 (the ends of SWAP_SPEED_TABLE), for colouring times on screen. */
	public static final double BEST_SWAP_MS = 100;
	public static final double WORST_SWAP_MS = 700;

	/** Average swap time in ms -> speed score. Faster than the first row scores its score, slower than the last row scores 0. */
	private static final double[][] SWAP_SPEED_TABLE = {
			{ 100, 100 }, { 125, 98 }, { 150, 92 }, { 175, 84 }, { 200, 75 }, { 225, 70 }, { 250, 65 },
			{ 300, 60 }, { 350, 55 }, { 400, 45 }, { 500, 30 }, { 600, 10 }, { 700, 0 } };

	/** Success rate in % -> reliability score. Above the last row scores its score, below the first row scores 0. */
	private static final double[][] SWAP_RELIABILITY_TABLE = {
			{ 50, 0 }, { 55, 10 }, { 60, 30 }, { 65, 45 }, { 70, 50 }, { 75, 60 }, { 80, 70 }, { 85, 81 },
			{ 90, 89 }, { 95, 96 }, { 98, 100 } };

	/** A straight line between the neighbouring rows of an ascending table; the end rows' scores beyond the ends. */
	private static double interpolate(double[][] table, double x) {
		if (x <= table[0][0]) return table[0][1];
		for (int i = 1; i < table.length; i++) {
			if (x <= table[i][0]) {
				double span = table[i][0] - table[i - 1][0];
				return table[i - 1][1] + (table[i][1] - table[i - 1][1]) * (x - table[i - 1][0]) / span;
			}
		}
		return table[table.length - 1][1];
	}

	// ========================================================================
	//  SKILL and PERFORMANCE (Wing only, next to Overall)
	//     SKILL        mechanics only, no results: the swap speed and reliability,
	//                  momentum and aim parts. Aim here is the aim score alone (shots
	//                  and shot impact); the long-range kill share that raises aim's
	//                  weight in Overall is a result, so it is not used.
	//     PERFORMANCE  your output plus some mechanics (the owner's mix, 2026-10-10):
	//                  40% mechanics (the Skill rating), 35% K/D, 15% kills per fight,
	//                  10% survival time.
	//     Kills per fight is the win rate (a fight ends with your kill or your
	//     death); survival time is the average length of the fights you died in.
	//     Both tables are first guesses: the owner gave the weights only.
	// ========================================================================

	public static final class Skill {
		private Skill() {}
		public static final double SWAP_SPEED = 0.5, SWAP_RELIABILITY = 0.5, MOMENTUM = 1.0, AIM = 1.0;
		/** The mechanical parts of the PvPs without swaps: Ground's movement-gun speed, JP's melee. */
		public static final double GROUND_MOVEMENT = 1.0, MELEE = 1.0;
	}

	public static final class Performance {
		private Performance() {}
		public static final double MECHANICS = 0.40, KD = 0.35, KILLS_PER_FIGHT = 0.15, SURVIVAL = 0.10;
	}

	/**
	 * Results (Performance) rating only: how much a kill counts when you fight in JP, Wing or Air and your opponent was doing Ground PvP
	 * (their PvP is worked out from their gear, fights.opponent_category) - a lot easier than fighting someone in your own PvP. Deaths always
	 * count in full. 0.5 is a first guess. Overall is not adjusted.
	 */
	public static final double EASY_OPPONENT_WIN_WEIGHT = 0.5;

	/** What a kill is worth in the Results rating: EASY_OPPONENT_WIN_WEIGHT against a Ground opponent in JP / Wing / Air, else 1 (also when the opponent is not known). */
	public static double winWeight(PvpCategory own, String opponentCategory) {
		if (opponentCategory == null) return 1.0;
		boolean flying = own == PvpCategory.WING || own == PvpCategory.JP || own == PvpCategory.AIR;
		return flying && "GROUND".equals(opponentCategory) ? EASY_OPPONENT_WIN_WEIGHT : 1.0;
	}

	/** Win rate in % (kills per fight) -> score: 20% = 0, 50% = 50, 80% = 100. */
	private static final double[][] WIN_RATE_TABLE = { { 20, 0 }, { 50, 50 }, { 80, 100 } };
	/** Average seconds survived in the fights you died in -> score (the median over players on 2026-10-10 was 17 s). */
	private static final double[][] SURVIVAL_TABLE = { { 0, 0 }, { 8, 25 }, { 17, 50 }, { 30, 80 }, { 45, 100 } };
	/** Deaths needed before survival time counts in full; fewer are pulled toward 50. */
	public static final int FULL_DEATHS = 5;

	public static double winRateScore(double rate) {
		return interpolate(WIN_RATE_TABLE, rate * 100.0);
	}

	public static double survivalScore(double seconds) {
		return interpolate(SURVIVAL_TABLE, seconds);
	}

	/** 0-100 from the average swap time in ms (see SWAP_SPEED_TABLE). */
	public static double swapSpeedScore(double avgMs) {
		return interpolate(SWAP_SPEED_TABLE, avgMs);
	}

	/** 0-100 from the success rate (0-1; see SWAP_RELIABILITY_TABLE). */
	public static double swapReliabilityScore(double successRate) {
		return interpolate(SWAP_RELIABILITY_TABLE, successRate * 100.0);
	}

	/**
	 * Momentum: the share of your speed kept through a swap (speed in the 2 s after / speed when the inventory opened) is scored against
	 * this ceiling - what is possible in the game, not what players reach today (the best average is about 57%, the best single swaps 93-97%).
	 * Keeping this much scores 100.
	 */
	public static final double MOMENTUM_CEILING = 0.95;

	// ========================================================================
	//  3. GROUND MOVEMENT
	//     Average horizontal speed right after movement gun shots (Sawed-off,
	//     Pump, Heavy Revolver). Our stats, 2026-09-28: averages 10-15 b/s,
	//     bests 16-20; by gun (2026-10-08): Heavy Revolver averages 15.8, Sawed-off 13.4,
	//     Pump 10.3. The guns don't launch equally far, so each has its own ceiling
	//     (movementCeiling) and is scored by how much of its own potential you get.
	// ========================================================================

	/** Movement gun shots needed before Ground movement is rated, and for it to count in full. */
	public static final int MIN_MOVEMENT_SHOTS = 5;
	public static final int FULL_MOVEMENT_SHOTS = 10;

	/** Speed (blocks/s) that scores 0: sprinting, so the shot added nothing. */
	public static final double MOVEMENT_FLOOR_BPS = 5.6;
	/** Speed (blocks/s) that scores 100 for a movement gun without its own ceiling below: about the best seen so far. */
	public static final double MOVEMENT_CEILING_BPS = 20.0;
	/** Per gun: the speed that scores 100. Heavy Revolver the highest, Sawed-off in the middle, Pump the lowest. */
	public static final double MOVEMENT_CEILING_HEAVY_REVOLVER_BPS = 24.0;
	public static final double MOVEMENT_CEILING_SAWED_OFF_BPS = 20.0;
	public static final double MOVEMENT_CEILING_PUMP_BPS = 16.0;

	/** The speed that scores 100 for this movement gun. */
	public static double movementCeiling(String gun) {
		String name = gun == null ? "" : gun.toLowerCase(Locale.ROOT);
		if (name.contains("revolver")) return MOVEMENT_CEILING_HEAVY_REVOLVER_BPS;
		if (name.contains("sawed")) return MOVEMENT_CEILING_SAWED_OFF_BPS;
		if (name.contains("pump")) return MOVEMENT_CEILING_PUMP_BPS;
		return MOVEMENT_CEILING_BPS;
	}

	/** 0-100: how much of this gun's own potential an average speed of avgBps is (sprinting = 0, the gun's ceiling = 100). */
	public static double movementScore(String gun, double avgBps) {
		double score = 100.0 * (avgBps - MOVEMENT_FLOOR_BPS) / (movementCeiling(gun) - MOVEMENT_FLOOR_BPS);
		return Math.max(0.0, Math.min(100.0, score));
	}

	// ========================================================================
	//  4. MELEE (JP's second rating, and part of Air's Overall)
	//     Three %s averaged by these weights. Share: 50 = as many combos as
	//     the enemy.
	// ========================================================================

	/** Combos (yours plus the enemy's) needed before melee is rated, and for it to count in full. */
	public static final int MIN_COMBOS = 3;
	public static final int FULL_COMBOS = 5;

	/**
	 * How much melee counts inside Aim for Air, next to gun aim (1.0): 0.5 = half as much. JP keeps Gun aim and
	 * Melee as separate ratings; Wing and Ground have no melee in their ratings. With no melee data yet, Aim is just gun aim (and the other way round).
	 */
	public static final double AIM_MELEE_WEIGHT = 0.5;

	/** % of enemy combos you broke. */
	public static final double MELEE_BROKE_WEIGHT = 1.0;
	/** % of your combos you kept (not broken). */
	public static final double MELEE_KEPT_WEIGHT = 1.0;
	/** Your share of all combos. */
	public static final double MELEE_SHARE_WEIGHT = 1.0;

	// ========================================================================
	//  5. K/D
	// ========================================================================

	/** The K/D that scores 50; twice it scores 100. */
	public static final double KD_BASELINE = 1.0;

	// ========================================================================
	//  6. LONG-RANGE AIM BONUS
	//     Aim's weight in Overall grows with the share of your kills that come
	//     from long-range guns:
	//       aim weight = <category>.AIM x (1 + LONG_RANGE_AIM_BONUS x share)
	// ========================================================================

	/** 1.0 = aim counts double for a player whose kills are all long-range. 0 = off. */
	public static final double LONG_RANGE_AIM_BONUS = 1.0;

	/** Long-range guns: the snipers, plus the Musket. */
	public static boolean isLongRange(String gun) {
		return GunType.of(gun) == GunType.SNIPER || gun.toLowerCase(Locale.ROOT).contains("musket");
	}

	// ========================================================================
	//  7. OVERALL - how the parts are mixed, per PvP category
	//
	//     Overall = sum(weight x part score) / sum(weights)
	//
	//     A part with no data yet drops out (its weight leaves the top and the
	//     bottom). Each category has its own weights, so changing one never
	//     changes another. AIM is before the long-range bonus (section 6).
	// ========================================================================

	/** Wing. Movement = Swap speed + Swap reliability + Momentum (these weights); Overall needs the swap parts and Aim. */
	public static final class Wing {
		private Wing() {}
		public static final double SWAP_SPEED = 0.5;
		public static final double SWAP_RELIABILITY = 0.5;
		/** % of your speed kept through the swap. */
		public static final double MOMENTUM = 1.0;
		public static final double AIM = 1.0;
		public static final double KD = 1.0;
	}

	/** Air. Movement = the Air swap parts and Momentum; Overall needs the swap parts and Aim. */
	public static final class Air {
		private Air() {}
		// Air is rated exactly like Wing (the owner's rule): these are Wing's numbers by reference, so the two cannot drift apart.
		public static final double SWAP_SPEED = Wing.SWAP_SPEED;
		public static final double SWAP_RELIABILITY = Wing.SWAP_RELIABILITY;
		/** % of your speed kept through a wingsuit swap into an empty hotbar slot (as for Wing). */
		public static final double MOMENTUM = Wing.MOMENTUM;
		public static final double AIM = Wing.AIM;
		public static final double KD = Wing.KD;
	}

	/** Ground. Movement = movement gun speed (section 3); Overall needs Movement and Aim. */
	public static final class Ground {
		private Ground() {}
		public static final double MOVEMENT = 1.0;
		public static final double AIM = 1.0;
		public static final double KD = 1.0;
	}

	/** JP. Second rating is Melee; Overall needs K/D and Gun aim or Melee. */
	public static final class JP {
		private JP() {}
		public static final double AIM = 1.0;
		public static final double MELEE = 1.0;
		/** K/D plays a big role in JP: as much as gun aim and melee together. */
		public static final double KD = 2.0;
	}

	// ========================================================================
	//  Lookups - no tuning below this line.
	// ========================================================================

	/** One row of the gun tables: see BY_TYPE. */
	private record Gun(double impact, double factor) {}

	private static Gun gun(String name) {
		Gun byName = BY_NAME.get(name.toLowerCase(Locale.ROOT));
		if (byName != null) return byName;
		GunType type = GunType.of(name);
		return type != null ? BY_TYPE.getOrDefault(type, DEFAULT_GUN) : DEFAULT_GUN;
	}

	public static double impact(String gun) {
		return gun(gun).impact();
	}

	/** Points per shot that scores 50 for this gun in this PvP category. */
	public static double baseline(PvpCategory category, String gun) {
		return CATEGORY_BASELINE.getOrDefault(category, DEFAULT_CATEGORY_BASELINE) * gun(gun).factor();
	}
}
