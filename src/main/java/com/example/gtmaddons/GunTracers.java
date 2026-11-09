package com.example.gtmaddons;

import com.example.gtmaddons.gun.ShotTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The "Gun tracers" mod (PVP > General): the size of GTM's gun tracers, for everyone's shots, as All x category x gun (each 0-100%;
 * 0 hides them). Found in our dev log on 2026-11-07 for both of GTM's tracer kinds:
 *   new     a line of dust at size 0.5, coloured by gun group: #404040 rifles / MGs, #808080 shotguns / pistols, #B8B8B8 SMGs,
 *           #F0F0F0 snipers;
 *   legacy  dust #AAAAAA at size 0.33 (rifles / MGs), enchant (SMGs, Minigun, Browning), smoke (shotguns), crit (pistols),
 *           enchanted_hit (Stun Gun), cloud (snipers), large_smoke (Musket);
 *   both    launchers (RPG, Grenade, Homing): firework and dust_plume along the path.
 * Each of those is one particle every half block or so, sent all at once. A packet of one of these kinds belongs to a tracer when a
 * player holding a gun is looking along it (the particle in front of them, within NEAR_RAY + SPREAD x distance of their look ray): that
 * player's gun gives the category and the gun. That check keeps crits from melee, enchanting tables, fire smoke and so on untouched.
 * The rest of one shot's line (packets close behind the first, within RUN_GAP_NANOS) reuses the answer, so it is worked out once per line.
 * Impact effects (sparks, puffs, block pieces) are not tracers and are left alone.
 */
public final class GunTracers {

	/** Tracer categories, in the order the settings list them. */
	public static final List<String> CATEGORIES = List.of("Rifles", "MGs", "SMGs", "Shotguns", "Pistols", "Snipers", "Launchers");

	/** Every core gun found in our dev log, by category (a gun not listed here falls back to its tracer's look). */
	public static final Map<String, String> GUNS = new LinkedHashMap<>();
	static {
		for (String g : List.of("Advanced Rifle", "Assault Rifle", "Bullpup Rifle", "Carbine Rifle", "Special Carbine", "M4")) GUNS.put(g, "Rifles");
		for (String g : List.of("MG", "Combat MG", "Minigun", "Browning M2")) GUNS.put(g, "MGs");
		for (String g : List.of("SMG", "Micro SMG", "Assault SMG", "Combat PDW", "Gusenberg Sweeper")) GUNS.put(g, "SMGs");
		for (String g : List.of("Pump Shotgun", "Sawed-off Shotgun", "Heavy Shotgun", "Assault Shotgun")) GUNS.put(g, "Shotguns");
		for (String g : List.of("Pistol", "Combat Pistol", "Heavy Pistol", "Marksman Pistol", "Heavy Revolver", "Stun Gun")) GUNS.put(g, "Pistols");
		for (String g : List.of("Sniper Rifle", "Heavy Sniper", "Assault Sniper", "Musket")) GUNS.put(g, "Snipers");
		for (String g : List.of("RPG", "Grenade Launcher", "Homing Launcher")) GUNS.put(g, "Launchers");
	}

	/** A tracer point belongs to a shooter when it is in front of them and within NEAR_RAY blocks of their look ray, plus SPREAD per block of distance. */
	private static final double NEAR_RAY = 1.5, SPREAD = 0.09, MAX_DISTANCE = 160.0, RUN_LINK = 2.0;
	private static final long RUN_GAP_NANOS = 120_000_000L;

	private static Settings settings;
	/** The line being received: where its last packet was, when, and the size worked out for it. */
	private static Vec3 runLast = null;
	private static long runNanos = 0L;
	private static float runSize = 1f;

	private GunTracers() {}

	public static void init(Settings modSettings) {
		settings = modSettings;
	}

	/** The size for this particle packet: 1 when it is not a tracer (or the mod is off), 0 to hide it. */
	public static float sizeFor(ClientboundLevelParticlesPacket packet) {
		if (settings == null || !settings.gunTracers || !looksLikeTracer(packet.getParticle())) return 1f;
		long now = System.nanoTime();
		Vec3 at = new Vec3(packet.getX(), packet.getY(), packet.getZ());
		if (runLast != null && now - runNanos <= RUN_GAP_NANOS && runLast.distanceTo(at) <= RUN_LINK) {
			runLast = at;
			runNanos = now;
			return runSize;
		}
		String gun = shooterGun(at);
		// No shooter for this point: not a tracer, and the next packet is checked on its own.
		if (gun == null) {
			runLast = null;
			return 1f;
		}
		runLast = at;
		runNanos = now;
		runSize = size(gun);
		return runSize;
	}

	/** All x category x gun, as a fraction. */
	public static float size(String gun) {
		String category = GUNS.getOrDefault(gun, null);
		int all = settings.tracerSizeAll;
		int cat = category != null ? settings.tracerSizeCategory.getOrDefault(category, 100) : 100;
		int own = settings.tracerSizeGun.getOrDefault(gun, 100);
		return all / 100f * cat / 100f * own / 100f;
	}

	/** One of the particles GTM's tracers are made of (either kind). */
	private static boolean looksLikeTracer(ParticleOptions options) {
		ParticleType<?> type = options.getType();
		if (options instanceof DustParticleOptions dust) {
			org.joml.Vector3f rgb = dust.getColor();
			int color = (Math.round(rgb.x() * 255) << 16) | (Math.round(rgb.y() * 255) << 8) | Math.round(rgb.z() * 255);
			float scale = dust.getScale();
			return (Math.abs(scale - 0.5f) < 0.01f && (color == 0x404040 || color == 0x808080 || color == 0xB8B8B8 || color == 0xF0F0F0))
					|| (Math.abs(scale - 0.33f) < 0.01f && color == 0xAAAAAA);
		}
		return type == ParticleTypes.ENCHANT || type == ParticleTypes.SMOKE || type == ParticleTypes.CRIT || type == ParticleTypes.ENCHANTED_HIT
				|| type == ParticleTypes.CLOUD || type == ParticleTypes.LARGE_SMOKE || type == ParticleTypes.FIREWORK || type == ParticleTypes.DUST_PLUME;
	}

	/** The gun of the player who is looking along this point while holding one, or null (then it is not a tracer). */
	private static String shooterGun(Vec3 at) {
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) return null;
		String best = null;
		double bestScore = Double.MAX_VALUE;
		for (Player player : client.level.players()) {
			ItemStack held = player.getMainHandItem();
			if (held.isEmpty()) continue;
			String name = held.getHoverName().getString();
			if (ShotTracker.parseAmmo(name) == null && name.indexOf('«') < 0) continue;
			Vec3 eye = player.getEyePosition();
			Vec3 look = player.getViewVector(1.0f);
			Vec3 to = at.subtract(eye);
			double along = to.dot(look);
			if (along < -0.5 || along > MAX_DISTANCE) continue;
			double offRay = to.subtract(look.scale(along)).length();
			if (offRay > NEAR_RAY + SPREAD * Math.max(0.0, along)) continue;
			double score = offRay / (1.0 + Math.max(0.0, along));
			if (score < bestScore) {
				bestScore = score;
				best = cleanName(name);
			}
		}
		return best;
	}

	/** "Combat MG «49/29897»" -> "Combat MG" (the name without its ammo readout and colours). */
	public static String cleanName(String itemName) {
		String name = itemName.replaceAll("«[^»]*»", "").replaceAll("§.", "").trim();
		for (String known : GUNS.keySet()) if (known.equalsIgnoreCase(name)) return known;
		return name.length() > 32 ? name.substring(0, 32) : name;
	}

}
