package com.example.gtmaddons.gui;

import com.example.gtmaddons.GTMAddOnsClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * GTMAddOns settings: sounds, hit sound, swap timer, corner swap text, advanced swap
 * info, better combo timers, boost angle, boost height, better near,
 * latency tester, and dev mode / QA mode (each opens its own menu, see
 * DevModeScreen).
 */
public class SettingsScreen extends Screen {

	private static final int BUTTON_WIDTH = 200;
	private static final int ROW = 22;
	private static final int OPTIONS = 10;
	private static final int GAP = 12;

	private final Screen parent;
	private final GTMAddOnsClient mod;
	private String status = null;

	public SettingsScreen(Screen parent, GTMAddOnsClient mod) {
		super(Text.literal("GTMAddOns - Settings"));
		this.parent = parent;
		this.mod = mod;
	}

	@Override
	protected void init() {
		int x = width / 2 - BUTTON_WIDTH / 2;
		int top = top();
		int row = 0;

		// Sounds and Move HUD share the first row.
		int half = (BUTTON_WIDTH - 4) / 2;
		addDrawableChild(ButtonWidget.builder(Text.literal("Sounds..."), b -> client.setScreen(new GunSoundsScreen(this)))
				.dimensions(x, top + ROW * row, half, 20)
				.build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Move HUD..."), b -> client.setScreen(new HudEditorScreen(this, mod::isHudElementOn)))
				.dimensions(x + BUTTON_WIDTH - half, top + ROW * row++, half, 20)
				.build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Hit sound..."), b -> client.setScreen(new HitSoundScreen(this, mod)))
				.dimensions(x, top + ROW * row++, BUTTON_WIDTH, 20)
				.build());
		addDrawableChild(ButtonWidget.builder(onOff("Swap timer", mod.isSwapTimerShown()), b -> {
			mod.setSwapTimerShown(!mod.isSwapTimerShown());
			b.setMessage(onOff("Swap timer", mod.isSwapTimerShown()));
		}).dimensions(x, top + ROW * row++, BUTTON_WIDTH, 20)
				.build());
		addDrawableChild(ButtonWidget.builder(onOff("Corner swap text", mod.isCornerSwapTextOn()), b -> {
			mod.setCornerSwapTextOn(!mod.isCornerSwapTextOn());
			b.setMessage(onOff("Corner swap text", mod.isCornerSwapTextOn()));
		}).dimensions(x, top + ROW * row++, BUTTON_WIDTH, 20)
				.build());
		addDrawableChild(ButtonWidget.builder(onOff("Advanced swap info", mod.isSwapDebugOn()), b -> {
			mod.setSwapDebugOn(!mod.isSwapDebugOn());
			b.setMessage(onOff("Advanced swap info", mod.isSwapDebugOn()));
		}).dimensions(x, top + ROW * row++, BUTTON_WIDTH, 20)
				.build());
		addDrawableChild(ButtonWidget.builder(onOff("Better combo timers", mod.isComboTimerOn()), b -> {
			mod.setComboTimerOn(!mod.isComboTimerOn());
			b.setMessage(onOff("Better combo timers", mod.isComboTimerOn()));
		}).dimensions(x, top + ROW * row++, BUTTON_WIDTH, 20)
				.build());
		addDrawableChild(ButtonWidget.builder(onOff("Boost angle", mod.isBoostAngleOn()), b -> {
			mod.setBoostAngleOn(!mod.isBoostAngleOn());
			b.setMessage(onOff("Boost angle", mod.isBoostAngleOn()));
		}).dimensions(x, top + ROW * row++, BUTTON_WIDTH, 20)
				.build());
		addDrawableChild(ButtonWidget.builder(onOff("Boost height", mod.isBoostHeightOn()), b -> {
			mod.setBoostHeightOn(!mod.isBoostHeightOn());
			b.setMessage(onOff("Boost height", mod.isBoostHeightOn()));
		}).dimensions(x, top + ROW * row++, BUTTON_WIDTH, 20)
				.build());
		addDrawableChild(ButtonWidget.builder(onOff("Better near", mod.isBetterNearOn()), b -> {
			mod.setBetterNearOn(!mod.isBetterNearOn());
			b.setMessage(onOff("Better near", mod.isBetterNearOn()));
		}).dimensions(x, top + ROW * row++, BUTTON_WIDTH, 20)
				.build());
		addDrawableChild(ButtonWidget.builder(onOff("Latency tester", mod.isLatencyTesterOn()), b -> {
			mod.setLatencyTesterOn(!mod.isLatencyTesterOn());
			b.setMessage(onOff("Latency tester", mod.isLatencyTesterOn()));
		}).dimensions(x, top + ROW * row++, BUTTON_WIDTH, 20)
				.build());

		// Dev and QA mode sit apart from the other options, side by side above
		// Back. Only one can be on; turning one on switches the other off.
		int devY = top + ROW * OPTIONS + GAP;
		addDrawableChild(ButtonWidget.builder(onOff("Dev mode", mod.isDevModeOn()), b -> openModeMenu(false))
				.dimensions(x, devY, half, 20)
				.build());
		addDrawableChild(ButtonWidget.builder(onOff("QA mode", mod.isQaModeOn()), b -> openModeMenu(true))
				.dimensions(x + BUTTON_WIDTH - half, devY, half, 20)
				.build());
		addDrawableChild(ButtonWidget.builder(onOff("Admin mode", mod.isAdminModeOn()), b -> toggleAdminMode())
				.dimensions(x, devY + ROW, BUTTON_WIDTH, 20)
				.build());
		addDrawableChild(ButtonWidget.builder(ScreenTexts.BACK, b -> close())
				.dimensions(x, devY + 2 * ROW, BUTTON_WIDTH, 20).build());
	}

