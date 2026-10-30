package com.example.gtmaddons.gui;

import com.example.gtmaddons.CombatTracker;
import com.example.gtmaddons.compat.Mc;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Stats screens are off-limits in combat: they close the moment you get tagged. */
final class CombatLock {
	private CombatLock() {}

	/** Call from a stats screen's tick(). */
	static void closeIfInCombat(Minecraft client) {
		if (!CombatTracker.INSTANCE.isTagged()) return;
		Mc.setScreen(client, null);
		if (client.player != null) {
			Mc.actionBar(client.player, Component.literal("[GTMAddOns] Stats closed - you're in combat.")
					.withStyle(ChatFormatting.GRAY));
		}
	}
}
