package com.example.gtmaddons;

import java.util.Locale;

/**
 * The setting "Black Ops boots glint": any item named Black Ops Boots shows the enchantment glint, wherever it is drawn (inventory,
 * hotbar, on the ground, worn by you or by another player). Purely visual and client side - the item itself is not changed. The
 * name is matched ignoring case and colour codes, so it also works if GTM words it as "Black Ops Boots [Tier 2]".
 */
public final class BlackOpsGlint {

	private static final String NAME = "black ops boots";

	private static Settings settings;

	private BlackOpsGlint() {}

	public static void init(Settings modSettings) {
		settings = modSettings;
	}

	/** True when the setting is on and an item with this display name should glint. */
	public static boolean wants(String displayName) {
		return settings != null && settings.blackOpsGlint && displayName != null && displayName.toLowerCase(Locale.ROOT).contains(NAME);
	}
}
