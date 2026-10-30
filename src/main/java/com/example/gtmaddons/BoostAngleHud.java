package com.example.gtmaddons;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/**
 * Settings > Boost angle: while gliding, shows how far up you're looking
 * and whether a wingsuit boost (shift) would work - under the crosshair,
 * unless moved in Settings > Move HUD.
 *
 * GTM only boosts when you look more than 10 degrees up (pitch below -10;
 * Minecraft's pitch is negative looking up). Found with dev mode on
 * 2026-09-27: boosts at 10.1 degrees up and above, none at 10.0 or below.
 */
public final class BoostAngleHud {

	/** Boosting needs a pitch below this. */
	public static final float BOOST_MAX_PITCH = -10.0f;
	private BoostAngleHud() {}

	/** The line to show (drawn where HudLayout puts it), or null when not gliding. */
	public static Component text(Minecraft client) {
		LocalPlayer player = client.player;
		if (player == null || !player.isFallFlying()) return null;

		float pitch = player.getXRot();
		float up = -pitch;
		String angle = up >= 0 ? String.format("%.1f° up", up) : String.format("%.1f° down", -up);
		boolean canBoost = pitch < BOOST_MAX_PITCH;
		String label = canBoost
				? "✔ Boost ready · " + angle
				: String.format("✘ Look up %.1f° more · %s", Math.max(0.1f, pitch - BOOST_MAX_PITCH), angle);

		return Component.literal(label).withStyle(canBoost ? ChatFormatting.GREEN : ChatFormatting.RED);
	}
}
