package com.example.gtmaddons.probe;

import com.example.gtmaddons.PvpCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Probe events that no other part of the mod watches, found by looking at your own player each frame (and at two packets). Each one is
 * only worked out while a running rule listens to it (Probes.wants), so with no rules this costs nothing.
 *   health          hp, change, absorption          your health changed (change < 0 = damage taken, > 0 = healed)
 *   hotbar          from_slot, to_slot, from_item, to_item, ms (since your previous switch)
 *   sprint          on                              you started / stopped sprinting (sprint resets, W-taps)
 *   velocity        bps, up                         the server set your motion (knockback, launches); horizontal and upward b/s
 *   boost_press     pitch, height                   shift pressed while gliding (a wing boost attempt)
 *   boost           pitch, height                   a wing boost started (its two sounds, as our dev log's Wing boosts filter)
 *   jetpack_tap     gap_ms, on_ground               jump double-tapped while wearing a jetpack
 *   jetpack_start   ok, gap_ms, peak_up             750 ms later: did it fly (as our dev log's Jetpack starts filter)
 *   near            dist, dy, players               once a second: nearest other player (blocks, their height minus yours), players within 32
 *   fight_start / fight_end   cat / outcome, hp, ping, fps
 */
public final class ProbeWatch {

	private static final long DOUBLE_TAP_NANOS = 1_000_000_000L, NORMAL_JUMP_GAP_MS = 350L, JETPACK_WATCH_NANOS = 750_000_000L;
	private static final long BOOST_PAIR_NANOS = 50_000_000L, NEAR_EVERY_NANOS = 1_000_000_000L;
	private static final double OWN_SOUND_RANGE = 3.0, STILL_RISING = 0.05, NEAR_RANGE = 32.0;
	private static final String BOOST_PICKUP = "entity.item.pickup", BOOST_ARROW = "entity.arrow.shoot";

	private static float lastHealth = -1f;
	private static int lastSlot = -1;
	private static ItemStack lastSlotItem = ItemStack.EMPTY;
	private static long lastSwitchNanos = -1L;
	private static int lastSprint = -1;
	private static boolean sneakWasDown, jumpWasDown;
	private static long lastJumpNanos = -1L, jetpackWatchNanos = -1L, jetpackGapMs;
	private static boolean jetpackOnGround, jetpackFlew;
	private static double jetpackPeakUp;
	private static long lastPickupNanos = -1L, nextNearNanos = 0L;

	private ProbeWatch() {}

	public static void onFrame(Minecraft client) {
		LocalPlayer me = client.player;
		if (me == null) return;
		long now = System.nanoTime();

		if (Probes.wants("health")) {
			float hp = me.getHealth();
			if (lastHealth >= 0 && hp != lastHealth) {
				Probes.emit("health", "hp", Probes.round(hp), "change", Probes.round(hp - lastHealth), "absorption", Probes.round(me.getAbsorptionAmount()));
			}
			lastHealth = hp;
		} else lastHealth = -1f;

		if (Probes.wants("hotbar")) {
			int slot = me.getInventory().getSelectedSlot();
			ItemStack item = me.getInventory().getItem(slot);
			if (lastSlot >= 0 && slot != lastSlot) {
				Probes.emit("hotbar", "from_slot", lastSlot, "to_slot", slot, "from_item", Probes.itemId(lastSlotItem), "to_item", Probes.itemId(item),
						"ms", lastSwitchNanos >= 0 ? Probes.round((now - lastSwitchNanos) / 1_000_000.0) : null);
				lastSwitchNanos = now;
			}
			lastSlot = slot;
			lastSlotItem = item;
		} else lastSlot = -1;

		if (Probes.wants("sprint")) {
			int sprint = me.isSprinting() ? 1 : 0;
			if (lastSprint >= 0 && sprint != lastSprint) Probes.emit("sprint", "on", sprint == 1);
			lastSprint = sprint;
		} else lastSprint = -1;

		boolean sneakDown = client.options.keyShift.isDown();
		if (sneakDown && !sneakWasDown && me.isFallFlying()) Probes.emit("boost_press", "pitch", Probes.round(me.getXRot()), "height", Probes.round(me.getY()));
		sneakWasDown = sneakDown;

		if (Probes.wants("jetpack_tap") || Probes.wants("jetpack_start")) watchJetpack(client, me, now);
		else jetpackWatchNanos = -1L;

		if (now >= nextNearNanos && Probes.wants("near")) {
			nextNearNanos = now + NEAR_EVERY_NANOS;
			nearPlayers(client, me);
		}
	}

	private static void watchJetpack(Minecraft client, LocalPlayer me, long now) {
		boolean jumpDown = client.options.keyJump.isDown();
		if (jumpDown && !jumpWasDown) {
			boolean doubleTap = lastJumpNanos >= 0 && now - lastJumpNanos <= DOUBLE_TAP_NANOS;
			if (doubleTap && jetpackWatchNanos < 0 && PvpCategory.isWornJetpack(me.getInventory().getItem(PvpCategory.CHEST_INVENTORY_INDEX))) {
				jetpackGapMs = (now - lastJumpNanos) / 1_000_000L;
				jetpackOnGround = me.onGround();
				jetpackWatchNanos = now;
				jetpackFlew = false;
				jetpackPeakUp = 0.0;
				lastJumpNanos = -1L;
				Probes.emit("jetpack_tap", "gap_ms", jetpackGapMs, "on_ground", jetpackOnGround);
			} else {
				lastJumpNanos = now;
			}
		}
		jumpWasDown = jumpDown;
		if (jetpackWatchNanos < 0) return;
		double up = me.getDeltaMovement().y;
		jetpackPeakUp = Math.max(jetpackPeakUp, up);
		if (me.getAbilities().flying || (now - jetpackWatchNanos >= 400_000_000L && up > STILL_RISING)) jetpackFlew = true;
		if (now - jetpackWatchNanos < JETPACK_WATCH_NANOS) return;
		jetpackWatchNanos = -1L;
		// Two separate jumps from the ground are not a jetpack attempt.
		if (!jetpackFlew && jetpackOnGround && jetpackGapMs > NORMAL_JUMP_GAP_MS) return;
		Probes.emit("jetpack_start", "ok", jetpackFlew, "gap_ms", jetpackGapMs, "peak_up", Probes.round(jetpackPeakUp * 20));
	}

	private static void nearPlayers(Minecraft client, LocalPlayer me) {
		if (client.level == null) return;
		double best = Double.MAX_VALUE, dy = 0;
		int count = 0;
		for (Player other : client.level.players()) {
			if (other == me || other.isSpectator() || !other.isAlive()) continue;
			double d = other.distanceTo(me);
			if (d <= NEAR_RANGE) count++;
			if (d < best) {
				best = d;
				dy = other.getY() - me.getY();
			}
		}
		if (best == Double.MAX_VALUE) return;
		Probes.emit("near", "dist", Probes.round(best), "dy", Probes.round(dy), "players", count);
	}

	/** Every sound with its distance from you: a wing boost is GTM's pickup sound then its arrow sound right after, on you. */
	public static void onSound(String soundId, double distance) {
		if (!Probes.wants("boost") || distance > OWN_SOUND_RANGE) return;
		String id = soundId.replace("minecraft:", "");
		long now = System.nanoTime();
		if (id.equals(BOOST_PICKUP)) {
			lastPickupNanos = now;
		} else if (id.equals(BOOST_ARROW) && lastPickupNanos >= 0 && now - lastPickupNanos <= BOOST_PAIR_NANOS) {
			lastPickupNanos = -1L;
			LocalPlayer me = Minecraft.getInstance().player;
			if (me != null) Probes.emit("boost", "pitch", Probes.round(me.getXRot()), "height", Probes.round(me.getY()));
		}
	}

	/** The server set an entity's motion: yours is sent as a velocity event. */
	public static void onMotion(int entityId, Vec3 movement) {
		LocalPlayer me = Minecraft.getInstance().player;
		if (me == null || entityId != me.getId() || !Probes.wants("velocity")) return;
		Probes.emit("velocity", "bps", Probes.round(Math.hypot(movement.x, movement.z) * 20), "up", Probes.round(movement.y * 20));
	}

	public static int ping(Minecraft client) {
		if (client.player == null || client.getConnection() == null) return -1;
		PlayerInfo entry = client.getConnection().getPlayerInfo(client.player.getUUID());
		return entry != null ? entry.getLatency() : -1;
	}

	/** A fight starts or ends: health, ping and frame rate at that moment. */
	public static void onFightEdge(String event, String key, String value) {
		if (!Probes.wants(event)) return;
		Minecraft client = Minecraft.getInstance();
		LocalPlayer me = client.player;
		Probes.emit(event, key, value, "hp", me != null ? Probes.round(me.getHealth()) : null, "ping", ping(client), "fps", client.getFps());
	}
}
