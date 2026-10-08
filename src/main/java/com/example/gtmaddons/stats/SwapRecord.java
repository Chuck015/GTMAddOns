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
 * The cursor fields describe how the inventory opened, in pixels so they don't depend on mouse sensitivity: cursorDx/cursorDy is
 * where the cursor was relative to the window centre when the inventory appeared (0, 0 in vanilla when opened from gameplay),
 * directPx/approachPx the straight and the travelled distance to the slot, guiX/guiY/scaledW/scaledH/guiScale where the inventory
 * was drawn (the recipe book moves it), creative 1 for the creative inventory, fromScreen 1 when it replaced another screen.
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
		Double speedAfterBps,
		Double cursorDx,
		Double cursorDy,
		Double directPx,
		Double approachPx,
		Double guiX,
		Double guiY,
		Double scaledW,
		Double scaledH,
		Double guiScale,
		Double creative,
		Double fromScreen,
		Double afterWMs,
		Double afterAMs,
		Double afterSMs,
		Double afterDMs,
		Double afterWindowMs,
		Double afterStrafeSwitches
) {
	/** This record with the movement keys over the window after the swap filled in (see MovementInputTracker). */
	public SwapRecord withAfterInput(double wMs, double aMs, double sMs, double dMs, double windowMs, double strafeSwitches) {
		return new SwapRecord(ts, result, totalMs, reachMs, slotToHotbarMs, hotbarToCloseMs, wingToHotbarMs, mouseDeg, approachDeg,
				neededDeg, efficiency, awayDeg, overflickDeg, overflickPeakDeg, afterDeg, category, swapType, speedBeforeBps, speedAfterBps,
				cursorDx, cursorDy, directPx, approachPx, guiX, guiY, scaledW, scaledH, guiScale, creative, fromScreen,
				wMs, aMs, sMs, dMs, windowMs, strafeSwitches);
	}

	/** This record with speedAfterBps filled in (it's measured after the swap ends). */
	public SwapRecord withSpeedAfterBps(double bps) {
		return new SwapRecord(ts, result, totalMs, reachMs, slotToHotbarMs, hotbarToCloseMs, wingToHotbarMs, mouseDeg, approachDeg,
				neededDeg, efficiency, awayDeg, overflickDeg, overflickPeakDeg, afterDeg, category, swapType, speedBeforeBps, bps,
					cursorDx, cursorDy, directPx, approachPx, guiX, guiY, scaledW, scaledH, guiScale, creative, fromScreen,
					afterWMs, afterAMs, afterSMs, afterDMs, afterWindowMs, afterStrafeSwitches);
	}
}
