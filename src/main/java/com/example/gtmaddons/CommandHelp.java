package com.example.gtmaddons;

import com.example.gtmaddons.compat.Mc;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * "/gao help": every command the mod has, in chat. Keep COMMANDS in step with
 * GTMAddOnsClient.registerCommands when a command is added or changed.
 */
public final class CommandHelp {

	private CommandHelp() {}

	/** {what you type, what it does}. */
	private static final String[][] COMMANDS = {
			{ "/gao", "open the menu (stats, settings)" },
			{ "/gao help", "show this list" },
			{ "/gao update", "download latest available update" },
			{ "/gao swapinfo start", "start recording your wing swaps" },
			{ "/gao swapinfo end", "stop recording wing swaps and show swap info" },
			{ "/gao swapinfo", "say whether a swap recording is running" },
	};

	public static void send() {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null) return;
		Mc.chat(client.player, Component.literal("━━━━━━━━━ ").withStyle(ChatFormatting.DARK_GRAY)
				.append(Component.literal("GTMAddOns commands").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
				.append(Component.literal(" ━━━━━━━━━").withStyle(ChatFormatting.DARK_GRAY)));
		for (String[] command : COMMANDS) {
			MutableComponent line = Component.literal(" " + command[0]).withStyle(ChatFormatting.YELLOW)
					.append(Component.literal("  " + command[1]).withStyle(ChatFormatting.GRAY));
			Mc.chat(client.player, line);
		}
		Mc.chat(client.player, Component.literal(" /gtmaddons and /GTMAddOns function the same as /gao").withStyle(ChatFormatting.DARK_GRAY));
	}
}
