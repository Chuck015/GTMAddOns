package com.example.gtmaddons;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The four kinds of PvP on GTM, decided by what you're carrying and wearing
 * at that moment. Checked in this order:
 *   AIR    - carrying both a jetpack and a wingsuit (worn or anywhere in
 *            your inventory), i.e. swapping between the two
 *   JP     - wearing a jetpack, or a jetpack in your hotbar with an empty
 *            chestplate slot
 *   WING   - wearing a wingsuit, or a wingsuit in your hotbar with an empty
 *            chestplate slot
 *   GROUND - anything else (e.g. a chestplate on, no jetpack or wingsuit)
 *
 * During a combat tag, jetpacks/wingsuits picked up mid-fight are ignored.
 */
public enum PvpCategory {
	GROUND("Ground"),
	WING("Wing"),
	JP("JP"),
	AIR("Air");

	public static final int CHEST_INVENTORY_INDEX = EquipmentSlot.CHEST.getOffsetEntitySlotId(PlayerInventory.MAIN_SIZE);
	private static final int HOTBAR_SIZE = 9;

	public final String label;

	PvpCategory(String label) {
		this.label = label;
	}

	/**
	 * While combat-tagged, only jetpacks/wingsuits you had when the tag
	 * started count (see CombatTracker) - one picked up from a kill mid-fight
	 * can't change your category.
	 */
	public static PvpCategory classify(PlayerEntity player) {
		PlayerInventory inventory = player.getInventory();
		ItemStack chest = inventory.getStack(CHEST_INVENTORY_INDEX);

		boolean hasJetpack = isWornJetpack(chest), hasWingsuit = false, jetpackInHotbar = false, wingsuitInHotbar = false;
		for (int i = 0; i < inventory.size(); i++) {
			ItemStack stack = inventory.getStack(i);
			if (isJetpack(stack)) {
				hasJetpack = true;
				if (i < HOTBAR_SIZE) jetpackInHotbar = true;
			}
			if (isWingsuit(stack)) {
				hasWingsuit = true;
				if (i < HOTBAR_SIZE) wingsuitInHotbar = true;
			}
		}

		CombatTracker combat = CombatTracker.INSTANCE;
		boolean jetpackAllowed = !combat.isTagged() || combat.jetpacksAtStart() > 0;
		boolean wingsuitAllowed = !combat.isTagged() || combat.wingsuitsAtStart() > 0;
		hasJetpack &= jetpackAllowed;
		hasWingsuit &= wingsuitAllowed;

		if (hasJetpack && hasWingsuit) return AIR;
		if (jetpackAllowed && (isWornJetpack(chest) || (jetpackInHotbar && chest.isEmpty()))) return JP;
		if (wingsuitAllowed && (isWingsuit(chest) || (wingsuitInHotbar && chest.isEmpty()))) return WING;
		return GROUND;
	}

	/**
	 * What the category is based on, for dev mode: every jetpack and
	 * wingsuit found and where, e.g. jetpack "Jetpack" (hotbar 3),
	 * wingsuit "Wingsuit" (worn), and whether a combat tag is limiting them.
	 */
	public static String explain(PlayerEntity player) {
		PlayerInventory inventory = player.getInventory();
		List<String> found = new ArrayList<>();
		for (int i = 0; i < inventory.size(); i++) {
			ItemStack stack = inventory.getStack(i);
			boolean worn = i == CHEST_INVENTORY_INDEX;
			String kind = isJetpack(stack) || (worn && isWornJetpack(stack)) ? "jetpack"
					: isWingsuit(stack) ? "wingsuit"
					: hasJetpackName(stack) ? "ignored (named jetpack but not wearable)" : null;
			if (kind == null) continue;
			String where = i == CHEST_INVENTORY_INDEX ? "worn" : i < HOTBAR_SIZE ? "hotbar " + (i + 1)
					: i < PlayerInventory.MAIN_SIZE ? "inventory" : "slot " + i;
			found.add(kind + " \"" + stack.getName().getString() + "\" (" + where + ")");
		}
		CombatTracker combat = CombatTracker.INSTANCE;
		String tag = combat.isTagged()
				? String.format(" | combat-tagged, counting %d jetpack(s) and %d wingsuit(s) from the tag start",
						combat.jetpacksAtStart(), combat.wingsuitsAtStart())
				: "";
		return (found.isEmpty() ? "no jetpack or wingsuit" : String.join(", ", found)) + tag;
	}

	/** Parses a stored/uploaded name, or null if it isn't one of the four. */
	public static PvpCategory fromName(String name) {
		for (PvpCategory category : values()) {
			if (category.name().equals(name)) return category;
		}
		return null;
	}

	/**
	 * A jetpack: "jetpack" in its name, and something you can wear in the
	 * chest slot (or glide with). Name alone isn't enough - other GTM items
	 * with "jetpack" in their name were making wingsuit-only fights count as
	 * Air. Whatever you're actually wearing is checked by name only (see
	 * isWornJetpack), in case GTM's jetpack is worn some other way.
	 */
	public static boolean isJetpack(ItemStack stack) {
		if (!hasJetpackName(stack)) return false;
		EquippableComponent equippable = stack.get(DataComponentTypes.EQUIPPABLE);
		return (equippable != null && equippable.slot() == EquipmentSlot.CHEST) || stack.contains(DataComponentTypes.GLIDER);
	}

	/** The item in your chest slot is a jetpack if it's named like one. */
	public static boolean isWornJetpack(ItemStack chest) {
		return hasJetpackName(chest);
	}

	/** "Jetpack" in the name - but not "Jetpack Fuel", which every GTM player always carries. */
	private static boolean hasJetpackName(ItemStack stack) {
		if (stack.isEmpty()) return false;
		String name = stack.getName().getString().toLowerCase(Locale.ROOT);
		return name.contains("jetpack") && !name.contains("fuel");
	}

	/**
	 * A wingsuit is anything that lets you glide - the elytra, plus any item
	 * a server has given the glider component - except jetpacks, in case
	 * the server builds those on gliding items too.
	 */
	public static boolean isWingsuit(ItemStack stack) {
		return !stack.isEmpty() && stack.contains(DataComponentTypes.GLIDER) && !isJetpack(stack);
	}
}
