package com.example.gtmaddons;

import com.example.gtmaddons.compat.Mc;
import com.example.gtmaddons.stats.SwapRecord;
import com.example.gtmaddons.stats.SwapSummary.Stat;
import com.example.gtmaddons.stats.SwapSummary;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "/gao swapinfo start" ... "/gao swapinfo end": records every swap made in between and, at the end,
 * prints their averages in chat (like the old SwapInfo mod). Swaps are fed in by GTMAddOnsClient as
 * they finish (after their momentum is measured), whatever the PvP category or fight.
 *
 * The numbers come from SwapSummary; this class only keeps the list and lays the message out.
 */
public final class SwapSession {

	public static final SwapSession INSTANCE = new SwapSession();

	private static final int BAR = 22;
	/** Most swap times shown in the "Swaps" line. */
	private static final int MAX_LISTED = 12;

	private boolean recording = false;
	private long startedAtMillis = 0L;
	private final List<SwapRecord> records = new ArrayList<>();

	private SwapSession() {}

	public boolean isRecording() {
		return recording;
	}

	/** Starts (or restarts) recording. Returns how many swaps a restart threw away. */
	public int start() {
		int dropped = recording ? records.size() : 0;
		records.clear();
		startedAtMillis = System.currentTimeMillis();
		recording = true;
		return dropped;
	}

	/** A finished swap: kept only while recording. */
	public void add(SwapRecord record) {
		if (recording) records.add(record);
	}

	// ---- Chat ----

	public void sendStarted(int dropped) {
		send(bar("Swap recording", ChatFormatting.GREEN));
		send(Component.literal(" Recording every swap you make from now on.").withStyle(ChatFormatting.GRAY));
		if (dropped > 0) send(Component.literal(" (A recording was already running: its " + dropped + " swaps were dropped.)").withStyle(ChatFormatting.DARK_GRAY));
		send(Component.literal(" Type ").withStyle(ChatFormatting.GRAY).append(command("/gao swapinfo end"))
				.append(Component.literal(" when you are done.").withStyle(ChatFormatting.GRAY)));
	}

	public void sendStatus() {
		if (recording) {
			send(Component.literal("Swap recording is running: ").withStyle(ChatFormatting.GREEN).append(Component.literal(
					records.size() + (records.size() == 1 ? " swap" : " swaps") + " in " + duration(System.currentTimeMillis() - startedAtMillis)).withStyle(ChatFormatting.WHITE)));
			send(Component.literal("Type ").withStyle(ChatFormatting.GRAY).append(command("/gao swapinfo end")).append(Component.literal(" for the averages.").withStyle(ChatFormatting.GRAY)));
		} else {
			send(Component.literal("Records your swaps and shows their averages: ").withStyle(ChatFormatting.GRAY));
			send(Component.literal("  ").append(command("/gao swapinfo start")).append(Component.literal("  begin recording").withStyle(ChatFormatting.GRAY)));
			send(Component.literal("  ").append(command("/gao swapinfo end")).append(Component.literal("    stop and show the averages").withStyle(ChatFormatting.GRAY)));
		}
	}

	public void sendNotRecording() {
		send(Component.literal("Not recording. Type ").withStyle(ChatFormatting.RED).append(command("/gao swapinfo start")).append(Component.literal(" first.").withStyle(ChatFormatting.RED)));
	}

