package com.example.gtmaddons.gun;

import com.example.gtmaddons.BoostAngleHud;
import com.example.gtmaddons.PvpCategory;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Dev mode movement logging, for finding GTM's wingsuit boost and jetpack
 * signals. Each event gets a detailed line in chat (with the sounds that
 * played at your position) and in the log:
 *
 *   - Wing boost: pressing sneak (shift) while gliding. A boost is
 *     confirmed by its start sounds (item pickup + arrow shot at your
 *     position, together); a press with none within BOOST_SOUND_NANOS
 *     didn't boost. GTM only boosts looking more than 10 degrees up (see
 *     BoostAngleHud), so each result says whether that was expected. It
 *     also has a height limit: a miss with a good angle counts toward it,
 *     and the session's highest boost vs lowest such miss narrows it down,
 *     both as world Y and as blocks above the ground. A boost that starts
 *     while shift is held (not just pressed) uses your look angle about one
 *     ping earlier, which is roughly what the server went by.
 *   - Jetpack start: pressing jump twice within DOUBLE_TAP_NANOS while
 *     wearing a jetpack. For WATCH_NANOS afterwards your vertical speed and
 *     flight state are sampled, and the start is judged:
 *       OK     - you started flying, or were still rising after
 *                RISING_CHECK_NANOS (a normal jump has peaked by then)
 *       FAILED - neither
 *     A slow "double-tap" (over NORMAL_JUMP_GAP_MS) whose second press is on
 *     the ground is just two jumps; it's logged as not a start, not a failure.
 *     This verdict is a first guess until the logs show GTM's real jetpack
 *     sound or flight state.
 */
final class MovementDebugger {

	private static final long DOUBLE_TAP_NANOS = 1_000_000_000L;
	/** A "double-tap" slower than this with the second press on the ground is just two jumps (our dev log, 2026-09-27). */
	private static final long NORMAL_JUMP_GAP_MS = 350L;
	private static final long WATCH_NANOS = 750_000_000L;
	private static final long RISING_CHECK_NANOS = 400_000_000L;
	private static final long SAMPLE_NANOS = 100_000_000L;
	private static final long BOOST_SOUND_NANOS = 1_000_000_000L;
	/** The boost's pickup and arrow sounds arrive together; this is "together". */
	private static final long BOOST_PAIR_NANOS = 50_000_000L;
	private static final long LOOK_HISTORY_NANOS = 2_000_000_000L;
	/** A miss this soon after a boost might be a cooldown, so it doesn't count toward the height limit. */
	private static final long RECENT_BOOST_NANOS = 1_500_000_000L;
	private static final String BOOST_PICKUP = "entity.item.pickup";
	private static final String BOOST_ARROW = "entity.arrow.shoot";
	private static final double STILL_RISING = 0.05;
	/** Sounds this close are yours (boost/jetpack sounds play at your position). */
	private static final double OWN_SOUND_RANGE = 3.0;

	private final DevLogger log;

	private boolean sneakWasDown = false;
	private boolean jumpWasDown = false;
	private long lastJumpPressNanos = -1L;

	// Shift press waiting for the boost sounds
	private long pressNanos = -1L;
	private float pressPitch = 0f;
	private double pressY = 0.0;
	private double pressGround = Double.NaN;
	private String pressMotion = "";
	private long lastPickupNanos = -1L;
	private long lastBoostNanos = -1L;
	/** Recent pitch while gliding, to look up what the server saw about a ping ago. */
	private final ArrayDeque<LookSample> lookHistory = new ArrayDeque<>();
	// Height limit so far: highest boost vs lowest miss that had a good angle
	private double highestBoostY = Double.NEGATIVE_INFINITY;
	private double lowestFailY = Double.POSITIVE_INFINITY;
	private double highestBoostGround = Double.NEGATIVE_INFINITY;
	private double lowestFailGround = Double.POSITIVE_INFINITY;

	private record LookSample(long nanos, float pitch) {}

	// Jetpack start being watched
	private long watchStartNanos = -1L;
	private long nextSampleNanos = 0L;
	private boolean sawFlying = false;
	private boolean stillRising = false;
	private double peakRise = 0.0;
	private long tapGapMs = 0L;
	private boolean secondPressOnGround = false;
	private final Set<String> jetpackSounds = new LinkedHashSet<>();

	MovementDebugger(DevLogger log) {
		this.log = log;
	}

