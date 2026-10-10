package com.example.gtmaddons.gun;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.Formatting;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dev / QA mode, PVE tab: the ingredient price checker and the business information catcher (see DevFilter.PRICES and BUSINESS).
 * Everything found goes to gtmaddons-pve.log next to the dev log (dev mode only: QA mode just shows chat lines).
 */
final class PveLogger {

	private final DevLogger log;

	PveLogger(DevLogger log) {
		this.log = log;
	}

	// ---- Ingredient prices ----

	/** The ingredients, as the player names them: label and the pattern that finds them in a message (any case). */
	private static final String[][] INGREDIENTS = {
			{ "Common cotton seeds", "(?<![a-z])common cotton seed" },
			{ "Uncommon cotton seeds", "uncommon cotton seed" },
			{ "Rare cotton seeds", "(?<![a-z])rare cotton seed" },
			{ "Legendary cotton seeds", "legendary cotton seed" },
			{ "Watering can", "watering can" },
			{ "Pebbles", "pebble" },
			{ "Green dye", "green dye" },
			{ "Detergent", "deterg[ea]nt" },
			{ "Batteries", "batter(?:y|ies|ys)" },
	};
	private static final Pattern[] INGREDIENT_PATTERNS = new Pattern[INGREDIENTS.length];
	static {
		for (int i = 0; i < INGREDIENTS.length; i++) INGREDIENT_PATTERNS[i] = Pattern.compile(INGREDIENTS[i][1], Pattern.CASE_INSENSITIVE);
	}
	/** A dollar amount: $1,250  $1.5k  $2m. */
	private static final Pattern MONEY = Pattern.compile("\\$\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kKmMbB])?");
	/** An amount: "x5", "5x", "5 rare cotton seeds". */
	private static final Pattern QUANTITY = Pattern.compile("(?:\\bx\\s*(\\d+)\\b)|(?:\\b(\\d+)\\s*x\\b)|(?:\\b(\\d+)\\s+(?:uncommon|common|rare|legendary|watering|pebble|green|deterg|batter))", Pattern.CASE_INSENSITIVE);

	/** True while a chat line of ours is being sent (the line re-enters onChat through the chat event). */
	private boolean printing = false;

	/** Per ingredient this session: how many prices were seen, their sum, lowest and highest. */
	private final Map<String, double[]> seen = new LinkedHashMap<>();

	/** A chat message: if it names an ingredient, log it and, when it has a price, show it. */
	void onChat(String message) {
		String plain = clean(message);
		// Our own chat lines ("[Dev] Price - ...") come back through the same chat event: never read them, or each one would print another.
		if (printing || plain.startsWith("[Dev]") || plain.startsWith("[QA]")) return;
		for (int i = 0; i < INGREDIENTS.length; i++) {
			if (!INGREDIENT_PATTERNS[i].matcher(plain).find()) continue;
			String label = INGREDIENTS[i][0];
			List<Double> prices = new ArrayList<>();
			Matcher money = MONEY.matcher(plain);
			while (money.find()) prices.add(amount(money.group(1), money.group(2)));
			int quantity = quantity(plain);
			if (prices.isEmpty()) {
				log.writePve("PRICE  " + label + "  (no price in the message)", List.of("message: " + plain));
				continue;
			}
			double price = prices.get(0);
			double each = quantity > 1 ? price / quantity : price;
			double[] stats = seen.computeIfAbsent(label, k -> new double[] { 0, 0, Double.MAX_VALUE, 0 });
			stats[0]++;
			stats[1] += each;
			stats[2] = Math.min(stats[2], each);
			stats[3] = Math.max(stats[3], each);
			String line = String.format(Locale.ROOT, "%s: %s%s | seen %d, average %s, lowest %s, highest %s", label, money(price),
					quantity > 1 ? " for " + quantity + " (" + money(each) + " each)" : "", (long) stats[0], money(stats[1] / stats[0]), money(stats[2]), money(stats[3]));
			printing = true;
			try {
				DevLogger.chat("Price - " + line, Formatting.GOLD);
			} finally {
				printing = false;
			}
			List<String> lines = new ArrayList<>();
			lines.add("message: " + plain);
			lines.add("amounts found: " + prices + (quantity > 1 ? "  quantity: " + quantity : ""));
			lines.add("this session: " + line);
			log.writePve("PRICE  " + label, lines);
		}
	}