	/** Stops recording and prints the averages. */
	public void end() {
		recording = false;
		long elapsed = System.currentTimeMillis() - startedAtMillis;
		SwapSummary s = SwapSummary.of(List.copyOf(records));
		send(bar("Swap Info", ChatFormatting.GOLD));
		if (s.total() == 0) {
			send(Component.literal(" Recorded " + duration(elapsed) + " - no swaps were made.").withStyle(ChatFormatting.GRAY));
			send(closingBar());
			return;
		}

		send(Component.literal(" Recorded ").withStyle(ChatFormatting.GRAY).append(white(duration(elapsed))).append(gray("  ·  ")).append(white(s.total() + (s.total() == 1 ? " swap" : " swaps")))
				.append(typeSplit(s)));
		MutableComponent tally = Component.literal(" ").append(Component.literal("✔ " + s.successes()).withStyle(ChatFormatting.GREEN));
		tally.append(gray("   ")).append(Component.literal("✘ " + s.failures()).withStyle(s.failures() > 0 ? ChatFormatting.RED : ChatFormatting.DARK_GRAY));
		tally.append(gray("   ")).append(Component.literal("⊘ " + s.cancels()).withStyle(ChatFormatting.GRAY));
		tally.append(gray("   ·   ")).append(Component.literal(String.format(Locale.ROOT, "%.0f%% success", s.successRate())).withStyle(rateColor(s.successRate())));
		send(tally);

		if (s.totalMs() == null) {
			send(Component.literal(" No successful swaps, so there are no times to average.").withStyle(ChatFormatting.GRAY));
			send(closingBar());
			return;
		}

		send(Component.empty());
		send(section("Average swap time"));
		boolean airOnly = s.successByCategory().size() == 1 && s.successByCategory().containsKey("AIR");
		Stat total = s.totalMs();
		MutableComponent totalLine = row("Total", seconds(total.avg()), ChatFormatting.YELLOW);
		if (total.n() > 1) {
			totalLine.append(gray("   best ")).append(Component.literal(seconds(total.min())).withStyle(ChatFormatting.AQUA))
					.append(gray(" · worst ")).append(Component.literal(seconds(total.max())).withStyle(ChatFormatting.RED))
					.append(gray(" · ±")).append(white(seconds(total.sd())));
		}
		send(totalLine);
		stepRow("Reach slot", s.reachMs());
		stepRow("Slot → hotbar", s.slotToHotbarMs());
		stepRow("Hotbar → close", s.hotbarToCloseMs());
		stepRow(airOnly ? "Swap made" : "Wing to hotbar", s.wingToHotbarMs());

		if (s.efficiency() != null || s.mouseDeg() != null || s.overflickDeg() != null) {
			send(Component.empty());
			send(section("Mouse"));
			if (s.efficiency() != null) {
				send(row("Efficiency", String.format(Locale.ROOT, "%.0f%%", s.efficiency().avg()), efficiencyColor(s.efficiency().avg()))
						.append(count(s.efficiency())));
			}
			if (s.mouseDeg() != null) send(row("Moved", String.format(Locale.ROOT, "%.1f°", s.mouseDeg().avg()), ChatFormatting.WHITE).append(gray(" on average")));
			if (s.overflickDeg() != null) send(row("After touching slot", String.format(Locale.ROOT, "%.1f°", s.overflickDeg().avg()), ChatFormatting.WHITE));
		}
		if (s.momentumKept() != null) {
			send(Component.empty());
			send(section("Momentum"));
			send(row("Speed kept", String.format(Locale.ROOT, "%.0f%%", s.momentumKept().avg()), rateColor(s.momentumKept().avg())).append(count(s.momentumKept())));
		}

		List<Double> times = s.successTotalsMs();
		if (times.size() > 1) {
			send(Component.empty());
			int from = Math.max(0, times.size() - MAX_LISTED);
			StringBuilder list = new StringBuilder();
			for (int i = from; i < times.size(); i++) list.append(i > from ? "  " : "").append(String.format(Locale.ROOT, "%.3f", times.get(i) / 1000.0));
			send(Component.literal(" " + (from > 0 ? "Last " + MAX_LISTED + " swaps: " : "Swaps: ")).withStyle(ChatFormatting.GRAY).append(white(list.toString())).append(gray(" s")));
		}
		send(closingBar());
	}

	// ---- One swap (Settings > Advanced swap info) ----

