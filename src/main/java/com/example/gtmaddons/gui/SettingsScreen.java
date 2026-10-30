package com.example.gtmaddons.gui;

import com.example.gtmaddons.CobwebTransparency;
import com.example.gtmaddons.GTMAddOnsClient;
import com.example.gtmaddons.PvpCategory;
import com.example.gtmaddons.compat.Gfx;
import com.example.gtmaddons.compat.Mc;
import com.example.gtmaddons.compat.ModScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * GTMAddOns settings, split into pages. The first page lists the categories; each category
 * opens the same screen on its own page:
 *
 *   hub   Move HUD, then PVP, QOL, Miscellaneous, and PVE (coming soon)
 *   PVP   a row of tabs across the top, one section below at a time:
 *           Wing    cobweb transparency (a slider), boost angle, boost height, swap recording,
 *                   advanced swap info, hide recipes in the recipe book, swap timer (opens SwapTimerScreen)
 *           JP      better combo timers, jetpack sneak animation, hide jetpack particles
 *           General hit sound, combat timer (a HUD line you can move)
 *   QOL   sounds, better near, dropped item names, Black Ops boots glint
 *   MISC  latency tester, GTMAddOns icon, fish cat, then dev mode / QA mode side by
 *         side and admin mode under them (with its Player info... and Notify players... buttons while it is on)
 */
public class SettingsScreen extends ModScreen {

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
	private static final int PVP_ROWS = 7;

	public SettingsScreen(Screen parent, GTMAddOnsClient mod) {
		this(parent, mod, Page.HUB);
	}

	public SettingsScreen(Screen parent, GTMAddOnsClient mod, Page page) {
		super(Component.literal(page.title));
		this.parent = parent;
		this.mod = mod;
		this.page = page;
	}

	@Override
	protected void init() {
		labels.clear();
		// Each settings page in its own accent (the sub-screens opened from it keep it).
		ThemedButton.accent = switch (page) {
			case HUB -> Ui.BLUE;
			case PVP -> Ui.accent(PvpCategory.JP);
			case QOL -> Ui.accent(PvpCategory.GROUND);
			case MISC -> Ui.accent(PvpCategory.AIR);
		};
		int x = width / 2 - BUTTON_WIDTH / 2;
		int y = top();
		switch (page) {
			case HUB -> y = buildHub(x, y);
			case PVP -> y = buildPvp(x, y);
			case QOL -> y = buildQol(x, y);
			case MISC -> y = buildMisc(x, y);
		}
		backY = y + GAP;
		addRenderableWidget(ThemedButton.create(CommonComponents.GUI_BACK, b -> onClose())
				.bounds(x, backY, BUTTON_WIDTH, 20).build());
	}

	private int buildHub(int x, int y) {
		addRenderableWidget(ThemedButton.create(Component.literal("Move HUD..."), b -> Mc.setScreen(minecraft, new HudEditorScreen(this, mod::isHudElementOn)))
				.bounds(x, y, BUTTON_WIDTH, 20).build());
		y += ROW + GAP;
		y = pageButton(x, y, "PVP", Page.PVP);
		y = pageButton(x, y, "QOL", Page.QOL);
		y = pageButton(x, y, "Miscellaneous", Page.MISC);
		Button pve = ThemedButton.create(Component.literal("PVE - coming soon"), b -> { })
				.bounds(x, y, BUTTON_WIDTH, 20).build();
		pve.active = false;
		addRenderableWidget(pve);
		return y + ROW;
	}

	private int pageButton(int x, int y, String label, Page target) {
		addRenderableWidget(ThemedButton.create(Component.literal(label + "..."), b -> Mc.setScreen(minecraft, new SettingsScreen(this, mod, target)))
				.bounds(x, y, BUTTON_WIDTH, 20).build());
		return y + ROW;
	}

