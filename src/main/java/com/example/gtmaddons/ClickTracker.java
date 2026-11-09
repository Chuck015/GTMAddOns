package com.example.gtmaddons;

import com.example.gtmaddons.mixin.KeyMappingAccessor;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

import java.util.ArrayDeque;

/**
 * Collects how you click during a fight - information only, nothing rates or flags anyone with it (it is stored with the fight next to
 * the mouse numbers and shows in Raw info). Clicks are timed the moment the game gets the button press (KeyClickMixin), not on the game
 * tick that later handles them, so intervals are real ones. Screens get their own clicks, so time in menus is left out.
 *
 *   - left and right clicks, and clicks per second: the average inside bursts (clicks under BURST_GAP_NANOS apart) and the best full second;
 *   - consistency: the spread of the intervals inside bursts (standard deviation, and as a share of the average: lower = steadier);
 *   - doubles: clicks under DOUBLE_NANOS after the last, the mark of a double-clicking mouse;
 *   - with a melee weapon, what each click did: a hit, a blank (GTM's combo rules refused it) or a miss, and the time between your hits
 *     with and without blanks in between (the numbers that show how much a blank shortens the wait; see ComboTracker).
 */
public final class ClickTracker {

	public static final ClickTracker INSTANCE = new ClickTracker();

	/** A longer gap than this starts a new burst of clicking. */
	private static final long BURST_GAP_NANOS = 500_000_000L;
	private static final long DOUBLE_NANOS = 40_000_000L;
	private static final long SECOND_NANOS = 1_000_000_000L;

	/** Everything counted over one fight. */
	public static final class Stats {
		public int left, right, intervals, doubles, bursts, peakCps, meleeHits, meleeBlanks, meleeMisses, gapPlain, gapBlank;
		public double intervalMs, intervalSqMs, minIntervalMs = -1, gapPlainMs, gapBlankMs;

		void addInterval(double ms) {
			intervals++;
			intervalMs += ms;
			intervalSqMs += ms * ms;
			if (minIntervalMs < 0 || ms < minIntervalMs) minIntervalMs = ms;
		}

		private static double r(double v) {
			return Math.round(v * 100.0) / 100.0;
		}

		/**
		 * Adds the fight's click numbers to the fight's mouse numbers: { click_left, click_right, click_bursts, click_cps, click_peak_cps,
		 * click_interval_ms, click_sd_ms, click_cv, click_min_ms, click_doubles, melee_hits, melee_blanks, melee_misses, hit_gap_plain_n,
		 * hit_gap_plain_ms, hit_gap_blank_n, hit_gap_blank_ms }.
		 */
		void addTo(com.google.gson.JsonObject o) {
			o.addProperty("click_left", left);
			o.addProperty("click_right", right);
			o.addProperty("click_bursts", bursts);
			o.addProperty("click_peak_cps", peakCps);
			o.addProperty("click_doubles", doubles);
			if (intervals > 0) {
				double mean = intervalMs / intervals;
				double sd = Math.sqrt(Math.max(0.0, intervalSqMs / intervals - mean * mean));
				o.addProperty("click_cps", r(1000.0 / mean));
				o.addProperty("click_interval_ms", r(mean));
				o.addProperty("click_sd_ms", r(sd));
				o.addProperty("click_cv", r(100.0 * sd / mean));
				o.addProperty("click_min_ms", r(minIntervalMs));
			}
			o.addProperty("melee_hits", meleeHits);
			o.addProperty("melee_blanks", meleeBlanks);
			o.addProperty("melee_misses", meleeMisses);
			o.addProperty("hit_gap_plain_n", gapPlain);
			o.addProperty("hit_gap_blank_n", gapBlank);
			if (gapPlain > 0) o.addProperty("hit_gap_plain_ms", r(gapPlainMs / gapPlain));
			if (gapBlank > 0) o.addProperty("hit_gap_blank_ms", r(gapBlankMs / gapBlank));
		}
	}

	private Stats fight = null;
	private long lastLeftNanos = -1L;
	/** Left clicks in the last second. */
	private final ArrayDeque<Long> recent = new ArrayDeque<>();

	private ClickTracker() {}

	public void onFightStart() {
		fight = new Stats();
		lastLeftNanos = -1L;
		recent.clear();
	}

	/** The fight ended: its numbers (null if none were being collected). */
	public Stats onFightEnd() {
		Stats done = fight;
		fight = null;
		return done;
	}

	public void onFightDrop() {
		fight = null;
	}

	/** A key or mouse button was pressed with no screen open (KeyClickMixin). */
	public void onKey(InputConstants.Key key) {
		Options options = Minecraft.getInstance().options;
		if (options == null) return;
		if (key.equals(((KeyMappingAccessor) options.keyAttack).gtmaddons$key())) {
			com.example.gtmaddons.probe.Probes.emit("click", "button", "left");
			onLeftClick();
		} else if (key.equals(((KeyMappingAccessor) options.keyUse).gtmaddons$key())) {
			com.example.gtmaddons.probe.Probes.emit("click", "button", "right");
			if (fight != null) fight.right++;
		}
	}

	private void onLeftClick() {
		ComboTracker.INSTANCE.onClick();
		if (fight == null) return;
		long now = System.nanoTime();
		fight.left++;
		if (lastLeftNanos < 0 || now - lastLeftNanos > BURST_GAP_NANOS) {
			fight.bursts++;
		} else {
			fight.addInterval((now - lastLeftNanos) / 1_000_000.0);
			if (now - lastLeftNanos < DOUBLE_NANOS) fight.doubles++;
		}
		lastLeftNanos = now;
		recent.addLast(now);
		while (now - recent.peekFirst() > SECOND_NANOS) recent.removeFirst();
		fight.peakCps = Math.max(fight.peakCps, recent.size());
	}

	/** What a melee click did (ComboTracker). */
	void onMeleeHit() {
		if (fight != null) fight.meleeHits++;
	}

	void onMeleeBlank() {
		if (fight != null) fight.meleeBlanks++;
	}

	void onMeleeMiss() {
		if (fight != null) fight.meleeMisses++;
	}

	/** The time between two of your melee hits, and whether blanks came between them. */
	void onHitGap(double ms, boolean blanks) {
		if (fight == null) return;
		if (blanks) {
			fight.gapBlank++;
			fight.gapBlankMs += ms;
		} else {
			fight.gapPlain++;
			fight.gapPlainMs += ms;
		}
	}
}
