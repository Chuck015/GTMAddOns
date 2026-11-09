package com.example.gtmaddons;

import com.example.gtmaddons.compat.WorldLines;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Boost height limit's "Limit view": while you glide (and for the set seconds after) within VIEW_RANGE blocks of the boost limit (BoostHeightHud.BOOST_MAX_Y), a grid
 * is drawn in the world at that height around you - green while you are under it (a boost works), red above it. Only a picture.
 */
public final class BoostLimitView {

	/** Half the grid's width in blocks, and the space between its lines. */
	private static final int RADIUS = 48, STEP = 4;
	/** The grid shows only this close to the limit, above or below. */
	private static final double VIEW_RANGE = 40.0;
	private static final int GREEN = 0x55FF55, RED = 0xFF5555;

	private static Settings settings;

	private BoostLimitView() {}

	public static void init(Settings modSettings) {
		settings = modSettings;
		WorldLines.register(BoostLimitView::draw);
	}

	private static void draw(WorldLines.Sink sink) {
		if (settings == null || !settings.boostHeight || !settings.boostLimitView) return;
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null || !BoostHeightHud.shown(player)) return;
		double y = BoostHeightHud.BOOST_MAX_Y;
		double distance = Math.abs(player.getY() - y);
		if (distance > VIEW_RANGE) return;
		// Fainter the further you are from it; green under it, red over it.
		int alpha = (int) Math.round(60 + 140 * (1 - distance / VIEW_RANGE));
		int argb = (alpha << 24) | (player.getY() < y ? GREEN : RED);
		double cx = Math.floor(player.getX() / STEP) * STEP, cz = Math.floor(player.getZ() / STEP) * STEP;
		for (int d = -RADIUS; d <= RADIUS; d += STEP) {
			sink.line(new Vec3(cx + d, y, cz - RADIUS), new Vec3(cx + d, y, cz + RADIUS), argb, 2.0f);
			sink.line(new Vec3(cx - RADIUS, y, cz + d), new Vec3(cx + RADIUS, y, cz + d), argb, 2.0f);
		}
	}
}
