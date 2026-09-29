package com.example.gtmaddons.gun;

import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Dev mode: when you run /near (GTM lists nearby players), the server's
 * reply is captured - every chat and action-bar message for a moment
 * afterwards, with its timing, exact text, and any hover text or click
 * action attached to parts of it (servers often put distances or details
 * there). The capture ends REPLY_GAP_NANOS after the last message (or
 * FIRST_REPLY_NANOS if nothing comes), at most MAX_NANOS after the
 * command. It's then written to the dev log as its own block, and a chat
 * line says how many messages were caught. With Settings > Better near on,
 * the reply is logged as the rewritten list (see NearList).
 */
final class NearLogger {

	private static final long FIRST_REPLY_NANOS = 3_000_000_000L;
	private static final long REPLY_GAP_NANOS = 1_000_000_000L;
	private static final long MAX_NANOS = 6_000_000_000L;

	private final DevLogger log;
	private long startNanos = -1L;
	private long endNanos = 0L;
	private String command = "";
	private int messages = 0;
	private final List<String> lines = new ArrayList<>();

	NearLogger(DevLogger log) {
		this.log = log;
	}

	/** A command you sent, without the slash. */
	void onCommand(String sent) {
		String name = sent.trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
		if (!name.equals("near")) return;
		if (startNanos >= 0) finish(); // a new /near before the last one finished
		command = "/" + sent.trim();
		// Before the capture starts - it's a chat message too, and isn't part of the reply.
		DevLogger.chat("Capturing the reply to " + command, Formatting.GRAY);
		startNanos = System.nanoTime();
		endNanos = startNanos + FIRST_REPLY_NANOS;
		messages = 0;
		lines.clear();
	}

	void onMessage(Text text, boolean actionBar) {
		if (startNanos < 0) return;
		long now = System.nanoTime();
		messages++;
		lines.add(String.format("+%5dms  %s  %s", (now - startNanos) / 1_000_000L, actionBar ? "ACTION_BAR" : "CHAT", plain(text)));
		for (String extra : attached(text)) lines.add("           " + extra);
		endNanos = Math.min(startNanos + MAX_NANOS, now + REPLY_GAP_NANOS);
	}

	void onFrame() {
		if (startNanos >= 0 && System.nanoTime() >= endNanos) finish();
	}

	private void finish() {
		long tookMs = (System.nanoTime() - startNanos) / 1_000_000L;
		startNanos = -1L;
		if (messages == 0) lines.add("(no reply within " + FIRST_REPLY_NANOS / 1_000_000_000L + "s)");
		log.writeBlock(command + " reply", lines);
		DevLogger.chat(String.format("%s reply: %d %s captured over %dms%s", command, messages, messages == 1 ? "message" : "messages",
				tookMs, log.isSavingLog() ? ", saved to the dev log" : " (QA mode - not saved)"), Formatting.GRAY);
		lines.clear();
	}

	/** The message's text, with anything outside printable ASCII written as \\uXXXX. */
	private static String plain(Text text) {
		StringBuilder out = new StringBuilder("\"");
		text.visit((Style style, String part) -> {
			for (int i = 0; i < part.length(); ) {
				int cp = part.codePointAt(i);
				if (cp < 0x20 || cp >= 0x7F) out.append(String.format("\\u%04X", cp));
				else out.appendCodePoint(cp);
				i += Character.charCount(cp);
			}
			return Optional.empty();
		}, Style.EMPTY);
		return out.append('"').toString();
	}

	/** Hover text and click actions on parts of the message, each once. */
	private static List<String> attached(Text text) {
		Set<String> found = new LinkedHashSet<>();
		text.visit((Style style, String part) -> {
			String on = part.isBlank() ? "" : " on \"" + part.strip() + "\"";
			if (style.getHoverEvent() instanceof HoverEvent.ShowText hover) found.add("hover" + on + ": " + plain(hover.value()));
			else if (style.getHoverEvent() != null) found.add("hover" + on + ": " + style.getHoverEvent());
			ClickEvent click = style.getClickEvent();
			if (click != null) found.add("click" + on + ": " + click);
			return Optional.empty();
		}, Style.EMPTY);
		return new ArrayList<>(found);
	}
}
