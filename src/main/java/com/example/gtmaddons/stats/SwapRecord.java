package com.example.gtmaddons.stats;

/**
 * Everything measured about one swap. Times are in milliseconds, mouse
 * movement in degrees. A null field means that step didn't happen (e.g.
 * the wingsuit never reached the hotbar) or couldn't be measured.
 * category is the PvpCategory name when the inventory was opened; only
 * WING and AIR swaps count toward swap stats. swapType is set for successful
 * swaps: WINGSUIT, JETPACK, JP_TO_WING or WING_TO_JP (the last three only in Air).
 * speedBeforeBps / speedAfterBps are your momentum, only for a successful
 * Wing swap that put the wingsuit into an empty hotbar slot: horizontal
 * speed in blocks/s when the inventory opened, and your average speed over
 * the 2 seconds after it closed (cut short if you land).
 */
public record SwapRecord(
		long ts,
		String result,
		double totalMs,
		Double reachMs,
		Double slotToHotbarMs,
		Double hotbarToCloseMs,
		Double wingToHotbarMs,
		double mouseDeg,
		Double approachDeg,
		Double neededDeg,
		Double efficiency,
		Double awayDeg,
		Double overflickDeg,
		Double overflickPeakDeg,
		Double afterDeg,
		String category,
		String swapType,
		Double speedBeforeBps,
		Double speedAfterBps
) {
	/** This record with speedAfterBps filled in (it's measured after the swap ends). */
	public SwapRecord withSpeedAfterBps(double bps) {
		return new SwapRecord(ts, result, totalMs, reachMs, slotToHotbarMs, hotbarToCloseMs, wingToHotbarMs, mouseDeg, approachDeg,
				neededDeg, efficiency, awayDeg, overflickDeg, overflickPeakDeg, afterDeg, category, swapType, speedBeforeBps, bps);
	}
}
