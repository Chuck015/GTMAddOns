package com.example.gtmaddons;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The "Cobweb transparency" setting (0-100%): how much of the cobweb texture is made see-through. Cobweb is a cutout
 * texture - each pixel is either drawn or not, with no half transparency - so this thins the web out: that share of its
 * opaque pixels is cleared, always the same pixels for the same percentage. The texture is only read when resources
 * load (see the SpriteContents mixin), so changing the setting reloads them once you leave the settings page.
 */
public final class CobwebTransparency {

	private static final String COBWEB = "minecraft:block/cobweb";

	private static Settings settings;
	/** The percentage the loaded texture was made with. */
	private static int applied = 0;
	/** Images already thinned (the sprite constructors can chain), so one is never thinned twice. */
	private static final Set<NativeImage> done = Collections.newSetFromMap(new WeakHashMap<>());

	private CobwebTransparency() {}

	public static void init(Settings modSettings) {
		settings = modSettings;
	}

	/** Called as each block sprite is created: thins the cobweb's pixels by the setting. */
	public static void thin(Identifier id, NativeImage image) {
		if (settings == null || !COBWEB.equals(id.toString())) return;
		int percent = settings.cobwebTransparency;
		applied = percent;
		if (percent <= 0 || !done.add(image)) return;
		List<int[]> opaque = new ArrayList<>();
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				if ((image.getColorArgb(x, y) >>> 24) != 0) opaque.add(new int[] { x, y });
			}
		}
		Collections.shuffle(opaque, new Random(0x6C0BEBL));
		int remove = (int) Math.round(opaque.size() * Math.min(100, percent) / 100.0);
		for (int i = 0; i < remove; i++) {
			int x = opaque.get(i)[0], y = opaque.get(i)[1];
			int argb = 0;
			image.setColorArgb(x, y, argb);
		}
	}

	/** Reloads resources if the setting no longer matches the loaded texture. */
	public static void reloadIfChanged() {
		if (settings != null && settings.cobwebTransparency != applied) MinecraftClient.getInstance().reloadResources();
	}
}
