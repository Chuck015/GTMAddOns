package com.example.gtmaddons.gun;

import com.example.gtmaddons.PvpCategory;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

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
	private Vec3d aimImpact = null;

	private static final class Followup {
		final long startNanos = System.nanoTime();
		final int targetId;
		final String targetName;
		final Vec3d impact;
		Vec3d lastPos;
		long lastNanos = startNanos;
		int next = 0;

		Followup(int targetId, String targetName, Vec3d impact, Vec3d startPos) {
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
	void onShot(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (player == null || client.world == null) return;
		Vec3d eye = player.getEyePos();
		Vec3d end = eye.add(player.getRotationVec(1.0f).multiply(AIM_RANGE));
		BlockHitResult block = client.world.raycast(new RaycastContext(
				eye, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player));
		aimImpact = block.getType() == HitResult.Type.MISS ? null : block.getPos();
	}

	/** ShotTracker's verdict for a Net Launcher shot, with its reason. */
	void onResult(MinecraftClient client, ShotResult r, String reason) {
		log.openWindow(client);
		String verdict = r.hit() ? "HIT " + r.target() : "MISS";
		String sounds = "gunshot sound=" + (r.gunshotSound() != null ? r.gunshotSound() : "none")
				+ (r.otherSounds().isEmpty() ? "" : ", other sounds=" + r.otherSounds());
		log.add(String.format("NET LAUNCHER  %s%s | %s | ammo %d -> %d, ping %dms, %s | %s",
				verdict, r.hit() && !Double.isNaN(r.distance()) ? String.format(" at %.1f blocks", r.distance()) : "",
				reason, r.ammoBefore(), r.ammoAfter(), r.pingMs(), sounds, r.category().label));
		DevLogger.chat("Net Launcher: " + verdict + " - " + reason, r.hit() ? Formatting.GREEN : Formatting.RED);

		// Follow up on whoever the net was meant for: the player it hit, or else the fireball's landing.
		PlayerEntity target = null;
		if (r.target() != null && client.world != null) {
			for (PlayerEntity player : client.world.getPlayers()) {
				if (player.getName().getString().equals(r.target())) target = player;
			}
		}
		if (target == null && aimImpact == null) return;
		followups.add(new Followup(target != null ? target.getId() : -1, target != null ? r.target() : null,
				aimImpact, target != null ? target.getEntityPos() : aimImpact));
	}

	/** Every frame: take the follow-up samples that are due. */
	void onFrame(MinecraftClient client) {
		if (followups.isEmpty()) return;
		if (client.world == null) {
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

	private void sample(MinecraftClient client, Followup f, long millis, long now) {
		log.openWindow(client);
		PlayerEntity target = f.targetId >= 0 && client.world.getEntityById(f.targetId) instanceof PlayerEntity p ? p : null;
		if (f.targetId >= 0) {
			if (target == null) {
				log.add(String.format("NET FOLLOW-UP +%dms  %s: no longer in view (died, left, or out of range)", millis, f.targetName));
				f.next = SAMPLE_NANOS.length;
				return;
			}
			Vec3d pos = target.getEntityPos();
			double seconds = Math.max(0.001, (now - f.lastNanos) / 1e9);
			double speed = pos.subtract(f.lastPos).length() / seconds;
			f.lastPos = pos;
			f.lastNanos = now;
			log.add(String.format("NET FOLLOW-UP +%dms  %s: %s, gliding %s, chest wingsuit %s, moved at %.1f b/s since the last sample, health %.1f, cobwebs within %d blocks: %d",
					millis, f.targetName, target.isOnGround() ? "on ground" : "airborne", target.isGliding(),
					PvpCategory.isWingsuit(target.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST)), speed,
					target.getHealth() + target.getAbsorptionAmount(), WEB_RADIUS, cobwebsAround(client, pos)));
		} else if (f.impact != null) {
			log.add(String.format("NET FOLLOW-UP +%dms  aim point (%.0f, %.0f, %.0f) where the crosshair ray hit a block when you fired: cobwebs within %d blocks: %d",
					millis, f.impact.x, f.impact.y, f.impact.z, WEB_RADIUS, cobwebsAround(client, f.impact)));
		}
	}

	private static int cobwebsAround(MinecraftClient client, Vec3d center) {
		BlockPos base = BlockPos.ofFloored(center);
		int count = 0;
		for (int dx = -WEB_RADIUS; dx <= WEB_RADIUS; dx++) {
			for (int dy = -WEB_RADIUS; dy <= WEB_RADIUS; dy++) {
				for (int dz = -WEB_RADIUS; dz <= WEB_RADIUS; dz++) {
					if (client.world.getBlockState(base.add(dx, dy, dz)).isOf(Blocks.COBWEB)) count++;
				}
			}
		}
		return count;
	}
}
