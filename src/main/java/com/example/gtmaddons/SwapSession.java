package com.example.gtmaddons;

import com.example.gtmaddons.stats.SwapRecord;
import com.example.gtmaddons.stats.SwapSummary;
import com.example.gtmaddons.stats.SwapSummary.Stat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

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
		send(bar("Swap recording", Formatting.GREEN));
		send(Text.literal(" Recording every swap you make from now on.").formatted(Formatting.GRAY));
		if (dropped > 0) send(Text.literal(" (A recording was already running: its " + dropped + " swaps were dropped.)").formatted(Formatting.DARK_GRAY));
		send(Text.literal(" Type ").formatted(Formatting.GRAY).append(command("/gao swapinfo end"))
				.append(Text.literal(" when you are done.").formatted(Formatting.GRAY)));
	}

	public void sendStatus() {
		if (recording) {
			send(Text.literal("Swap recording is running: ").formatted(Formatting.GREEN).append(Text.literal(
					records.size() + (records.size() == 1 ? " swap" : " swaps") + " in " + duration(System.currentTimeMillis() - startedAtMillis)).formatted(Formatting.WHITE)));
			send(Text.literal("Type ").formatted(Formatting.GRAY).append(command("/gao swapinfo end")).append(Text.literal(" for the averages.").formatted(Formatting.GRAY)));
		} else {
			send(Text.literal("Records your swaps and shows their averages: ").formatted(Formatting.GRAY));
			send(Text.literal("  ").append(command("/gao swapinfo start")).append(Text.literal("  begin recording").formatted(Formatting.GRAY)));
			send(Text.literal("  ").append(command("/gao swapinfo end")).append(Text.literal("    stop and show the averages").formatted(Formatting.GRAY)));
		}
	}

	public void sendNotRecording() {
		send(Text.literal("Not recording. Type ").formatted(Formatting.RED).append(command("/gao swapinfo start")).append(Text.literal(" first.").formatted(Formatting.RED)));
	}

	/** Stops recording and prints the averages. */
	public void end() {
		recording = false;
		long elapsed = System.currentTimeMillis() - startedAtMillis;
		SwapSummary s = SwapSummary.of(List.copyOf(records));
		send(bar("Swap Info", Formatting.GOLD));
		if (s.total() == 0) {
			send(Text.literal(" Recorded " + duration(elapsed) + " - no swaps were made.").formatted(Formatting.GRAY));
			send(closingBar());
			return;
		}

		send(Text.literal(" Recorded ").formatted(Formatting.GRAY).append(white(duration(elapsed))).append(gray("  ·  ")).append(white(s.total() + (s.total() == 1 ? " swap" : " swaps")))
				.append(typeSplit(s)));
		MutableText tally = Text.literal(" ").append(Text.literal("✔ " + s.successes()).formatted(Formatting.GREEN));
		tally.append(gray("   ")).append(Text.literal("✘ " + s.failures()).formatted(s.failures() > 0 ? Formatting.RED : Formatting.DARK_GRAY));
		tally.append(gray("   ")).append(Text.literal("⊘ " + s.cancels()).formatted(Formatting.GRAY));
		tally.append(gray("   ·   ")).append(Text.literal(String.format(Locale.ROOT, "%.0f%% success", s.successRate())).formatted(rateColor(s.successRate())));
		send(tally);

		if (s.totalMs() == null) {
			send(Text.literal(" No successful swaps, so there are no times to average.").formatted(Formatting.GRAY));
			send(closingBar());
			return;
		}

		send(Text.empty());
		send(section("Average swap time"));
		boolean airOnly = s.successByCategory().size() == 1 && s.successByCategory().containsKey("AIR");
		Stat total = s.totalMs();
		MutableText totalLine = row("Total", seconds(total.avg()), Formatting.YELLOW);
		if (total.n() > 1) {
			totalLine.append(gray("   best ")).append(Text.literal(seconds(total.min())).formatted(Formatting.AQUA))
					.append(gray(" · worst ")).append(Text.literal(seconds(total.max())).formatted(Formatting.RED))
					.append(gray(" · ±")).append(white(seconds(total.sd())));
		}
		send(totalLine);
		stepRow("Reach slot", s.reachMs());
		stepRow("Slot → hotbar", s.slotToHotbarMs());
		stepRow("Hotbar → close", s.hotbarToCloseMs());
		stepRow(airOnly ? "Swap made" : "Wing to hotbar", s.wingToHotbarMs());

		if (s.efficiency() != null || s.mouseDeg() != null || s.overflickDeg() != null) {
			send(Text.empty());
			send(section("Mouse"));
			if (s.efficiency() != null) {
				send(row("Efficiency", String.format(Locale.ROOT, "%.0f%%", s.efficiency().avg()), efficiencyColor(s.efficiency().avg()))
						.append(count(s.efficiency())));
			}
			if (s.mouseDeg() != null) send(row("Moved", String.format(Locale.ROOT, "%.1f°", s.mouseDeg().avg()), Formatting.WHITE).append(gray(" on average")));
			if (s.overflickDeg() != null) send(row("After touching slot", String.format(Locale.ROOT, "%.1f°", s.overflickDeg().avg()), Formatting.WHITE));
		}
		if (s.momentumKept() != null) {
			send(Text.empty());
			send(section("Momentum"));
			send(row("Speed kept", String.format(Locale.ROOT, "%.0f%%", s.momentumKept().avg()), rateColor(s.momentumKept().avg())).append(count(s.momentumKept())));
		}

		List<Double> times = s.successTotalsMs();
		if (times.size() > 1) {
			send(Text.empty());
			int from = Math.max(0, times.size() - MAX_LISTED);
			StringBuilder list = new StringBuilder();
			for (int i = from; i < times.size(); i++) list.append(i > from ? "  " : "").append(String.format(Locale.ROOT, "%.3f", times.get(i) / 1000.0));
			send(Text.literal(" " + (from > 0 ? "Last " + MAX_LISTED + " swaps: " : "Swaps: ")).formatted(Formatting.GRAY).append(white(list.toString())).append(gray(" s")));
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
		send(bar("Swap Info", Formatting.GOLD).append(Text.literal(" " + resultName).formatted(success ? Formatting.GREEN : failed ? Formatting.RED : Formatting.GRAY, Formatting.BOLD)));
		send(Text.literal(" ").append(Text.literal(label(r.category())).formatted(Formatting.AQUA))
				.append(swapTypeLabel != null ? gray("  ·  ").append(white(swapTypeLabel)) : Text.empty()));

		send(Text.empty());
		send(section("Timing"));
		send(timeRow("Reach " + slotName, r.reachMs()));
		send(timeRow(slotName.substring(0, 1).toUpperCase(Locale.ROOT) + slotName.substring(1) + " → hotbar", r.slotToHotbarMs()));
		send(timeRow("Hotbar → close", r.hotbarToCloseMs()));
		send(timeRow(airSwap ? "Swap made" : "Wing to hotbar", r.wingToHotbarMs()));
		send(row("Total", seconds(r.totalMs()), Formatting.YELLOW));

		if (r.speedBeforeBps() != null && r.speedAfterBps() != null) {
			double kept = r.speedBeforeBps() > 0 ? r.speedAfterBps() / r.speedBeforeBps() * 100 : 100.0;
			send(Text.empty());
			send(section("Momentum"));
			send(row("Speed", String.format(Locale.ROOT, "%.1f → %.1f b/s", r.speedBeforeBps(), r.speedAfterBps()), Formatting.WHITE)
					.append(gray("  ")).append(Text.literal(String.format(Locale.ROOT, "%.0f%% kept", kept)).formatted(rateColor(kept)))
					.append(gray("  (2 s average)")));
		}

		send(Text.empty());
		send(section("Mouse"));
		send(row("Moved", String.format(Locale.ROOT, "%.1f°", r.mouseDeg()), Formatting.WHITE));
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
		send(row("Approach", String.format(Locale.ROOT, "%.1f°", r.approachDeg()), Formatting.WHITE).append(gray(String.format(Locale.ROOT, "  moved, %.1f° needed", r.neededDeg()))));
		send(row("Sideways", String.format(Locale.ROOT, "%.1f°", sideways), Formatting.WHITE).append(gray("  off the straight line")));
		send(row("Backward", String.format(Locale.ROOT, "%.1f°", r.awayDeg()), Formatting.WHITE).append(gray("  away from the slot (counts twice)")));
		if (r.overflickDeg() != null) {
			send(row("After touching slot", String.format(Locale.ROOT, "%.1f°", r.overflickDeg()), Formatting.WHITE)
					.append(gray(String.format(Locale.ROOT, "  peak %.1f° past it", r.overflickPeakDeg()))));
		}
		if (r.afterDeg() != null) {
			send(row("After wing in hotbar", String.format(Locale.ROOT, "%.1f°", r.afterDeg()), Formatting.WHITE).append(gray("  unneeded (not part of efficiency)")));
		}
		send(closingBar());
	}

	/** A timing step: gray when the swap never reached it. */
	private static MutableText timeRow(String label, Double ms) {
		return ms == null ? Text.literal("  " + label + ": ").formatted(Formatting.GRAY).append(Text.literal("n/a").formatted(Formatting.DARK_GRAY))
				: row(label, seconds(ms), Formatting.WHITE);
	}

	// ---- Layout helpers ----

	private void stepRow(String label, Stat stat) {
		if (stat == null) return;
		send(row(label, seconds(stat.avg()), Formatting.WHITE).append(count(stat)));
	}

	/** "  Label      value" with the label padded so the values line up (roughly: the chat font is not monospaced). */
	private static MutableText row(String label, String value, Formatting valueColor) {
		return Text.literal("  " + label + ": ").formatted(Formatting.GRAY).append(Text.literal(value).formatted(valueColor, Formatting.BOLD));
	}

	/** " (over n swaps)" when the step was not reached by every swap. */
	private static MutableText count(Stat stat) {
		return stat.n() > 1 ? gray("  (" + stat.n() + " swaps)") : Text.empty();
	}

	/** " · Wing 12 · Air 6" - how the swaps split by PvP category, when there is more than one. */
	private static MutableText typeSplit(SwapSummary s) {
		MutableText out = Text.empty();
		if (s.byCategory().size() < 2) {
			if (s.byCategory().size() == 1) {
				String name = s.byCategory().keySet().iterator().next();
				out.append(gray("  ·  ")).append(Text.literal(label(name)).formatted(Formatting.AQUA));
			}
			return out;
		}
		for (Map.Entry<String, Integer> e : s.byCategory().entrySet()) {
			out.append(gray("  ·  ")).append(Text.literal(label(e.getKey()) + " " + e.getValue()).formatted(Formatting.AQUA));
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

	private static MutableText section(String title) {
		return Text.literal(" " + title).formatted(Formatting.GOLD);
	}

	private static MutableText bar(String title, Formatting color) {
		return Text.literal("━".repeat(BAR / 2) + " ").formatted(Formatting.DARK_GRAY).append(Text.literal(title).formatted(color, Formatting.BOLD))
				.append(Text.literal(" " + "━".repeat(BAR / 2)).formatted(Formatting.DARK_GRAY));
	}

	private static MutableText closingBar() {
		return Text.literal("━".repeat(BAR + 8)).formatted(Formatting.DARK_GRAY);
	}

	private static MutableText command(String text) {
		return Text.literal(text).formatted(Formatting.YELLOW);
	}

	private static MutableText gray(String text) {
		return Text.literal(text).formatted(Formatting.GRAY);
	}

	private static MutableText white(String text) {
		return Text.literal(text).formatted(Formatting.WHITE);
	}

	private static Formatting rateColor(double percent) {
		return percent >= 85 ? Formatting.GREEN : percent >= 60 ? Formatting.YELLOW : Formatting.RED;
	}

	private static Formatting efficiencyColor(double percent) {
		return percent >= 80 ? Formatting.GREEN : percent >= 50 ? Formatting.YELLOW : Formatting.RED;
	}

	private static String seconds(double ms) {
		return String.format(Locale.ROOT, "%.3fs", ms / 1000.0);
	}

	private static String duration(long millis) {
		long total = Math.max(0, millis / 1000);
		return total >= 60 ? (total / 60) + "m " + (total % 60) + "s" : total + "s";
	}

	private static void send(Text message) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player != null) client.player.sendMessage(message, false);
	}
}
