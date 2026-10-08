package com.example.gtmaddons;

import net.minecraft.client.MinecraftClient;

/**
 * The "Old sneaking" setting: Minecraft 1.8 showed you crouching while you flew and held sneak; newer versions only fly
 * down, because the player's pose is "crouching" only when sneaking and not flying. With this on, every player's MODEL (yours and everyone
 * else's) is drawn crouched in that case (see PlayerSneakAnimationMixin) - nothing else. It is only a picture: the pose, the
 * dimensions, the hitbox and what the server sees are never changed.
 */
public final class OldSneaking {

	private static Settings settings;

	private OldSneaking() {}

	public static void init(Settings modSettings) {
		settings = modSettings;
	}

	/** Whether to draw this player's model crouched: the setting is on and they are holding sneak (everyone's model, not only yours). */
	public static boolean showsFor(Object player, boolean sneakHeld) {
		return sneakHeld && settings != null && settings.oldSneaking;
	}
}