	private int buildPvp(int x, int y) {
		int start = y;
		// The tabs, side by side; the open one is greyed out.
		int chip = 62, gap = 4;
		int tx = width / 2 - (PvpTab.values().length * (chip + gap) - gap) / 2;
		for (PvpTab tab : PvpTab.values()) {
			Button button = ThemedButton.create(Component.literal(tab.label), b -> {
				pvpTab = tab;
				rebuildWidgets();
			}).bounds(tx, y, chip, 20).build();
			button.active = tab != pvpTab;
			addRenderableWidget(button);
			tx += chip + gap;
		}
		y += ROW + GAP / 2;

		switch (pvpTab) {
			case WING -> {
				addRenderableWidget(new CobwebSlider(x, y));
				y += ROW;
				y = toggle(x, y, "Boost angle", mod::isBoostAngleOn, mod::setBoostAngleOn);
				y = toggle(x, y, "Boost height", mod::isBoostHeightOn, mod::setBoostHeightOn);
				y = toggle(x, y, "Swap recording", mod::isSwapRecordButtonOn, mod::setSwapRecordButtonOn);
				y = toggle(x, y, "Advanced swap info", mod::isSwapDebugOn, mod::setSwapDebugOn);
				y = toggle(x, y, "Hide recipes in book", mod::isHideRecipeBookOn, mod::setHideRecipeBookOn);
				addRenderableWidget(ThemedButton.create(Component.literal("Swap timer..."), b -> Mc.setScreen(minecraft, new SwapTimerScreen(this, mod)))
						.bounds(x, y, BUTTON_WIDTH, 20).build());
			}
			case JP -> {
				y = toggle(x, y, "Better combo timers", mod::isComboTimerOn, mod::setComboTimerOn);
				y = toggle(x, y, "Jetpack sneak animation", mod::isOldSneakingOn, mod::setOldSneakingOn);
				toggle(x, y, "Hide jetpack particles", mod::isHideJetpackParticlesOn, mod::setHideJetpackParticlesOn);
			}
			case GENERAL -> {
				addRenderableWidget(ThemedButton.create(Component.literal("Hit sound..."), b -> Mc.setScreen(minecraft, new HitSoundScreen(this, mod)))
						.bounds(x, y, BUTTON_WIDTH, 20).build());
				toggle(x, y + ROW, "Combat timer", mod::isCombatTimerOn, mod::setCombatTimerOn);
			}
		}
		return start + ROW + GAP / 2 + PVP_ROWS * ROW;
	}

	private int buildQol(int x, int y) {
		addRenderableWidget(ThemedButton.create(Component.literal("Sounds..."), b -> Mc.setScreen(minecraft, new GunSoundsScreen(this)))
				.bounds(x, y, BUTTON_WIDTH, 20).build());
		y += ROW;
		addRenderableWidget(new InventorySlider(x, y));
		y += ROW;
		y = toggle(x, y, "Better near", mod::isBetterNearOn, mod::setBetterNearOn);
		y = toggle(x, y, "Dropped item names", mod::isDroppedItemNamesOn, mod::setDroppedItemNamesOn);
		y = toggle(x, y, "Black Ops boots glint", mod::isBlackOpsGlintOn, mod::setBlackOpsGlintOn);
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
		addRenderableWidget(ThemedButton.create(onOff("Dev mode", mod.isDevModeOn()), b -> openModeMenu(false))
				.bounds(x, y, half, 20).build());
		addRenderableWidget(ThemedButton.create(onOff("QA mode", mod.isQaModeOn()), b -> openModeMenu(true))
				.bounds(x + BUTTON_WIDTH - half, y, half, 20).build());
		y += ROW;
		addRenderableWidget(ThemedButton.create(onOff("Admin mode", mod.isAdminModeOn()), b -> toggleAdminMode())
				.bounds(x, y, BUTTON_WIDTH, 20).build());
		if (mod.isAdminModeOn()) {
			// Admin mode's own tools sit under its switch.
			y += ROW;
			addRenderableWidget(ThemedButton.create(Component.literal("Player info..."), b -> Mc.setScreen(minecraft, new AdminPlayerInfoScreen(this, mod)))
					.bounds(x, y, BUTTON_WIDTH, 20).build());
			y += ROW;
			addRenderableWidget(ThemedButton.create(Component.literal("Notify players..."), b -> Mc.setScreen(minecraft, new AdminNoticeScreen(this, mod)))
					.bounds(x, y, BUTTON_WIDTH, 20).build());
		}
		return y + ROW - GAP;
	}

