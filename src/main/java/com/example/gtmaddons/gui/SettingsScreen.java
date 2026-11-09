package com.example.gtmaddons.gui;

import com.example.gtmaddons.CobwebTransparency;
import com.example.gtmaddons.GTMAddOnsClient;
import com.example.gtmaddons.PvpCategory;
import com.example.gtmaddons.Settings;
import com.example.gtmaddons.SwapSession;
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
 *           Wing    cobweb transparency, boost angle, boost height limit (readout, limit view), swap timer, swap information (recording, its inventory
 *                   button, advanced swap info, auto playback), armor hotkey lock, better recipe book
 *           JP      better combo timers, better swing, jetpack sneak animation, better jetpack particles
 *           General hit sound, gun tracers (sizes: all, by category, by gun), combat timer
 *   QOL   sounds, inventory transparency, better near, dropped item names, dropped item size, Black Ops boots glint
 *   MISC  latency tester, GTMAddOns icon, fish cat, menu keybind, then dev mode, QA mode and admin mode
 *
 * Every setting is one row: its name with an ON/OFF pill (click to switch it) and a ⚙ on the right that opens what can be
 * adjusted in it (an OptionsScreen of switches and sliders, the swap timer or hit sound menu, the dev / QA filters, the admin
 * tools). Move HUD is only on the settings hub. A setting with nothing to adjust has a greyed-out ⚙.
 */
public class SettingsScreen extends ModScreen {

	/** Which page of the settings this screen shows. */
	public enum Page {
		HUB("GTMAddOns - Mods"),
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

		Settings settings = mod.settings();
		switch (pvpTab) {
			case WING -> {
				y = row(x, y, "Cobweb transparency", () -> mod.getCobwebTransparency() > 0, mod::setCobwebTransparencyOn,
						() -> open(new OptionsScreen(this, "Cobweb transparency").sliderWithOff("Transparency", 0, 100, mod::getCobwebTransparency, mod::setCobwebTransparency)));
				y = row(x, y, "Boost angle", mod::isBoostAngleOn, mod::setBoostAngleOn, null);
				y = row(x, y, "Boost height limit", mod::isBoostHeightOn, mod::setBoostHeightOn,
						() -> open(new OptionsScreen(this, "Boost height limit")
								.toggle("Height readout", () -> settings.boostHeightReadout, on -> save(() -> settings.boostHeightReadout = on))
								.toggle("Limit view", () -> settings.boostLimitView, on -> save(() -> settings.boostLimitView = on))
								.slider("Show after gliding", 0, 10, " s", () -> settings.boostLingerSeconds, v -> save(() -> settings.boostLingerSeconds = v))
								.note("Limit view: a grid at the boost limit while you glide near it")));
				y = row(x, y, "Swap timer", () -> SwapTimerScreen.isOn(mod), on -> SwapTimerScreen.setOn(mod, on),
						() -> Mc.setScreen(minecraft, new SwapTimerScreen(this, mod)));
				y = row(x, y, "Swap information", () -> settings.swapInfo, on -> save(() -> settings.swapInfo = on),
						() -> open(new OptionsScreen(this, "Swap information")
								.action(() -> SwapSession.INSTANCE.isRecording() ? "Stop recording" : "Start recording", this::toggleRecording)
								.toggle("Recording button in inventory", mod::isSwapRecordButtonOn, mod::setSwapRecordButtonOn)
								.toggle("Advanced swap info", mod::isSwapDebugOn, mod::setSwapDebugOn)
								.toggle("Auto playback", () -> settings.swapAutoPlayback, on -> save(() -> settings.swapAutoPlayback = on))
								.note("Auto playback: your swap info in chat after every recorded fight")));
				y = row(x, y, "Armor hotkey lock", () -> settings.chestHotkeysOnly, on -> save(() -> settings.chestHotkeysOnly = on), null);
				row(x, y, "Better recipe book", mod::isHideRecipeBookOn, mod::setHideRecipeBookOn, null);
			}
			case JP -> {
				y = row(x, y, "Better combo timers", mod::isComboTimerOn, mod::setComboTimerOn, null);
				y = row(x, y, "Better swing", mod::isBetterSwingOn, mod::setBetterSwingOn,
						() -> open(new OptionsScreen(this, "Better swing")
								.toggle("Always swing", () -> settings.betterSwingRestart, on -> save(() -> settings.betterSwingRestart = on))
								.slider("Swing speed", 50, 300, () -> settings.betterSwingSpeed, v -> save(() -> settings.betterSwingSpeed = v))
								.note("Always swing: every click starts a new swing")));
				y = row(x, y, "Jetpack sneak animation", mod::isOldSneakingOn, mod::setOldSneakingOn, null);
				row(x, y, "Better jetpack particles", mod::isHideJetpackParticlesOn, mod::setHideJetpackParticlesOn,
						() -> open(new OptionsScreen(this, "Better jetpack particles")
								.toggle("Hide", () -> settings.jetpackParticlesHide, on -> save(() -> settings.jetpackParticlesHide = on))
								.slider("Size", 25, 300, () -> settings.jetpackParticleSize, v -> save(() -> settings.jetpackParticleSize = v))
								.note("Size is used while Hide is off")));
			}
			case GENERAL -> {
				y = row(x, y, "Hit sound", () -> mod.settings().hitSound, on -> {
					mod.settings().hitSound = on;
					mod.settings().save();
				}, () -> Mc.setScreen(minecraft, new HitSoundScreen(this, mod)));
				y = row(x, y, "Gun tracers", () -> settings.gunTracers, on -> save(() -> settings.gunTracers = on),
						() -> open(new OptionsScreen(this, "Gun tracers")
								.sliderWithOff("All tracers", 0, 100, () -> settings.tracerSizeAll, v -> save(() -> settings.tracerSizeAll = v))
								.link("By category", () -> open(new SizeListScreen(currentScreen(), "Tracers by category", com.example.gtmaddons.GunTracers.CATEGORIES,
										settings.tracerSizeCategory, settings::save)))
								.link("By gun", () -> open(new SizeListScreen(currentScreen(), "Tracers by gun", new java.util.ArrayList<>(com.example.gtmaddons.GunTracers.GUNS.keySet()),
										settings.tracerSizeGun, settings::save)))
								.note("Size = All x category x gun; 0% hides; everyone's shots, both kinds")));
				row(x, y, "Combat timer", mod::isCombatTimerOn, mod::setCombatTimerOn,
						() -> open(new OptionsScreen(this, "Combat timer")
								.toggle("Sound when combat ends", () -> settings.combatTimerSound, on -> save(() -> settings.combatTimerSound = on))));
			}
		}
		return start + ROW + GAP / 2 + PVP_ROWS * ROW;
	}

