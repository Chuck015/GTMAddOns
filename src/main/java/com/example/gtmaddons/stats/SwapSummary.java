package com.example.gtmaddons.stats;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The averages of a run of swaps, for "/gao swapinfo end" (see SwapSession). Pure arithmetic with no
 * game classes, so it is identical in every version of the mod.
 *
 * Timing and movement averages are over the successful swaps only (a failed or canceled swap has no
 * meaningful time); the tallies count every swap. A step a swap never reached (for example the
 * slot was not on screen) is left out of that step's average, so each Stat says how many swaps it
 * is over (n).
 */
public record SwapSummary(
		int total, int successes, int failures, int cancels,
		Stat totalMs, Stat reachMs, Stat slotToHotbarMs, Stat hotbarToCloseMs, Stat wingToHotbarMs,
		Stat efficiency, Stat mouseDeg, Stat overflickDeg, Stat momentumKept,
		Map<String, Integer> byCategory, Map<String, Integer> bySwapType, Map<String, Integer> successByCategory,
		List<Double> successTotalsMs) {

	/** Count, mean, best (lowest), worst (highest) and standard deviation of some numbers. */
	public record Stat(int n, double avg, double min, double max, double sd) {}

	public double successRate() {
		return total > 0 ? 100.0 * successes / total : 0.0;
	}

	/** Null for no numbers. */
	static Stat stat(List<Double> values) {
		if (values.isEmpty()) return null;
		double sum = 0, min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
		for (double v : values) {
			sum += v;
			min = Math.min(min, v);
			max = Math.max(max, v);
		}
		double avg = sum / values.size();
		double squares = 0;
		for (double v : values) squares += (v - avg) * (v - avg);
		double sd = values.size() > 1 ? Math.sqrt(squares / (values.size() - 1)) : 0.0;
		return new Stat(values.size(), avg, min, max, sd);
	}

	/** Summarizes the swaps, in the order they were made. */
	public static SwapSummary of(List<SwapRecord> records) {
		int successes = 0, failures = 0, cancels = 0;
		List<Double> total = new ArrayList<>(), reach = new ArrayList<>(), slot = new ArrayList<>(), close = new ArrayList<>(),
				wing = new ArrayList<>(), eff = new ArrayList<>(), mouse = new ArrayList<>(), over = new ArrayList<>(), kept = new ArrayList<>();
		Map<String, Integer> byCategory = new LinkedHashMap<>(), byType = new LinkedHashMap<>(), successByCategory = new LinkedHashMap<>();
		for (SwapRecord r : records) {
			if (r.category() != null) byCategory.merge(r.category(), 1, Integer::sum);
			switch (r.result()) {
				case "SUCCESS" -> successes++;
				case "FAILED" -> failures++;
				default -> cancels++;
			}
			if (!"SUCCESS".equals(r.result())) continue;
			if (r.category() != null) successByCategory.merge(r.category(), 1, Integer::sum);
			if (r.swapType() != null) byType.merge(r.swapType(), 1, Integer::sum);
			total.add(r.totalMs());
			if (r.reachMs() != null) reach.add(r.reachMs());
			if (r.slotToHotbarMs() != null) slot.add(r.slotToHotbarMs());
			if (r.hotbarToCloseMs() != null) close.add(r.hotbarToCloseMs());
			if (r.wingToHotbarMs() != null) wing.add(r.wingToHotbarMs());
			if (r.efficiency() != null) eff.add(r.efficiency());
			mouse.add(r.mouseDeg());
			if (r.overflickDeg() != null) over.add(r.overflickDeg());
			if (r.speedBeforeBps() != null && r.speedAfterBps() != null && r.speedBeforeBps() > 0) {
				kept.add(Math.min(100.0, 100.0 * r.speedAfterBps() / r.speedBeforeBps()));
			}
		}
		return new SwapSummary(records.size(), successes, failures, cancels,
				stat(total), stat(reach), stat(slot), stat(close), stat(wing), stat(eff), stat(mouse), stat(over), stat(kept),
				byCategory, byType, successByCategory, total);
	}
}