	/** One ON/OFF button that flips a setting and relabels itself. Returns the next row's y. */
	private int toggle(int x, int y, String label, java.util.function.BooleanSupplier get, java.util.function.Consumer<Boolean> set) {
		addRenderableWidget(ThemedButton.create(onOff(label, get.getAsBoolean()), b -> {
			set.accept(!get.getAsBoolean());
			b.setMessage(onOff(label, get.getAsBoolean()));
		}).bounds(x, y, BUTTON_WIDTH, 20).build());
		return y + ROW;
	}

	/** Total height of the page's buttons plus Back, to centre it vertically. */
	private int contentHeight() {
		int rows = switch (page) {
			case HUB -> ROW + GAP + 4 * ROW;
			case PVP -> ROW + GAP / 2 + PVP_ROWS * ROW;
			case QOL -> 4 * ROW;
			case MISC -> 3 * ROW + GAP + 2 * ROW - GAP + (mod.isAdminModeOn() ? 2 * ROW : 0);
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
			rebuildWidgets();
			return;
		}
		mod.checkAdminAccess(message -> status = message, () -> Mc.setScreen(minecraft, new ConfirmScreen(yes -> {
			if (yes) mod.enableAdminMode(message -> status = message);
			Mc.setScreen(minecraft, this);
		},
				Component.literal("Enable admin mode?").withStyle(ChatFormatting.RED),
				Component.literal("Admin mode shows buttons on the stats screens that delete players' stats data from the server: "
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
			Mc.setScreen(minecraft, new DevModeScreen(this, mod, qa));
		} else {
			mod.checkDevAccess(message -> status = message, () -> Mc.setScreen(minecraft, new DevModeScreen(this, mod, qa)));
		}
	}

	@Override
	public void onClose() {
		// The cobweb texture is only read when resources load, so a changed slider reloads them once.
		if (page == Page.PVP) CobwebTransparency.reloadIfChanged();
		Mc.setScreen(minecraft, parent);
	}

	/** 0-100% of the cobweb made see-through, saved as it's dragged; applied when you leave this page. */
	private final class CobwebSlider extends ThemedSlider {
		CobwebSlider(int x, int y) {
			super(x, y, BUTTON_WIDTH, 20, Component.empty(), mod.getCobwebTransparency() / 100.0);
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			int percent = (int) Math.round(value * 100);
			setMessage(Component.literal(percent == 0 ? "Cobweb transparency: Off" : "Cobweb transparency: " + percent + "%"));
		}

		@Override
		protected void applyValue() {
			mod.setCobwebTransparency((int) Math.round(value * 100));
		}
	}

	private final class InventorySlider extends ThemedSlider {
		InventorySlider(int x, int y) {
			super(x, y, BUTTON_WIDTH, 20, Component.empty(), mod.getInventoryTransparency() / 100.0);
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			int percent = (int) Math.round(value * 100);
			setMessage(Component.literal(percent == 0 ? "Inventory transparency: Off" : "Inventory transparency: " + percent + "%"));
		}

		@Override
		protected void applyValue() {
			mod.setInventoryTransparency((int) Math.round(value * 100));
		}
	}

	private static Component onOff(String label, boolean on) {
		return Component.literal(label + ": " + (on ? "ON" : "OFF"));
	}

	@Override
	public void draw(Gfx context, int mouseX, int mouseY, float deltaTicks) {
		context.fill(0, 0, width, height, Ui.PAGE);
		super.draw(context, mouseX, mouseY, deltaTicks);
		context.centeredText(font, title, width / 2, top() - 16, 0xFFFFFFFF);
		for (Object[] label : labels) {
			context.centeredText(font, Component.literal((String) label[0]), width / 2, (int) label[1] + 1, (int) label[2]);
		}
		if (status != null) {
			context.centeredText(font, Component.literal(status), width / 2, backY + 26, 0xFFFFFF55);
		}
	}
}
