package com.example.gtmaddons;

import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;

/**
 * The "Better jetpack particles" setting: a flying jetpack sends flame and cloud particles at its wearer; these are hidden
 * (Hide on its ⚙ page) or drawn at the size set there. Only flame and cloud particles within a few blocks of a jetpack wearer who
 * is off the ground count, so the same particles from explosions, fire or smoke elsewhere are left alone. Works for everyone's
 * jetpack, not just yours. Resizing: while such a packet is handled, sizeNow() is the scale and JetpackParticleSizeMixin applies it
 * to each particle the packet makes.
 */
public final class JetpackParticles {

	/** How near a jetpack wearer a particle has to spawn to be theirs, in blocks. */
	private static final double RADIUS = 3.0;

	private static Settings settings;

	private JetpackParticles() {}

	public static void init(Settings modSettings) {
		settings = modSettings;
	}

	/** The scale of the particles being made right now (1 = untouched); set only while a jetpack packet is handled. */
	private static float sizeNow = 1f;

	/** Whether this particle packet is a jetpack's exhaust that should not be shown. */
	public static boolean shouldHide(ClientboundLevelParticlesPacket packet) {
		return settings != null && settings.hideJetpackParticles && settings.jetpackParticlesHide && isExhaust(packet);
	}

	/**
	 * A packet starts being handled: the size its particles get - a jetpack's exhaust at Better jetpack particles' size, a gun tracer at
	 * Gun tracers' size (GunTracers), else 1. Returns false when the packet should not be shown at all (a tracer at 0%).
	 */
	public static boolean beginPacket(ClientboundLevelParticlesPacket packet) {
		boolean resize = settings != null && settings.hideJetpackParticles && !settings.jetpackParticlesHide && settings.jetpackParticleSize != 100;
		if (resize && isExhaust(packet)) {
			sizeNow = settings.jetpackParticleSize / 100f;
			return true;
		}
		sizeNow = GunTracers.sizeFor(packet);
		return sizeNow > 0f;
	}

	public static void endPacket() {
		sizeNow = 1f;
	}

	public static float sizeNow() {
		return sizeNow;
	}

	/** A flame or cloud particle spawned near a flying jetpack wearer. */
	private static boolean isExhaust(ClientboundLevelParticlesPacket packet) {
		ParticleType<?> type = packet.getParticle().getType();
		if (type != ParticleTypes.FLAME && type != ParticleTypes.CLOUD) return false;
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) return false;
		for (Player player : client.level.players()) {
			if (player.onGround() || !PvpCategory.isWornJetpack(player.getItemBySlot(EquipmentSlot.CHEST))) continue;
			double dx = packet.getX() - player.getX(), dy = packet.getY() - player.getY(), dz = packet.getZ() - player.getZ();
			if (dx * dx + dy * dy + dz * dz <= RADIUS * RADIUS) return true;
		}
		return false;
	}
}
