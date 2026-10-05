package com.example.gtmaddons;

import com.example.gtmaddons.stats.StatsClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.UUID;

/**
 * The GTMAddOns icon after a player's name, in the tab list and on nametags (see the PlayerListHud and name tag
 * mixins). Who runs the mod comes from the backend through StatsClient. The icon goes after the name: Lunar Client
 * puts its own logo before the name of its users, so the two never overlap.
 */
public final class ModUsers {

	private static final String ICON = " ✦";

	private static StatsClient stats;
	private static Settings settings;

	private ModUsers() {}

	private static long nextPruneMillis = 0L;

	/** Every couple of seconds: forget remembered mod users who left the server (see StatsClient.retainOnline). */
	public static void onFrame(net.minecraft.client.MinecraftClient client) {
		if (stats == null) return;
		long now = System.currentTimeMillis();
		if (now < nextPruneMillis) return;
		nextPruneMillis = now + 2000L;
		stats.retainOnline(client.getNetworkHandler() != null ? client.getNetworkHandler().getPlayerUuids() : java.util.List.<java.util.UUID>of());
	}

	public static void init(StatsClient statsClient, Settings modSettings) {
		stats = statsClient;
		settings = modSettings;
	}

	/** The name with the icon after it if this player runs the mod (and the setting is on); the same name otherwise. */
	public static Text decorate(Text name, UUID player) {
		if (stats == null || settings == null || !settings.showModUsers || player == null || !stats.isModUser(player)) return name;
		return name.copy().append(Text.literal(ICON).formatted(Formatting.AQUA));
	}
}
