package com.example.gtmaddons.stats;

import com.example.gtmaddons.PvpCategory;
import com.example.gtmaddons.rating.Ratings;
import com.example.gtmaddons.stats.PlayerStats.AirSwapStat;
import com.example.gtmaddons.stats.PlayerStats.Averages;
import com.example.gtmaddons.stats.PlayerStats.ComboRow;
import com.example.gtmaddons.stats.PlayerStats.ComboStat;
import com.example.gtmaddons.stats.PlayerStats.FightData;
import com.example.gtmaddons.stats.PlayerStats.FightHistory;
import com.example.gtmaddons.stats.PlayerStats.GunRow;
import com.example.gtmaddons.stats.PlayerStats.GunStat;
import com.example.gtmaddons.stats.PlayerStats.MovementGunStat;
import com.example.gtmaddons.stats.PlayerStats.PlayerDetail;
import com.example.gtmaddons.stats.PlayerStats.RecentFight;
import com.example.gtmaddons.stats.PlayerStats.RecentSwap;
import com.example.gtmaddons.stats.PlayerStats.SwapRow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Builds a PlayerDetail - the same shape the backend's /players/:uuid answers
 * with - from any set of a player's raw fights, on the client. That is what
 * lets the stats pages show a custom selection (a chosen number of fights,
 * only certain opponents) and the fight log show ratings for a single fight.
 *
 * It mirrors the backend's playerDetail queries:
 *   - swap stats: Wing swaps only, averages over successful ones;
 *   - Air swaps grouped by type, guns grouped by category and gun,
 *     combos by category, movement guns from Ground fights;
 *   - the 15 newest Wing swaps and the 10 newest fights.
 * Fights are expected newest first, as the backend sends them.
 */
public final class FightStats {
	private FightStats() {}

	private static final int RECENT_SWAPS = 15;
	private static final int RECENT_FIGHTS = 10;
	private static final int MAX_GUN_ROWS = 80;

	/** Stats over these fights (newest first). */
	public static PlayerDetail detail(FightHistory history, List<FightData> fights) {
		int kills = 0, deaths = 0;
		List<SwapRow> wing = new ArrayList<>(), air = new ArrayList<>();
		Map<String, long[]> gunTotals = new LinkedHashMap<>();
		Map<String, long[]> gunLastUsed = new LinkedHashMap<>();
		Map<String, double[]> movement = new LinkedHashMap<>();
		Map<String, long[]> comboTotals = new LinkedHashMap<>();

		for (FightData fight : fights) {
			if (fight.won()) kills++;
			else if ("DEATH".equals(fight.outcome())) deaths++;

			for (SwapRow swap : fight.swapRows()) {
				if ("WING".equals(swap.category())) wing.add(swap);
				else if ("AIR".equals(swap.category())) air.add(swap);
			}
			for (GunRow gun : fight.gunRows()) {
				String key = gun.category() + "\u0000" + gun.gun();
				long[] t = gunTotals.computeIfAbsent(key, k -> new long[4]);
				t[0] += gun.shots();
				t[1] += gun.hits();
				t[2] += gun.headshots();
				t[3] += gun.kills();
				long[] last = gunLastUsed.computeIfAbsent(key, k -> new long[] { 0L });
				last[0] = Math.max(last[0], fight.endedAt());

				// Movement guns: shots that have a speed, their summed and best speed.
				if ("GROUND".equals(gun.category()) && gun.speedShots() != null && gun.speedShots() > 0) {
					double[] m = movement.computeIfAbsent(gun.gun(), k -> new double[] { 0, 0, 0 });
					m[0] += gun.speedShots();
					m[1] += gun.speedTotalBps() != null ? gun.speedTotalBps() : 0.0;
					m[2] = Math.max(m[2], gun.speedBestBps() != null ? gun.speedBestBps() : 0.0);
				}
			}
			for (ComboRow combo : fight.comboRows()) {
				long[] t = comboTotals.computeIfAbsent(combo.category(), k -> new long[6]);
				t[0] += combo.enemyCombos();
				t[1] += combo.enemyBroken();
				t[2] += combo.ownCombos();
				t[3] += combo.ownBroken();
				t[4] += combo.enemyFirstHits() != null ? combo.enemyFirstHits() : 0L;
				t[5] += combo.ownFirstHits() != null ? combo.ownFirstHits() : 0L;
			}
		}

		// Wing swaps: counts, best time, averages over the successful ones, newest 15.
		int successes = 0, failures = 0, cancels = 0;
		List<SwapRow> good = new ArrayList<>();
		for (SwapRow swap : wing) {
			switch (swap.result()) {
				case "SUCCESS" -> {
					successes++;
					good.add(swap);
				}
				case "FAILED" -> failures++;
				case "CANCELED" -> cancels++;
				default -> {}
			}
		}
		Double best = good.isEmpty() ? null : good.stream().mapToDouble(SwapRow::totalMs).min().getAsDouble();
		List<RecentSwap> recent = wing.stream()
				.sorted(Comparator.comparingLong(SwapRow::ts).reversed())
				.limit(RECENT_SWAPS)
				.map(s -> new RecentSwap(s.ts(), s.result(), s.totalMs(), s.wingToHotbarMs(), s.efficiency(), s.overflickDeg()))
				.toList();

		List<GunStat> guns = new ArrayList<>();
		for (Map.Entry<String, long[]> e : gunTotals.entrySet()) {
			String[] key = e.getKey().split("\u0000", 2);
			long[] t = e.getValue();
			guns.add(new GunStat(key[0], key[1], t[0], t[1], t[2], t[3], gunLastUsed.get(e.getKey())[0]));
		}
		guns.sort(Comparator.comparingLong(GunStat::shots).reversed());
		if (guns.size() > MAX_GUN_ROWS) guns = new ArrayList<>(guns.subList(0, MAX_GUN_ROWS));

		List<AirSwapStat> airSwaps = airSwaps(air);

		List<ComboStat> combos = new ArrayList<>();
		for (Map.Entry<String, long[]> e : comboTotals.entrySet()) {
			long[] t = e.getValue();
			combos.add(new ComboStat(e.getKey(), t[0], t[1], t[2], t[3], t[4], t[5]));
		}

		List<RecentFight> recentFights = fights.stream().limit(RECENT_FIGHTS)
				.map(f -> new RecentFight(f.outcome(), f.opponent(), f.startedAt(), f.endedAt()))
				.toList();

		List<MovementGunStat> movementGuns = new ArrayList<>();
		for (Map.Entry<String, double[]> e : movement.entrySet()) {
			double[] m = e.getValue();
			movementGuns.add(new MovementGunStat(e.getKey(), (long) m[0], m[0] > 0 ? m[1] / m[0] : null, m[2]));
		}
		movementGuns.sort(Comparator.comparingLong(MovementGunStat::shots).reversed());

		List<SwapRow> airGood = air.stream().filter(s -> "SUCCESS".equals(s.result())).toList();
		return new PlayerDetail(history.uuid(), history.name(), history.firstSeen(), history.lastSeen(),
				wing.size(), successes, failures, cancels, best, averages(good), recent, guns, airSwaps, combos,
				fights.size(), kills, deaths, recentFights, movementGuns, airGood.isEmpty() ? null : averages(airGood));
	}

