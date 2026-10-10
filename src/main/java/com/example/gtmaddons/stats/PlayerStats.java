package com.example.gtmaddons.stats;

import java.util.List;

/**
 * Shapes of the backend's /players and /me responses. Everything is over
 * the player's last 25 fights (the only ones the backend keeps).
 */
public final class PlayerStats {
	private PlayerStats() {}

	public record PlayerDetail(
			String uuid,
			String name,
			long firstSeen,
			long lastSeen,
			int total,
			int successes,
			int failures,
			int cancels,
			Double bestMs,
			Averages avg,
			List<RecentSwap> recent,
			List<GunStat> guns,
			List<AirSwapStat> airSwaps,
			List<ComboStat> combos,
			int fights,
			int kills,
			int deaths,
			/** The last few fights in the view, newest first (null from older backends). */
			List<RecentFight> recentFights,
			/** Ground PvP movement gun shots, one per gun (null from older backends). */
			List<MovementGunStat> movementGuns,
			/** Mouse movement and step averages over the successful Air swaps (null from older backends, or without any). */
			Averages airAvg,
			/** Average length (ms) of the fights you died in, in the view (null without deaths or from older backends). */
			Double deathMs,
			/** Kills and deaths by the opponent's PvP ("" = not known); null from older backends. The Results rating counts kills by it. */
			List<OpponentOutcome> byOpponent
	) {}

	/** Your kills and deaths against opponents doing one PvP (category "" when it was not recorded). */
	public record OpponentOutcome(String category, long kills, long deaths) {}

	/** Speed (horizontal blocks/s) right after a movement gun's shots: how many, the average and the best. */
	public record MovementGunStat(String gun, long shots, Double avgBps, Double bestBps) {}

	/** One recent fight: KILL or DEATH, against whom (if known), and when (epoch ms). */
	public record RecentFight(String outcome, String opponent, long startedAt, long endedAt) {}

	/** Averages over the player's successful swaps. */
	public record Averages(
			Double totalMs,
			Double reachMs,
			Double slotToHotbarMs,
			Double hotbarToCloseMs,
			Double wingToHotbarMs,
			Double mouseDeg,
			Double approachDeg,
			Double neededDeg,
			Double efficiency,
			Double awayDeg,
			Double overflickDeg,
			Double overflickPeakDeg,
			Double afterDeg,
			/** Momentum (horizontal blocks/s) before and after swaps into an empty hotbar slot. */
			Double speedBeforeBps,
			Double speedAfterBps
	) {}

	public record RecentSwap(
			long ts,
			String result,
			double totalMs,
			Double wingToHotbarMs,
			Double efficiency,
			Double overflickDeg
	) {}

	/** Totals for one gun in one PvP category (a PvpCategory name). */
	public record GunStat(String category, String gun, long shots, long hits, long headshots, long kills, long lastUsed,
			long netShots, long netHits, long netHeadshots) {}

	/** Air PvP swaps of one type (a swap type name, or null for failed/canceled attempts). */
	public record AirSwapStat(String swapType, int total, int successes, int cancels, Double avgMs, Double bestMs,
			Double speedBeforeBps, Double speedAfterBps, int momentumSwaps) {}

	/**
	 * Melee combo totals for one PvP category: enemy combos on you and how
	 * many you broke, yours and how many got broken, and first hits (hits
	 * that were neither a break nor a hold - see ComboTracker) by them and
	 * by you. First hits are only counted from 2026-09-29 on, so older fights
	 * add nothing to them.
	 */
	public record ComboStat(String category, long enemyCombos, long enemyBroken, long ownCombos, long ownBroken,
			long enemyFirstHits, long ownFirstHits) {}

	/** Who the backend thinks we are: dev = allowed dev/QA mode, admin = allowed admin mode (deleting stats data). */
	public record Me(String uuid, String name, boolean dev, boolean admin) {}

