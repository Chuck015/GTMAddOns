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
	//  2. SWAPS (Wing, Air)
	//     Speed and reliability together: effective time = average time /
	//     success rate, so 0.40s at 80% success scores like a 0.50s swap.
	// ========================================================================

	/** Successful swaps needed before the swap score is rated, and for it to count in full. */
	public static final int MIN_SWAPS = 3;
	public static final int FULL_SWAPS = 5;

	/** Effective swap time (ms) that scores 100, and the one that scores 0. Wing and Air swaps share these bounds. */
	public static final double BEST_SWAP_MS = 250;
	public static final double WORST_SWAP_MS = 900;

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

	/** Wing. Movement = Swap + Momentum (these weights); Overall needs Swap and Aim. */
	public static final class Wing {
		private Wing() {}
		public static final double SWAP = 1.0;
		/** % of your speed kept through the swap. */
		public static final double MOMENTUM = 1.0;
		public static final double AIM = 1.0;
		public static final double KD = 1.0;
	}

	/** Air. Movement = the Air swap score; Overall needs Swap and Aim. No momentum (only recorded for Wing). */
	public static final class Air {
		private Air() {}
		public static final double SWAP = 1.0;
		/** % of your speed kept through a wingsuit swap into an empty hotbar slot (as for Wing). */
		public static final double MOMENTUM = 1.0;
		public static final double AIM = 1.0;
		public static final double KD = 1.0;
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
