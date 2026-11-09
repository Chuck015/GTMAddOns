package com.example.gtmaddons;

import com.example.gtmaddons.compat.Mc;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * How well you keep your crosshair on the player you are fighting in melee, for a future aim rating - information only for now (stored
 * with the fight next to the mouse and click numbers, shown in Raw info; nothing rates anyone with it yet).
 *
 * Every frame of a fight in which you hold a melee weapon, have no screen open and another player is within RANGE blocks, the nearest
 * such player is the target, and this adds up:
 *   - time in range, and of that the time your crosshair ray actually crosses their hitbox (on target);
 *   - aim error: the angle between where you look and the middle of their body, time-weighted, with its spread, split into yaw and pitch;
 *   - how hard they were to follow: how fast they moved across your view (degrees per second), and how far away they were;
 *   - re-acquiring: each time your crosshair comes back onto them after being off, how long it was off (gaps over MAX_REACQUIRE_NANOS,
 *     e.g. turning away on purpose, are not counted as re-acquires);
 *   - aimed clicks: each melee click in range, whether the crosshair was on them, and (via ComboTracker) whether an on-target click
 *     hit, blanked on the combo rules or missed.
 * Sampled once per rendered frame, so it costs a few vector operations per frame and nothing grows with the length of a fight.
 */
public final class MeleeAimTracker {

	public static final MeleeAimTracker INSTANCE = new MeleeAimTracker();

	/** A player this close (blocks, eye to body) counts as the melee target. */
	private static final double RANGE = 6.0;
	private static final long MAX_FRAME_NANOS = 100_000_000L;
	private static final long MAX_REACQUIRE_NANOS = 1_500_000_000L;

	/** Where a melee click was aimed: no player in range, in range but crosshair off them, or on them. */
	public static final int NO_TARGET = 0, OFF_TARGET = 1, ON_TARGET = 2;

	/** Everything counted over one fight. */
	public static final class Stats {
		public double rangeMs, onMs, errDegMs, errSqMs, yawErrMs, pitchErrMs, targetDpsMs, distMs, reacquireMs;
		public int reacquires, clicksInRange, clicksOn, onHits, onBlanks, onMisses;

		private static double r(double v) {
			return Math.round(v * 100.0) / 100.0;
		}

		/**
		 * Adds the fight's aim numbers to its mouse numbers: { aim_range_ms, aim_on_ms, aim_on_pct, aim_err_deg, aim_err_sd, aim_yaw_err,
		 * aim_pitch_err, aim_target_dps, aim_dist, aim_reacquires, aim_reacquire_ms, aim_clicks_range, aim_clicks_on, aim_on_hits,
		 * aim_on_blanks, aim_on_misses }.
		 */
		void addTo(com.google.gson.JsonObject o) {
			o.addProperty("aim_range_ms", Math.round(rangeMs));
			if (rangeMs > 0) {
				double err = errDegMs / rangeMs;
				o.addProperty("aim_on_ms", Math.round(onMs));
				o.addProperty("aim_on_pct", r(100.0 * onMs / rangeMs));
				o.addProperty("aim_err_deg", r(err));
				o.addProperty("aim_err_sd", r(Math.sqrt(Math.max(0.0, errSqMs / rangeMs - err * err))));
				o.addProperty("aim_yaw_err", r(yawErrMs / rangeMs));
				o.addProperty("aim_pitch_err", r(pitchErrMs / rangeMs));
				o.addProperty("aim_target_dps", r(targetDpsMs / rangeMs));
				o.addProperty("aim_dist", r(distMs / rangeMs));
			}
			o.addProperty("aim_reacquires", reacquires);
			if (reacquires > 0) o.addProperty("aim_reacquire_ms", r(reacquireMs / reacquires));
			o.addProperty("aim_clicks_range", clicksInRange);
			o.addProperty("aim_clicks_on", clicksOn);
			o.addProperty("aim_on_hits", onHits);
			o.addProperty("aim_on_blanks", onBlanks);
			o.addProperty("aim_on_misses", onMisses);
		}
	}

