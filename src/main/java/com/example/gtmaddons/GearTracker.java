package com.example.gtmaddons;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Who is around you and what they are doing, always. Every SCAN_NANOS each player within RANGE blocks is looked at: their five
 * visible slots (head, chest, legs, feet, main hand), what their chest slot holds (a jetpack, a wingsuit or neither) and from that
 * the kind of PvP they are doing - Ground, Wing, JP, or Air when both a jetpack and a wingsuit were worn in the last KEEP_NANOS.
 * Each change of the chest slot is a swap. All of it is kept for KEEP_NANOS, then dropped, and a player who leaves the range loses
 * their data, so a saved window always means "in range for that long".
 *
 * When a fight starts (FightTracker) those windows join it and tracking goes on until it ends. onFightEnd(opponent) then hands back
 * the opponent's gear and class (jetpack and wingsuit both seen = Air) and their timeline (see trackJson), plus the others nearby.
 * Only the chest slot decides the type: other players' hotbars are never sent to us.
 *
 * To stay cheap: a look at a player is stored only when something changed, and players beyond RANGE cost one distance check.
 */
public final class GearTracker {

	public static final GearTracker INSTANCE = new GearTracker();

	private static final long SCAN_NANOS = 250_000_000L;
	private static final long KEEP_NANOS = 10_000_000_000L;
	/** How far away a player still counts as around you (what the server sends is about this far). */
	public static final double RANGE = 40.0;
	private static final int SLOTS = 5;
	/** The most entries of each list stored with a fight, and the longest the text may be (the backend keeps up to 2000). */
	private static final int MAX_LIST = 25, MAX_JSON = 1800, MAX_OTHERS = 4;
	private static final EquipmentSlot[] ORDER = { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND };

	/** What the chest slot holds. */
	private enum Kind {
		NONE('N'), JETPACK('J'), WINGSUIT('W');

		final char code;

		Kind(char code) {
			this.code = code;
		}
	}

	/** The five item names ("-" for empty) and the chest kind as of a moment; valid until the next look. */
	private record Look(long nanos, String[] slots, Kind kind) {}

	private record Swap(long nanos, Kind from, Kind to) {}

	private record TypeChange(long nanos, PvpCategory type) {}

	/** One player within range. */
	private static final class Track {
		String name;
		final Deque<Look> looks = new ArrayDeque<>();
		final Deque<Swap> swaps = new ArrayDeque<>();
		final Deque<TypeChange> types = new ArrayDeque<>();
		Kind kind = null;
		PvpCategory type = null;
		long lastSeen;
		double minDistance = Double.MAX_VALUE;
		/** In a fight: everything is kept, and these gather what was worn (from the window and the fight). */
		boolean inFight;
		boolean jetpack, wingsuit;
		final Set<String> chests = new LinkedHashSet<>(), heads = new LinkedHashSet<>(), legs = new LinkedHashSet<>(), feet = new LinkedHashSet<>();
		String[] startSlots, lastSlots;
	}

	/** An opponent at the end of a fight. category is a PvpCategory name; trackJson is their timeline (stored in fights.opponent_track). */
	public record OpponentGear(String category, String[] atStart, String[] atEnd, List<String> chests,
			List<String> heads, List<String> legs, List<String> feet, String trackJson, String summary) {
		/** Short JSON text for the backend: { "start": [...], "end": [...], "chests": [...], "heads": [...], "legs": [...], "feet": [...] }. */
		public String json() {
			com.google.gson.JsonObject o = new com.google.gson.JsonObject();
			o.add("start", strings(Arrays.asList(atStart)));
			o.add("end", strings(Arrays.asList(atEnd)));
			o.add("chests", strings(chests));
			o.add("heads", strings(heads));
			o.add("legs", strings(legs));
			o.add("feet", strings(feet));
			return o.toString();
		}

		private static com.google.gson.JsonArray strings(Collection<String> list) {
			com.google.gson.JsonArray array = new com.google.gson.JsonArray();
			for (String s : list) array.add(s);
			return array;
		}

		/** "Air (chest seen: Jetpack, Wingsuit; 6 swaps, closest 4.2 blocks)". */
		public String describe() {
			return category + " (chest seen: " + (chests.isEmpty() ? "nothing" : String.join(", ", chests)) + "; " + summary + ")";
		}
	}