	/** Centers everything vertically: the options, the gap, Dev mode and Back. */
	private int top() {
		return Math.max(30, (height - (ROW * OPTIONS + GAP + 2 * ROW + 20)) / 2);
	}

	/**
	 * Opens the dev mode (or QA mode) menu, where each thing to show is switched
	 * on or off and the mode itself is turned on or off. If the mode is already
	 * on, access was already approved; otherwise it's checked with the backend
	 * first, and the menu only opens if this account is allowed. Back returns
	 * here, which re-draws the buttons with the mode's new state.
	 */
	/**
	 * Turning admin mode off is immediate. Turning it on checks the account with the
	 * backend (ADMIN_UUIDS) and then asks for confirmation, since it adds buttons that
	 * permanently delete other players' data.
	 */
	private void toggleAdminMode() {
		if (mod.isAdminModeOn()) {
			mod.disableAdminMode(message -> status = message);
			clearAndInit();
			return;
		}
		mod.checkAdminAccess(message -> status = message, () -> client.setScreen(new ConfirmScreen(yes -> {
			if (yes) mod.enableAdminMode(message -> status = message);
			client.setScreen(this);
		},
				Text.literal("Enable admin mode?").formatted(Formatting.RED),
				Text.literal("Admin mode shows buttons on the stats screens that delete players' stats data from the server: "
						+ "a whole player, or a single fight.\n\nDeleted data cannot be recovered.\n\nDo you want to enable it?"))));
	}

	private void openModeMenu(boolean qa) {
		boolean alreadyOn = qa ? mod.isQaModeOn() : mod.isDevModeOn();
		if (alreadyOn) {
			client.setScreen(new DevModeScreen(this, mod, qa));
		} else {
			mod.checkDevAccess(message -> status = message, () -> client.setScreen(new DevModeScreen(this, mod, qa)));
		}
	}

	@Override
	public void close() {
		client.setScreen(parent);
	}

	private static Text onOff(String label, boolean on) {
		return Text.literal(label + ": " + (on ? "ON" : "OFF"));
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		super.render(context, mouseX, mouseY, deltaTicks);
		int top = top();
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, top - 16, 0xFFFFFFFF);
		if (status != null) {
			context.drawCenteredTextWithShadow(textRenderer, Text.literal(status), width / 2, top + ROW * OPTIONS + GAP + 2 * ROW + 26, 0xFFFFFF55);
		}
	}
}