	/**
	 * The report for a single swap, shown right after it when "Advanced swap info" is on. Same style as the averages:
	 * a title bar naming the result, the timing steps, momentum, and what the mouse did.
	 * swapTypeLabel is e.g. "Wingsuit" (null for failed / canceled swaps).
	 */
	public static void sendSwapReport(SwapRecord r, boolean airSwap, boolean chestSlot, String swapTypeLabel) {
		String slotName = chestSlot ? "chest slot" : "wingsuit slot";
		boolean success = "SUCCESS".equals(r.result());
		boolean failed = "FAILED".equals(r.result());
		String resultName = success ? "Success" : failed ? "Failed" : "Canceled";
		send(bar("Swap Info", ChatFormatting.GOLD).append(Component.literal(" " + resultName).withStyle(success ? ChatFormatting.GREEN : failed ? ChatFormatting.RED : ChatFormatting.GRAY, ChatFormatting.BOLD)));
		send(Component.literal(" ").append(Component.literal(label(r.category())).withStyle(ChatFormatting.AQUA))
				.append(swapTypeLabel != null ? gray("  ·  ").append(white(swapTypeLabel)) : Component.empty()));

		send(Component.empty());
		send(section("Timing"));
		send(timeRow("Reach " + slotName, r.reachMs()));
		send(timeRow(slotName.substring(0, 1).toUpperCase(Locale.ROOT) + slotName.substring(1) + " → hotbar", r.slotToHotbarMs()));
		send(timeRow("Hotbar → close", r.hotbarToCloseMs()));
		send(timeRow(airSwap ? "Swap made" : "Wing to hotbar", r.wingToHotbarMs()));
		send(row("Total", seconds(r.totalMs()), ChatFormatting.YELLOW));

		if (r.speedBeforeBps() != null && r.speedAfterBps() != null) {
			double kept = r.speedBeforeBps() > 0 ? Math.min(100.0, r.speedAfterBps() / r.speedBeforeBps() * 100) : 100.0;
			send(Component.empty());
			send(section("Momentum"));
			send(row("Speed", String.format(Locale.ROOT, "%.1f → %.1f b/s", r.speedBeforeBps(), r.speedAfterBps()), ChatFormatting.WHITE)
					.append(gray("  ")).append(Component.literal(String.format(Locale.ROOT, "%.0f%% kept", kept)).withStyle(rateColor(kept)))
					.append(gray("  (2 s average)")));
		}

		send(Component.empty());
		send(section("Mouse"));
		send(row("Moved", String.format(Locale.ROOT, "%.1f°", r.mouseDeg()), ChatFormatting.WHITE));
		if (r.approachDeg() == null) {
			send(gray("  (the " + slotName + " wasn't on screen - no movement breakdown)"));
			send(closingBar());
			return;
		}
		// Efficiency = needed / (needed + sideways + 2 x backward + after-touch), so everything counted
		// against you is what is left of it (see SwapDebugTracker).
		double touched = r.overflickDeg() != null ? r.overflickDeg() : 0.0;
		double extra = r.efficiency() > 0 ? r.neededDeg() * (100.0 / r.efficiency() - 1.0) : 0.0;
		double sideways = Math.max(0.0, extra - 2.0 * r.awayDeg() - touched);
		send(row("Efficiency", String.format(Locale.ROOT, "%.0f%%", r.efficiency()), efficiencyColor(r.efficiency()))
				.append(gray(String.format(Locale.ROOT, "  %.1f° needed, %.1f° of extra movement counted against you", r.neededDeg(), extra))));
		send(row("Approach", String.format(Locale.ROOT, "%.1f°", r.approachDeg()), ChatFormatting.WHITE).append(gray(String.format(Locale.ROOT, "  moved, %.1f° needed", r.neededDeg()))));
		send(row("Sideways", String.format(Locale.ROOT, "%.1f°", sideways), ChatFormatting.WHITE).append(gray("  off the straight line")));
		send(row("Backward", String.format(Locale.ROOT, "%.1f°", r.awayDeg()), ChatFormatting.WHITE).append(gray("  away from the slot (counts twice)")));
		if (r.overflickDeg() != null) {
			send(row("After touching slot", String.format(Locale.ROOT, "%.1f°", r.overflickDeg()), ChatFormatting.WHITE)
					.append(gray(String.format(Locale.ROOT, "  peak %.1f° past it", r.overflickPeakDeg()))));
		}
		if (r.afterDeg() != null) {
			send(row("After wing in hotbar", String.format(Locale.ROOT, "%.1f°", r.afterDeg()), ChatFormatting.WHITE).append(gray("  unneeded (not part of efficiency)")));
		}
		send(closingBar());
	}

