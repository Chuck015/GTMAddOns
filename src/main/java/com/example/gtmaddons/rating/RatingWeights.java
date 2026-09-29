package com.example.gtmaddons.rating;

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
 *      below them the part shows "not enough ... yet".
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
	//     Per gun: points per shot, compared to that gun's baseline.
	//     score = 50 x (points per shot / baseline), capped at 100.
	// ========================================================================

	/** Points a single shot earns. A miss earns 0. */
	public static final double HEADSHOT_POINTS = 1.0;
	public static final double BODY_SHOT_POINTS = 0.6;

	/** Shots a gun needs (in that category) before it counts toward aim. */
	public static final int MIN_SHOTS_PER_GUN = 20;
	/** Past this many shots, more shots don't make a gun count for more. */
	public static final int SHOT_WEIGHT_CAP = 300;

	/**
	 * Per kind of gun:
	 *   impact   - how much the gun counts in the aim average. Harder, more
	 *              skill-based guns count more; splash or utility less.
	 *   baseline - points per shot that scores 50. Lower = the gun is harder
	 *              to land shots with, so the same accuracy scores higher.
	 */
	private static final Map<GunType, Gun> BY_TYPE = Map.of(
			//                            impact  baseline
			GunType.SNIPER,      new Gun(  1.3,    0.35 ),
			GunType.RIFLE,       new Gun(  1.2,    0.30 ),
			GunType.SMG,         new Gun(  1.0,    0.25 ),
			GunType.SHOTGUN,     new Gun(  1.0,    0.35 ),
			GunType.MACHINE_GUN, new Gun(  1.0,    0.22 ),
			GunType.PISTOL,      new Gun(  0.9,    0.30 ),
			GunType.LAUNCHER,    new Gun(  0.5,    0.30 ));

	/**
	 * Single guns that don't fit their type (or have none), by name in
	 * lowercase. These win over BY_TYPE.
	 */
	private static final Map<String, Gun> BY_NAME = Map.of(
			//                            impact  baseline
			"net launcher",      new Gun(  0.4,    0.30 ),
			"musket",            new Gun(  1.1,    0.35 ));

	/** Any gun not listed above. */
	private static final Gun DEFAULT_GUN = new Gun(1.0, 0.30);

	// ========================================================================
	//  2. SWAPS (Wing, Air)
	//     Speed and reliability together: effective time = average time /
	//     success rate, so 0.40s at 80% success scores like a 0.50s swap.
	// ========================================================================

	/** Successful swaps needed before the swap score is rated. */
	public static final int MIN_SWAPS = 5;

	/** Wing: effective swap time (ms) that scores 100, and the one that scores 0. */
	public static final double BEST_SWAP_MS = 250;
	public static final double WORST_SWAP_MS = 900;

	/** Air (jetpack, wingsuit and between them) takes longer, so it has its own bounds. */
	public static final double AIR_BEST_SWAP_MS = 350;
	public static final double AIR_WORST_SWAP_MS = 1200;

	// ========================================================================
	//  3. GROUND MOVEMENT
	//     Average horizontal speed right after movement gun shots (Sawed-off,
	//     Pump, Heavy Revolver). Our stats, 2026-09-28: averages 10-15 b/s,
	//     bests 16-20.
	// ========================================================================

	/** Movement gun shots needed before Ground movement is rated. */
	public static final int MIN_MOVEMENT_SHOTS = 10;

	/** Speed (blocks/s) that scores 0: sprinting, so the shot added nothing. */
	public static final double MOVEMENT_FLOOR_BPS = 5.6;
	/** Speed (blocks/s) that scores 100: about the best seen so far. */
	public static final double MOVEMENT_CEILING_BPS = 20.0;

	// ========================================================================
	//  4. MELEE (JP's second rating, and part of Air's Overall)
	//     Three %s averaged by these weights. Share: 50 = as many combos as
	//     the enemy.
	// ========================================================================

	/** Combos (yours plus the enemy's) needed before melee is rated. */
	public static final int MIN_COMBOS = 5;

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
		public static final double AIM = 1.0;
		public static final double MELEE = 1.0;
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
	private record Gun(double impact, double baseline) {}

	private static Gun gun(String name) {
		Gun byName = BY_NAME.get(name.toLowerCase(Locale.ROOT));
		if (byName != null) return byName;
		GunType type = GunType.of(name);
		return type != null ? BY_TYPE.getOrDefault(type, DEFAULT_GUN) : DEFAULT_GUN;
	}

	public static double impact(String gun) {
		return gun(gun).impact();
	}

	public static double baseline(String gun) {
		return gun(gun).baseline();
	}
}
