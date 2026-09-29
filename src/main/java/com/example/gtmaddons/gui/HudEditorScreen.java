package com.example.gtmaddons.gui;

import com.example.gtmaddons.HudLayout;
import com.example.gtmaddons.HudLayout.Element;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;

import java.util.function.Predicate;

/**
 * Settings > Move HUD: every on-screen element is shown with sample text
 * in a box. Drag a box to move it, right-click it to put it back in its
 * default spot, or Reset all. Positions are saved when you let go (see
 * HudLayout). A centered element snaps to the middle of the screen, and a
 * line shows when it has.
 *
 * The game stays visible behind (no blur), so things can be placed around
 * the crosshair and hotbar. Elements that are turned off in Settings are
 * shown dimmed, and can still be moved.
 */
public class HudEditorScreen extends Screen {

	private static final int SNAP = 4;
	private static final int BOX_PAD = 2;
	private static final int BOX = 0x60000000;
	private static final int BOX_OFF = 0x30000000;
	private static final int BORDER = 0xFF6E6E78;
	private static final int BORDER_HOVER = 0xFFFFFFFF;
	private static final int GUIDE = 0x8055D7FF;

	private final Screen parent;
	private final Predicate<Element> enabled;
	private Element dragging = null;
	private int grabX, grabY;
	private boolean snapped = false;

	/** enabled says whether each element's setting is on (off ones are dimmed). */
	public HudEditorScreen(Screen parent, Predicate<Element> enabled) {
		super(Text.literal("Move HUD"));
		this.parent = parent;
		this.enabled = enabled;
	}

	@Override
	protected void init() {
		addDrawableChild(ButtonWidget.builder(Text.literal("Reset all"), b -> HudLayout.reset())
				.dimensions(width / 2 - 104, height - 28, 100, 20).build());
		addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, b -> close())
				.dimensions(width / 2 + 4, height - 28, 100, 20).build());
	}

	@Override
	public void close() {
		HudLayout.save();
		client.setScreen(parent);
	}

	/** No blur: the game should be visible to place things around it. */
	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		context.fill(0, 0, width, height, 0x30000000);
	}

	// ---- Boxes ----

	/** {left, top, right, bottom} of an element's box on this screen. */
	private int[] box(Element element) {
		int[] anchor = HudLayout.anchor(element, width, height);
		int textWidth = textRenderer.getWidth(element.sample);
		int left = HudLayout.left(element, anchor[0], textWidth, width);
		int top = HudLayout.top(anchor[1], height);
		return new int[] { left - BOX_PAD, top - BOX_PAD, left + textWidth + BOX_PAD, top + 9 + BOX_PAD };
	}

	/** The top-most element under the mouse, or null. */
	private Element elementAt(double x, double y) {
		Element[] elements = Element.values();
		for (int i = elements.length - 1; i >= 0; i--) {
			int[] b = box(elements[i]);
			if (x >= b[0] && x < b[2] && y >= b[1] && y < b[3]) return elements[i];
		}
		return null;
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (super.mouseClicked(click, doubled)) return true;
		Element element = elementAt(click.x(), click.y());
		if (element == null) return false;
		if (click.button() == 1) {
			HudLayout.resetOne(element);
			return true;
		}
		if (click.button() != 0) return false;
		int[] anchor = HudLayout.anchor(element, width, height);
		dragging = element;
		grabX = (int) click.x() - anchor[0];
		grabY = (int) click.y() - anchor[1];
		return true;
	}

	@Override
	public boolean mouseDragged(Click click, double deltaX, double deltaY) {
		if (dragging == null) return super.mouseDragged(click, deltaX, deltaY);
		int x = (int) click.x() - grabX;
		int y = (int) click.y() - grabY;
		// Centered elements snap to the middle of the screen.
		snapped = !dragging.leftAligned && Math.abs(x - width / 2) <= SNAP;
		if (snapped) x = width / 2;
		HudLayout.setAnchor(dragging, Math.max(0, Math.min(width, x)), Math.max(0, Math.min(height - 9, y)), width, height);
		return true;
	}

	@Override
	public boolean mouseReleased(Click click) {
		if (dragging != null) {
			dragging = null;
			snapped = false;
			HudLayout.save();
			return true;
		}
		return super.mouseReleased(click);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		super.render(context, mouseX, mouseY, deltaTicks);
		if (snapped) context.fill(width / 2, 0, width / 2 + 1, height, GUIDE);

		Element hovered = dragging != null ? dragging : elementAt(mouseX, mouseY);
		for (Element element : Element.values()) {
			int[] b = box(element);
			boolean on = enabled.test(element);
			context.fill(b[0], b[1], b[2], b[3], on ? BOX : BOX_OFF);
			int border = element == hovered ? BORDER_HOVER : BORDER;
			context.fill(b[0], b[1], b[2], b[1] + 1, border);
			context.fill(b[0], b[3] - 1, b[2], b[3], border);
			context.fill(b[0], b[1], b[0] + 1, b[3], border);
			context.fill(b[2] - 1, b[1], b[2], b[3], border);
			context.drawTextWithShadow(textRenderer, element.sample, b[0] + BOX_PAD, b[1] + BOX_PAD, on ? 0xFFFFFFFF : 0x80FFFFFF);
		}

		// Name of the hovered element, above its box (or below if there's no room).
		if (hovered != null) {
			int[] b = box(hovered);
			String name = hovered.label + (enabled.test(hovered) ? "" : " (off in Settings)");
			int y = b[1] >= 12 ? b[1] - 11 : b[3] + 2;
			int x = Math.max(2, Math.min(width - textRenderer.getWidth(name) - 2, b[0]));
			context.drawTextWithShadow(textRenderer, name, x, y, 0xFFFFFF55);
		}

		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 8, 0xFFFFFFFF);
	}
}