	private static double amount(String digits, String suffix) {
		double value = Double.parseDouble(digits.replace(",", ""));
		if (suffix == null) return value;
		return switch (Character.toLowerCase(suffix.charAt(0))) {
			case 'k' -> value * 1_000;
			case 'm' -> value * 1_000_000;
			case 'b' -> value * 1_000_000_000;
			default -> value;
		};
	}

	private static int quantity(String text) {
		Matcher m = QUANTITY.matcher(text);
		if (!m.find()) return 1;
		for (int g = 1; g <= 3; g++) if (m.group(g) != null) return Math.max(1, Integer.parseInt(m.group(g)));
		return 1;
	}

	private static String money(double value) {
		return value == Math.rint(value) ? String.format(Locale.ROOT, "$%,d", (long) value) : String.format(Locale.ROOT, "$%,.2f", value);
	}

	// ---- Business information ----

	/** The GUI titles worth reading (any case; "dyeing" counts too, and "soap machine" still counts as the soap dispenser). */
	private static final String[][] MACHINES = {
			{ "Pressing machine", "pressing machine" }, { "Dying machine", "dy(?:e)?ing machine" }, { "Cutting machine", "cutting machine" },
			{ "Washing machine", "washing machine" }, { "Pot machine", "pot machine" }, { "Vacuum", "vacuum" }, { "Soap dispenser", "soap (?:dispenser|machine)" },
	};
	private static final int TOP_MIDDLE = 4;
	private static final long SETTLE_NANOS = 400_000_000L, MIN_GAP_NANOS = 2_000_000_000L;
	private static final int MAX_WRITES = 12;

	private Object target = null;
	private String machine, title;
	private String lastSignature = "";
	private long changedAt = 0L, wroteAt = 0L;
	private boolean pending = false;
	private int writes = 0;

	/** A screen was opened (or resized): start reading it if it is one of the machines. */
	void onScreenOpened(MinecraftClient client, Screen screen) {
		if (screen == target) return;
		target = null;
		if (!(screen instanceof HandledScreen<?>) || screen instanceof InventoryScreen || screen instanceof CreativeInventoryScreen) return;
		String name = clean(screen.getTitle().getString());
		for (String[] m : MACHINES) {
			if (Pattern.compile(m[1], Pattern.CASE_INSENSITIVE).matcher(name).find()) {
				machine = m[0];
				title = name;
				target = screen;
				lastSignature = "";
				changedAt = System.nanoTime();
				wroteAt = 0L;
				pending = false;
				writes = 0;
				return;
			}
		}
	}

	/** Every frame while a machine GUI is open: once its contents stop changing for a moment, write them down. */
	void onFrame(MinecraftClient client) {
		if (target == null) return;
		if (client.currentScreen != target || client.player == null) {
			target = null;
			return;
		}
		HandledScreen<?> screen = (HandledScreen<?>) target;
		List<ItemStack> items = contents(client, screen);
		StringBuilder signature = new StringBuilder();
		boolean any = false;
		for (ItemStack stack : items) {
			if (stack.isEmpty()) {
				signature.append('-').append(';');
			} else {
				any = true;
				signature.append(line(stack)).append(';');
			}
		}
		if (!any) return; // the server has not filled it yet
		long now = System.nanoTime();
		if (!signature.toString().equals(lastSignature)) {
			lastSignature = signature.toString();
			changedAt = now;
			pending = true;
			return;
		}
		if (!pending || now - changedAt < SETTLE_NANOS || (wroteAt != 0L && now - wroteAt < MIN_GAP_NANOS) || writes >= MAX_WRITES) return;
		pending = false;
		wroteAt = now;
		writes++;
		write(items);
	}

