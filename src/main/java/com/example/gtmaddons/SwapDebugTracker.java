package com.example.gtmaddons;

import java.util.ArrayList;
import java.util.List;

/**
 * Mouse tracking for a single swap.
 *
 * Fed one cursor position per rendered frame (in window pixels) along with
 * the on-screen rectangle of the slot the wingsuit starts in. Each frame's
 * cursor movement is treated as a straight line. The ideal swap is one
 * perfectly straight move from where the cursor started to the slot, then
 * no more movement until the wingsuit is in the hotbar (the swap itself is a
 * key press). The path is split into:
 *   - approach:   the path from the start to the first touch of the slot.
 *   - sideways:   how far the approach strayed to either side of the straight
 *                 line from the start to that first touch.
 *   - backward:   how far it went back away from the slot along that line.
 *   - overflick:  ALL movement after first touching the slot, inside it or
 *                 out, until the wingsuit reaches the hotbar (overshooting
 *                 and coming back, wiggling on the slot).
 *   - after:      movement once the wingsuit is in the hotbar - none of it
 *                 is needed, since closing the inventory is a key press.
 *                 Reported on its own; not part of efficiency.
 *
 * Efficiency (see efficiency()) charges every pixel of sideways, backward
 * and post-touch movement in full, so 100% is only a perfectly straight
 * line. The old score was the straight-line distance divided by the path
 * length, and that hardly notices a sideways bow (a 40 px bow on a 300 px
 * move still scored 96%) and ignored movement inside the slot.
 *
 * Positions are whole pixels, so a perfectly straight diagonal comes out as
 * a staircase. Sideways movement inside +-TOLERANCE_PX of the line is
 * treated as on the line, so that staircase isn't counted against you.
 */
final class SwapDebugTracker {

	/** Pixel noise allowed either side of the straight line before movement counts as sideways or backward. */
	private static final double TOLERANCE_PX = 1.5;
	/** Below this, the slot is already under the cursor: there is no line to be straight along. */
	private static final double MIN_NEEDED_PX = 1.0;

	private boolean hasSample;
	private double lastX, lastY;

	private boolean directKnown;
	/** Nearest point of the slot to where the cursor started; the target if the slot is never touched. */
	private double nearestX, nearestY;
	private long reachNanos;

	/** Every vertex of the path from the start until the wingsuit arrived, including the exact first-touch point. */
	private final List<double[]> path = new ArrayList<>();
	/** Index in path of the first touch of the slot, or -1. */
	private int entryIndex;

	private double totalPath;
	private double overflickPeak;
	private double afterArrivalPath;

	void reset() {
		hasSample = false;
		directKnown = false;
		reachNanos = -1L;
		path.clear();
		entryIndex = -1;
		totalPath = 0.0;
		overflickPeak = 0.0;
		afterArrivalPath = 0.0;
	}

	/**
	 * @param rect    {minX, minY, maxX, maxY} of the slot in window pixels,
	 *                or null if the slot isn't on screen (e.g. another
	 *                creative tab)
	 * @param arrived whether the wingsuit had already reached the hotbar
	 *                before this frame's movement
	 */
	void sample(double x, double y, double[] rect, boolean arrived, long nowNanos) {
		if (!hasSample) {
			hasSample = true;
			lastX = x;
			lastY = y;
			path.add(new double[] { x, y });
		}
		if (rect != null && !directKnown) {
			directKnown = true;
			double[] start = path.get(0);
			nearestX = Math.max(rect[0], Math.min(rect[2], start[0]));
			nearestY = Math.max(rect[1], Math.min(rect[3], start[1]));
		}

		double seg = Math.hypot(x - lastX, y - lastY);
		totalPath += seg;

		if (arrived) {
			afterArrivalPath += seg;
		} else if (entryIndex < 0) {
			double[] clip = rect != null ? clip(lastX, lastY, x, y, rect) : null;
			if (clip != null) {
				// First touch, part way along this frame's movement. Everything from
				// there to the end of the segment is already movement past the touch.
				reachNanos = nowNanos;
				path.add(new double[] { lastX + (x - lastX) * clip[0], lastY + (y - lastY) * clip[0] });
				entryIndex = path.size() - 1;
				path.add(new double[] { x, y });
			} else {
				path.add(new double[] { x, y });
			}
		} else {
			path.add(new double[] { x, y });
		}

		if (!arrived && entryIndex >= 0 && rect != null) {
			overflickPeak = Math.max(overflickPeak, distanceToRect(x, y, rect));
		}

		lastX = x;
		lastY = y;
	}

	boolean reachedSlot() { return reachNanos >= 0; }
	long reachNanos() { return reachNanos; }
	boolean slotSeen() { return directKnown; }

	/** All cursor movement from the swap opening to now. */
	double totalPath() { return totalPath; }

	/** Path length from the start to the first touch of the slot (or to where it got, if it never did). */
	double approachPath() {
		return pathLength(0, approachEnd());
	}

	/**
	 * The straight-line distance from the start to the first touch of the
	 * slot: the least movement the swap needed. If the slot was never
	 * touched, the distance to its nearest point.
	 */
	double directDistance() {
		return directKnown ? distance(path.get(0), targetX(), targetY()) : 0.0;
	}

	double unneededApproach() { return Math.max(0.0, approachPath() - directDistance()); }

	/** How far the approach strayed sideways of the straight line, counting every pixel out and back. */
	double sidewaysPath() { return Math.abs(analyze()[0]); }

	/** How far the approach went back away from the slot along the line (each such stretch counts once, not the trip back). */
	double awayPath() { return analyze()[1]; }

	/** All movement after first touching the slot until the wingsuit reached the hotbar. */
	double overflickPath() {
		return entryIndex >= 0 ? pathLength(entryIndex, path.size() - 1) : 0.0;
	}

