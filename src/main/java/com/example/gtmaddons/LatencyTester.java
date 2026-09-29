package com.example.gtmaddons;

import com.example.gtmaddons.gun.ShotResult;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Set;

/**
 * Settings > Latency tester: after each measurement, says in chat how long
 * the server took to respond, with a running average for this session.
 *
 *   - Gun: right-click to the shot (the ammo count dropping), for the first
 *     shot of each trigger pull - timed by ShotTracker.
 *   - Wing boost: pressing sneak while gliding to GTM's boost-start sound
 *     (item pickup / arrow shot at your position), which the dev logs show
 *     arriving 40-80ms after the key press. No sound within
 *     BOOST_TIMEOUT_NANOS means no boost happened, and nothing is reported.
 */
public final class LatencyTester {

	public static final LatencyTester INSTANCE = new LatencyTester();

	private static final long BOOST_TIMEOUT_NANOS = 1_000_000_000L;
	/** Colour bands for the reported time. */
	private static final long FAST_MS = 60;
	private static final long SLOW_MS = 120;
	private static final double OWN_SOUND_RANGE = 3.0;
	private static final Set<String> BOOST_SOUNDS = Set.of("minecraft:entity.item.pickup", "minecraft:entity.arrow.shoot");

	/** Running totals for one kind of measurement. */
	private static final class Stat {
		long count, totalMs, bestMs = Long.MAX_VALUE;

		void add(long ms) {
			count++;
			totalMs += ms;
			bestMs = Math.min(bestMs, ms);
		}
	}

	private boolean enabled = false;
	private final Stat gun = new Stat();
	private final Stat boost = new Stat();

	private boolean sneakWasDown = false;
	private long boostPressedNanos = -1L;

	private LatencyTester() {}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
		boostPressedNanos = -1L;
	}

	public void onShot(ShotResult shot) {
		if (!enabled || shot.responseMs() == null) return;
		gun.add(shot.responseMs());
		report("Gun", shot.responseMs(), gun);
	}

	public void onFrame(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		boolean sneakDown = client.options.sneakKey.isPressed();
		if (enabled && player != null) {
			long now = System.nanoTime();
			if (sneakDown && !sneakWasDown && player.isGliding() && boostPressedNanos < 0) boostPressedNanos = now;
			if (boostPressedNanos >= 0 && now - boostPressedNanos > BOOST_TIMEOUT_NANOS) boostPressedNanos = -1L;
		}
		sneakWasDown = sneakDown;
	}

	/** Every sound from the server, with its distance from you (NaN if unknown). */
	public void onSound(String soundId, double distance) {
		if (!enabled || boostPressedNanos < 0 || Double.isNaN(distance) || distance > OWN_SOUND_RANGE) return;
		if (!BOOST_SOUNDS.contains(soundId)) return;
		long ms = (System.nanoTime() - boostPressedNanos) / 1_000_000L;
		boostPressedNanos = -1L;
		boost.add(ms);
		report("Wing boost", ms, boost);
	}

	/**
	 * e.g.  \u23F1 Gun  47ms   avg 52ms \u00B7 best 31ms \u00B7 12 tests
	 * The time is green when quick, yellow when middling, red when slow.
	 */
	private static void report(String what, long ms, Stat stat) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) return;
		Formatting speed = ms <= FAST_MS ? Formatting.GREEN : ms <= SLOW_MS ? Formatting.YELLOW : Formatting.RED;
		MutableText line = Text.literal("\u23F1 ").formatted(Formatting.DARK_AQUA)
				.append(Text.literal(what + "  ").formatted(Formatting.AQUA))
				.append(Text.literal(ms + "ms").formatted(speed, Formatting.BOLD))
				.append(Text.literal(String.format("   avg %dms \u00B7 best %dms \u00B7 %d %s",
						stat.totalMs / stat.count, stat.bestMs, stat.count, stat.count == 1 ? "test" : "tests"))
						.formatted(Formatting.DARK_GRAY));
		client.player.sendMessage(line, false);
	}
}
