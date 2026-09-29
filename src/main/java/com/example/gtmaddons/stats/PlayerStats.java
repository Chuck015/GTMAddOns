package com.example.gtmaddons.stats;

import java.util.List;

/**
 * Shapes of the backend's /players and /me responses. Everything is over
 * the player's last 25 fights (the only ones the backend keeps).
 */
public final class PlayerStats {
	private PlayerStats() {}

	public record PlayerList(List<PlayerSummary> players) {}

	public record PlayerSummary(
			String uuid,
			String name,
			long lastSeen,
			int total,
			int successes,
			int failures,
			int cancels,
			Double avgMs,
			Double bestMs,
			long shots,
			long hits,
			long headshots,
			int fights,
			int kills,
			int deaths
	) {}

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
			List<MovementGunStat> movementGuns
	) {}

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
	public record GunStat(String category, String gun, long shots, long hits, long headshots, long kills, long lastUsed) {}

	/** Air PvP swaps of one type (a swap type name, or null for failed/canceled attempts). */
	public record AirSwapStat(String swapType, int total, int successes, int cancels, Double avgMs, Double bestMs) {}

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

	// ---- Raw fights (GET /players/:uuid/fights) ----
	// What the fight log, per-fight ratings and custom filters are built from; see FightStats.

	/** A player's last 100 fights, newest first. */
	public record FightHistory(String uuid, String name, long firstSeen, long lastSeen, List<FightData> fights) {}

	/** One finished fight: how it went and everything recorded in it. Lists may be null from an odd reply - use the accessors below. */
	public record FightData(String fightKey, long startedAt, long endedAt, String outcome, String opponent, String category,
			List<SwapRow> swaps, List<GunRow> guns, List<ComboRow> combos) {
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
			Long speedShots, Double speedTotalBps, Double speedBestBps) {}

	/** Combo totals for one PvP category in a fight. First hits are null for fights before they were counted. */
	public record ComboRow(String category, long enemyCombos, long enemyBroken, long ownCombos, long ownBroken,
			Long enemyFirstHits, Long ownFirstHits) {}
}