	/** The GUI's own slots in order (the player's inventory slots are left out); the position is the slot number. */
	private List<ItemStack> contents(MinecraftClient client, HandledScreen<?> screen) {
		List<ItemStack> items = new ArrayList<>();
		for (Slot slot : screen.getScreenHandler().slots) {
			if (slot.inventory == client.player.getInventory()) continue;
			items.add(slot.getStack());
		}
		return items;
	}

	private void write(List<ItemStack> items) {
		List<String> lines = new ArrayList<>();
		lines.add("opened as: " + machine + "   title: \"" + title + "\"   slots: " + items.size() + "   read at " + LocalTime.now().withNano(0) + "   (reading " + writes + ")");
		lines.add("");
		ItemStack book = items.size() > TOP_MIDDLE ? items.get(TOP_MIDDLE) : ItemStack.EMPTY;
		lines.add("THE BOOK (top middle, slot " + TOP_MIDDLE + "):");
		if (book.isEmpty()) {
			lines.add("  (nothing in that slot)");
		} else {
			lines.add("  " + line(book));
			for (String l : loreLines(book)) lines.add("      lore: " + l);
			lines.add("      all data: " + full(book));
		}
		lines.add("");
		lines.add("EVERYTHING ELSE:");
		int shown = 0;
		for (int i = 0; i < items.size(); i++) {
			ItemStack stack = items.get(i);
			if (stack.isEmpty() || i == TOP_MIDDLE) continue;
			shown++;
			lines.add("  [" + i + "]  " + line(stack));
			for (String l : loreLines(stack)) lines.add("        lore: " + l);
			String all = full(stack);
			if (!all.isEmpty()) lines.add("        all data: " + all);
		}
		if (shown == 0) lines.add("  (no other items)");
		log.writePve("BUSINESS  " + machine, lines);
		DevLogger.chat("Business - read the " + machine + " (" + (items.size() - countEmpty(items)) + " items, book: "
				+ (book.isEmpty() ? "none" : clean(book.getName().getString())) + ")" + (log.isSavingLog() ? " - saved to gtmaddons-pve.log" : " (QA mode - not saved)"), Formatting.AQUA);
	}

	private static int countEmpty(List<ItemStack> items) {
		int n = 0;
		for (ItemStack s : items) if (s.isEmpty()) n++;
		return n;
	}

	// ---- Reading an item ----

	/** One line: id, count, name and the first lines of lore. */
	private static String line(ItemStack stack) {
		StringBuilder sb = new StringBuilder();
		sb.append(Registries.ITEM.getId(stack.getItem())).append(" x").append(stack.getCount()).append("  name=\"").append(clean(stack.getName().getString())).append('"');
		List<String> lore = loreLines(stack);
		if (!lore.isEmpty()) sb.append("  lore=[").append(String.join(" | ", lore)).append(']');
		return sb.toString();
	}

	/** The item's lore lines, formatting removed. */
	private static List<String> loreLines(ItemStack stack) {
		List<String> out = new ArrayList<>();
		LoreComponent lore = stack.get(DataComponentTypes.LORE);
		if (lore != null) for (net.minecraft.text.Text t : lore.lines()) out.add(clean(t.getString()));
		return out;
	}

	/** Everything the item carries (every data component), cut at 4000 characters. */
	private static String full(ItemStack stack) {
		StringBuilder sb = new StringBuilder();
		for (var component : stack.getComponents()) {
			if (sb.length() > 0) sb.append(", ");
			sb.append(component.type()).append('=').append(component.value());
		}
		return sb.length() > 4000 ? sb.substring(0, 4000) + "..." : sb.toString();
	}

	/** Text without formatting codes, on one line. */
	static String clean(String text) {
		return text.replaceAll("\u00A7.", "").replace('\n', ' ').trim();
	}
}