	/** Forgets the wing boost height limit found so far (dev mode turned on again). */
	void reset() {
		pressNanos = -1L;
		lastPickupNanos = -1L;
		lastBoostNanos = -1L;
		lookHistory.clear();
		highestBoostY = highestBoostGround = Double.NEGATIVE_INFINITY;
		lowestFailY = lowestFailGround = Double.POSITIVE_INFINITY;
	}

	/** Every sound while dev mode is on; distance from your eyes, or NaN if unknown. */
	void onSound(String soundId, double distance) {
		if (Double.isNaN(distance) || distance > OWN_SOUND_RANGE) return;
		String id = soundId.replace("minecraft:", "");
		if (watchStartNanos >= 0) jetpackSounds.add(id);
		if (!log.wants(DevFilter.WING_BOOST)) return;

		long now = System.nanoTime();
		if (id.equals(BOOST_PICKUP)) {
			lastPickupNanos = now;
		} else if (id.equals(BOOST_ARROW) && lastPickupNanos >= 0 && now - lastPickupNanos <= BOOST_PAIR_NANOS) {
			lastPickupNanos = -1L; // the boost trail repeats the arrow sound; only the pair is a start
			onBoostStart(MinecraftClient.getInstance(), now);
		}
	}

	void onFrame(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (player == null) return;
		long now = System.nanoTime();
		ItemStack chest = player.getInventory().getStack(PvpCategory.CHEST_INVENTORY_INDEX);

		boolean boostOn = log.wants(DevFilter.WING_BOOST);
		boolean jetpackOn = log.wants(DevFilter.JETPACK);

		// Wing boost: shift while gliding
		if (player.isGliding()) lookHistory.addLast(new LookSample(now, player.getPitch()));
		while (!lookHistory.isEmpty() && now - lookHistory.peekFirst().nanos() > LOOK_HISTORY_NANOS) lookHistory.removeFirst();

		if (!boostOn) pressNanos = -1L;
		if (pressNanos >= 0 && now - pressNanos > BOOST_SOUND_NANOS) onNoBoost(player);
		boolean sneakDown = client.options.sneakKey.isPressed();
		if (boostOn && sneakDown && !sneakWasDown && player.isGliding()) {
			if (pressNanos >= 0) onNoBoost(player);
			pressNanos = now;
			pressPitch = player.getPitch();
			pressY = player.getY();
			pressGround = groundBelow(client, player);
			pressMotion = motion(player);
			log.openWindow(client);
			log.add("WING BOOST (shift)  " + look(pressPitch) + "  " + height(pressY, pressGround) + "  " + pressMotion
					+ "  wearing=\"" + name(chest) + "\"");
		}
		sneakWasDown = sneakDown;

		// Jetpack: double-tap jump while wearing one
		boolean jumpDown = client.options.jumpKey.isPressed();
		if (!jetpackOn) watchStartNanos = -1L;
		if (jetpackOn && jumpDown && !jumpWasDown) {
			boolean doubleTap = lastJumpPressNanos >= 0 && now - lastJumpPressNanos <= DOUBLE_TAP_NANOS;
			if (doubleTap && PvpCategory.isWornJetpack(chest) && watchStartNanos < 0) {
				tapGapMs = (now - lastJumpPressNanos) / 1_000_000L;
				secondPressOnGround = player.isOnGround();
				watchStartNanos = now;
				nextSampleNanos = now;
				sawFlying = false;
				stillRising = false;
				peakRise = 0.0;
				jetpackSounds.clear();
				log.openWindow(client);
				log.add(String.format("JETPACK DOUBLE-TAP (%dms apart)  %s  onGround=%s  wearing=\"%s\"",
						tapGapMs, motion(player), player.isOnGround(), name(chest)));
				lastJumpPressNanos = -1L; // a third press starts a new double-tap
			} else {
				lastJumpPressNanos = now;
			}
		}
		jumpWasDown = jumpDown;

		if (watchStartNanos >= 0) watchJetpackStart(client, player, now);
	}

