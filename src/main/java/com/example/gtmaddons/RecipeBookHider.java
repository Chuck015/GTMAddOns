package com.example.gtmaddons;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;

/**
 * The "Hide recipes" setting: in the survival inventory the recipe book still opens and closes as normal, but while it is open its panel
 * (tabs, search box, recipes), tooltips and clicks are switched off (see RecipeBookMixin). Crafting tables are left alone.
 */
public final class RecipeBookHider {

	private static Settings settings;

	private RecipeBookHider() {}

	public static void init(Settings modSettings) {
		settings = modSettings;
	}

	/** True while the survival inventory is the open screen and the setting is on: the recipe book's contents are hidden. */
	public static boolean hides() {
		return settings != null && settings.hideRecipeBook && MinecraftClient.getInstance().currentScreen instanceof InventoryScreen;
	}
}
