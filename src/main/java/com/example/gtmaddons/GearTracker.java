package com.example.gtmaddons;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What the players around you are wearing, always: every SCAN_NANOS each loaded player's five slots (head, chest, legs, feet,
 * main hand) are noted and kept for KEEP_NANOS, then dropped. When a fight starts (FightTracker) that history is copied
 * out - each player's gear at that moment, and whether a jetpack and/or wingsuit was worn at any time in those seconds - and
 * tracking goes on until the fight ends, so someone who swaps between a jetpack and a wingsuit mid-fight is seen doing both.
 * When it ends, onFightEnd(opponent) classifies that opponent from everything seen on them (see classify) and hands back their
 * gear. Only the chest slot decides the class: the hotbar of other players is never sent to us.
 */
public final class GearTracker {

	public static final GearTracker INSTANCE = new GearTracker();

	private static final long SCAN_NANOS = 250_000_000L;
	private static final long KEEP_NANOS = 20_000_000_000L;
	private static final int SLOTS = 5;
	private static final EquipmentSlot[] ORDER = { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND };

	/** One look at a player: the five item names ("-" for empty) and whether the chest item is a jetpack / wingsuit. */
	private record Sample(long nanos, String[] slots, boolean jetpack, boolean wingsuit) {}

	/** Everything seen on one player since the fight started (and the 20 s before it). */
	private static final class Sighting {
		String name;
		String[] startSlots;
		String[] lastSlots;
		boolean jetpack, wingsuit;
		final Set<String> chests = new LinkedHashSet<>();
		/** Every helmet, pair of pants and pair of boots seen (not used yet; kept for later). */
		final Set<String> heads = new LinkedHashSet<>(), legs = new LinkedHashSet<>(), feet = new LinkedHashSet<>();
	}

	/** An opponent as classified at the end of a fight. category is a PvpCategory name. */
	public record OpponentGear(String category, String[] atStart, String[] atEnd, List<String> chests,
			List<String> heads, List<String> legs, List<String> feet) {
		/** Short JSON text for the backend: { "start": [...], "end": [...], "chests": [...] }. */
		public String json() {
			com.google.gson.JsonObject o = new com.google.gson.JsonObject();
			com.google.gson.JsonArray start = new com.google.gson.JsonArray(), end = new com.google.gson.JsonArray(), chestList = new com.google.gson.JsonArray();
			for (String s : atStart) start.add(s);
			for (String s : atEnd) end.add(s);
			for (String s : chests) chestList.add(s);
			o.add("start", start);
			o.add("end", end);
			o.add("chests", chestList);
			o.add("heads", strings(heads));
			o.add("legs", strings(legs));
			o.add("feet", strings(feet));
			return o.toString();
		}

		private static com.google.gson.JsonArray strings(List<String> list) {
			com.google.gson.JsonArray array = new com.google.gson.JsonArray();
			for (String s : list) array.add(s);
			return array;
		}

		/** "Air (chest seen: Jetpack, Wingsuit)". */
		public String describe() {
			return category + " (chest seen: " + (chests.isEmpty() ? "nothing" : String.join(", ", chests)) + ")";
		}
	}

	private final Map<String, Deque<Sample>> history = new HashMap<>();
	private final Map<String, String> names = new HashMap<>();
	/** Null outside a fight. */
	private Map<String, Sighting> fight = null;
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
			history.clear();
			names.clear();
			return;
		}
		Set<String> present = new HashSet<>();
		for (PlayerEntity p : client.world.getPlayers()) {
			if (p == me) continue;
			String name = p.getName().getString();
			String key = key(name);
			present.add(key);
			names.put(key, name);
			String[] slots = new String[SLOTS];
			boolean jetpack = false, wingsuit = false;
			for (int i = 0; i < SLOTS; i++) {
				ItemStack stack = p.getEquippedStack(ORDER[i]);
				slots[i] = stack.isEmpty() ? "-" : stack.getName().getString();
				if (ORDER[i] == EquipmentSlot.CHEST) {
					jetpack = PvpCategory.isWornJetpack(stack);
					wingsuit = PvpCategory.isWingsuit(stack);
				}
			}
			Sample sample = new Sample(now, slots, jetpack, wingsuit);
			Deque<Sample> samples = history.computeIfAbsent(key, k -> new ArrayDeque<>());
			samples.addLast(sample);
			if (fight != null) {
				Sighting s = fight.get(key);
				if (s == null) {
					s = new Sighting();
					s.name = name;
					s.startSlots = slots;
					fight.put(key, s);
				}
				update(s, sample);
			}
		}
		// The 20 second window: drop older looks, and players gone for that long.
		for (java.util.Iterator<Map.Entry<String, Deque<Sample>>> it = history.entrySet().iterator(); it.hasNext();) {
			Map.Entry<String, Deque<Sample>> entry = it.next();
			Deque<Sample> samples = entry.getValue();
			while (!samples.isEmpty() && now - samples.peekFirst().nanos() > KEEP_NANOS) samples.removeFirst();
			if (samples.isEmpty()) {
				it.remove();
				if (!present.contains(entry.getKey())) names.remove(entry.getKey());
			}
		}
	}

	private static void update(Sighting s, Sample sample) {
		s.jetpack |= sample.jetpack();
		s.wingsuit |= sample.wingsuit();
		s.lastSlots = sample.slots();
		if (!sample.slots()[1].equals("-")) s.chests.add(sample.slots()[1]);
		if (!sample.slots()[0].equals("-")) s.heads.add(sample.slots()[0]);
		if (!sample.slots()[2].equals("-")) s.legs.add(sample.slots()[2]);
		if (!sample.slots()[3].equals("-")) s.feet.add(sample.slots()[3]);
	}

	/** A fight began: save the last 20 seconds of every player nearby and keep watching them. */
	public void onFightStart() {
		fight = new HashMap<>();
		for (Map.Entry<String, Deque<Sample>> entry : history.entrySet()) {
			Deque<Sample> samples = entry.getValue();
			if (samples.isEmpty()) continue;
			Sighting s = new Sighting();
			s.name = names.getOrDefault(entry.getKey(), entry.getKey());
			s.startSlots = samples.peekLast().slots();
			for (Sample sample : samples) update(s, sample);
			fight.put(entry.getKey(), s);
		}
	}

	/** The fight was dropped (no kill or death): forget what was saved. */
	public void onFightDrop() {
		fight = null;
	}

	/**
	 * The fight ended against this opponent (null when unknown): their class and gear, or null if they were never seen. With no name,
	 * the only player seen during the fight counts as the opponent.
	 */
	public OpponentGear onFightEnd(String opponent) {
		Map<String, Sighting> seen = fight;
		fight = null;
		if (seen == null) return null;
		Sighting s = opponent != null ? seen.get(key(opponent)) : null;
		if (s == null && opponent == null && seen.size() == 1) s = seen.values().iterator().next();
		if (s == null || s.lastSlots == null) return null;
		return new OpponentGear(classify(s).name(), s.startSlots, s.lastSlots, new ArrayList<>(s.chests),
				new ArrayList<>(s.heads), new ArrayList<>(s.legs), new ArrayList<>(s.feet));
	}

	/** Both a jetpack and a wingsuit worn at some point = Air; one of them = JP / Wing; neither = Ground. */
	private static PvpCategory classify(Sighting s) {
		if (s.jetpack && s.wingsuit) return PvpCategory.AIR;
		if (s.jetpack) return PvpCategory.JP;
		if (s.wingsuit) return PvpCategory.WING;
		return PvpCategory.GROUND;
	}
}
