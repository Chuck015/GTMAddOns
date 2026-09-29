package com.example.gtmaddons.gui;

import com.example.gtmaddons.stats.StatsClient;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletionException;

/** Formatting for the stats screens. Null values (not measured) show as "-". */
public final class Format {
	private Format() {}

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

	static String seconds(Double ms) {
		return ms == null ? "-" : String.format("%.3fs", ms / 1000.0);
	}

	static String degrees(Double deg) {
		return deg == null ? "-" : String.format("%.1f°", deg);
	}

	static String percent(Double pct) {
		return pct == null ? "-" : String.format("%.0f%%", pct);
	}

	static String bps(Double bps) {
		return bps == null ? "-" : String.format("%.1f b/s", bps);
	}

	static String date(long epochMillis) {
		return DATE.format(Instant.ofEpochMilli(epochMillis));
	}

	static String time(long epochMillis) {
		return TIME.format(Instant.ofEpochMilli(epochMillis));
	}

	public static String error(Throwable error) {
		Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
		return cause.getMessage() != null ? cause.getMessage() : cause.toString();
	}
}