	private int buildQol(int x, int y) {
		addRenderableWidget(ThemedButton.create(Component.literal("Sounds..."), b -> Mc.setScreen(minecraft, new GunSoundsScreen(this)))
				.bounds(x, y, BUTTON_WIDTH, 20).build());
		y += ROW;
		Settings settings = mod.settings();
		y = row(x, y, "Inventory transparency", () -> mod.getInventoryTransparency() > 0, mod::setInventoryTransparencyOn,
				() -> open(new OptionsScreen(this, "Inventory transparency").sliderWithOff("Transparency", 0, 100, mod::getInventoryTransparency, mod::setInventoryTransparency)));
		y = row(x, y, "Better near", mod::isBetterNearOn, mod::setBetterNearOn,
				() -> open(new OptionsScreen(this, "Better near").toggle("Show job tags", () -> settings.nearJobTags, mod::setNearJobTags)));
		y = row(x, y, "Dropped item names", mod::isDroppedItemNamesOn, mod::setDroppedItemNamesOn,
				() -> open(new OptionsScreen(this, "Dropped item names")
						.toggle("Only listed items", () -> settings.droppedItemFilterOn, on -> save(() -> settings.droppedItemFilterOn = on))
						.link("Item filter", () -> Mc.setScreen(minecraft, new ItemFilterScreen(currentScreen(), settings, "Item names filter",
								settings.droppedItemFilter, () -> settings.droppedItemFilterOn, "Only these items are named", "Every item is named (Only listed items is off)")))));
		y = row(x, y, "Dropped item size", () -> settings.droppedItemSize, on -> save(() -> settings.droppedItemSize = on),
				() -> open(new OptionsScreen(this, "Dropped item size")
						.slider("Size", 100, 400, () -> settings.droppedItemSizePercent, v -> save(() -> settings.droppedItemSizePercent = v))
						.toggle("Only listed items", () -> settings.droppedItemSizeFilterOn, on -> save(() -> settings.droppedItemSizeFilterOn = on))
						.link("Item filter", () -> Mc.setScreen(minecraft, new ItemFilterScreen(currentScreen(), settings, "Item size filter",
								settings.droppedItemSizeFilter, () -> settings.droppedItemSizeFilterOn, "Only these items are made bigger", "Every item is made bigger (Only listed items is off)")))));
		y = row(x, y, "Black Ops boots glint", mod::isBlackOpsGlintOn, mod::setBlackOpsGlintOn,
				() -> open(new OptionsScreen(this, "Black Ops boots glint")
						.slider("Glint strength", 0, 100, mod::getGlintStrength, mod::setGlintStrength)
						.note("The game's Glint Strength: it applies to every glint")));
		return y;
	}

