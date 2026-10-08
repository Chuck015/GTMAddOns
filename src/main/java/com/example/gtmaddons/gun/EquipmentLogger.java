package com.example.gtmaddons.gun;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Dev / QA filter "Equipment": what every player the client has loaded wears and holds, as the server sends it. Whenever a
 * player first shows up, or the item in one of their five slots changes, the full detail of that slot goes to the dev log: item id,
 * name, count, durability, and everything the item carries (every data component: custom name, lore, model data, attribute modifiers, enchantments, glider, equippable...). The held weapon's ammo counter (the numbers in
 * the name) doesn't count as a change. Your own gear is logged too, with your whole inventory (only yours: the server never sends
 * anyone else's inventory, just the slots shown on their body).
 * It also tracks how far away players are loaded: the farthest seen so far, and the last distance of each player who disappeared,
 * which is the range the server actually sends (the "RANGE" lines).
 */
final class EquipmentLogger {

	private static final long SCAN_NANOS = 250_000_000L;
	/** Chat gets a short line for changes of players this close (the log gets everyone). */
	private static final double CHAT_RANGE = 40.0;
	private static final int SLOTS = 5;

	private final DevLogger log;
	private long lastScanNanos = 0L;
	/** Per player: the last signature of each of their five slots, their name and the last distance seen. */
	private final Map<UUID, String[]> seen = new HashMap<>();
	private final Map<UUID, String> names = new HashMap<>();
	private final Map<UUID, Double> lastDistance = new HashMap<>();
	private String inventorySignature = "";
	private double farthest = 0.0;

	EquipmentLogger(DevLogger log) {
		this.log = log;
	}

	private static final EquipmentSlot[] ORDER = { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND };
	private static final String[] LABEL = { "head", "chest", "legs", "feet", "mainhand" };

	void reset() {
		seen.clear();
		names.clear();
		lastDistance.clear();
		inventorySignature = "";
		farthest = 0.0;
		lastScanNanos = 0L;
	}

	void onFrame(MinecraftClient client) {
		long now = System.nanoTime();
		if (now - lastScanNanos < SCAN_NANOS) return;
		lastScanNanos = now;
		PlayerEntity me = client.player;
		if (client.world == null || me == null) {
			if (!seen.isEmpty()) reset();
			return;
		}
		java.util.Set<UUID> present = new java.util.HashSet<>();
		for (PlayerEntity p : client.world.getPlayers()) {
			UUID id = p.getUuid();
			present.add(id);
			boolean self = p == me;
			double distance = self ? 0.0 : me.distanceTo(p);
			String name = p.getName().getString();
			names.put(id, name);
			if (!self) {
				lastDistance.put(id, distance);
				if (distance > farthest + 1.0) {
					farthest = distance;
					log.writeBlock("RANGE", List.of(String.format("RANGE  farthest loaded player so far: %s at %.1f blocks", name, distance)));
				}
			}
			String[] previous = seen.get(id);
			String[] now6 = new String[SLOTS];
			List<String> changes = new ArrayList<>();
			List<String> detail = new ArrayList<>();
			for (int i = 0; i < SLOTS; i++) {
				ItemStack stack = p.getEquippedStack(ORDER[i]);
				now6[i] = signature(stack);
				String before = previous != null ? previous[i] : null;
				if (before == null || !before.equals(now6[i])) {
					changes.add(LABEL[i] + ": " + (before == null ? "" : shortName(before) + " -> ") + shortName(now6[i]));
					detail.add("  " + LABEL[i] + "  " + describe(stack));
				}
			}
			seen.put(id, now6);
			if (changes.isEmpty()) continue;
			List<String> lines = new ArrayList<>();
			lines.add(String.format("%s%s  %s  %s  armor=%d  health=%.1f  gliding=%s", name, self ? " (YOU)" : "",
					previous == null ? "FIRST SEEN" : "CHANGED", self ? "" : String.format("%.1f blocks away", distance),
					p.getArmor(), p.getHealth(), p.isGliding()));
			lines.addAll(detail);
			log.writeBlock("EQUIPMENT  " + name, lines);
			if (previous != null && (self || distance <= CHAT_RANGE)) {
				DevLogger.chat(name + (self ? " (you)" : "") + " now: " + String.join(", ", changes), Formatting.GRAY);
			}
		}
		// Players that disappeared: the last distance we saw them at is how far the server sends players.
		List<UUID> gone = new ArrayList<>();
		for (UUID id : seen.keySet()) if (!present.contains(id)) gone.add(id);
		for (UUID id : gone) {
			Double last = lastDistance.remove(id);
			String name = names.remove(id);
			seen.remove(id);
			if (last != null) log.writeBlock("RANGE", List.of(String.format("RANGE  %s dropped out of view; last seen %.1f blocks away", name, last)));
		}
		logInventory(me);
	}

	private void logInventory(PlayerEntity me) {
		StringBuilder sig = new StringBuilder();
		PlayerInventory inventory = me.getInventory();
		for (int i = 0; i < inventory.size(); i++) sig.append(i).append('=').append(signature(inventory.getStack(i))).append(';');
		String signature = sig.toString();
		if (signature.equals(inventorySignature)) return;
		inventorySignature = signature;
		List<String> lines = new ArrayList<>();
		lines.add("YOUR INVENTORY  (slot 0-8 hotbar, 9-35 main, 36-39 armor feet to head)");
		for (int i = 0; i < inventory.size(); i++) {
			ItemStack stack = inventory.getStack(i);
			if (i == 40) continue; // the off hand is not tracked
			if (!stack.isEmpty()) lines.add("  [" + i + "]  " + describe(stack));
		}
		log.writeBlock("EQUIPMENT  inventory", lines);
	}

	/** What counts as a change: the item and its name without the ammo counter. */
	private static String signature(ItemStack stack) {
		if (stack.isEmpty()) return "-";
		return itemId(stack) + " | " + stripCounter(stack.getName().getString());
	}

	private static String shortName(String signature) {
		int bar = signature.indexOf(" | ");
		return bar < 0 ? signature : signature.substring(bar + 3);
	}

	/** "Assault Rifle «22/548578»" -> "Assault Rifle": drops the ammo / serial in guillemets. */
	private static String stripCounter(String name) {
		return name.replaceAll("\\s*\u00AB[^\u00BB]*\u00BB", "").trim();
	}

	private static String itemId(ItemStack stack) {
		return Registries.ITEM.getId(stack.getItem()).toString();
	}

	/** Everything we can read from one item. */
	private static String describe(ItemStack stack) {
		if (stack.isEmpty()) return "(empty)";
		StringBuilder sb = new StringBuilder();
		sb.append(itemId(stack)).append(" x").append(stack.getCount());
		sb.append("  name=\"").append(stack.getName().getString()).append('"');
		if (stack.getMaxDamage() > 0) sb.append("  durability=").append(stack.getMaxDamage() - stack.getDamage()).append('/').append(stack.getMaxDamage());
		StringBuilder components = new StringBuilder();
		for (var component : stack.getComponents()) {
			if (components.length() > 0) components.append(", ");
			components.append(component.type()).append('=').append(component.value());
		}
		if (components.length() > 0) sb.append("  components{").append(components).append('}');
		return sb.toString();
	}
}
