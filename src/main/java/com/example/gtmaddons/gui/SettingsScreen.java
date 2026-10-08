package com.example.gtmaddons.gui;

import com.example.gtmaddons.CobwebTransparency;
import com.example.gtmaddons.GTMAddOnsClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * GTMAddOns settings, split into pages. The first page lists the categories; each category
 * opens the same screen on its own page:
 *
 *   hub   Move HUD, then PVP, QOL, Miscellaneous, and PVE (coming soon)
 *   PVP   a row of tabs across the top, one section below at a time:
 *           Wing    cobweb transparency (a slider), boost angle, boost height, swap recording,
 *                   advanced swap info, swap timer (opens SwapTimerScreen)
 *           JP      better combo timers, jetpack sneak animation, hide jetpack particles
 *           General hit sound, combat timer (a HUD line you can move)
 *   QOL   sounds, better near
 *   MISC  latency tester, GTMAddOns icon, fish cat, then dev mode / QA mode side by
 *         side and admin mode under them (with its Player info... button while it is on)
 */
public class SettingsScreen extends Screen {

	/** Which page of the settings this screen shows. */
	public enum Page {
		HUB("GTMAddOns - Settings"),
		PVP("GTMAddOns - PVP"),
		QOL("GTMAddOns - QOL"),
		MISC("GTMAddOns - Miscellaneous");

		final String title;

		Page(String title) {
			this.title = title;
		}
	}

	private static final int BUTTON_WIDTH = 200;
	private static final int ROW = 22;
	/** Extra space between the groups on a page (and between dev, QA and admin mode). */
	private static final int GAP = 12;

	private final Screen parent;
	private final GTMAddOnsClient mod;
	private final Page page;
	private String status = null;
	/** Where the Back button ended up, so the status line can go under it. */
	private int backY = 0;
	/** Section titles and notes drawn between the buttons (PVP page): text, y and colour. */
	private final java.util.List<Object[]> labels = new java.util.ArrayList<>();
	private static final int NOTE_COLOR = 0xFF888888;

	/** The PVP page's tabs. The one open is remembered, so coming back shows the same one. */
	private enum PvpTab {
		WING("Wing"), JP("JP"), GENERAL("General");

		final String label;

		PvpTab(String label) {
			this.label = label;
		}
	}

	private static PvpTab pvpTab = PvpTab.WING;
	/** Rows in the biggest tab (Wing): every tab reserves this much, so Back stays put when you switch. */
	private static final int PVP_ROWS = 6;

	public SettingsScreen(Screen parent, GTMAddOnsClient mod) {
		this(parent, mod, Page.HUB);
	}

	public SettingsScreen(Screen parent, GTMAddOnsClient mod, Page page) {
		super(Text.literal(page.title));
		this.parent = parent;
		this.mod = mod;
		this.page = page;
	}

	@Override
	protected void init() {
		labels.clear();
		int x = width / 2 - BUTTON_WIDTH / 2;
		int y = top();
		switch (page) {
			case HUB -> y = buildHub(x, y);
			case PVP -> y = buildPvp(x, y);
			case QOL -> y = buildQol(x, y);
			case MISC -> y = buildMisc(x, y);
		}
		backY = y + GAP;
		addDrawableChild(ButtonWidget.builder(ScreenTexts.BACK, b -> close())
				.dimensions(x, backY, BUTTON_WIDTH, 20).build());
	}

	private int buildHub(int x, int y) {
		addDrawableChild(ButtonWidget.builder(Text.literal("Move HUD..."), b -> client.setScreen(new HudEditorScreen(this, mod::isHudElementOn)))
				.dimensions(x, y, BUTTON_WIDTH, 20).build());
		y += ROW + GAP;
		y = pageButton(x, y, "PVP", Page.PVP);
		y = pageButton(x, y, "QOL", Page.QOL);
		y = pageButton(x, y, "Miscellaneous", Page.MISC);
		ButtonWidget pve = ButtonWidget.builder(Text.literal("PVE - coming soon"), b -> { })
				.dimensions(x, y, BUTTON_WIDTH, 20).build();
		pve.active = false;
		addDrawableChild(pve);
		return y + ROW;
	}

