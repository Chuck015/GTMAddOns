package com.example.gtmaddons;

import com.example.gtmaddons.gun.ShotResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Map;

/**
 * How you were moving at each moment that matters in a fight - information only, stored with the fight's movement numbers (Raw info).
 * Four states, decided from your keys and the ground: air (off the ground), still (on the ground, no movement key held), strafe (on
 * the ground, A or D held) and run (on the ground, only W / S). Per fight and per state:
 *   - time spent in it;
 *   - your gun shots fired in it, and of those the hits and headshots (the state is taken when the shot is fired, the result when
 *     ShotTracker settles it);
 *   - hits you took from players in it (any weapon).
 * Divided by the time in each state, that answers "am I hit more while standing still?" and "do I hit more while strafing?" moment by
 * moment instead of fight by fight. Sampled once per rendered frame.
 */
public final class MoveStates {

	public static final MoveStates INSTANCE = new MoveStates();

	public static final int AIR = 0, STILL = 1, STRAFE = 2, RUN = 3;
	private static final String[] NAMES = { "air", "still", "strafe", "run" };
	private static final long MAX_FRAME_NANOS = 100_000_000L;

	/** Everything counted over one fight, per state. */
	public static final class Stats {
		final double[] ms = new double[4];
		final int[] shots = new int[4], hits = new int[4], headshots = new int[4], taken = new int[4];

		/** Adds { st_<state>_ms, st_<state>_shots, st_<state>_hits, st_<state>_hs, st_<state>_taken } for air, still, strafe, run. */
		void addTo(com.google.gson.JsonObject o) {
			for (int i = 0; i < 4; i++) {
				String k = "st_" + NAMES[i] + "_";
				o.addProperty(k + "ms", Math.round(ms[i]));
				o.addProperty(k + "shots", shots[i]);
				o.addProperty(k + "hits", hits[i]);
				o.addProperty(k + "hs", headshots[i]);
				o.addProperty(k + "taken", taken[i]);
			}
		}
	}

	private Stats fight = null;
	private long lastNanos = 0L;
	/** The state last sent as a probe "move" event. */
	private int lastState = -1;
	/** Your shots not settled yet: when fired (ShotResult.timestampMillis) -> the state then. */
	private final Map<Long, Integer> shotStates = new HashMap<>();

	private MoveStates() {}

	public void onFightStart() {
		fight = new Stats();
		shotStates.clear();
	}

	public Stats onFightEnd() {
		Stats done = fight;
		fight = null;
		return done;
	}

	public void onFightDrop() {
		fight = null;
	}

	/** Your movement state right now, as its name (air / still / strafe / run). */
	public static String stateName(Minecraft client) {
		return NAMES[state(client)];
	}

	/** Your movement state right now. */
	public static int state(Minecraft client) {
		LocalPlayer me = client.player;
		if (me == null || !me.onGround()) return AIR;
		var o = client.options;
		if (o.keyLeft.isDown() || o.keyRight.isDown()) return STRAFE;
		if (o.keyUp.isDown() || o.keyDown.isDown()) return RUN;
		return STILL;
	}

	public void onFrame(Minecraft client) {
		long now = System.nanoTime();
		long dt = Math.min(now - lastNanos, MAX_FRAME_NANOS);
		lastNanos = now;
		if (client.player != null && com.example.gtmaddons.probe.Probes.wants("move")) {
			int st = state(client);
			if (st != lastState) {
				lastState = st;
				com.example.gtmaddons.probe.Probes.emit("move", "state", NAMES[st]);
			}
		}
		if (fight == null || client.player == null || dt <= 0) return;
		fight.ms[state(client)] += dt / 1_000_000.0;
	}

	/** ShotTracker saw you fire (timestampMillis is the shot's own): remember how you were moving. */
	public void onShotFired(long timestampMillis) {
		if (fight == null) return;
		if (shotStates.size() > 512) shotStates.clear();
		shotStates.put(timestampMillis, state(Minecraft.getInstance()));
	}

	/** A shot of yours is settled. */
	public void onShotResult(ShotResult shot) {
		Integer s = shotStates.remove(shot.timestampMillis());
		if (com.example.gtmaddons.probe.Probes.wants("shot_result")) com.example.gtmaddons.probe.Probes.emit("shot_result", "gun", shot.gun(), "cat", shot.category().name(),
				"hit", shot.hit(), "headshot", shot.headshot(), "kill", shot.kill(), "dist", com.example.gtmaddons.probe.Probes.round(shot.distance()), "netted", shot.netted(),
				"move", s != null ? NAMES[s] : null);
		if (fight == null || s == null) return;
		fight.shots[s]++;
		if (shot.hit()) fight.hits[s]++;
		if (shot.headshot()) fight.headshots[s]++;
	}

	/** A damage event: a hit on you by another player counts against how you are moving now. */
	public void onDamage(int targetId, int causeId) {
		Minecraft client = Minecraft.getInstance();
		if (fight == null || client.player == null || client.level == null || targetId != client.player.getId() || causeId == targetId) return;
		Entity cause = client.level.getEntity(causeId);
		if (cause instanceof Player) fight.taken[state(client)]++;
	}
}
