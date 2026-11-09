package com.example.gtmaddons.probe;

import com.example.gtmaddons.update.Updater;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Tracking that can be added without a mod update. The mod sends named events from hooks it already has (EVENTS below); the remote
 * config (Remote) holds rules that say what to measure from them. Rules are data, not code: five kinds, each filtered by a "where":
 *
 *   count    {"id":"x", "op":"count", "event":"cooldown", "where":{"ticks":0}}                         x = how many
 *   value    {"id":"x", "op":"value", "event":"melee_hit", "field":"dist"}                              x.n / .sum / .min / .max of a field
 *   between  {"id":"x", "op":"between", "event":"melee_hit", "end":"cooldown", "end_where":{"ticks":0},
 *             "same":["group"], "within_ms":1000}                                                       x.n / .sum / .min / .max in ms
 *            (from a start event to the next matching end event; "same" fields must be equal on both; a newer start replaces an older)
 *   by       {"id":"x", "op":"by", "event":"shot_result", "field":"move", "where":{"hit":true}}         x.<value> = count per value
 *   bucket   {"id":"x", "op":"bucket", "event":"shot_result", "field":"dist", "edges":[10,30,60]}     x.lt_10 / x.10_30 / x.30_60 / x.ge_60
 *            (count per range of a number; at most 10 edges, rising; decimals written with p, minus with m)
 *
 * A "where" maps a field to a value (equal; text ignores case) or to {"min", "max", "re", "in": [...], "not": true}. A rule can also have
 * "sample" (0-1, the share of players who run it, fixed per player), "min_mod" / "max_mod" (mod versions) and "enabled": false (kept in the
 * config, ready, but not run - the mod skips it and it is not listed under Extra tracking). Results are per fight,
 * uploaded with it as "probes" (flat numbers, shown in Raw info). Only while a fight is being recorded and probes are on (Remote.probesOn).
 *
 * Events and their fields (dist in blocks, times in ms, text lowercased and cut to 200 characters):
 *   damage       to_me, by_me, type, direct, dist, cause_player, target_player, item (the cause's held item id), move (yours, if to_me),
 *                target_hp (the target's health as your client knew it when the hit arrived)
 *   melee_hit    item, group, dist, gap_ms, clicks, blanks            (your melee hit on a player, as ComboTracker counts it)
 *   melee_hit_me item, dist, move                                    (a player's melee hit on you)
 *   combo_msg    left_ms     /   recover_msg  left_ms                 (GTM's "Combo'd" / "Target recovering" action bar)
 *   cooldown     group, ticks                                        (the server setting your item cooldown)
 *   action_bar / title / subtitle   text
 *   sound        id, dist, volume, pitch, own                         (own = played on you)
 *   particle     id, count
 *   click        button (left / right)
 *   shot         gun, cat, move          /   shot_result  gun, cat, hit, headshot, kill, dist, netted, move (at the shot)
 *   move         state (air / still / strafe / run), on each change
 *   swap         result, ms, cat, type
 *   health, hotbar, sprint, velocity, boost_press, boost, jetpack_tap, jetpack_start, near, fight_start, fight_end: see ProbeWatch
 */
public final class Probes {

	public static final int MAX_RULES = 64;
	private static final int MAX_KEYS = 200, MAX_BY_VALUES = 12, MAX_TEXT = 200, MAX_ID = 32;
	private static final Pattern ID = Pattern.compile("[a-z0-9_]{1," + MAX_ID + "}");
	public static final List<String> EVENTS = List.of("damage", "melee_hit", "melee_hit_me", "combo_msg", "recover_msg", "cooldown", "action_bar",
			"title", "subtitle", "sound", "particle", "click", "shot", "shot_result", "move", "swap",
			"health", "hotbar", "sprint", "velocity", "boost_press", "boost", "jetpack_tap", "jetpack_start", "near", "fight_start", "fight_end");

	/** One condition on one field. */
	static final class Cond {
		String eq;
		Double min, max;
		Pattern re;
		Set<String> in;
		boolean not;

		static Cond parse(JsonElement e) {
			Cond c = new Cond();
			if (e.isJsonPrimitive()) {
				c.eq = e.getAsString();
				return c;
			}
			if (!e.isJsonObject()) return null;
			JsonObject o = e.getAsJsonObject();
			try {
				if (o.has("eq")) c.eq = o.get("eq").getAsString();
				if (o.has("min")) c.min = o.get("min").getAsDouble();
				if (o.has("max")) c.max = o.get("max").getAsDouble();
				if (o.has("re")) {
					String re = o.get("re").getAsString();
					if (re.length() > 200) return null;
					c.re = Pattern.compile(re, Pattern.CASE_INSENSITIVE);
				}
				if (o.has("in") && o.get("in").isJsonArray()) {
					c.in = new HashSet<>();
					for (JsonElement v : o.getAsJsonArray("in")) c.in.add(v.getAsString().toLowerCase(Locale.ROOT));
				}
				if (o.has("not")) c.not = o.get("not").getAsBoolean();
			} catch (Exception bad) {
				return null;
			}
			return c;
		}

		boolean test(Object v) {
			return not != matches(v);
		}

		private boolean matches(Object v) {
			if (v == null) return false;
			if (eq != null) {
				if (v instanceof Number n) {
					try {
						if (n.doubleValue() != Double.parseDouble(eq)) return false;
					} catch (NumberFormatException e) {
						return false;
					}
				} else if (!String.valueOf(v).equalsIgnoreCase(eq)) return false;
			}
			if (min != null || max != null) {
				if (!(v instanceof Number n)) return false;
				if (min != null && n.doubleValue() < min) return false;
				if (max != null && n.doubleValue() > max) return false;
			}
			if (re != null && !re.matcher(String.valueOf(v)).find()) return false;
			return in == null || in.contains(String.valueOf(v).toLowerCase(Locale.ROOT));
		}
	}

	/** One rule from the remote config. */
	public static final class Rule {
		public final String id, op, event;
		final String field, end, minMod, maxMod;
		final Map<String, Cond> where, endWhere;
		final List<String> same;
		final long withinNanos;
		final double sample;
		/** bucket: the range edges, rising. */
		final double[] edges;
		/** What the config says it is for, shown under Mods > Misc > Extra tracking. */
		public final String about;

		private Rule(String id, String op, String event, String field, String end, Map<String, Cond> where, Map<String, Cond> endWhere,
				List<String> same, long withinNanos, double sample, String minMod, String maxMod, String about, double[] edges) {
			this.id = id;
			this.op = op;
			this.event = event;
			this.field = field;
			this.end = end;
			this.where = where;
			this.endWhere = endWhere;
			this.same = same;
			this.withinNanos = withinNanos;
			this.sample = sample;
			this.minMod = minMod;
			this.maxMod = maxMod;
			this.about = about;
			this.edges = edges;
		}

		/** The rule, or null if it is malformed or uses an event or kind this mod doesn't have (an older mod skips newer rules). */
		static Rule parse(JsonObject o) {
			try {
				// Switched off in the config: ready for later, not run.
				if (o.has("enabled") && !o.get("enabled").getAsBoolean()) return null;
				String id = o.get("id").getAsString(), op = o.get("op").getAsString(), event = o.get("event").getAsString();
				if (!ID.matcher(id).matches() || !EVENTS.contains(event)) return null;
				String field = o.has("field") ? o.get("field").getAsString() : null;
				String end = o.has("end") ? o.get("end").getAsString() : null;
				switch (op) {
					case "count" -> { }
					case "value", "by" -> {
						// Message text is only for filtering (where), never sent: a "by" on it would upload pieces of it as names.
						if (field == null || field.equals("text")) return null;
					}
					case "between" -> {
						if (end == null || !EVENTS.contains(end)) return null;
					}
					case "bucket" -> {
						if (field == null || field.equals("text") || !o.has("edges") || !o.get("edges").isJsonArray()) return null;
					}
					default -> {
						return null;
					}
				}
				Map<String, Cond> where = conds(o, "where"), endWhere = conds(o, "end_where");
				if (where == null || endWhere == null) return null;
				List<String> same = new ArrayList<>();
				if (o.has("same") && o.get("same").isJsonArray()) for (JsonElement s : o.getAsJsonArray("same")) same.add(s.getAsString());
				double withinMs = o.has("within_ms") ? o.get("within_ms").getAsDouble() : 5_000;
				double sample = o.has("sample") ? o.get("sample").getAsDouble() : 1.0;
				String about = o.has("about") ? o.get("about").getAsString() : "";
				double[] edges = new double[0];
				if (op.equals("bucket")) {
					var list = o.getAsJsonArray("edges");
					if (list.isEmpty() || list.size() > 10) return null;
					edges = new double[list.size()];
					for (int i = 0; i < edges.length; i++) {
						edges[i] = list.get(i).getAsDouble();
						if (!Double.isFinite(edges[i]) || (i > 0 && edges[i] <= edges[i - 1])) return null;
					}
				}
				return new Rule(id, op, event, field, end, where, endWhere, same, (long) (Math.max(0, Math.min(withinMs, 600_000)) * 1_000_000L),
						sample, o.has("min_mod") ? o.get("min_mod").getAsString() : null, o.has("max_mod") ? o.get("max_mod").getAsString() : null,
						about.length() > 120 ? about.substring(0, 120) : about, edges);
			} catch (Exception bad) {
				return null;
			}
		}

		private static Map<String, Cond> conds(JsonObject o, String key) {
			Map<String, Cond> out = new LinkedHashMap<>();
			if (!o.has(key)) return out;
			if (!o.get(key).isJsonObject()) return null;
			for (var e : o.getAsJsonObject(key).entrySet()) {
				Cond c = Cond.parse(e.getValue());
				if (c == null) return null;
				out.put(e.getKey(), c);
			}
			return out;
		}

		boolean forThisMod() {
			String version = Updater.modVersion();
			return (minMod == null || compareVersions(version, minMod) >= 0) && (maxMod == null || compareVersions(version, maxMod) <= 0);
		}

		/** Whether this player runs the rule: a fixed share of players, by their UUID and the rule's id. */
		boolean sampled(String playerUuid) {
			if (sample >= 1.0) return true;
			if (sample <= 0.0) return false;
			int h = (playerUuid + "|" + id).hashCode() & 0x7fffffff;
			return (h % 10_000) / 10_000.0 < sample;
		}

		boolean listensTo(String e) {
			return event.equals(e) || e.equals(end);
		}
	}

	/** What one rule has measured in the current fight. */
	private static final class Tally {
		final Rule rule;
		int n;
		double sum, min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
		final Map<String, Integer> by = new LinkedHashMap<>();
		long startNanos = -1L;
		Map<String, Object> startFields;

		Tally(Rule rule) {
			this.rule = rule;
		}

		void add(double v) {
			n++;
			sum += v;
			min = Math.min(min, v);
			max = Math.max(max, v);
		}

		void onEvent(String event, Map<String, Object> fields, long now) {
			switch (rule.op) {
				case "count" -> {
					if (matches(rule.where, fields)) n++;
				}
				case "value" -> {
					if (!matches(rule.where, fields)) return;
					Double v = number(fields.get(rule.field));
					if (v != null) add(v);
				}
				case "by" -> {
					if (!matches(rule.where, fields)) return;
					Object v = fields.get(rule.field);
					if (v == null) return;
					String key = clean(String.valueOf(v));
					if (!by.containsKey(key) && by.size() >= MAX_BY_VALUES) key = "other";
					by.merge(key, 1, Integer::sum);
				}
				case "bucket" -> {
					if (!matches(rule.where, fields)) return;
					Double v = number(fields.get(rule.field));
					if (v != null) by.merge(bucketKey(rule.edges, v), 1, Integer::sum);
				}
				case "between" -> {
					// The end is checked first, so a rule whose start and end are the same event measures from one to the next.
					if (event.equals(rule.end) && startNanos >= 0 && matches(rule.endWhere, fields)) {
						if (now - startNanos > rule.withinNanos) {
							startNanos = -1L;
						} else if (sameFields(fields)) {
							add((now - startNanos) / 1_000_000.0);
							startNanos = -1L;
						}
					}
					if (event.equals(rule.event) && matches(rule.where, fields)) {
						startNanos = now;
						startFields = fields;
					}
				}
				default -> { }
			}
		}

		private boolean sameFields(Map<String, Object> fields) {
			for (String f : rule.same) {
				Object a = startFields.get(f), b = fields.get(f);
				if (a == null || b == null || !String.valueOf(a).equalsIgnoreCase(String.valueOf(b))) return false;
			}
			return true;
		}

		void addTo(JsonObject out) {
			String id = rule.id;
			switch (rule.op) {
				case "count" -> out.addProperty(id, n);
				case "by", "bucket" -> {
					for (var e : by.entrySet()) out.addProperty(id + "." + e.getKey(), e.getValue());
				}
				default -> {
					out.addProperty(id + ".n", n);
					if (n == 0) return;
					out.addProperty(id + ".sum", round(sum));
					out.addProperty(id + ".min", round(min));
					out.addProperty(id + ".max", round(max));
				}
			}
		}
	}

	/** The rules running in the current fight. */
	private static final class Fight {
		final List<Tally> tallies = new ArrayList<>();
		final Map<String, List<Tally>> byEvent = new HashMap<>();
	}

	private static volatile Fight fight = null;

	private Probes() {}

	// ---- Fight lifecycle (MouseTracker) ----

	public static synchronized void onFightStart() {
		fight = null;
		LocalPlayer me = Minecraft.getInstance().player;
		if (!Remote.probesOn() || me == null) return;
		String uuid = me.getUUID().toString();
		Fight f = new Fight();
		for (Rule rule : Remote.config().rules()) {
			if (!rule.sampled(uuid)) continue;
			Tally t = new Tally(rule);
			f.tallies.add(t);
			for (String e : EVENTS) if (rule.listensTo(e)) f.byEvent.computeIfAbsent(e, k -> new ArrayList<>()).add(t);
		}
		if (!f.tallies.isEmpty()) fight = f;
		ProbeWatch.onFightEdge("fight_start", "cat", com.example.gtmaddons.PvpCategory.classify(me).name());
	}

	/** The fight's probe results as flat numbers, or null if no rules ran. */
	public static synchronized JsonObject onFightEnd() {
		Fight f = fight;
		fight = null;
		if (f == null) return null;
		JsonObject out = new JsonObject();
		for (Tally t : f.tallies) {
			if (out.size() >= MAX_KEYS) break;
			t.addTo(out);
		}
		while (out.size() > MAX_KEYS) out.remove(out.keySet().iterator().next());
		out.addProperty("cfg", Remote.config().version());
		return out;
	}

	public static synchronized void onFightDrop() {
		fight = null;
	}

	// ---- Events ----

	/** Whether any running rule listens to this event: hooks check it first, so nothing is built for events nobody wants. */
	public static boolean wants(String event) {
		Fight f = fight;
		return f != null && f.byEvent.containsKey(event);
	}

	/** An event with its fields as name, value pairs (values: text, numbers or true/false). */
	public static void emit(String event, Object... kv) {
		if (!wants(event)) return;
		Map<String, Object> fields = new HashMap<>();
		for (int i = 0; i + 1 < kv.length; i += 2) {
			Object v = kv[i + 1];
			if (v instanceof String s) v = s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) : s;
			if (v != null) fields.put((String) kv[i], v);
		}
		long now = System.nanoTime();
		synchronized (Probes.class) {
			Fight f = fight;
			if (f == null) return;
			List<Tally> tallies = f.byEvent.get(event);
			if (tallies != null) for (Tally t : tallies) t.onEvent(event, fields, now);
		}
	}

	/** A damage packet (ClientPlayNetworkHandlerMixin). */
	public static void onDamage(int targetId, int causeId, int directId, String type) {
		if (!wants("damage")) return;
		Minecraft client = Minecraft.getInstance();
		LocalPlayer me = client.player;
		if (me == null || client.level == null) return;
		Entity target = client.level.getEntity(targetId), cause = client.level.getEntity(causeId);
		boolean toMe = targetId == me.getId();
		emit("damage", "to_me", toMe, "by_me", causeId == me.getId(), "type", type, "direct", directId == causeId,
				"dist", target != null && cause != null ? round(target.distanceTo(cause)) : null,
				"cause_player", cause instanceof Player, "target_player", target instanceof Player,
				"item", cause instanceof Player p ? itemId(p.getMainHandItem()) : null,
				"move", toMe ? com.example.gtmaddons.MoveStates.stateName(client) : null,
				"target_hp", target instanceof net.minecraft.world.entity.LivingEntity living ? round(living.getHealth()) : null);
	}

	public static void onText(String event, String text) {
		if (wants(event)) emit(event, "text", text.toLowerCase(Locale.ROOT).trim());
	}

	public static String itemId(ItemStack stack) {
		return stack == null || stack.isEmpty() ? "empty" : BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
	}

	// ---- Helpers ----

	private static boolean matches(Map<String, Cond> where, Map<String, Object> fields) {
		for (var e : where.entrySet()) if (!e.getValue().test(fields.get(e.getKey()))) return false;
		return true;
	}

	private static Double number(Object v) {
		if (v instanceof Number n) return n.doubleValue();
		if (v instanceof Boolean b) return b ? 1.0 : 0.0;
		return null;
	}

	/** The range a value falls in: lt_<first edge>, <edge>_<next edge> (from inclusive, to exclusive) or ge_<last edge>. */
	static String bucketKey(double[] edges, double v) {
		if (v < edges[0]) return "lt_" + edge(edges[0]);
		for (int i = 1; i < edges.length; i++) if (v < edges[i]) return edge(edges[i - 1]) + "_" + edge(edges[i]);
		return "ge_" + edge(edges[edges.length - 1]);
	}

	private static String edge(double e) {
		String s = e == Math.rint(e) && Math.abs(e) < 1e9 ? Long.toString((long) e) : Double.toString(round(e));
		return s.replace("-", "m").replace(".", "p");
	}

	private static String clean(String v) {
		String s = v.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_");
		if (s.length() > 24) s = s.substring(0, 24);
		return s.isEmpty() ? "_" : s;
	}

	public static double round(double v) {
		return Math.round(v * 100) / 100.0;
	}

	static int compareVersions(String a, String b) {
		String[] x = a.split("[^0-9]+"), y = b.split("[^0-9]+");
		for (int i = 0; i < Math.max(x.length, y.length); i++) {
			int p = i < x.length && !x[i].isEmpty() ? Integer.parseInt(x[i]) : 0, q = i < y.length && !y[i].isEmpty() ? Integer.parseInt(y[i]) : 0;
			if (p != q) return p < q ? -1 : 1;
		}
		return 0;
	}

	/** The rules running now, for the Extra tracking page. */
	public static List<Rule> activeRules() {
		return Remote.config().rules();
	}

}