	private int pageButton(int x, int y, String label, Page target) {
		addDrawableChild(ButtonWidget.builder(Text.literal(label + "..."), b -> client.setScreen(new SettingsScreen(this, mod, target)))
				.dimensions(x, y, BUTTON_WIDTH, 20).build());
		return y + ROW;
	}

	private int buildPvp(int x, int y) {
		int start = y;
		// The tabs, side by side; the open one is greyed out.
		int chip = 62, gap = 4;
		int tx = width / 2 - (PvpTab.values().length * (chip + gap) - gap) / 2;
		for (PvpTab tab : PvpTab.values()) {
			ButtonWidget button = ButtonWidget.builder(Text.literal(tab.label), b -> {
				pvpTab = tab;
				clearAndInit();
			}).dimensions(tx, y, chip, 20).build();
			button.active = tab != pvpTab;
			addDrawableChild(button);
			tx += chip + gap;
		}
		y += ROW + GAP / 2;

		switch (pvpTab) {
			case WING -> {
				addDrawableChild(new CobwebSlider(x, y));
				y += ROW;
				y = toggle(x, y, "Boost angle", mod::isBoostAngleOn, mod::setBoostAngleOn);
				y = toggle(x, y, "Boost height", mod::isBoostHeightOn, mod::setBoostHeightOn);
				y = toggle(x, y, "Swap recording", mod::isSwapRecordButtonOn, mod::setSwapRecordButtonOn);
				y = toggle(x, y, "Advanced swap info", mod::isSwapDebugOn, mod::setSwapDebugOn);
				addDrawableChild(ButtonWidget.builder(Text.literal("Swap timer..."), b -> client.setScreen(new SwapTimerScreen(this, mod)))
						.dimensions(x, y, BUTTON_WIDTH, 20).build());
			}
			case JP -> {
				y = toggle(x, y, "Better combo timers", mod::isComboTimerOn, mod::setComboTimerOn);
				y = toggle(x, y, "Jetpack sneak animation", mod::isOldSneakingOn, mod::setOldSneakingOn);
				toggle(x, y, "Hide jetpack particles", mod::isHideJetpackParticlesOn, mod::setHideJetpackParticlesOn);
			}
			case GENERAL -> {
				addDrawableChild(ButtonWidget.builder(Text.literal("Hit sound..."), b -> client.setScreen(new HitSoundScreen(this, mod)))
						.dimensions(x, y, BUTTON_WIDTH, 20).build());
				toggle(x, y + ROW, "Combat timer", mod::isCombatTimerOn, mod::setCombatTimerOn);
			}
		}
		return start + ROW + GAP / 2 + PVP_ROWS * ROW;
	}

	private int buildQol(int x, int y) {
		addDrawableChild(ButtonWidget.builder(Text.literal("Sounds..."), b -> client.setScreen(new GunSoundsScreen(this)))
				.dimensions(x, y, BUTTON_WIDTH, 20).build());
		y += ROW;
		y = toggle(x, y, "Better near", mod::isBetterNearOn, mod::setBetterNearOn);
		return y;
	}

