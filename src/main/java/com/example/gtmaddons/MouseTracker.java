package com.example.gtmaddons;

import com.example.gtmaddons.compat.Mc;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;

/**
 * Collects how you move the mouse during a fight - information only, nothing rates or flags anyone with it (it is stored with the fight
 * and shows in Raw info). Two things are kept apart, frame by frame:
 *   - the turning the MOUSE made: the game hands every mouse movement to Entity.changeLookDirection (EntityLookMixin), which turns the
 *     head by 0.15 degrees per mouse unit - so that is exactly how far the mouse turned you, with your sensitivity already in it;
 *   - the turning that actually happened: how far yaw and pitch changed since the last frame.
 * They match for a plain mouse; whatever turned you without the mouse (a server teleport or knockback rotation, respawn, or a mod
 * setting the angles) shows as "other" turning.
 *
 * Sampled once per rendered frame (like MovementInputTracker), so the cost is a few additions per frame and nothing grows with the length of
 * a fight. Time with a screen open is left out. Speeds are degrees per second over one frame, so a very low frame rate smooths peaks.
 */
public final class MouseTracker {

	public static final MouseTracker INSTANCE = new MouseTracker();

	private static final long MAX_FRAME_NANOS = 100_000_000L;
	/** What the game turns the head by per mouse unit (Entity.changeLookDirection). */
	private static final float DEGREES_PER_UNIT = 0.15f;
	/** Under this much turning in a frame nothing counts as moving (degrees). */
	private static final double STILL_DEG = 0.02;
	/** A fast move starts when the mouse turns faster than this (degrees per second). */
	private static final double FAST_DPS = 1000.0;

	/** Everything counted over one fight. */
	public static final class Stats {
		public double ms, mouseDeg, yawDeg, pitchDeg, turnDeg, otherDeg, movingMs, stillMs, peakDps;
		/** How you clicked over the same fight (ClickTracker), sent with these numbers. */
		public ClickTracker.Stats clicks;
		public int frames, mouseFrames, otherFrames, fastMoves;
		private boolean fast = false;

		void add(double dtMs, double mouseYaw, double mousePitch, double turnYaw, double turnPitch, double otherYaw, double otherPitch) {
			ms += dtMs;
			frames++;
			double mouse = Math.hypot(mouseYaw, mousePitch);
			double dps = mouse / (dtMs / 1000.0);
			if (mouse > STILL_DEG) {
				mouseFrames++;
				movingMs += dtMs;
				mouseDeg += mouse;
				yawDeg += Math.abs(mouseYaw);
				pitchDeg += Math.abs(mousePitch);
				peakDps = Math.max(peakDps, dps);
			} else {
				stillMs += dtMs;
			}
			boolean nowFast = dps > FAST_DPS;
			if (nowFast && !fast) fastMoves++;
			fast = nowFast;
			turnDeg += Math.hypot(turnYaw, turnPitch);
			double other = Math.hypot(otherYaw, otherPitch);
			if (other > STILL_DEG) {
				otherFrames++;
				otherDeg += other;
			}
		}

		private static double r(double v) {
			return Math.round(v * 100.0) / 100.0;
		}

		/**
		 * The fight's numbers: { ms, frames, mouse_deg, yaw_deg, pitch_deg, turn_deg, other_deg, other_frames, moving_ms, still_ms, mouse_frames,
		 * peak_dps, fast_moves }. mouse_deg is the turning the mouse made, turn_deg all the turning that happened, other_deg what the mouse did not
		 * make.
		 */
		public com.google.gson.JsonObject toJson() {
			com.google.gson.JsonObject o = new com.google.gson.JsonObject();
			o.addProperty("ms", Math.round(ms));
			o.addProperty("frames", frames);
			o.addProperty("mouse_deg", r(mouseDeg));
			o.addProperty("yaw_deg", r(yawDeg));
			o.addProperty("pitch_deg", r(pitchDeg));
			o.addProperty("turn_deg", r(turnDeg));
			o.addProperty("other_deg", r(otherDeg));
			o.addProperty("other_frames", otherFrames);
			o.addProperty("moving_ms", Math.round(movingMs));
			o.addProperty("still_ms", Math.round(stillMs));
			o.addProperty("mouse_frames", mouseFrames);
			o.addProperty("peak_dps", r(peakDps));
			o.addProperty("fast_moves", fastMoves);
			if (clicks != null) clicks.addTo(o);
			return o;
		}
	}

	private Stats fight = null;
	/** Mouse turning handed to the game since the last frame (degrees, as the game applies it: yaw right, pitch down). */
	private double pendingYaw = 0, pendingPitch = 0;
	private long lastNanos = 0L;
	private float lastYaw = 0f, lastPitch = 0f;
	private boolean hasLast = false;

	private MouseTracker() {}

	public void onFightStart() {
		fight = new Stats();
		ClickTracker.INSTANCE.onFightStart();
	}

	/** The fight ended: its numbers (null if none were being collected). */
	public Stats onFightEnd() {
		Stats done = fight;
		fight = null;
		ClickTracker.Stats clicks = ClickTracker.INSTANCE.onFightEnd();
		if (done != null) done.clicks = clicks;
		return done;
	}

	public void onFightDrop() {
		fight = null;
		ClickTracker.INSTANCE.onFightDrop();
	}

	/** Called by EntityLookMixin for every mouse movement the game applies to an entity; only the local player's counts. */
	public void onMouseTurn(Entity entity, double cursorDeltaX, double cursorDeltaY) {
		Minecraft client = Minecraft.getInstance();
		if (entity != client.player) return;
		pendingYaw += cursorDeltaX * DEGREES_PER_UNIT;
		pendingPitch += cursorDeltaY * DEGREES_PER_UNIT;
	}

	/** Every frame, after the game has read the mouse for it. */
	public void onFrame(Minecraft client) {
		long now = System.nanoTime();
		long dtNanos = Math.min(now - lastNanos, MAX_FRAME_NANOS);
		lastNanos = now;
		net.minecraft.client.player.LocalPlayer player = client.player;
		double mouseYaw = pendingYaw, mousePitchWanted = pendingPitch;
		pendingYaw = 0;
		pendingPitch = 0;
		if (player == null) {
			hasLast = false;
			return;
		}
		float yaw = player.getYRot(), pitch = player.getXRot();
		boolean first = !hasLast;
		double turnYaw = first ? 0.0 : Mth.wrapDegrees(yaw - lastYaw), turnPitch = first ? 0.0 : pitch - lastPitch;
		// The head cannot go past straight up or down, so the mouse only turned it as far as the clamp allowed.
		double mousePitch = first ? 0.0 : Mth.clamp(lastPitch + mousePitchWanted, -90.0, 90.0) - lastPitch;
		lastYaw = yaw;
		lastPitch = pitch;
		hasLast = true;
		if (first || fight == null) return;
		// Inventory, chat and menus: the mouse is not turning you, so not counted.
		if (Mc.screen(client) != null || dtNanos <= 0) return;
		fight.add(dtNanos / 1_000_000.0, mouseYaw, mousePitch, turnYaw, turnPitch, Mth.wrapDegrees(turnYaw - mouseYaw), turnPitch - mousePitch);
	}
}
