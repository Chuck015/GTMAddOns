package com.example.gtmaddons.stats;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns GET /players/:uuid/raw (see the backend's playerRaw) into plain lines for the Raw info screen: the player row, then each fight
 * newest first with its swaps, gun totals and combo totals underneath, as "name=value" with the stored column names. Values that were
 * not recorded are left out; nothing is converted (times are epoch milliseconds, as stored).
 */
public final class RawInfo {

	private RawInfo() {}

	public static List<String> lines(JsonObject root) {
		List<String> out = new ArrayList<>();
		JsonObject player = root.getAsJsonObject("player");
		JsonObject fights = root.getAsJsonObject("fights"), swaps = root.getAsJsonObject("swaps");
		JsonObject guns = root.getAsJsonObject("guns"), combos = root.getAsJsonObject("combos");
		out.add("RAW INFO - everything stored about this player, as stored. Times are epoch milliseconds; distances are degrees unless the name says px or blocks; empty values are left out.");
		out.add("PLAYER " + pairs(player));
		int fightCount = rows(fights).size();
		out.add("COUNTS fights=" + fightCount + " swaps=" + rows(swaps).size() + " gun_rows=" + rows(guns).size() + " combo_rows=" + rows(combos).size()
				+ " (the newest fights of each PvP category the server keeps)");
		List<List<String>> swapLines = group(swaps, fightCount, "SWAP"), gunLines = group(guns, fightCount, "GUN"), comboLines = group(combos, fightCount, "COMBO");
		JsonArray fightRows = rows(fights);
		for (int i = 0; i < fightRows.size(); i++) {
			out.add("");
			out.add("FIGHT " + pairs(columns(fights), fightRows.get(i).getAsJsonArray()));
			out.addAll(swapLines.get(i));
			out.addAll(gunLines.get(i));
			out.addAll(comboLines.get(i));
		}
		return out;
	}

	private static JsonArray rows(JsonObject table) {
		return table != null && table.has("rows") ? table.getAsJsonArray("rows") : new JsonArray();
	}

	private static List<String> columns(JsonObject table) {
		List<String> cols = new ArrayList<>();
		if (table != null && table.has("cols")) for (JsonElement c : table.getAsJsonArray("cols")) cols.add(c.getAsString());
		return cols;
	}

	/** The child rows of one table, as indented lines, grouped by the row number of their fight. */
	private static List<List<String>> group(JsonObject table, int fightCount, String label) {
		List<List<String>> byFight = new ArrayList<>();
		for (int i = 0; i < fightCount; i++) byFight.add(new ArrayList<>());
		if (table == null || !table.has("fight")) return byFight;
		List<String> cols = columns(table);
		JsonArray rows = rows(table), fight = table.getAsJsonArray("fight");
		for (int i = 0; i < rows.size(); i++) {
			int f = fight.get(i).getAsInt();
			if (f >= 0 && f < fightCount) byFight.get(f).add("    " + label + " " + pairs(cols, rows.get(i).getAsJsonArray()));
		}
		return byFight;
	}

	private static String pairs(JsonObject object) {
		StringBuilder sb = new StringBuilder();
		for (var entry : object.entrySet()) append(sb, entry.getKey(), entry.getValue());
		return sb.toString().trim();
	}

	private static String pairs(List<String> cols, JsonArray values) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < cols.size() && i < values.size(); i++) append(sb, cols.get(i), values.get(i));
		return sb.toString().trim();
	}

	private static void append(StringBuilder sb, String name, JsonElement value) {
		if (value == null || value.isJsonNull()) return;
		String text;
		if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
			double d = value.getAsDouble();
			text = d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : value.getAsString();
		} else {
			text = value.getAsString();
		}
		sb.append(' ').append(name).append('=').append(text);
	}
}
