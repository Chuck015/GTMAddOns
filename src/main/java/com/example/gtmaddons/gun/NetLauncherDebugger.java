package com.example.gtmaddons.gun;

import com.example.gtmaddons.PvpCategory;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Dev mode, "Net Launcher": says whether each Net Launcher shot was counted as a hit or a miss,
 * and why, then follows up for a second to see whether the net actually landed.
 *
 * ShotTracker only counts a Net Launcher hit on a player who is on a wingsuit (wearing one, or
 * gliding): that is what the net catches. On anyone else it is a single damage tick and nothing
 * else, and a miss fires a fireball that webs whatever it lands on, so neither counts.
 * ShotTracker writes the reason (every damage event near the shot with the target's chest item,
 * gliding state and distance, and any that arrived too late to match) and this class prints it.
 *
 *   NET LAUNCHER  - the verdict and the reason, also in chat.
 *   NET FOLLOW-UP - 250 ms, 500 ms and 1 s after the shot: is the target still gliding, how fast
 *                   is it moving (a caught player stops), its health, and how many cobwebs are
 *                   around it. For a miss, the cobwebs around where the crosshair ray hit a block
 *                   when you fired (the fireball's landing).
 */
final class NetLauncherDebugger {

	private static final long[] SAMPLE_NANOS = { 250_000_000L, 500_000_000L, 1_000_000_000L };
	private static final double AIM_RANGE = 256.0;
	private static final int WEB_RADIUS = 2;

	private final DevLogger log;

	/** Where the crosshair ray hit a block when the latest Net Launcher shot was fired (null if it hit nothing). */
	private Vec3 aimImpact = null;

	private static final class Followup {
		final long startNanos = System.nanoTime();
		final int targetId;
		final String targetName;
		final Vec3 impact;
		Vec3 lastPos;
		long lastNanos = startNanos;
		int next = 0;

		Followup(int targetId, String targetName, Vec3 impact, Vec3 startPos) {
			this.targetId = targetId;
			this.targetName = targetName;
			this.impact = impact;
			this.lastPos = startPos;
		}
	}

	private final List<Followup> followups = new ArrayList<>();

	NetLauncherDebugger(DevLogger log) {
		this.log = log;
	}

	void reset() {
		followups.clear();
		aimImpact = null;
	}

	/** The moment a Net Launcher shot is fired: remember where the crosshair pointed. */
	void onShot(Minecraft client) {
		LocalPlayer player = client.player;
		if (player == null || client.level == null) return;
		Vec3 eye = player.getEyePosition();
		Vec3 end = eye.add(player.getViewVector(1.0f).scale(AIM_RANGE));
		BlockHitResult block = client.level.clip(new ClipContext(
				eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		aimImpact = block.getType() == HitResult.Type.MISS ? null : block.getLocation();
	}

	/** ShotTracker's verdict for a Net Launcher shot, with its reason. */
	void onResult(Minecraft client, ShotResult r, String reason) {
		log.openWindow(client);
		String verdict = r.hit() ? "HIT " + r.target() : "MISS";
		String sounds = "gunshot sound=" + (r.gunshotSound() != null ? r.gunshotSound() : "none")
				+ (r.otherSounds().isEmpty() ? "" : ", other sounds=" + r.otherSounds());
		log.add(String.format("NET LAUNCHER  %s%s | %s | ammo %d -> %d, ping %dms, %s | %s",
				verdict, r.hit() && !Double.isNaN(r.distance()) ? String.format(" at %.1f blocks", r.distance()) : "",
				reason, r.ammoBefore(), r.ammoAfter(), r.pingMs(), sounds, r.category().label));
		DevLogger.chat("Net Launcher: " + verdict + " - " + reason, r.hit() ? ChatFormatting.GREEN : ChatFormatting.RED);

		// Follow up on whoever the net was meant for: the player it hit, or else the fireball's landing.
		Player target = null;
		if (r.target() != null && client.level != null) {
			for (Player player : client.level.players()) {
				if (player.getName().getString().equals(r.target())) target = player;
			}
		}
		if (target == null && aimImpact == null) return;
		followups.add(new Followup(target != null ? target.getId() : -1, target != null ? r.target() : null,
				aimImpact, target != null ? target.position() : aimImpact));
	}

	/** Every frame: take the follow-up samples that are due. */
	void onFrame(Minecraft client) {
		if (followups.isEmpty()) return;
		if (client.level == null) {
			followups.clear();
			return;
		}
		long now = System.nanoTime();
		for (Iterator<Followup> it = followups.iterator(); it.hasNext(); ) {
			Followup f = it.next();
			while (f.next < SAMPLE_NANOS.length && now - f.startNanos >= SAMPLE_NANOS[f.next]) {
				sample(client, f, SAMPLE_NANOS[f.next] / 1_000_000L, now);
				f.next++;
			}
			if (f.next >= SAMPLE_NANOS.length) it.remove();
		}
	}

	private void sample(Minecraft client, Followup f, long millis, long now) {
		log.openWindow(client);
		Player target = f.targetId >= 0 && client.level.getEntity(f.targetId) instanceof Player p ? p : null;
		if (f.targetId >= 0) {
			if (target == null) {
				log.add(String.format("NET FOLLOW-UP +%dms  %s: no longer in view (died, left, or out of range)", millis, f.targetName));
				f.next = SAMPLE_NANOS.length;
				return;
			}
			Vec3 pos = target.position();
			double seconds = Math.max(0.001, (now - f.lastNanos) / 1e9);
			double speed = pos.subtract(f.lastPos).length() / seconds;
			f.lastPos = pos;
			f.lastNanos = now;
			log.add(String.format("NET FOLLOW-UP +%dms  %s: %s, gliding %s, chest wingsuit %s, moved at %.1f b/s since the last sample, health %.1f, cobwebs within %d blocks: %d",
					millis, f.targetName, target.onGround() ? "on ground" : "airborne", target.isFallFlying(),
					PvpCategory.isWingsuit(target.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST)), speed,
					target.getHealth() + target.getAbsorptionAmount(), WEB_RADIUS, cobwebsAround(client, pos)));
		} else if (f.impact != null) {
			log.add(String.format("NET FOLLOW-UP +%dms  aim point (%.0f, %.0f, %.0f) where the crosshair ray hit a block when you fired: cobwebs within %d blocks: %d",
					millis, f.impact.x, f.impact.y, f.impact.z, WEB_RADIUS, cobwebsAround(client, f.impact)));
		}
	}

	private static int cobwebsAround(Minecraft client, Vec3 center) {
		BlockPos base = BlockPos.containing(center);
		int count = 0;
		for (int dx = -WEB_RADIUS; dx <= WEB_RADIUS; dx++) {
			for (int dy = -WEB_RADIUS; dy <= WEB_RADIUS; dy++) {
				for (int dz = -WEB_RADIUS; dz <= WEB_RADIUS; dz++) {
					if (client.level.getBlockState(base.offset(dx, dy, dz)).is(Blocks.COBWEB)) count++;
				}
			}
		}
		return count;
	}
}
