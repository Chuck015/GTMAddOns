package com.example.gtmaddons;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Settings > Boost height: while gliding, shows whether you're low enough
 * for a wingsuit boost (shift) - under the crosshair, unless moved in
 * Settings > Move HUD.
 *
 * GTM only boosts below Y 200 (your feet, as on F3). Found with dev mode on
 * 2026-09-27: boosts up to Y 199.9, none from Y 200.5 up. Height above the
 * ground doesn't matter.
 */
public final class BoostHeightHud {

	/** Boosting needs your Y below this. */
	public static final double BOOST_MAX_Y = 200.0;
	private BoostHeightHud() {}

	/** The line to show (drawn where HudLayout puts it), or null when not gliding. */
	public static Text text(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (player == null || !player.isGliding()) return null;

		double height = player.getY();
		boolean canBoost = height < BOOST_MAX_Y;
		// Whole blocks like F3's block position: Y 199.9 shows as 199, 200.5 as 200.
		String label = String.format("%s · Y %d (limit %d)", canBoost ? "✔ Low enough to boost" : "✘ Too high to boost",
				(int) Math.floor(height), (int) BOOST_MAX_Y);

		return Text.literal(label).formatted(canBoost ? Formatting.GREEN : Formatting.RED);
	}
}