	/** A timing step: gray when the swap never reached it. */
	private static MutableComponent timeRow(String label, Double ms) {
		return ms == null ? Component.literal("  " + label + ": ").withStyle(ChatFormatting.GRAY).append(Component.literal("n/a").withStyle(ChatFormatting.DARK_GRAY))
				: row(label, seconds(ms), ChatFormatting.WHITE);
	}

	// ---- Layout helpers ----

	private void stepRow(String label, Stat stat) {
		if (stat == null) return;
		send(row(label, seconds(stat.avg()), ChatFormatting.WHITE).append(count(stat)));
	}

	/** "  Label      value" with the label padded so the values line up (roughly: the chat font is not monospaced). */
	private static MutableComponent row(String label, String value, ChatFormatting valueColor) {
		return Component.literal("  " + label + ": ").withStyle(ChatFormatting.GRAY).append(Component.literal(value).withStyle(valueColor, ChatFormatting.BOLD));
	}

	/** " (over n swaps)" when the step was not reached by every swap. */
	private static MutableComponent count(Stat stat) {
		return stat.n() > 1 ? gray("  (" + stat.n() + " swaps)") : Component.empty();
	}

	/** " · Wing 12 · Air 6" - how the swaps split by PvP category, when there is more than one. */
	private static MutableComponent typeSplit(SwapSummary s) {
		MutableComponent out = Component.empty();
		if (s.byCategory().size() < 2) {
			if (s.byCategory().size() == 1) {
				String name = s.byCategory().keySet().iterator().next();
				out.append(gray("  ·  ")).append(Component.literal(label(name)).withStyle(ChatFormatting.AQUA));
			}
			return out;
		}
		for (Map.Entry<String, Integer> e : s.byCategory().entrySet()) {
			out.append(gray("  ·  ")).append(Component.literal(label(e.getKey()) + " " + e.getValue()).withStyle(ChatFormatting.AQUA));
		}
		return out;
	}

	private static String label(String category) {
		try {
			return PvpCategory.valueOf(category).label;
		} catch (IllegalArgumentException e) {
			return category;
		}
	}

	private static MutableComponent section(String title) {
		return Component.literal(" " + title).withStyle(ChatFormatting.GOLD);
	}

	private static MutableComponent bar(String title, ChatFormatting color) {
		return Component.literal("━".repeat(BAR / 2) + " ").withStyle(ChatFormatting.DARK_GRAY).append(Component.literal(title).withStyle(color, ChatFormatting.BOLD))
				.append(Component.literal(" " + "━".repeat(BAR / 2)).withStyle(ChatFormatting.DARK_GRAY));
	}

	private static MutableComponent closingBar() {
		return Component.literal("━".repeat(BAR + 8)).withStyle(ChatFormatting.DARK_GRAY);
	}

	private static MutableComponent command(String text) {
		return Component.literal(text).withStyle(ChatFormatting.YELLOW);
	}

	private static MutableComponent gray(String text) {
		return Component.literal(text).withStyle(ChatFormatting.GRAY);
	}

	private static MutableComponent white(String text) {
		return Component.literal(text).withStyle(ChatFormatting.WHITE);
	}

	private static ChatFormatting rateColor(double percent) {
		return percent >= 85 ? ChatFormatting.GREEN : percent >= 60 ? ChatFormatting.YELLOW : ChatFormatting.RED;
	}

	private static ChatFormatting efficiencyColor(double percent) {
		return percent >= 80 ? ChatFormatting.GREEN : percent >= 50 ? ChatFormatting.YELLOW : ChatFormatting.RED;
	}

	private static String seconds(double ms) {
		return String.format(Locale.ROOT, "%.3fs", ms / 1000.0);
	}

	private static String duration(long millis) {
		long total = Math.max(0, millis / 1000);
		return total >= 60 ? (total / 60) + "m " + (total % 60) + "s" : total + "s";
	}

	private static void send(Component message) {
		Minecraft client = Minecraft.getInstance();
		if (client.player != null) Mc.chat(client.player, message);
	}
}
