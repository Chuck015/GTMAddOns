package com.example.gtmaddons.gun;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Dev / QA filter "Gun tracers": GTM draws a gun's tracer as a quick run of particle packets along the bullet's path, and now has two
 * kinds (legacy, like the old ones, and new). To tell them apart before adding settings for them, every run of particles that looks
 * like a tracer is summed up in our dev log: packets that arrive within BURST_GAP_NANOS of the last one and within LINK_BLOCKS of it
 * belong to one run, and a run is reported once it is quiet if it has at least MIN_PACKETS packets and spans at least MIN_SPAN
 * blocks (so jetpack exhaust, hits and explosions stay out).
 *
 * For each run: how long it took, its length and direction, how straight it is, the nearest player to its start (the likely
 * shooter) with what they hold, and per particle type the packet and particle counts, the particle's own settings as the server
 * sent them (colour, size, target... from the type's codec), speed, spread and the force / always-show flags.
 */
final class TracerLogger {

	private static final long BURST_GAP_NANOS = 120_000_000L;
	private static final double LINK_BLOCKS = 6.0;
	private static final int MIN_PACKETS = 3;
	private static final double MIN_SPAN = 3.0;
	/** Distinct particle settings listed per type in a run. */
	private static final int MAX_DETAILS = 3;

	private static final class TypeStats {
		int packets, particles, forced, alwaysShown;
		float minSpeed = Float.MAX_VALUE, maxSpeed = 0f, maxSpread = 0f;
		final Set<String> details = new LinkedHashSet<>();
	}

	private static final class Run {
		final long startNanos;
		long lastNanos;
		final Vec3 first;
		Vec3 last;
		final List<Vec3> points = new ArrayList<>();
		final Map<String, TypeStats> types = new LinkedHashMap<>();
		String shooter;

		Run(long now, Vec3 at) {
			startNanos = lastNanos = now;
			first = last = at;
		}
	}

	private final DevLogger log;
	private final List<Run> open = new ArrayList<>();

	TracerLogger(DevLogger log) {
		this.log = log;
	}

	void onPacket(Minecraft client, ClientboundLevelParticlesPacket packet) {
		long now = System.nanoTime();
		Vec3 at = new Vec3(packet.getX(), packet.getY(), packet.getZ());
		Run run = null;
		for (Run r : open) {
			if (now - r.lastNanos <= BURST_GAP_NANOS && r.last.distanceTo(at) <= LINK_BLOCKS) {
				run = r;
				break;
			}
		}
		if (run == null) {
			run = new Run(now, at);
			run.shooter = nearestPlayer(client, at);
			open.add(run);
		}
		run.lastNanos = now;
		run.last = at;
		run.points.add(at);
		ParticleOptions options = packet.getParticle();
		String id = String.valueOf(BuiltInRegistries.PARTICLE_TYPE.getKey(options.getType()));
		TypeStats t = run.types.computeIfAbsent(id, k -> new TypeStats());
		t.packets++;
		t.particles += Math.max(1, packet.getCount());
		if (packet.isOverrideLimiter()) t.forced++;
		if (packet.alwaysShow()) t.alwaysShown++;
		t.minSpeed = Math.min(t.minSpeed, packet.getMaxSpeed());
		t.maxSpeed = Math.max(t.maxSpeed, packet.getMaxSpeed());
		t.maxSpread = Math.max(t.maxSpread, Math.max(packet.getXDist(), Math.max(packet.getYDist(), packet.getZDist())));
		if (t.details.size() < MAX_DETAILS) t.details.add(describe(client, options));
	}

	void onFrame(Minecraft client) {
		long now = System.nanoTime();
		for (int i = open.size() - 1; i >= 0; i--) {
			Run run = open.get(i);
			if (now - run.lastNanos <= BURST_GAP_NANOS) continue;
			open.remove(i);
			report(client, run);
		}
	}

	private void report(Minecraft client, Run run) {
		int packets = run.points.size();
		double span = run.first.distanceTo(run.last);
		if (packets < MIN_PACKETS || span < MIN_SPAN) return;
		Vec3 dir = run.last.subtract(run.first).normalize();
		double yaw = Math.toDegrees(Math.atan2(-dir.x, dir.z)), pitch = Math.toDegrees(-Math.asin(Math.max(-1, Math.min(1, dir.y))));
		double off = 0;
		for (Vec3 p : run.points) {
			Vec3 rel = p.subtract(run.first);
			off = Math.max(off, rel.subtract(dir.scale(rel.dot(dir))).length());
		}
		StringBuilder types = new StringBuilder();
		StringBuilder shortTypes = new StringBuilder();
		for (Map.Entry<String, TypeStats> e : run.types.entrySet()) {
			TypeStats t = e.getValue();
			types.append(String.format(Locale.ROOT, "\n    %s: %d packets, %d particles, speed %.2f-%.2f, spread %.2f, forced %d, always shown %d, settings %s",
					e.getKey(), t.packets, t.particles, t.minSpeed, t.maxSpeed, t.maxSpread, t.forced, t.alwaysShown, String.join(" | ", t.details)));
			if (!shortTypes.isEmpty()) shortTypes.append(" + ");
			shortTypes.append(e.getKey().replace("minecraft:", "")).append(" x").append(t.packets);
		}
		log.openWindow(client);
		log.add(String.format(Locale.ROOT, "TRACER  %d packets over %dms, %.1f blocks (%.2f blocks between packets), heading yaw %.0f pitch %.0f, off the line by up to %.2f, from (%.1f, %.1f, %.1f), %s%s",
				packets, (run.lastNanos - run.startNanos) / 1_000_000L, span, span / Math.max(1, packets - 1), yaw, pitch, off,
				run.first.x, run.first.y, run.first.z, run.shooter, types));
		DevLogger.chat(String.format(Locale.ROOT, "Tracer: %s, %.0f blocks, %s", shortTypes, span, run.shooter), net.minecraft.ChatFormatting.GRAY);
	}

	/** The player nearest where a run starts, and what they hold: the likely shooter. */
	private static String nearestPlayer(Minecraft client, Vec3 at) {
		if (client.level == null) return "no player near the start";
		Player best = null;
		double bestDistance = 4.0;
		for (Player player : client.level.players()) {
			double d = player.getEyePosition().distanceTo(at);
			if (d < bestDistance) {
				best = player;
				bestDistance = d;
			}
		}
		if (best == null) return "no player within 4 blocks of the start";
		String held = best.getMainHandItem().isEmpty() ? "nothing" : best.getMainHandItem().getHoverName().getString();
		return String.format(Locale.ROOT, "start %.1f blocks from %s%s holding \"%s\"", bestDistance, best.getName().getString(),
				best == client.player ? " (you)" : "", held);
	}

	/** The particle's own settings as the server sent them (from its type's codec), or its class name if they can't be written out. */
	@SuppressWarnings("unchecked")
	private static String describe(Minecraft client, ParticleOptions options) {
		try {
			MapCodec<ParticleOptions> codec = (MapCodec<ParticleOptions>) options.getType().codec();
			var ops = client.level != null ? client.level.registryAccess().createSerializationContext(JsonOps.INSTANCE) : JsonOps.INSTANCE;
			return codec.codec().encodeStart(ops, options).result().map(JsonElement::toString).orElse(options.getClass().getSimpleName());
		} catch (RuntimeException e) {
			return options.getClass().getSimpleName();
		}
	}
}