	private Stats fight = null;
	private long lastNanos = 0L;
	private int lastTargetId = -1;
	private double lastTargetYaw, lastTargetPitch;
	private boolean wasOn = false;
	private long offSinceNanos = -1L;
	/** The aim at the latest frame, for the click that comes next. */
	private int aimNow = NO_TARGET;

	private MeleeAimTracker() {}

	public void onFightStart() {
		fight = new Stats();
		lastTargetId = -1;
		wasOn = false;
		offSinceNanos = -1L;
	}

	public Stats onFightEnd() {
		Stats done = fight;
		fight = null;
		return done;
	}

	public void onFightDrop() {
		fight = null;
	}

	/** Where the current melee click is aimed (ComboTracker keeps it with the click until its outcome is known). */
	public int aimForClick() {
		if (fight != null && aimNow != NO_TARGET) {
			fight.clicksInRange++;
			if (aimNow == ON_TARGET) fight.clicksOn++;
		}
		return aimNow;
	}

	/** What an on-target melee click did: 0 hit, 1 blank, 2 miss (ComboTracker). */
	public void onClickOutcome(int aim, int outcome) {
		if (fight == null || aim != ON_TARGET) return;
		if (outcome == 0) fight.onHits++;
		else if (outcome == 1) fight.onBlanks++;
		else fight.onMisses++;
	}

	public void onFrame(Minecraft client) {
		long now = System.nanoTime();
		long dtNanos = Math.min(now - lastNanos, MAX_FRAME_NANOS);
		lastNanos = now;
		aimNow = NO_TARGET;
		LocalPlayer me = client.player;
		if (fight == null || me == null || client.level == null || Mc.screen(client) != null || !ComboTracker.isMeleeItem(me.getMainHandItem())) {
			lastTargetId = -1;
			return;
		}
		Vec3 eye = me.getEyePosition();
		Player target = null;
		double best = RANGE;
		for (Player p : client.level.players()) {
			if (p == me || p.isSpectator() || p.isDeadOrDying() || p.isInvisible()) continue;
			double d = eye.distanceTo(p.getBoundingBox().getCenter());
			if (d < best) {
				best = d;
				target = p;
			}
		}
		if (target == null) {
			lastTargetId = -1;
			return;
		}
		double dt = dtNanos / 1_000_000.0;
		Vec3 look = me.getViewVector(1.0f);
		Vec3 center = target.getBoundingBox().getCenter();
		Vec3 to = center.subtract(eye);
		double err = Math.toDegrees(Math.acos(Mth.clamp(look.dot(to.normalize()), -1.0, 1.0)));
		double targetYaw = Math.toDegrees(Math.atan2(-to.x, to.z)), targetPitch = Math.toDegrees(-Math.atan2(to.y, Math.hypot(to.x, to.z)));
		AABB box = target.getBoundingBox().inflate(target.getPickRadius());
		boolean on = box.contains(eye) || box.clip(eye, eye.add(look.scale(RANGE))).isPresent();
		aimNow = on ? ON_TARGET : OFF_TARGET;
		if (dtNanos <= 0) return;

		fight.rangeMs += dt;
		if (on) fight.onMs += dt;
		fight.errDegMs += err * dt;
		fight.errSqMs += err * err * dt;
		fight.yawErrMs += Math.abs(Mth.wrapDegrees(targetYaw - me.getYRot())) * dt;
		fight.pitchErrMs += Math.abs(targetPitch - me.getXRot()) * dt;
		fight.distMs += best * dt;
		if (target.getId() == lastTargetId) {
			double moved = Math.hypot(Mth.wrapDegrees(targetYaw - lastTargetYaw), targetPitch - lastTargetPitch);
			fight.targetDpsMs += moved / (dt / 1000.0) * dt;
		}
		if (on && !wasOn && offSinceNanos >= 0 && now - offSinceNanos <= MAX_REACQUIRE_NANOS && target.getId() == lastTargetId) {
			fight.reacquires++;
			fight.reacquireMs += (now - offSinceNanos) / 1_000_000.0;
		}
		if (!on && (wasOn || offSinceNanos < 0)) offSinceNanos = now;
		wasOn = on;
		lastTargetId = target.getId();
		lastTargetYaw = targetYaw;
		lastTargetPitch = targetPitch;
	}
}
