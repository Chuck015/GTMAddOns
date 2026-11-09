package com.example.gtmaddons;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Locale;

/**
 * The "Dropped item size" mod (QOL): items lying on the ground are drawn bigger, by the size on its ⚙ page (100-400%). Built like
 * Dropped item names: with "Only listed items" on (the default) only items whose name contains one of its item filter's names
 * (ignoring case and colours) are resized; with it off every dropped item is. Only the picture changes: where the item is, its
 * hitbox and picking it up are untouched (drawn by DroppedItemLabelMixin, which scales the item before vanilla draws it).
 */
public final class DroppedItemSizes {

	private static Settings settings;

	private DroppedItemSizes() {}

	public static void init(Settings modSettings) {
		settings = modSettings;
	}

	/** How big to draw this item: 1 when the mod is off or the item is not one to resize. */
	public static float sizeFor(ItemEntity item) {
		if (settings == null || !settings.droppedItemSize || settings.droppedItemSizePercent == 100) return 1f;
		ItemStack stack = item.getItem();
		if (stack.isEmpty()) return 1f;
		if (settings.droppedItemSizeFilterOn && !matches(settings.droppedItemSizeFilter, stack)) return 1f;
		return settings.droppedItemSizePercent / 100f;
	}

	/** Whether the item's name contains one of these names, ignoring case and colours (shared with Dropped item names). */
	static boolean matches(List<String> names, ItemStack stack) {
		String name = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
		for (String listed : names) {
			if (!listed.isBlank() && name.contains(listed.toLowerCase(Locale.ROOT))) return true;
		}
		return false;
	}
}