	private int buildMisc(int x, int y) {
		y = row(x, y, "Latency tester", mod::isLatencyTesterOn, mod::setLatencyTesterOn, null);
		y = row(x, y, "GTMAddOns icon", mod::isModIconsOn, mod::setModIconsOn, null);
		y = row(x, y, "Fish cat", mod::isFishHeadsOn, mod::setFishHeadsOn, null);
		Settings settings = mod.settings();
		y = row(x, y, "Extra tracking", () -> settings.extraTracking, on -> save(() -> settings.extraTracking = on), this::openExtraTracking);
		y = row(x, y, "Menu keybind", () -> settings.menuKeyOn, on -> save(() -> settings.menuKeyOn = on),
				() -> open(new KeyCaptureScreen(this, settings)));

		// Dev, QA and admin mode sit apart from the other options. Only one of dev / QA can be on; turning one on switches the other
		// off. Their ⚙ opens what they show (dev / QA) or their tools (admin).
		y += GAP;
		y = row(x, y, "Dev mode", mod::isDevModeOn, on -> setMode(false, on), () -> openModeMenu(false));
		y = row(x, y, "QA mode", mod::isQaModeOn, on -> setMode(true, on), () -> openModeMenu(true));
		Runnable adminTools = mod.isAdminModeOn() ? () -> Mc.setScreen(minecraft, new AdminToolsScreen(this, mod)) : null;
		y = row(x, y, "Admin mode", mod::isAdminModeOn, on -> toggleAdminMode(), adminTools);
		return y - GAP;
	}

	/** What the remote config's tracking rules measure right now (Probes), one note each. */
	private void openExtraTracking() {
		OptionsScreen page = new OptionsScreen(this, "Extra tracking")
				.note("Tracking rules sent by the GTMAddOns server: numbers per fight, uploaded with it")
				.note("and shown in Raw info. Turn this off to run none of them.");
		var rules = com.example.gtmaddons.probe.Probes.activeRules();
		if (rules.isEmpty()) page.note("No rules right now.");
		for (var rule : rules) page.note(rule.id + (rule.about.isEmpty() ? "" : ": " + rule.about));
		open(page);
	}

	private static final int GEAR = 20;

	private void open(Screen screen) {
		Mc.setScreen(minecraft, screen);
	}

	private Screen currentScreen() {
		return Mc.screen(minecraft);
	}

	/**
	 * Swap information's Start / Stop recording (the same as /gao swapinfo start / end). Stopping prints the averages in chat, so the
	 * menu closes to show them.
	 */
	private void toggleRecording() {
		if (SwapSession.INSTANCE.isRecording()) {
			SwapSession.INSTANCE.end();
			Mc.setScreen(minecraft, null);
		} else {
			SwapSession.INSTANCE.sendStarted(SwapSession.INSTANCE.start());
		}
	}

	/** Changes a setting and saves it. */
	private void save(Runnable change) {
		change.run();
		mod.settings().save();
	}

	/**
	 * One setting: its name with an ON/OFF pill (click it to switch the setting), and a ⚙ on the far right that opens what can be
	 * adjusted in it. With nothing to adjust the ⚙ is greyed out. Returns the next row's y.
	 */
	private int row(int x, int y, String label, java.util.function.BooleanSupplier get, java.util.function.Consumer<Boolean> set, Runnable gear) {
		addRenderableWidget(ThemedButton.create(onOff(label, get.getAsBoolean()), b -> {
			set.accept(!get.getAsBoolean());
			b.setMessage(onOff(label, get.getAsBoolean()));
		}).bounds(x, y, BUTTON_WIDTH - GEAR - 4, 20).build());
		Button settings = ThemedButton.create(Component.literal("⚙"), b -> {
			if (gear != null) gear.run();
		}).bounds(x + BUTTON_WIDTH - GEAR, y, GEAR, 20)
				.tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(gear != null ? label + " settings" : "Nothing to adjust in " + label)))
				.build();
		settings.active = gear != null;
		addRenderableWidget(settings);
		return y + ROW;
	}

	/** Total height of the page's buttons plus Back, to centre it vertically. */
	private int contentHeight() {
		int rows = switch (page) {
			case HUB -> ROW + GAP + 4 * ROW;
			case PVP -> ROW + GAP / 2 + PVP_ROWS * ROW;
			case QOL -> 6 * ROW;
			case MISC -> 4 * ROW + GAP + 3 * ROW - GAP;
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
	 * Dev or QA mode's switch. Off is immediate; on is checked with the backend first (the account must be allowed), and switches
	 * the other mode off.
	 */
	private void setMode(boolean qa, boolean on) {
		if (!on) {
			if (qa) mod.disableQaMode(message -> status = message);
			else mod.disableDevMode(message -> status = message);
			rebuildWidgets();
			return;
		}
		mod.checkDevAccess(message -> status = message, () -> {
			if (qa) mod.enableQaMode(message -> status = message);
			else mod.enableDevMode(message -> status = message);
			Mc.setScreen(minecraft, this);
			rebuildWidgets();
		});
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
