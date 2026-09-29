package com.example.gtmaddons.gui;

import com.example.gtmaddons.CombatTracker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** Stats screens are off-limits in combat: they close the moment you get tagged. */
final class CombatLock {
	private CombatLock() {}

	/** Call from a stats screen's tick(). */
	static void closeIfInCombat(MinecraftClient client) {
		if (!CombatTracker.INSTANCE.isTagged()) return;
		client.setScreen(null);
		if (client.player != null) {
			client.player.sendMessage(Text.literal("[GTMAddOns] Stats closed - you're in combat.")
					.formatted(Formatting.GRAY), true);
		}
	}
}