	private final Map<String, Track> tracks = new HashMap<>();
	private boolean fightActive = false;
	private long fightStartNanos = 0L;
	private long lastScanNanos = 0L;

	private GearTracker() {}

	private static String key(String name) {
		return name.toLowerCase(Locale.ROOT);
	}

	public void onFrame(MinecraftClient client) {
		long now = System.nanoTime();
		if (now - lastScanNanos < SCAN_NANOS) return;
		lastScanNanos = now;
		PlayerEntity me = client.player;
		if (client.world == null || me == null) {
			tracks.clear();
			return;
		}
		Set<String> present = new HashSet<>();
		for (PlayerEntity p : client.world.getPlayers()) {
			if (p == me) continue;
			double distance = me.distanceTo(p);
			if (distance > RANGE) continue; // out of range: no data (an existing track is dropped below unless a fight is on)
			String name = p.getName().getString();
			String key = key(name);
			present.add(key);
			Track t = tracks.computeIfAbsent(key, k -> {
				Track created = new Track();
				created.inFight = fightActive;
				return created;
			});
			t.name = name;
			t.lastSeen = now;
			t.minDistance = Math.min(t.minDistance, distance);
			String[] slots = new String[SLOTS];
			Kind kind = Kind.NONE;
			for (int i = 0; i < SLOTS; i++) {
				ItemStack stack = p.getEquippedStack(ORDER[i]);
				slots[i] = stack.isEmpty() ? "-" : stack.getName().getString();
				if (ORDER[i] == EquipmentSlot.CHEST) kind = PvpCategory.isWornJetpack(stack) ? Kind.JETPACK : PvpCategory.isWingsuit(stack) ? Kind.WINGSUIT : Kind.NONE;
			}
			Look last = t.looks.peekLast();
			if (last == null || last.kind() != kind || !Arrays.equals(last.slots(), slots)) {
				t.looks.addLast(new Look(now, slots, kind));
				if (t.inFight) {
					if (t.startSlots == null) t.startSlots = slots;
					gather(t, slots, kind);
				}
			}
			if (t.kind != null && t.kind != kind) t.swaps.addLast(new Swap(now, t.kind, kind));
			t.kind = kind;
			PvpCategory type = windowType(t, now);
			if (type != t.type) {
				t.type = type;
				t.types.addLast(new TypeChange(now, type));
			}
		}
		// Gone or out of range: no data (unless a fight is on - they may be the opponent).
		for (Iterator<Map.Entry<String, Track>> it = tracks.entrySet().iterator(); it.hasNext();) {
			Map.Entry<String, Track> entry = it.next();
			Track t = entry.getValue();
			if (!present.contains(entry.getKey()) && !t.inFight) {
				it.remove();
			} else if (!t.inFight) {
				prune(t, now);
			}
		}
	}

	/** What a player's slots hold, added to what has been seen on them this fight. */
	private static void gather(Track t, String[] slots, Kind kind) {
		if (kind == Kind.JETPACK) t.jetpack = true;
		if (kind == Kind.WINGSUIT) t.wingsuit = true;
		if (!slots[0].equals("-")) t.heads.add(slots[0]);
		if (!slots[1].equals("-")) t.chests.add(slots[1]);
		if (!slots[2].equals("-")) t.legs.add(slots[2]);
		if (!slots[3].equals("-")) t.feet.add(slots[3]);
		t.lastSlots = slots;
	}