	/** Air swaps grouped by type (failed and canceled attempts have no type and group as null), with their momentum. */
	private static List<AirSwapStat> airSwaps(List<SwapRow> air) {
		Map<String, List<SwapRow>> byType = new LinkedHashMap<>();
		for (SwapRow swap : air) byType.computeIfAbsent(swap.swapType() == null ? "\u0000" : swap.swapType(), k -> new ArrayList<>()).add(swap);
		List<AirSwapStat> out = new ArrayList<>();
		for (Map.Entry<String, List<SwapRow>> e : byType.entrySet()) {
			List<SwapRow> rows = e.getValue();
			int ok = 0, canceled = 0, moving = 0;
			double sum = 0, min = Double.MAX_VALUE, before = 0, after = 0;
			for (SwapRow r : rows) {
				if ("SUCCESS".equals(r.result())) {
					ok++;
					sum += r.totalMs();
					min = Math.min(min, r.totalMs());
					if (r.speedBeforeBps() != null && r.speedAfterBps() != null) {
						moving++;
						before += r.speedBeforeBps();
						after += r.speedAfterBps();
					}
				} else if ("CANCELED".equals(r.result())) {
					canceled++;
				}
			}
			out.add(new AirSwapStat("\u0000".equals(e.getKey()) ? null : e.getKey(), rows.size(), ok, canceled,
					ok > 0 ? sum / ok : null, ok > 0 ? min : null,
					moving > 0 ? before / moving : null, moving > 0 ? after / moving : null, moving));
		}
		return out;
	}

	/** Averages over successful swaps, each metric over the swaps that have it (null if none do). */
	private static Averages averages(List<SwapRow> good) {
		return new Averages(
				avg(good, SwapRow::totalMs), avg(good, SwapRow::reachMs), avg(good, SwapRow::slotToHotbarMs),
				avg(good, SwapRow::hotbarToCloseMs), avg(good, SwapRow::wingToHotbarMs), avg(good, SwapRow::mouseDeg),
				avg(good, SwapRow::approachDeg), avg(good, SwapRow::neededDeg), avg(good, SwapRow::efficiency),
				avg(good, SwapRow::awayDeg), avg(good, SwapRow::overflickDeg), avg(good, SwapRow::overflickPeakDeg),
				avg(good, SwapRow::afterDeg), avg(good, SwapRow::speedBeforeBps), avg(good, SwapRow::speedAfterBps));
	}

	private static Double avg(List<SwapRow> rows, Function<SwapRow, Double> metric) {
		double sum = 0;
		int n = 0;
		for (SwapRow r : rows) {
			Double v = metric.apply(r);
			if (v != null) {
				sum += v;
				n++;
			}
		}
		return n > 0 ? sum / n : null;
	}

	// ---- Ratings for the fight log ----

	/** The ratings for this fight alone, in the PvP category it was fought in (null category: none). Always complete: see Ratings.computeForFight. */
	public static Ratings.Result ratingsForFight(FightHistory history, FightData fight) {
		PvpCategory category = PvpCategory.fromName(fight.category());
		if (category == null) return null;
		return Ratings.computeForFight(detail(history, List.of(fight)), category);
	}
}
