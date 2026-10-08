package com.example.gtmaddons;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;

/**
 * Collects how you move with the keys, to learn what good movement looks like (nothing uses it for a rating yet):
 *   - fight: from the start of a fight (FightTracker) to its end, every movement key and speed;
 *   - swap window: the same, over the seconds right after a Wing / Air swap into an empty hotbar slot (GTMAddOnsClient holds
 *     such a swap's record until the window ends, like it does for momentum).
 * Sampled once per rendered frame and weighted by the frame time, so the result does not depend on the fps. Time with a screen open
 * (inventory, chat) is left out. Held times are milliseconds; a "switch" is the strafe (A to D or back) or the forward / back (W to
 * S or back) direction changing, even through a gap; jumps count key presses.
 */
public final class MovementInputTracker {

	public static final MovementInputTracker INSTANCE = new MovementInputTracker();

	private static final long MAX_FRAME_NANOS = 100_000_000L;

	/** Everything counted over one window (a fight or the time after a swap). */
	public static final class Stats {
		public double ms, w, a, s, d, still, ground, sprint, sneak;
		public int strafeSwitches, fbSwitches, jumps;
		public double distance, maxBps;
		private int lastLat = 0, lastFb = 0;

		void add(double dt, boolean fw, boolean bk, boolean lf, boolean rt, boolean jumped, boolean sneakKey, boolean sprintKey, boolean onGround,
				double step, double bps) {
			ms += dt;
			if (fw) w += dt;
			if (lf) a += dt;
			if (bk) s += dt;
			if (rt) d += dt;
			if (!fw && !bk && !lf && !rt) still += dt;
			if (onGround) ground += dt;
			if (sprintKey) sprint += dt;
			if (sneakKey) sneak += dt;
			int lat = (rt ? 1 : 0) - (lf ? 1 : 0), fb = (fw ? 1 : 0) - (bk ? 1 : 0);
			if (lat != 0) {
				if (lastLat != 0 && lat != lastLat) strafeSwitches++;
				lastLat = lat;
			}
			if (fb != 0) {
				if (lastFb != 0 && fb != lastFb) fbSwitches++;
				lastFb = fb;
			}
			if (jumped) jumps++;
			distance += step;
			maxBps = Math.max(maxBps, bps);
		}

		private static double r(double v) {
			return Math.round(v * 100.0) / 100.0;
		}

		/** The fight's numbers: { ms, w_ms, a_ms, s_ms, d_ms, still_ms, ground_ms, sprint_ms, sneak_ms, strafe_switches, fb_switches, jumps, distance, max_bps }. */
		public com.google.gson.JsonObject toJson() {
			com.google.gson.JsonObject o = new com.google.gson.JsonObject();
			o.addProperty("ms", Math.round(ms));
			o.addProperty("w_ms", Math.round(w));
			o.addProperty("a_ms", Math.round(a));
			o.addProperty("s_ms", Math.round(s));
			o.addProperty("d_ms", Math.round(d));
			o.addProperty("still_ms", Math.round(still));
			o.addProperty("ground_ms", Math.round(ground));
			o.addProperty("sprint_ms", Math.round(sprint));
			o.addProperty("sneak_ms", Math.round(sneak));
			o.addProperty("strafe_switches", strafeSwitches);
			o.addProperty("fb_switches", fbSwitches);
			o.addProperty("jumps", jumps);
			o.addProperty("distance", r(distance));
			o.addProperty("max_bps", r(maxBps));
			return o;
		}

		/** One line for the chat / dev log: "14.2 s: W 60% A 31% S 4% D 29%, still 12%, 18 strafe switches, 6 jumps, top 9.4 b/s". */
		public String describe() {
			if (ms <= 0) return "no movement recorded";
			return String.format("%.1f s: W %.0f%% A %.0f%% S %.0f%% D %.0f%%, still %.0f%%, %d strafe switches, %d back/forth, %d jumps, %.0f blocks, top %.1f b/s",
					ms / 1000, 100 * w / ms, 100 * a / ms, 100 * s / ms, 100 * d / ms, 100 * still / ms, strafeSwitches, fbSwitches, jumps, distance, maxBps);
		}
	}

	private Stats fight = null;
	private Stats swap = null;
	private long lastNanos = 0L;
	private boolean lastJump = false;
	private double lastX = 0, lastZ = 0;
	private boolean hasLast = false;

	private MovementInputTracker() {}

	// ---- Fight ----

	public void onFightStart() {
		fight = new Stats();
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

	// ---- The window after a swap ----

	public void startSwapWindow() {
		swap = new Stats();
	}

	/** The swap window's numbers, or null if none was open. */
	public Stats finishSwapWindow() {
		Stats done = swap;
		swap = null;
		return done;
	}

	// ---- Every frame ----

	public void onFrame(MinecraftClient client) {
		long now = System.nanoTime();
		long dtNanos = Math.min(now - lastNanos, MAX_FRAME_NANOS);
		lastNanos = now;
		net.minecraft.client.network.ClientPlayerEntity player = client.player;
		if (player == null) {
			hasLast = false;
			return;
		}
		double x = player.getX(), z = player.getZ();
		double step = hasLast ? Math.hypot(x - lastX, z - lastZ) : 0.0;
		lastX = x;
		lastZ = z;
		hasLast = true;
		boolean jump = client.options.jumpKey.isPressed();
		boolean jumped = jump && !lastJump;
		lastJump = jump;
		if (fight == null && swap == null) return;
		// Inventory, chat and menus: not moving with the keys, so not counted.
		if (client.currentScreen != null || dtNanos <= 0) return;
		double dt = dtNanos / 1_000_000.0;
		double bps = step > 5.0 ? 0.0 : step / (dtNanos / 1e9);
		if (step > 5.0) step = 0.0;
		boolean fw = client.options.forwardKey.isPressed(), bk = client.options.backKey.isPressed(), lf = client.options.leftKey.isPressed(), rt = client.options.rightKey.isPressed();
		boolean sneak = client.options.sneakKey.isPressed(), sprint = client.options.sprintKey.isPressed();
		boolean ground = player.isOnGround();
		if (fight != null) fight.add(dt, fw, bk, lf, rt, jumped, sneak, sprint, ground, step, bps);
		if (swap != null) swap.add(dt, fw, bk, lf, rt, jumped, sneak, sprint, ground, step, bps);
	}
}
