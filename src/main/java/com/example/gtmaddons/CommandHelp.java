package com.example.gtmaddons;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

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
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) return;
		client.player.sendMessage(Text.literal("━━━━━━━━━ ").formatted(Formatting.DARK_GRAY)
				.append(Text.literal("GTMAddOns commands").formatted(Formatting.GOLD, Formatting.BOLD))
				.append(Text.literal(" ━━━━━━━━━").formatted(Formatting.DARK_GRAY)), false);
		for (String[] command : COMMANDS) {
			MutableText line = Text.literal(" " + command[0]).formatted(Formatting.YELLOW)
					.append(Text.literal("  " + command[1]).formatted(Formatting.GRAY));
			client.player.sendMessage(line, false);
		}
		client.player.sendMessage(Text.literal(" /gtmaddons and /GTMAddOns function the same as /gao").formatted(Formatting.DARK_GRAY), false);
	}
}