	/** What an admin account sees about a player (GET /admin/player-info); shown by AdminPlayerInfoScreen. modVersion is null if never recorded. */
	public record AdminPlayerInfo(String uuid, String name, long firstSeen, long lastSeen, String modVersion, boolean online, boolean dev,
			boolean admin, long fights, long kills, long deaths, java.util.Map<String, Long> fightsByCategory, Long lastFightAt, long swaps,
			java.util.List<Flag> flags) {}

	/** One out-of-the-norm stat in AdminPlayerInfo: level is "warn" or "note". */
	public record Flag(String level, String text) {}

	/** What a flag needs before it shows (GET /admin/flag-rules); listed by AdminPlayerInfoScreen. */
	public record FlagRule(String title, String requirement) {}

	public record FlagRules(java.util.List<FlagRule> rules) {}

	/** The answer to an admin notice (POST /admin/notify): who it is for (targeted, of whom online now), the version a bulk notice is about, names not found. */
	public record NoticeResult(int id, String mode, int targeted, int online, String targetVersion, java.util.List<String> unknownNames) {}

	/** A player with at least one warning flag (GET /admin/flagged), with all their flags. */
	public record FlaggedPlayer(String uuid, String name, java.util.List<Flag> flags) {}

	public record FlaggedPlayers(java.util.List<FlaggedPlayer> players, long computedAt) {}

	// ---- Raw fights (GET /players/:uuid/fights) ----
	// What the fight log, per-fight ratings and custom filters are built from; see FightStats.

	/** A player's stored fights (up to 500 per PvP category), newest first. */
	public record FightHistory(String uuid, String name, long firstSeen, long lastSeen, List<FightData> fights) {}

	/**
	 * One finished fight: how it went and everything recorded in it. Lists may be null from an odd reply - use the accessors below.
	 * id is the backend's own number for it (later fights have higher ones); null from a backend that does not send it.
	 */
	public record FightData(String fightKey, long startedAt, long endedAt, String outcome, String opponent, String category, String opponentCategory,
			List<SwapRow> swaps, List<GunRow> guns, List<ComboRow> combos, Long id) {
		public boolean won() {
			return "KILL".equals(outcome);
		}

		public List<SwapRow> swapRows() {
			return swaps != null ? swaps : List.of();
		}

		public List<GunRow> gunRows() {
			return guns != null ? guns : List.of();
		}

		public List<ComboRow> comboRows() {
			return combos != null ? combos : List.of();
		}
	}

	// ---- Leaderboard (GET /leaderboard) ----

	/**
	 * Everyone's stats for one PvP category over their newest `fights` fights (against certain
	 * opponents, if filtered). Each player is a PlayerDetail carrying only what that category's
	 * ratings read - see Leaderboard for ranking them.
	 */
	public record LeaderboardData(String category, int fights, List<PlayerDetail> players, List<OpponentCount> opponents) {}

	/** The answer to an admin delete: how many fights it removed. */
	public record DeleteResult(int deletedFights) {}

	/** An opponent seen in the leaderboard's fights, and how many of them. key is lowercase. */
	public record OpponentCount(String key, String name, int fights) {}

	/** One swap in a fight (Wing and Air only). */
	public record SwapRow(long ts, String result, String category, String swapType, double totalMs, Double reachMs,
			Double slotToHotbarMs, Double hotbarToCloseMs, Double wingToHotbarMs, Double mouseDeg, Double approachDeg,
			Double neededDeg, Double efficiency, Double awayDeg, Double overflickDeg, Double overflickPeakDeg,
			Double afterDeg, Double speedBeforeBps, Double speedAfterBps) {}

	/** One gun's totals in one PvP category in a fight. speed* are set for movement guns only. */
	public record GunRow(String category, String gun, long shots, long hits, long headshots, long kills,
			Long speedShots, Double speedTotalBps, Double speedBestBps, Long netShots, Long netHits, Long netHeadshots) {}

	/** Combo totals for one PvP category in a fight. First hits are null for fights before they were counted. */
	public record ComboRow(String category, long enemyCombos, long enemyBroken, long ownCombos, long ownBroken,
			Long enemyFirstHits, Long ownFirstHits) {}
}
