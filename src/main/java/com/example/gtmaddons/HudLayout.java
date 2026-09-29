package com.example.gtmaddons;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/**
 * Where each on-screen element is drawn. Positions the player has set in
 * Settings > Move HUD are saved in Settings.hudPositions as fractions of
 * the screen (so they stay put when the window or GUI scale changes);
 * elements that haven't been moved use their default spot.
 *
 * An element's x is its center, or its left edge for left-aligned ones
 * (whose text changes width, like the corner swap text); y is its top.
 *
 * Only one thing shows in any spot at a time: an element that would
 * overlap one already drawn that frame is skipped, so draw the most
 * important first.
 */
public final class HudLayout {

	public enum Element {
		SWAP_TIMER("Swap timer", false, Text.literal("Swap Timer: 0.412s").formatted(Formatting.YELLOW)),
		LAST_SWAP("Corner swap text", true, Text.literal("Last Swap: 0.412s")),
		BOOST_ANGLE("Boost angle", false, Text.literal("✔ Boost ready · 14.2° up").formatted(Formatting.GREEN)),
		BOOST_HEIGHT("Boost height", false, Text.literal("✔ Low enough to boost · Y 187 (limit 200)").formatted(Formatting.GREEN)),
		COMBO_LOCK("Combo'd timer", false, Text.literal("⚔ Combo'd |||||||||||||||||||| 0.43s").formatted(Formatting.RED)),
		COMBO_HIT("Hit again timer", false, Text.literal("⚔ Hit again |||||||||||||||||||| 0.31s").formatted(Formatting.AQUA));

		public final String label;
		public final boolean leftAligned;
		/** What the Move HUD screen shows for it. */
		public final Text sample;

		Element(String label, boolean leftAligned, Text sample) {
			this.label = label;
			this.leftAligned = leftAligned;
			this.sample = sample;
		}

		String key() {
			return name().toLowerCase(java.util.Locale.ROOT);
		}

		/** Default anchor in pixels: where these were drawn before they could be moved. */
		int[] defaultAnchor(int w, int h) {
			return switch (this) {
				case SWAP_TIMER -> new int[] { w / 2, h - 72 };   // where the action bar sits
				case LAST_SWAP -> new int[] { 6, 6 };
				case BOOST_ANGLE -> new int[] { w / 2, h / 2 + 12 };
				case BOOST_HEIGHT -> new int[] { w / 2, h / 2 + 22 };
				case COMBO_LOCK -> new int[] { w / 2, h / 2 - 34 };
				case COMBO_HIT -> new int[] { w / 2, h / 2 - 24 };
			};
		}
	}

	private static Settings settings;

	private HudLayout() {}

	public static void init(Settings loaded) {
		settings = loaded;
	}

	/** Anchor (center or left edge, and top) in pixels for this screen size. */
	public static int[] anchor(Element element, int w, int h) {
		double[] saved = settings != null ? settings.hudPositions.get(element.key()) : null;
		if (saved == null || saved.length != 2) return element.defaultAnchor(w, h);
		return new int[] { (int) Math.round(saved[0] * w), (int) Math.round(saved[1] * h) };
	}

	/** Saves a new anchor (in pixels) for this screen size. */
	public static void setAnchor(Element element, int x, int y, int w, int h) {
		settings.hudPositions.put(element.key(), new double[] { (double) x / w, (double) y / h });
	}

	public static void resetOne(Element element) {
		settings.hudPositions.remove(element.key());
		settings.save();
	}

	public static void reset() {
		settings.hudPositions.clear();
		settings.save();
	}

	public static void save() {
		settings.save();
	}

	/** Left edge to draw text of this width at, kept on screen. */
	public static int left(Element element, int anchorX, int textWidth, int w) {
		int x = element.leftAligned ? anchorX : anchorX - textWidth / 2;
		return Math.max(0, Math.min(w - textWidth, x));
	}

	public static int top(int anchorY, int h) {
		return Math.max(0, Math.min(h - 9, anchorY));
	}

	/** Boxes drawn so far this frame: {left, top, right, bottom}. */
	private static final List<int[]> drawn = new ArrayList<>();

	/** Call once per frame, before the draws - most important element first. */
	public static void beginFrame() {
		drawn.clear();
	}

	/**
	 * Draws text at the element's position; null draws nothing. Skipped if
	 * it would overlap something already drawn this frame, so displays moved
	 * on top of each other never show at the same time.
	 */
	public static void draw(DrawContext context, TextRenderer font, Element element, Text text) {
		if (text == null) return;
		int w = context.getScaledWindowWidth(), h = context.getScaledWindowHeight();
		int[] anchor = anchor(element, w, h);
		int textWidth = font.getWidth(text);
		int x = left(element, anchor[0], textWidth, w), y = top(anchor[1], h);
		int[] box = { x, y, x + textWidth, y + 9 };
		for (int[] other : drawn) {
			if (box[0] < other[2] && other[0] < box[2] && box[1] < other[3] && other[1] < box[3]) return;
		}
		drawn.add(box);
		context.drawTextWithShadow(font, text, x, y, 0xFFFFFFFF);
	}
}