	double overflickPeak() { return overflickPeak; }
	double afterArrivalPath() { return afterArrivalPath; }

	/**
	 * How direct the mouse movement to the wingsuit was, 0-100. 100% is one
	 * perfectly straight move to the slot and then no movement at all.
	 *
	 *   efficiency = needed / (needed + sideways + 2 x backward + after-touch)
	 *
	 * needed is the straight-line distance from the start to the first touch.
	 * sideways is every pixel moved to either side of that line, and after-touch
	 * is every pixel moved once the slot has been touched (inside it too). A
	 * stretch of backward movement costs twice - going back, then again to
	 * make the ground up. Movement once the wingsuit is in the hotbar isn't
	 * included (see afterArrivalPath).
	 *
	 * e.g. 300 px needed, a 20 px bow (40 px sideways): 300 / 340 = 88%.
	 * 30 px past the slot and back: 300 / 360 = 83%.
	 */
	double efficiency() {
		if (!directKnown) return 100.0;
		double[] parts = analyze();
		double needed = entryIndex >= 0 ? directDistance() : Math.max(0.0, alongAtEnd());
		double extra = Math.abs(parts[0]) + 2.0 * parts[1] + overflickPath();
		if (needed < MIN_NEEDED_PX) return extra < MIN_NEEDED_PX ? 100.0 : 0.0;
		return needed / (needed + extra) * 100.0;
	}

	// ---- Path analysis ----

	/** Index of the last vertex that counts as approach: the first touch, or the end of the path if there was none. */
	private int approachEnd() {
		return entryIndex >= 0 ? entryIndex : path.size() - 1;
	}

	private double targetX() {
		return entryIndex >= 0 ? path.get(entryIndex)[0] : nearestX;
	}

	private double targetY() {
		return entryIndex >= 0 ? path.get(entryIndex)[1] : nearestY;
	}

	/** How far along the start-to-slot line the approach got. */
	private double alongAtEnd() {
		double[] start = path.get(0);
		double length = distance(start, targetX(), targetY());
		if (length < MIN_NEEDED_PX) return 0.0;
		double[] last = path.get(approachEnd());
		return ((last[0] - start[0]) * (targetX() - start[0]) + (last[1] - start[1]) * (targetY() - start[1])) / length;
	}

	/**
	 * {sideways, backward} over the approach, relative to the straight line
	 * from the start to the target. Sideways is measured on the offset with
	 * +-TOLERANCE_PX taken off, so whole-pixel steps along a straight line
	 * cost nothing; backward is the depth of each retreat from the furthest
	 * point reached along the line (retreats shallower than the tolerance
	 * aren't counted).
	 */
	private double[] analyze() {
		if (!directKnown || path.isEmpty()) return new double[] { 0.0, 0.0 };
		double[] start = path.get(0);
		double length = distance(start, targetX(), targetY());
		if (length < MIN_NEEDED_PX) return new double[] { 0.0, 0.0 };
		double ux = (targetX() - start[0]) / length, uy = (targetY() - start[1]) / length;

		double sideways = 0.0, previousOffset = 0.0;
		double peak = 0.0, low = 0.0, backward = 0.0;
		boolean retreating = false;
		for (int i = 1; i <= approachEnd(); i++) {
			double dx = path.get(i)[0] - start[0], dy = path.get(i)[1] - start[1];
			double along = dx * ux + dy * uy;
			double side = dx * -uy + dy * ux;

			double offset = Math.signum(side) * Math.max(0.0, Math.abs(side) - TOLERANCE_PX);
			sideways += Math.abs(offset - previousOffset);
			previousOffset = offset;

			if (along >= peak) {
				if (retreating) backward += peak - low;
				retreating = false;
				peak = along;
				low = along;
			} else {
				low = Math.min(low, along);
				if (peak - low > TOLERANCE_PX) retreating = true;
			}
		}
		if (retreating) backward += peak - low;
		return new double[] { sideways, backward };
	}

	private double pathLength(int from, int to) {
		double total = 0.0;
		for (int i = from + 1; i <= to && i < path.size(); i++) {
			total += Math.hypot(path.get(i)[0] - path.get(i - 1)[0], path.get(i)[1] - path.get(i - 1)[1]);
		}
		return total;
	}

	private static double distance(double[] from, double toX, double toY) {
		return Math.hypot(toX - from[0], toY - from[1]);
	}

	private static double distanceToRect(double x, double y, double[] r) {
		double dx = Math.max(Math.max(r[0] - x, 0.0), x - r[2]);
		double dy = Math.max(Math.max(r[1] - y, 0.0), y - r[3]);
		return Math.hypot(dx, dy);
	}

	/**
	 * Liang-Barsky clip of the segment (x0,y0)-(x1,y1) against the rect.
	 * Returns {tEnter, tExit} along the segment, or null if it misses.
	 */
	private static double[] clip(double x0, double y0, double x1, double y1, double[] r) {
		double dx = x1 - x0, dy = y1 - y0;
		double[] p = { -dx, dx, -dy, dy };
		double[] q = { x0 - r[0], r[2] - x0, y0 - r[1], r[3] - y0 };
		double t0 = 0.0, t1 = 1.0;
		for (int i = 0; i < 4; i++) {
			if (p[i] == 0.0) {
				if (q[i] < 0.0) return null;
			} else {
				double t = q[i] / p[i];
				if (p[i] < 0.0) {
					if (t > t1) return null;
					if (t > t0) t0 = t;
				} else {
					if (t < t0) return null;
					if (t < t1) t1 = t;
				}
			}
		}
		return new double[] { t0, t1 };
	}
}