	private void watchJetpackStart(MinecraftClient client, ClientPlayerEntity player, long now) {
		long elapsed = now - watchStartNanos;
		double ySpeed = player.getVelocity().y;
		peakRise = Math.max(peakRise, ySpeed);
		if (player.getAbilities().flying) sawFlying = true;
		if (elapsed >= RISING_CHECK_NANOS && ySpeed > STILL_RISING) stillRising = true;

		if (now >= nextSampleNanos) {
			nextSampleNanos = now + SAMPLE_NANOS;
			log.openWindow(client);
			log.add(String.format("  jetpack +%dms  %s  flying=%s",
					elapsed / 1_000_000L, motion(player), player.getAbilities().flying));
		}

		if (elapsed >= WATCH_NANOS) {
			boolean ok = sawFlying || stillRising;
			String why = sawFlying ? "started flying" : stillRising ? "still rising after a jump would have peaked" : "no flight and no lift";
			String sounds = soundList(jetpackSounds);
			watchStartNanos = -1L;
			// Two separate jumps (the second from the ground, well after the first)
			// aren't a jetpack attempt - log it, but don't call it a failure.
			if (!ok && secondPressOnGround && tapGapMs > NORMAL_JUMP_GAP_MS) {
				log.add(String.format("JETPACK NOT A START  (normal jump: second press on the ground, %dms after the first)", tapGapMs));
				return;
			}
			log.add(String.format("JETPACK START %s  (double-tap %dms apart; %s; peak y-speed %+.1f b/s; sounds: %s)",
					ok ? "OK" : "FAILED", tapGapMs, why, peakRise * 20, sounds));
			DevLogger.chat(String.format("Jetpack start %s | %dms double-tap | %s | peak y-speed %+.1f b/s | sounds: %s",
					ok ? "OK" : "FAILED", tapGapMs, why, peakRise * 20, sounds), ok ? Formatting.GRAY : Formatting.RED);
		}
	}

	private static String soundList(Set<String> sounds) {
		return sounds.isEmpty() ? "none" : String.join(", ", sounds);
	}

	private static String motion(ClientPlayerEntity player) {
		Vec3d v = player.getVelocity();
		// Velocity is blocks per tick; x20 for blocks per second.
		return String.format("speed %.1f b/s, y-speed %+.1f b/s%s",
				Math.hypot(v.x, v.z) * 20, v.y * 20, player.isGliding() ? ", gliding" : "");
	}

	// ---- Wing boost results ----

	/** The boost start sounds arrived: judge it by the press, or by holding shift. */
	private void onBoostStart(MinecraftClient client, long now) {
		ClientPlayerEntity player = client.player;
		if (player == null) return;
		String how;
		float pitch;
		// The highest you looked between the shift press and the boost: the
		// crosshair can move a lot in that time, and the server may go by a
		// later angle than the press.
		float highestPitch;
		double y, ground;
		if (pressNanos >= 0) {
			pitch = pressPitch;
			highestPitch = Math.min(pressPitch, highestPitchSince(pressNanos, player.getPitch()));
			y = pressY;
			ground = pressGround;
			how = String.format("at shift press, %dms to boost, highest %s in between",
					(now - pressNanos) / 1_000_000L, upDown(-highestPitch));
			pressNanos = -1L;
		} else if (sneakWasDown) {
			int ping = Math.max(0, ShotTracker.ping(client));
			pitch = pitchAt(now - ping * 1_000_000L, player.getPitch());
			highestPitch = pitch;
			y = player.getY();
			ground = groundBelow(client, player);
			how = String.format("holding shift, angle ~%dms ago (ping)", ping);
		} else {
			log.openWindow(client);
			log.add("WING BOOST started without shift  " + look(player.getPitch()) + "  " + height(player.getY(), groundBelow(client, player))
					+ "  " + motion(player));
			return;
		}
		lastBoostNanos = now;
		highestBoostY = Math.max(highestBoostY, y);
		if (!Double.isNaN(ground)) highestBoostGround = Math.max(highestBoostGround, ground);
		// Only unexpected if you stayed below the angle the whole time.
		String expected = angleOk(pitch) ? "as expected"
				: angleOk(highestPitch) ? "as expected - looked up past the angle after pressing"
				: "UNEXPECTED - angle stayed too low";
		log.openWindow(client);
		log.add(String.format("WING BOOST OK (%s)  %s  %s  (%s)  %s  height limit: %s",
				expected, look(pitch), height(y, ground), how, motion(player), heightLimit()));
		DevLogger.chat(String.format("Wing boost OK (%s) | %s | %s | %s | limit: %s", expected, look(pitch), how, height(y, ground), heightLimit()),
				Formatting.GREEN);
	}

