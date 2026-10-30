package com.example.gtmaddons;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * What input happened while the inventory was open for a swap, and how it was closed - stored with the swap (information only, shown in
 * Raw info; nothing rates or flags with it). It tells a swap made by hand (key presses, clicks, a cursor that moves over many frames)
 * from one made by code (the inventory opened and closed with no input and no frame in between).
 *
 * Counted per swap, from the inventory opening to it closing:
 *   - keys: key presses the open inventory screen received (of which hotbar number keys 1-9, the swap-by-key presses);
 *   - clicks: mouse button presses; scrolls: scroll wheel steps;
 *   - frames: rendered frames with the inventory open; moves: those frames where the cursor was somewhere new;
 *   - how it closed: "key" (a key press just before it closed - closeKey is the GLFW key code: 69 = E, 256 = Escape), "mouse" (a click just
 *     before) or "none" (nothing: it was closed by code or by the server).
 * Only counts and the one key code near the close are kept; what is typed is not recorded.
 */
public final class SwapInputs {

	/** An input this soon before the screen closed is what closed it. */
	private static final long CLOSE_WINDOW_NANOS = 150_000_000L;
	private static final int FIRST_HOTBAR_KEY = 49, LAST_HOTBAR_KEY = 57;

	/** Slot-click packets the client sent since begin() (counted where packets are sent, so it sees clicks made by code too). */
	private static final AtomicInteger clickPackets = new AtomicInteger();

	private int keys, hotbarKeys, clicks, scrolls, frames, moves;
	private long lastKeyNanos = -1L, lastClickNanos = -1L;
	private int lastKey = -1;
	private double lastX, lastY;
	private boolean hasCursor = false;

	/** The inventory opened for a swap. */
	public void begin() {
		keys = hotbarKeys = clicks = scrolls = frames = moves = 0;
		lastKeyNanos = lastClickNanos = -1L;
		lastKey = -1;
		hasCursor = false;
		clickPackets.set(0);
	}

	/** The client sent a slot-click packet (called from the network mixin, on any thread). */
	public static void onClickPacket() {
		clickPackets.incrementAndGet();
	}

	public void onKey(int keyCode) {
		keys++;
		if (keyCode >= FIRST_HOTBAR_KEY && keyCode <= LAST_HOTBAR_KEY) hotbarKeys++;
		lastKey = keyCode;
		lastKeyNanos = System.nanoTime();
	}

	public void onClick() {
		clicks++;
		lastClickNanos = System.nanoTime();
	}

	public void onScroll() {
		scrolls++;
	}

	/** Every rendered frame with the inventory open, with the cursor position in window pixels. */
	public void onFrame(double cursorX, double cursorY) {
		frames++;
		if (hasCursor && (cursorX != lastX || cursorY != lastY)) moves++;
		lastX = cursorX;
		lastY = cursorY;
		hasCursor = true;
	}

	/** "key", "mouse" or "none" for how the screen closed at this moment. */
	public String closeHow(long closeNanos) {
		long key = lastKeyNanos >= 0 ? closeNanos - lastKeyNanos : Long.MAX_VALUE;
		long click = lastClickNanos >= 0 ? closeNanos - lastClickNanos : Long.MAX_VALUE;
		if (key > CLOSE_WINDOW_NANOS && click > CLOSE_WINDOW_NANOS) return "none";
		return key <= click ? "key" : "mouse";
	}

	/** The key code of the press that closed it, or null (closeHow was not "key"). */
	public Double closeKey(long closeNanos) {
		return "key".equals(closeHow(closeNanos)) ? (double) lastKey : null;
	}

	public double keys() { return keys; }
	public double hotbarKeys() { return hotbarKeys; }
	public double clicks() { return clicks; }
	public double scrolls() { return scrolls; }
	public double moves() { return moves; }
	public double frames() { return frames; }
	public double clickPackets() { return clickPackets.get(); }
}
