package com.example.gtmaddons;

import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;

/**
 * The "Hide jetpack particles" setting: a flying jetpack sends flame and cloud particles at its wearer, and this drops
 * them. Only flame and cloud particles within a few blocks of a jetpack wearer who is off the ground count, so the
 * same particles from explosions, fire or smoke elsewhere are left alone. Works for everyone's jetpack, not just yours.
 */
public final class JetpackParticles {

	/** How near a jetpack wearer a particle has to spawn to be theirs, in blocks. */
	private static final double RADIUS = 3.0;

	private static Settings settings;

	private JetpackParticles() {}

	public static void init(Settings modSettings) {
		settings = modSettings;
	}

	/** Whether this particle packet is a jetpack's exhaust that should not be shown. */
	public static boolean shouldHide(ClientboundLevelParticlesPacket packet) {
		if (settings == null || !settings.hideJetpackParticles) return false;
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