	/**
	 * A shift press with no boost sounds in time. If the angle was fine, it
	 * should have boosted - so something else (probably height) stopped it,
	 * and it counts toward the height limit. Right after a boost it doesn't,
	 * in case GTM has a cooldown.
	 */
	private void onNoBoost(ClientPlayerEntity player) {
		pressNanos = -1L;
		String why;
		Formatting color;
		long sinceBoost = lastBoostNanos >= 0 ? (System.nanoTime() - lastBoostNanos) / 1_000_000L : -1L;
		if (!angleOk(pressPitch)) {
			why = "as expected - angle too low";
			color = Formatting.GRAY;
		} else if (sinceBoost >= 0 && sinceBoost < RECENT_BOOST_NANOS / 1_000_000L) {
			why = String.format("angle OK but only %dms after a boost (cooldown?), not counted for height", sinceBoost);
			color = Formatting.GOLD;
		} else {
			why = "angle OK, should have boosted";
			color = Formatting.RED;
			lowestFailY = Math.min(lowestFailY, pressY);
			if (!Double.isNaN(pressGround)) lowestFailGround = Math.min(lowestFailGround, pressGround);
		}
		String since = sinceBoost >= 0 ? String.format("  %.1fs since last boost", sinceBoost / 1000.0) : "";
		log.add(String.format("WING BOOST NONE (%s)  %s  %s  was %s%s  height limit: %s",
				why, look(pressPitch), height(pressY, pressGround), pressMotion, since, heightLimit()));
		DevLogger.chat(String.format("Wing boost NONE (%s) | %s | %s | limit: %s", why, look(pressPitch), height(pressY, pressGround), heightLimit()),
				color);
	}

	private static boolean angleOk(float pitch) {
		return pitch < BoostAngleHud.BOOST_MAX_PITCH;
	}

	/**
	 * What this session says about the height limit, both as world Y and as
	 * blocks above the ground - whichever stays consistent (highest boost
	 * below lowest angle-OK miss) is the one GTM uses.
	 */
	private String heightLimit() {
		return "Y " + range(highestBoostY, lowestFailY) + ", above ground " + range(highestBoostGround, lowestFailGround);
	}

	private static String range(double highestBoost, double lowestFail) {
		boolean boosted = highestBoost > Double.NEGATIVE_INFINITY, missed = lowestFail < Double.POSITIVE_INFINITY;
		if (!boosted && !missed) return "no data";
		if (!missed) return String.format("boosted up to %.1f, no misses yet", highestBoost);
		if (!boosted) return String.format("no boost yet, missed from %.1f", lowestFail);
		if (highestBoost < lowestFail) return String.format("between %.1f and %.1f", highestBoost, lowestFail);
		return String.format("MIXED (boosted at %.1f, missed at %.1f)", highestBoost, lowestFail);
	}

	/** Blocks straight down to the ground (water counts), or NaN if there's none in the world. */
	private static double groundBelow(MinecraftClient client, ClientPlayerEntity player) {
		if (client.world == null) return Double.NaN;
		Vec3d feet = new Vec3d(player.getX(), player.getY(), player.getZ());
		Vec3d bottom = new Vec3d(feet.x, client.world.getBottomY() - 1, feet.z);
		BlockHitResult hit = client.world.raycast(new RaycastContext(
				feet, bottom, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.ANY, player));
		return hit.getType() == HitResult.Type.MISS ? Double.NaN : feet.y - hit.getPos().y;
	}

	private static String height(double y, double ground) {
		return String.format("Y %.1f, %s", y, Double.isNaN(ground) ? "no ground below" : String.format("%.1f above ground", ground));
	}

	/** The lowest pitch (highest look up) recorded since a moment, or fallback if none. */
	private float highestPitchSince(long nanos, float fallback) {
		float best = fallback;
		for (LookSample sample : lookHistory) {
			if (sample.nanos() >= nanos) best = Math.min(best, sample.pitch());
		}
		return best;
	}

	/** Pitch at a moment in the last couple of seconds, or fallback if not recorded. */
	private float pitchAt(long nanos, float fallback) {
		LookSample best = null;
		for (LookSample sample : lookHistory) {
			if (best == null || Math.abs(sample.nanos() - nanos) < Math.abs(best.nanos() - nanos)) best = sample;
		}
		return best != null ? best.pitch() : fallback;
	}

	/** Degrees up from level (negative = down) as text. */
	private static String upDown(float up) {
		return up >= 0 ? String.format("%.1f° up", up) : String.format("%.1f° down", -up);
	}

	/**
	 * Where you're looking. Minecraft's pitch is -90 straight up, 0 level
	 * and +90 straight down, so it's also shown as degrees up/down from level.
	 */
	private static String look(float pitch) {
		return String.format("looking %s (pitch %.1f)", pitch == 0 ? "level" : upDown(-pitch), pitch);
	}

	private static String name(ItemStack stack) {
		return stack.isEmpty() ? "-" : stack.getName().getString();
	}
}