	/** The PvP type of the last KEEP_NANOS: a jetpack and a wingsuit both worn = Air, one of them = JP / Wing, neither = Ground. */
	private static PvpCategory windowType(Track t, long now) {
		boolean jetpack = false, wingsuit = false;
		Look[] looks = t.looks.toArray(new Look[0]);
		for (int i = 0; i < looks.length; i++) {
			long end = i + 1 < looks.length ? looks[i + 1].nanos() : now;
			if (end < now - KEEP_NANOS) continue; // ended before the window
			if (looks[i].kind() == Kind.JETPACK) jetpack = true;
			if (looks[i].kind() == Kind.WINGSUIT) wingsuit = true;
		}
		if (jetpack && wingsuit) return PvpCategory.AIR;
		if (jetpack) return PvpCategory.JP;
		if (wingsuit) return PvpCategory.WING;
		return PvpCategory.GROUND;
	}

	/** Drops what is older than the window; the look that was current when the window began stays. */
	private static void prune(Track t, long now) {
		long from = now - KEEP_NANOS;
		while (t.looks.size() > 1) {
			Iterator<Look> it = t.looks.iterator();
			it.next();
			if (it.next().nanos() > from) break;
			t.looks.removeFirst();
		}
		while (!t.swaps.isEmpty() && t.swaps.peekFirst().nanos() < from) t.swaps.removeFirst();
		while (t.types.size() > 1) {
			Iterator<TypeChange> it = t.types.iterator();
			it.next();
			if (it.next().nanos() > from) break;
			t.types.removeFirst();
		}
	}

	/** A fight began: the saved windows of everyone in range join it, and they are watched until it ends. */
	public void onFightStart() {
		long now = System.nanoTime();
		fightActive = true;
		fightStartNanos = now;
		for (Track t : tracks.values()) {
			prune(t, now);
			t.inFight = true;
			t.jetpack = t.wingsuit = false;
			t.chests.clear();
			t.heads.clear();
			t.legs.clear();
			t.feet.clear();
			for (Look look : t.looks) gather(t, look.slots(), look.kind());
			Look latest = t.looks.peekLast();
			t.startSlots = latest != null ? latest.slots() : new String[SLOTS];
			t.lastSlots = t.startSlots;
		}
	}

	/** The fight was dropped (no kill or death): back to the plain window. */
	public void onFightDrop() {
		endFight();
	}

	private void endFight() {
		fightActive = false;
		for (Track t : tracks.values()) t.inFight = false;
	}

	/**
	 * The fight ended against this opponent (null when unknown): their class, gear and timeline, or null if they were never seen. With no
	 * name, the only player seen during the fight counts as the opponent.
	 */
	public OpponentGear onFightEnd(String opponent) {
		long now = System.nanoTime();
		Track opp = opponent != null ? tracks.get(key(opponent)) : null;
		if (opp != null && !opp.inFight) opp = null;
		if (opp == null && opponent == null) {
			Track only = null;
			int count = 0;
			for (Track t : tracks.values()) {
				if (t.inFight) {
					only = t;
					count++;
				}
			}
			if (count == 1) opp = only;
		}
		OpponentGear result = null;
		if (opp != null && opp.lastSlots != null) {
			if (opp.startSlots == null) opp.startSlots = opp.lastSlots;
			PvpCategory type = opp.jetpack && opp.wingsuit ? PvpCategory.AIR : opp.jetpack ? PvpCategory.JP : opp.wingsuit ? PvpCategory.WING : PvpCategory.GROUND;
			String json = trackJson(opp, now);
			String summary = opp.swaps.size() + " swaps, closest " + String.format(Locale.ROOT, "%.1f", opp.minDistance) + " blocks";
			result = new OpponentGear(type.name(), opp.startSlots, opp.lastSlots, new ArrayList<>(opp.chests), new ArrayList<>(opp.heads),
					new ArrayList<>(opp.legs), new ArrayList<>(opp.feet), json, summary);
			// Others in range, nearest first: name, type, closest distance.
			List<Track> others = new ArrayList<>();
			for (Track t : tracks.values()) if (t != opp && t.inFight && t.type != null) others.add(t);
			others.sort((a, b) -> Double.compare(a.minDistance, b.minDistance));
			com.google.gson.JsonObject parsed = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
			com.google.gson.JsonArray near = new com.google.gson.JsonArray();
			for (int i = 0; i < others.size() && i < MAX_OTHERS; i++) {
				Track t = others.get(i);
				com.google.gson.JsonArray one = new com.google.gson.JsonArray();
				one.add(t.name);
				one.add(t.type.name());
				one.add(Math.round(t.minDistance * 10.0) / 10.0);
				near.add(one);
			}
			if (near.size() > 0) {
				parsed.add("near", near);
				result = new OpponentGear(result.category(), result.atStart(), result.atEnd(), result.chests(), result.heads(), result.legs(),
						result.feet(), parsed.toString(), summary);
			}
		}
		endFight();
		for (Track t : tracks.values()) prune(t, now);
		return result;
	}

