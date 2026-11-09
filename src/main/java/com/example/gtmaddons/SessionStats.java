package com.example.gtmaddons;

import com.example.gtmaddons.stats.SwapRecord;

/**
 * This game session's fights at a glance, for the main screen: kills, deaths, the last fight, and the average of the
 * successful Wing / Air swaps made in them. Kept in memory only (reset when the game closes); fed by FightTracker's listeners.
 */
public final class SessionStats {

	public static final SessionStats INSTANCE = new SessionStats();

	private int kills, deaths, swaps;
	private double swapMsSum;
	private FightTracker.Fight last;

	private SessionStats() {}

	public synchronized void onFight(FightTracker.Fight fight) {
		if ("KILL".equals(fight.outcome())) kills++;
		else if ("DEATH".equals(fight.outcome())) deaths++;
		for (SwapRecord swap : fight.swaps()) {
			if ("SUCCESS".equals(swap.result()) && ("WING".equals(swap.category()) || "AIR".equals(swap.category()))) {
				swaps++;
				swapMsSum += swap.totalMs();
			}
		}
		last = fight;
	}

	/** Starts this session's numbers over (the main screen's Reset session). */
	public synchronized void reset() {
		kills = deaths = swaps = 0;
		swapMsSum = 0;
		last = null;
	}

	public synchronized int kills() { return kills; }
	public synchronized int deaths() { return deaths; }
	/** The average successful Wing / Air swap this session in ms, or -1 with none. */
	public synchronized double averageSwapMs() { return swaps > 0 ? swapMsSum / swaps : -1; }
	public synchronized FightTracker.Fight lastFight() { return last; }
}