	private int buildMisc(int x, int y) {
		y = toggle(x, y, "Latency tester", mod::isLatencyTesterOn, mod::setLatencyTesterOn);
		y = toggle(x, y, "GTMAddOns icon", mod::isModIconsOn, mod::setModIconsOn);
		y = toggle(x, y, "Fish cat", mod::isFishHeadsOn, mod::setFishHeadsOn);

		// Dev and QA mode sit apart from the other options, side by side, with admin
		// mode under them. Only one of dev / QA can be on; turning one on switches the
		// other off.
		y += GAP;
		int half = (BUTTON_WIDTH - 4) / 2;
		addDrawableChild(ButtonWidget.builder(onOff("Dev mode", mod.isDevModeOn()), b -> openModeMenu(false))
				.dimensions(x, y, half, 20).build());
		addDrawableChild(ButtonWidget.builder(onOff("QA mode", mod.isQaModeOn()), b -> openModeMenu(true))
				.dimensions(x + BUTTON_WIDTH - half, y, half, 20).build());
		y += ROW;
		addDrawableChild(ButtonWidget.builder(onOff("Admin mode", mod.isAdminModeOn()), b -> toggleAdminMode())
				.dimensions(x, y, BUTTON_WIDTH, 20).build());
		if (mod.isAdminModeOn()) {
			// Admin mode's own tools sit under its switch.
			y += ROW;
			addDrawableChild(ButtonWidget.builder(Text.literal("Player info..."), b -> client.setScreen(new AdminPlayerInfoScreen(this, mod)))
					.dimensions(x, y, BUTTON_WIDTH, 20).build());
		}
		return y + ROW - GAP;
	}

	/** One ON/OFF button that flips a setting and relabels itself. Returns the next row's y. */
	private int toggle(int x, int y, String label, java.util.function.BooleanSupplier get, java.util.function.Consumer<Boolean> set) {
		addDrawableChild(ButtonWidget.builder(onOff(label, get.getAsBoolean()), b -> {
			set.accept(!get.getAsBoolean());
			b.setMessage(onOff(label, get.getAsBoolean()));
		}).dimensions(x, y, BUTTON_WIDTH, 20).build());
		return y + ROW;
	}

	/** Total height of the page's buttons plus Back, to centre it vertically. */
	private int contentHeight() {
		int rows = switch (page) {
			case HUB -> ROW + GAP + 4 * ROW;
			case PVP -> ROW + GAP / 2 + PVP_ROWS * ROW;
			case QOL -> 2 * ROW;
			case MISC -> 3 * ROW + GAP + 2 * ROW - GAP + (mod.isAdminModeOn() ? ROW : 0);
		};
		return rows + GAP + 20;
	}

	private int top() {
		return Math.max(30, (height - contentHeight()) / 2);
	}

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

	/**
	 * Opens the dev mode (or QA mode) menu, where each thing to show is switched
	 * on or off and the mode itself is turned on or off. If the mode is already
	 * on, access was already approved; otherwise it's checked with the backend
	 * first, and the menu only opens if this account is allowed. Back returns
	 * here, which re-draws the buttons with the mode's new state.
	 */
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
		// The cobweb texture is only read when resources load, so a changed slider reloads them once.
		if (page == Page.PVP) CobwebTransparency.reloadIfChanged();
		client.setScreen(parent);
	}

	/** 0-100% of the cobweb made see-through, saved as it's dragged; applied when you leave this page. */
	private final class CobwebSlider extends SliderWidget {
		CobwebSlider(int x, int y) {
			super(x, y, BUTTON_WIDTH, 20, Text.empty(), mod.getCobwebTransparency() / 100.0);
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			int percent = (int) Math.round(value * 100);
			setMessage(Text.literal(percent == 0 ? "Cobweb transparency: Off" : "Cobweb transparency: " + percent + "%"));
		}

		@Override
		protected void applyValue() {
			mod.setCobwebTransparency((int) Math.round(value * 100));
		}
	}

	private static Text onOff(String label, boolean on) {
		return Text.literal(label + ": " + (on ? "ON" : "OFF"));
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		super.render(context, mouseX, mouseY, deltaTicks);
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, top() - 16, 0xFFFFFFFF);
		for (Object[] label : labels) {
			context.drawCenteredTextWithShadow(textRenderer, Text.literal((String) label[0]), width / 2, (int) label[1] + 1, (int) label[2]);
		}
		if (status != null) {
			context.drawCenteredTextWithShadow(textRenderer, Text.literal(status), width / 2, backY + 26, 0xFFFFFF55);
		}
	}
}