	/**
	 * The opponent's timeline as short JSON: d closest distance, pre ms of data from before the fight (up to 10 s), t0 the type when the
	 * fight began, types [[ms from the fight start, TYPE]...], swaps [[ms, "J>W"]...] (J jetpack, W wingsuit, N neither), ms time per type.
	 * Negative times are before the fight started. Each list is cut to MAX_LIST entries, and further if the text is too long.
	 */
	private String trackJson(Track t, long now) {
		long windowStart = Math.max(t.looks.isEmpty() ? now : t.looks.peekFirst().nanos(), fightStartNanos - KEEP_NANOS);
		Map<PvpCategory, Long> time = new EnumMap<>(PvpCategory.class);
		List<TypeChange> changes = new ArrayList<>(t.types);
		for (int i = 0; i < changes.size(); i++) {
			long from = Math.max(changes.get(i).nanos(), windowStart);
			long to = i + 1 < changes.size() ? changes.get(i + 1).nanos() : now;
			if (to > from) time.merge(changes.get(i).type(), to - from, Long::sum);
		}
		PvpCategory atStart = null;
		for (TypeChange c : changes) if (c.nanos() <= fightStartNanos) atStart = c.type();
		int limit = MAX_LIST;
		String text;
		do {
			com.google.gson.JsonObject o = new com.google.gson.JsonObject();
			o.addProperty("d", Math.round(t.minDistance * 10.0) / 10.0);
			o.addProperty("pre", Math.max(0L, Math.min(KEEP_NANOS, fightStartNanos - windowStart)) / 1_000_000L);
			if (atStart != null) o.addProperty("t0", atStart.name());
			com.google.gson.JsonArray typeArray = new com.google.gson.JsonArray();
			for (int i = 0; i < changes.size() && i < limit; i++) typeArray.add(pair(changes.get(i).nanos(), changes.get(i).type().name()));
			o.add("types", typeArray);
			com.google.gson.JsonArray swapArray = new com.google.gson.JsonArray();
			int n = 0;
			for (Swap s : t.swaps) {
				if (n++ >= limit) break;
				swapArray.add(pair(s.nanos(), s.from().code + ">" + s.to().code));
			}
			o.add("swaps", swapArray);
			com.google.gson.JsonObject ms = new com.google.gson.JsonObject();
			for (Map.Entry<PvpCategory, Long> e : time.entrySet()) ms.addProperty(e.getKey().name(), e.getValue() / 1_000_000L);
			o.add("ms", ms);
			text = o.toString();
			limit = limit > 4 ? limit / 2 : 0;
		} while (text.length() > MAX_JSON && limit > 0);
		return text;
	}

	private com.google.gson.JsonArray pair(long nanos, String value) {
		com.google.gson.JsonArray a = new com.google.gson.JsonArray();
		a.add((nanos - fightStartNanos) / 1_000_000L);
		a.add(value);
		return a;
	}
}
