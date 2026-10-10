package com.example.gtmaddons;

import net.minecraft.client.gui.widget.ButtonWidget;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import com.example.gtmaddons.update.Updater;
import com.example.gtmaddons.gui.PlayersScreen;
import com.example.gtmaddons.gui.Format;
import com.example.gtmaddons.gui.MainScreen;
import com.example.gtmaddons.gui.PlayerScreen;
import com.example.gtmaddons.mixin.HandledScreenAccessor;
import com.example.gtmaddons.stats.PlayerStats;
import com.example.gtmaddons.stats.StatsClient;
import com.example.gtmaddons.stats.SwapRecord;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import com.example.gtmaddons.gun.DevFilter;
import com.example.gtmaddons.gun.DevLogger;
import com.example.gtmaddons.gun.ShotTracker;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.Window;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * GTMAddOns
 *
 * Two add-ons for GTM, sharing one stats backend:
 *   - swapinfo: times your wingsuit swaps, from the moment your inventory
 *     opens to the moment it closes, with a debug mode that breaks each
 *     swap down in chat.
 *   - guninfo: tracks gun shots, hits, headshots and kills all the time
 *     (see ShotTracker), with a dev mode for detailed logging (DevLogger).
 *
 * An inventory open is only treated as a swap attempt if there is a
 * wingsuit (any gliding item, e.g. an elytra) somewhere in your inventory
 * outside the hotbar when it opens. Each attempt ends in one of three
 * results:
 *   - Success:  a wingsuit reached your hotbar at some point while the
 *               inventory was open. This is checked every frame rather
 *               than at close, because on high ping the wingsuit can still
 *               show in your armor slot after it has really been moved.
 *   - Canceled: no wingsuit reached the hotbar, but you moved other items.
 *   - Failed:   no wingsuit reached the hotbar and nothing was moved.
 *
 * Detection is event-driven (Fabric's ScreenEvents), checked every
 * rendered frame while the inventory is open rather than polled once per
 * 20Hz client tick - this keeps timing precision to roughly one render
 * frame instead of up to one full tick (~50ms) behind.
 *
 * Swaps and gun totals are uploaded to the stats backend (see StatsClient)
 * under your Minecraft account, and anyone using the mod can view
 * everyone's stats. It changes nothing about gameplay.
 *
 * Everything is done through the GUI: /GTMAddOns (or /gao, /gtmaddons) opens
 * the main menu (MainScreen) with Player Stats, Personal Stats and Settings.
 * The stats sections are locked while you're combat-tagged.
 */
public class GTMAddOnsClient implements ClientModInitializer {

	// How long the corner "Last Swap" text stays up after a completed swap.
	private static final long RESULT_DISPLAY_MS = 15_000L;
	// How long the swap timer (success/failed/canceled) stays up, like an action bar message.
	private static final long SWAP_TIMER_DISPLAY_MS = 3_000L;
	private static final int HOTBAR_SIZE = 9;
	private static final long DEV_CHECK_TIMEOUT_SECONDS = 20;
	private static final int CHEST_INVENTORY_INDEX = PvpCategory.CHEST_INVENTORY_INDEX;

	private enum SwapResult { SUCCESS, FAILED, CANCELED }

	/** What an Air PvP swap was, going by what came off your body. Wing PvP swaps are all WINGSUIT. */
	private enum SwapType {
		WINGSUIT("Wingsuit swap"),
		JETPACK("Jetpack swap"),
		JP_TO_WING("Jetpack -> Wingsuit"),
		WING_TO_JP("Wingsuit -> Jetpack");

		final String label;

		SwapType(String label) {
			this.label = label;
		}
	}

	// Display state
	private boolean swapInProgress = false;
	/** How the inventory of the current swap opened (see SwapRecord): cursor offset from the window centre, GUI placement and scale. */
	private double openCursorDx, openCursorDy, openGuiX, openGuiY, openScaledW, openScaledH, openGuiScale;
	private boolean openCreative, openFromScreen;
	private long lastScreenRemovedNanos = -1L;
	/** The swap recording button was used during this swap: it counts as canceled. */
	private boolean swapCanceledByButton = false;
	private long openTimeNanos = 0L;
	// Last completed swap, shown in the corner for RESULT_DISPLAY_MS
	private double lastDurationSeconds = -1;
	private long lastSwapMillis = 0L;
	// Swap timer line and when it was set
	private Text swapTimerText = null;
	private long swapTimerMillis = 0L;

	// Wingsuit-arrival detection
	private ItemStack[] inventoryAtOpen = new ItemStack[0];
	/** The inventory differed from how it was at open at some frame: an item that was moved and put back still makes the swap CANCELED. */
	private boolean inventoryTouched = false;
	private int hotbarWingsuitsAtOpen = 0;
	private long arrivalNanos = -1L;
	private PvpCategory swapCategory = PvpCategory.WING;

	// Air PvP swaps are read from how many jetpacks/wingsuits your hotbar
	// gains or loses (the chest slot can show stale items on high ping).
	private boolean airSwap = false;
	private ItemStack chestAtOpen = ItemStack.EMPTY;
	// Momentum: horizontal speed (blocks/s) when the inventory opened, and
	// whether the wingsuit went into a hotbar slot that was empty at open.
	private double speedAtOpenBps = 0.0;
	private boolean wingToBlankSlot = false;
	// Momentum after the swap: the record waits up to MOMENTUM_WINDOW_NANOS
	// after the close (less if you land) while your speed is averaged over
	// that time, then goes to the fight.
	private static final long MOMENTUM_WINDOW_NANOS = 2_000_000_000L;
	/** Momentum is only measured when you were already moving this fast (blocks/s): sprinting is 5.6, a glide 20 or more. */
	private static final double MOMENTUM_MIN_BPS = 10.0;
	private SwapRecord momentumRecord = null;
	/** The held swap has a speed to average, and/or a window of movement keys to record. */
	private boolean momentumHasSpeed = false, momentumInputWindow = false;
	private boolean momentumInFight = false;
	private long momentumStartNanos = 0L;
	private long momentumLastNanos = 0L;
	private double momentumLastBps = 0.0;
	/** Speed x time so far, in blocks/s x nanoseconds. */
	private double momentumSum = 0.0;
	private int hotbarJetpacksAtOpen = 0;
	private int mainJetpacksAtOpen = 0;
	private int mainWingsuitsAtOpen = 0;
	private int airJetpackDelta = 0;
	private int airWingsuitDelta = 0;
	private SwapType lastSwapType = null;

	// Debug mode
	private int wingsuitInventoryIndex = -1;
	private final SwapDebugTracker debugTracker = new SwapDebugTracker();

	private final StatsClient stats = new StatsClient();
	private final Settings settings = Settings.load();

	@Override
	public void onInitializeClient() {
		Updater.INSTANCE.start();
		ModUsers.init(stats, settings);
		JetpackParticles.init(settings);
		RecipeBookHider.init(settings);
		DroppedItemLabels.init(settings);
		CobwebTransparency.init(settings);
		com.example.gtmaddons.gui.FightViews.init(settings);
		OldSneaking.init(settings);
		stats.setShowUsers(settings.showModUsers);
		HudRenderCallback.EVENT.register(this::onHudRender);
		ClientCommandRegistrationCallback.EVENT.register(this::registerCommands);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> stats.flushBlocking(3000));
		// A fight ending inside a swap's momentum window still gets that swap.
		FightTracker.INSTANCE.setBeforeFightEnds(this::finishMomentum);
		WorldRenderEvents.START_MAIN.register(context -> {
			MinecraftClient client = MinecraftClient.getInstance();
			long frameStart = System.nanoTime();
			momentumFrame(client);
			ModUsers.onFrame(client);
			// Before CombatTracker, so a death is seen before it clears the tag.
			GearTracker.INSTANCE.onFrame(client);
			DroppedItemLabels.onFrame(client);
			MovementInputTracker.INSTANCE.onFrame(client);
			FightTracker.INSTANCE.onFrame(client);
			CombatTracker.INSTANCE.onFrame(client);
			ShotTracker.INSTANCE.onFrame(client);
			NetFightEnd.INSTANCE.onFrame(client);
			showUpdateNotice(client);
			HitSounds.INSTANCE.onFrame(client);
			ComboTracker.INSTANCE.onFrame(client);
			LatencyTester.INSTANCE.onFrame(client);
			long devStart = System.nanoTime();
			DevLogger.INSTANCE.onFrame(client);
			long frameEnd = System.nanoTime();
			DevLogger.INSTANCE.perf(DevLogger.PERF_DEV, frameEnd - devStart);
			DevLogger.INSTANCE.perf(DevLogger.PERF_HOOKS, devStart - frameStart);
		});
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay) {
				FightTracker.INSTANCE.onMessage(message);
				CombatTracker.INSTANCE.onMessage(message);
			}
			ShotTracker.INSTANCE.onMessage(message, overlay);
			DevLogger.INSTANCE.onMessage(message, overlay);
		});
		// A /near reply nobody asked for is hidden (see NearList.isUnsolicitedReply).
		ClientSendMessageEvents.COMMAND.register(command -> NearList.onCommand());
		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
			if (overlay || !settings.betterNear || !NearList.isUnsolicitedReply(message)) return true;
			DevLogger.INSTANCE.noteHiddenNear(message);
			return false;
		});
		// GTM's /near reply, rewritten in place as a sorted list (see NearList).
		ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) ->
				!overlay && settings.betterNear ? NearList.reformat(message) : message);
		// Dev mode captures the reply to /near (see NearLogger).
		ClientSendMessageEvents.COMMAND.register(DevLogger.INSTANCE::onCommand);
		// Stats are only recorded during fights (combat tag to kill or death):
		// FightTracker holds them, then hands each finished fight over.
		ShotTracker.INSTANCE.addListener(FightTracker.INSTANCE::addShot);
		ComboTracker.INSTANCE.addListener(FightTracker.INSTANCE::addCombo);
		FightTracker.INSTANCE.addListener(stats::submitFight);
		ShotTracker.INSTANCE.addListener(DevLogger.INSTANCE::onShotResult);
		GunSounds.INSTANCE.init(settings);
		HudLayout.init(settings);
		ComboTracker.INSTANCE.setTimersEnabled(settings.comboTimer);
		LatencyTester.INSTANCE.setEnabled(settings.latencyTester);
		DevLogger.INSTANCE.init(settings);
		HitSounds.INSTANCE.init(settings);
		// "Use to draw lines" - drawn with the vanilla debug renders.
		WorldRenderEvents.BEFORE_DEBUG_RENDER.register(TrajectoryLines.INSTANCE::render);
		ShotTracker.INSTANCE.addListener(LatencyTester.INSTANCE::onShot);
		ShotTracker.INSTANCE.addListener(GunSounds.INSTANCE::onShot);
		ComboTracker.INSTANCE.addListener(DevLogger.INSTANCE::onComboResult);
		stats.start();

		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			CombatTracker.INSTANCE.onScreenOpened(screen);
			DevLogger.INSTANCE.onScreenOpened(screen);
			if (settings.swapRecordButton && screen instanceof InventoryScreen) addSwapRecordButton(screen);
			// AFTER_INIT also fires when the open screen is resized. Fabric
			// clears the screen's listeners then, so re-register them, but
			// don't restart a swap that's already in progress.
			// Which screen was just replaced by this one (for fromScreen): setScreen removes the old screen right before this runs.
			ScreenEvents.remove(screen).register(closedScreen -> lastScreenRemovedNanos = System.nanoTime());
			boolean resumed = swapInProgress;
			if (isTrackedInventoryScreen(screen) && (resumed || onInventoryOpened(client))) {
				if (!resumed) captureOpenState(client, screen);
				// Checked every frame so arrival and mouse tracking are frame-accurate.
				ScreenEvents.afterRender(screen).register((s, drawContext, mouseX, mouseY, tickDelta) -> onInventoryFrame(client, s));
				// Fires the instant THIS screen instance is removed - i.e.
				// exactly when it closes (or is swapped for another screen).
				ScreenEvents.remove(screen).register(closedScreen -> onInventoryClosed(client));
			}
		});
	}

	/** The Start / Stop swap recording button to the right of the inventory; follows the inventory when it moves (recipe book). */
	private void addSwapRecordButton(Screen screen) {
		HandledScreenAccessor accessor = (HandledScreenAccessor) screen;
		ButtonWidget button = ButtonWidget.builder(swapRecordLabel(), b -> {
			toggleSwapRecording();
			b.setMessage(swapRecordLabel());
		}).dimensions(accessor.gtmaddons$getX() + INVENTORY_WIDTH + 4, accessor.gtmaddons$getY(), 92, 20).build();
		Screens.getButtons(screen).add(button);
		ScreenEvents.beforeRender(screen).register((s, drawContext, mouseX, mouseY, tickDelta) -> {
			button.setX(accessor.gtmaddons$getX() + INVENTORY_WIDTH + 4);
			button.setY(accessor.gtmaddons$getY());
			button.setMessage(swapRecordLabel());
		});
	}

	private static final int INVENTORY_WIDTH = 176;

	private Text swapRecordLabel() {
		return Text.literal(SwapSession.INSTANCE.isRecording() ? "Stop recording" : "Start recording");
	}

	/** Same as /gao swapinfo start and end. */
	private void toggleSwapRecording() {
		if (swapInProgress) swapCanceledByButton = true;
		if (!SwapSession.INSTANCE.isRecording()) {
			SwapSession.INSTANCE.sendStarted(SwapSession.INSTANCE.start());
			return;
		}
		// A swap still having its momentum measured counts too.
		finishMomentum();
		SwapSession.INSTANCE.end();
	}

	/** Admin mode: what the backend knows about a player (see AdminPlayerInfoScreen). */
	public CompletableFuture<PlayerStats.AdminPlayerInfo> fetchAdminPlayerInfo(String name) {
		return stats.fetchAdminPlayerInfo(name);
	}

	/** Admin mode: sends a notice to players running the mod (see AdminNoticeScreen). */
	public CompletableFuture<PlayerStats.NoticeResult> sendAdminNotice(java.util.List<String> names, String message) {
		return stats.sendAdminNotice(names, message);
	}

	/** Admin mode: every player with a warning flag (see AdminFlaggedScreen). */
	public CompletableFuture<PlayerStats.FlaggedPlayers> fetchAdminFlagged() {
		return stats.fetchAdminFlagged();
	}

	/** Admin mode: what each flag needs before it shows (see AdminPlayerInfoScreen). */
	public CompletableFuture<PlayerStats.FlagRules> fetchAdminFlagRules() {
		return stats.fetchAdminFlagRules();
	}

	public boolean isFishHeadsOn() {
		return settings.fishHeads;
	}

	public void setFishHeadsOn(boolean on) {
		settings.fishHeads = on;
		settings.save();
	}

	public boolean isCombatTimerOn() {
		return settings.combatTimer;
	}

	public void setCombatTimerOn(boolean on) {
		settings.combatTimer = on;
		settings.save();
	}

	public boolean isOldSneakingOn() {
		return settings.oldSneaking;
	}

	public void setOldSneakingOn(boolean on) {
		settings.oldSneaking = on;
		settings.save();
	}

	public int getCobwebTransparency() {
		return settings.cobwebTransparency;
	}

	public void setCobwebTransparency(int percent) {
		settings.cobwebTransparency = Math.max(0, Math.min(100, percent));
		settings.save();
	}

	public boolean isHideJetpackParticlesOn() {
		return settings.hideJetpackParticles;
	}

	public void setHideJetpackParticlesOn(boolean on) {
		settings.hideJetpackParticles = on;
		settings.save();
	}

	public boolean isSwapRecordButtonOn() {
		return settings.swapRecordButton;
	}

	public void setSwapRecordButtonOn(boolean on) {
		settings.swapRecordButton = on;
		settings.save();
	}

	private boolean isTrackedInventoryScreen(Screen screen) {
		return screen instanceof InventoryScreen || screen instanceof CreativeInventoryScreen;
	}

	/** The only commands: /GTMAddOns, /gao and /gtmaddons, which open the menu. Everything else is in the GUI. */
	private void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher,
			net.minecraft.command.CommandRegistryAccess registryAccess) {
		for (String name : new String[] { "GTMAddOns", "gao", "gtmaddons" }) {
			dispatcher.register(literal(name).then(literal("update").executes(ctx -> {
				Updater.INSTANCE.requestUpdate();
				return 1;
			})).then(literal("help").executes(ctx -> {
				CommandHelp.send();
				return 1;
			})).then(literal("swapinfo")
					.then(literal("start").executes(ctx -> {
						SwapSession.INSTANCE.sendStarted(SwapSession.INSTANCE.start());
						return 1;
					}))
					.then(literal("end").executes(ctx -> {
						if (!SwapSession.INSTANCE.isRecording()) {
							SwapSession.INSTANCE.sendNotRecording();
						} else {
							// A swap still having its momentum measured counts too.
							finishMomentum();
							SwapSession.INSTANCE.end();
						}
						return 1;
					}))
					.executes(ctx -> {
						SwapSession.INSTANCE.sendStatus();
						return 1;
					})).executes(ctx -> {
				openMainScreen();
				return 1;
			}));
		}
	}

	/** Opens the main menu. Called from a command, so it waits for the chat screen to close first. */
	/** Update messages (see Updater), shown in chat once you're in a world. */
	/** How often the update card is shown again while an update is waiting (found, or downloaded and waiting for the game to close). */
	private static final long UPDATE_CARD_GAP_NANOS = 5L * 60 * 1_000_000_000L;
	private long lastUpdateCardNanos = 0L;

	/** Updater messages in one styled line, and the update card now and every 5 minutes while an update is waiting. */
	private void showUpdateNotice(MinecraftClient client) {
		if (client.player == null) return;
		String notice = Updater.INSTANCE.takeNotice();
		if (notice != null) {
			Text line = Text.literal("✦ ").formatted(Formatting.AQUA).append(Text.literal("GTMAddOns » ").formatted(Formatting.AQUA)).append(Text.literal(notice).formatted(Formatting.GRAY));
			client.player.sendMessage(line, false);
		}
		// A message an admin sent (Admin mode > Notify players), with the update button.
		String adminNotice = AdminNotices.INSTANCE.take();
		if (adminNotice != null) {
			Text card = Text.literal("")
					.append(Text.literal("▬".repeat(28)).formatted(Formatting.DARK_GRAY)).append("\n")
					.append(Text.literal(" ✦ ").formatted(Formatting.AQUA)).append(Text.literal("GTMAddOns » ").formatted(Formatting.AQUA)).append(Text.literal(adminNotice).formatted(Formatting.WHITE)).append("\n ")
					.append(Text.literal("[ UPDATE NOW ]").formatted(Formatting.GREEN).formatted(Formatting.BOLD).formatted(Formatting.UNDERLINE).styled(s -> s.withClickEvent(new net.minecraft.text.ClickEvent.RunCommand("/gao update")).withHoverEvent(new net.minecraft.text.HoverEvent.ShowText(Text.literal("Download the update now. It is installed when you close the game."))))).append("\n")
					.append(Text.literal("▬".repeat(28)).formatted(Formatting.DARK_GRAY));
			client.player.sendMessage(card, false);
		}
		Updater.State state = Updater.INSTANCE.state();
		if (state != Updater.State.AVAILABLE && state != Updater.State.READY) {
			lastUpdateCardNanos = 0L;
			return;
		}
		long now = System.nanoTime();
		if (lastUpdateCardNanos != 0L && now - lastUpdateCardNanos < UPDATE_CARD_GAP_NANOS) return;
		lastUpdateCardNanos = now;
		String latest = Updater.INSTANCE.latestVersion() != null ? Updater.INSTANCE.latestVersion() : "?";
		net.minecraft.text.MutableText card = Text.literal("")
				.append(Text.literal("▬".repeat(28)).formatted(Formatting.DARK_GRAY)).append("\n")
				.append(Text.literal(" ✦ ").formatted(Formatting.AQUA)).append(Text.literal(state == Updater.State.READY ? "GTMAddOns " + latest + " is downloaded" : "GTMAddOns update available").formatted(Formatting.WHITE).formatted(Formatting.BOLD)).append("\n");
		if (state == Updater.State.AVAILABLE) {
			card = card.append(Text.literal(" " + Updater.modVersion() + " → ").formatted(Formatting.GRAY)).append(Text.literal(latest).formatted(Formatting.GREEN).formatted(Formatting.BOLD)).append("   ")
					.append(Text.literal("[ UPDATE NOW ]").formatted(Formatting.GREEN).formatted(Formatting.BOLD).formatted(Formatting.UNDERLINE).styled(s -> s.withClickEvent(new net.minecraft.text.ClickEvent.RunCommand("/gao update")).withHoverEvent(new net.minecraft.text.HoverEvent.ShowText(Text.literal("Download the update now. It is installed when you close the game."))))).append("\n")
					.append(Text.literal(" Downloads in the background and installs when you close the game.").formatted(Formatting.GRAY)).append("\n")
					.append(Text.literal(" Reminder every 5 minutes until you update.").formatted(Formatting.DARK_GRAY)).append("\n");
		} else {
			card = card.append(Text.literal(" Close the game fully to finish installing it.").formatted(Formatting.GRAY)).append("\n")
					.append(Text.literal(" Reminder every 5 minutes until then.").formatted(Formatting.DARK_GRAY)).append("\n");
		}
		card = card.append(Text.literal("▬".repeat(28)).formatted(Formatting.DARK_GRAY));
		client.player.sendMessage(card, false);
	}

	private void openMainScreen() {
		MinecraftClient client = MinecraftClient.getInstance();
		client.send(() -> client.setScreen(new MainScreen(this)));
	}

	// ---- Used by the menus ----

	public boolean isSwapTimerShown() {
		return settings.showSwapTimer;
	}

	public void setSwapTimerShown(boolean shown) {
		settings.showSwapTimer = shown;
		settings.save();
	}

	public boolean isCornerSwapTextOn() {
		return settings.cornerSwapText;
	}

	public void setCornerSwapTextOn(boolean on) {
		settings.cornerSwapText = on;
		settings.save();
	}

	public boolean isLatencyTesterOn() {
		return settings.latencyTester;
	}

	public void setLatencyTesterOn(boolean on) {
		settings.latencyTester = on;
		settings.save();
		LatencyTester.INSTANCE.setEnabled(on);
	}

	/** The saved settings, for the Hit sound menu's several linked options. */
	public Settings settings() {
		return settings;
	}

	/** Dev mode's (qa = false) or QA mode's (qa = true) on/off choice for one filter. */
	public boolean isDevFilterOn(boolean qa, DevFilter filter) {
		return settings.filterOn(qa, filter);
	}

	public void setDevFilterOn(boolean qa, DevFilter filter, boolean on) {
		settings.setFilter(qa, filter, on);
	}

	public boolean isComboTimerOn() {
		return settings.comboTimer;
	}

	public void setComboTimerOn(boolean on) {
		settings.comboTimer = on;
		settings.save();
		ComboTracker.INSTANCE.setTimersEnabled(on);
	}

	public boolean isBoostAngleOn() {
		return settings.boostAngle;
	}

	public void setBoostAngleOn(boolean on) {
		settings.boostAngle = on;
		settings.save();
	}

	public boolean isModIconsOn() {
		return settings.showModUsers;
	}

	public void setModIconsOn(boolean on) {
		settings.showModUsers = on;
		settings.save();
		stats.setShowUsers(on);
	}

	public boolean isDroppedItemNamesOn() {
		return settings.droppedItemNames;
	}

	public void setDroppedItemNamesOn(boolean on) {
		settings.droppedItemNames = on;
		settings.save();
	}

	public boolean isHideRecipeBookOn() {
		return settings.hideRecipeBook;
	}

	public void setHideRecipeBookOn(boolean on) {
		settings.hideRecipeBook = on;
		settings.save();
	}

	public boolean isBetterNearOn() {
		return settings.betterNear;
	}

	public void setBetterNearOn(boolean on) {
		settings.betterNear = on;
		settings.save();
	}

	/** The Personal Stats tab last opened (Wing if it was never set). */
	public PvpCategory getStatsTab() {
		PvpCategory tab = settings.statsTab != null ? PvpCategory.fromName(settings.statsTab) : null;
		return tab != null ? tab : PvpCategory.WING;
	}

	public void setStatsTab(PvpCategory tab) {
		settings.statsTab = tab.name();
		settings.save();
	}

	/** Whether an on-screen element's setting is on, for the Move HUD screen. */
	public boolean isHudElementOn(HudLayout.Element element) {
		return switch (element) {
			case SWAP_TIMER -> settings.showSwapTimer;
			case LAST_SWAP -> settings.cornerSwapText;
			case BOOST_ANGLE -> settings.boostAngle;
			case BOOST_HEIGHT -> settings.boostHeight;
			case COMBO_LOCK, COMBO_HIT -> settings.comboTimer;
			case COMBAT_TIMER -> settings.combatTimer;
		};
	}

	public boolean isBoostHeightOn() {
		return settings.boostHeight;
	}

	public void setBoostHeightOn(boolean on) {
		settings.boostHeight = on;
		settings.save();
	}

	public boolean isSwapDebugOn() {
		return settings.swapDebug;
	}

	public void setSwapDebugOn(boolean on) {
		settings.swapDebug = on;
		settings.save();
	}

	/** Stats screens are locked while combat-tagged. */
	public boolean isInCombat() {
		return CombatTracker.INSTANCE.isTagged();
	}

	public boolean isDevModeOn() {
		return DevLogger.INSTANCE.isEnabled() && DevLogger.INSTANCE.isSavingLog();
	}

	/** QA mode: dev mode's chat lines without the log file. Same access check as dev mode. */
	public boolean isQaModeOn() {
		return DevLogger.INSTANCE.isEnabled() && !DevLogger.INSTANCE.isSavingLog();
	}

	/**
	 * Opens the Player Stats leaderboard (last opened PvP tab, last 25 fights) once it has
	 * loaded; Back returns to parent (null = close). Progress and errors are reported to status.
	 */
	public void openStatsScreen(Screen parent, Consumer<String> status) {
		if (!StatsClient.isConfigured()) {
			status.accept("The stats backend isn't set up in this build.");
			return;
		}
		status.accept("Loading the leaderboard...");
		PvpCategory tab = getLeaderboardTab();
		stats.fetchLeaderboard(tab, com.example.gtmaddons.stats.FightFilter.MAX_FIGHTS, java.util.Set.of()).whenComplete((board, error) -> {
			MinecraftClient client = MinecraftClient.getInstance();
			// Runs on the game thread, after the chat screen has closed.
			client.execute(() -> {
				if (error != null) {
					status.accept("Couldn't open stats: " + Format.error(error));
				} else if (isInCombat()) {
					status.accept("Stats are locked while you're in combat.");
				} else {
					client.setScreen(new PlayersScreen(parent, stats, this, tab, board));
				}
			});
		});
	}

	/**
	 * Opens your own stats (your last 25 fights, from the backend; the page
	 * can switch to 50 or 100). Back returns to parent.
	 */
	public void openPersonalStats(Screen parent, Consumer<String> status) {
		if (!StatsClient.isConfigured()) {
			status.accept("The stats backend isn't set up in this build.");
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		UUID me = client.getSession().getUuidOrNull();
		if (me == null) {
			status.accept("No Minecraft account signed in.");
			return;
		}
		status.accept("Loading your stats...");
		stats.fetchPlayer(me.toString().replace("-", ""), com.example.gtmaddons.gui.FightViews.get(), PlayerScreen.openingTab(this)).whenComplete((detail, error) -> client.execute(() -> {
			if (error != null) {
				status.accept("Couldn't load your stats: " + Format.error(error));
			} else if (isInCombat()) {
				status.accept("Stats are locked while you're in combat.");
			} else {
				client.setScreen(new PlayerScreen(parent, detail, stats, this));
			}
		}));
	}

	public boolean isAdminModeOn() {
		return AdminMode.isOn();
	}

	public void enableAdminMode(Consumer<String> status) {
		AdminMode.set(true);
		status.accept("Admin mode: ON - the stats screens now show delete buttons");
	}

	public void disableAdminMode(Consumer<String> status) {
		AdminMode.set(false);
		status.accept("Admin mode: OFF");
	}

	/** The Player Stats leaderboard tab last opened (Wing if it was never set). */
	public PvpCategory getLeaderboardTab() {
		PvpCategory tab = settings.leaderboardTab != null ? PvpCategory.fromName(settings.leaderboardTab) : null;
		return tab != null ? tab : PvpCategory.WING;
	}

	public void setLeaderboardTab(PvpCategory tab) {
		settings.leaderboardTab = tab.name();
		settings.save();
	}

	public void disableDevMode(Consumer<String> status) {
		DevLogger.INSTANCE.setEnabled(false);
		status.accept("Dev mode: OFF");
	}

	public void checkDevAccess(Consumer<String> status, Runnable onAllowed) {
		checkAccess("Dev access", PlayerStats.Me::dev, status, onAllowed);
	}

	/** Admin mode is only for accounts the backend lists in ADMIN_UUIDS. Same answers as the dev check, as "Admin access". */
	public void checkAdminAccess(Consumer<String> status, Runnable onAllowed) {
		checkAccess("Admin access", PlayerStats.Me::admin, status, onAllowed);
	}

	/**
	 * Dev mode is only for accounts the backend lists in DEV_UUIDS. Checks
	 * access and, if allowed, runs onAllowed (which asks for confirmation
	 * before calling enableDevMode). Always ends with an answer in status:
	 *   "Dev access: denied"      - the backend says this account isn't a dev
	 *   "Dev access: unfindable"  - no answer could be had (offline, login
	 *                               failed, no reply in DEV_CHECK_TIMEOUT, or
	 *                               an internal error), with the reason
	 */
	private void checkAccess(String label, java.util.function.Predicate<PlayerStats.Me> allowed, Consumer<String> status, Runnable onAllowed) {
		if (!StatsClient.isConfigured()) {
			status.accept(label + ": unfindable - the stats backend isn't set up in this build.");
			return;
		}
		status.accept("Checking " + label.toLowerCase(java.util.Locale.ROOT) + "...");
		CompletableFuture<PlayerStats.Me> check;
		try {
			check = stats.fetchMe().orTimeout(DEV_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (Throwable t) {
			status.accept(label + ": unfindable - " + Format.error(t));
			return;
		}
		check.whenComplete((me, error) -> MinecraftClient.getInstance().execute(() -> {
			if (error != null) {
				Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
				status.accept(label + ": unfindable - " + (cause instanceof TimeoutException
						? "no answer from the stats server within " + DEV_CHECK_TIMEOUT_SECONDS + "s"
						: Format.error(cause)));
			} else if (me == null) {
				status.accept(label + ": unfindable - the stats server sent an empty answer");
			} else if (!allowed.test(me)) {
				status.accept(label + ": denied");
			} else {
				status.accept(null);
				try {
					onAllowed.run();
				} catch (Throwable t) {
					status.accept(label + ": allowed, but the confirmation couldn't open - " + Format.error(t));
				}
			}
		}));
	}

	public void enableDevMode(Consumer<String> status) {
		DevLogger.INSTANCE.setEnabled(true);
		status.accept("Dev mode: ON - full log at " + DevLogger.INSTANCE.logPath());
	}

	/** Turns QA mode on (switching dev mode off if it was on). */
	public void enableQaMode(Consumer<String> status) {
		DevLogger.INSTANCE.setEnabled(true, false);
		status.accept("QA mode: ON - chat messages only, nothing saved");
	}

	public void disableQaMode(Consumer<String> status) {
		DevLogger.INSTANCE.setEnabled(false);
		status.accept("QA mode: OFF");
	}

	/**
	 * Starts a swap attempt if one is possible. Returns false (and tracks
	 * nothing) when there's nothing to swap: outside Air PvP that means no
	 * wingsuit outside the hotbar to move into it.
	 */
	private boolean onInventoryOpened(MinecraftClient client) {
		// A new swap ends the last one's momentum window.
		finishMomentum();
		if (client.player == null) return false;
		PlayerInventory inventory = client.player.getInventory();
		// Decided at open, before the swap changes what you're wearing.
		PvpCategory category = PvpCategory.classify(client.player);

		int targetIndex = -1;
		if (category == PvpCategory.AIR) {
			// Air swaps always go through the chest slot.
			targetIndex = CHEST_INVENTORY_INDEX;
		} else if (isWingsuit(inventory.getStack(CHEST_INVENTORY_INDEX))) {
			// Prefer the chest armor slot; otherwise the first wingsuit found.
			targetIndex = CHEST_INVENTORY_INDEX;
		} else {
			for (int i = HOTBAR_SIZE; i < inventory.size(); i++) {
				if (isWingsuit(inventory.getStack(i))) {
					targetIndex = i;
					break;
				}
			}
		}
		if (targetIndex < 0) return false;

		swapInProgress = true;
		swapCanceledByButton = false;
		arrivalNanos = -1L;
		wingsuitInventoryIndex = targetIndex;
		swapCategory = category;
		airSwap = category == PvpCategory.AIR;
		chestAtOpen = inventory.getStack(CHEST_INVENTORY_INDEX).copy();
		hotbarJetpacksAtOpen = countHotbar(inventory, PvpCategory::isJetpack);
		mainJetpacksAtOpen = countMain(inventory, PvpCategory::isJetpack);
		mainWingsuitsAtOpen = countMain(inventory, GTMAddOnsClient::isWingsuit);
		airJetpackDelta = 0;
		airWingsuitDelta = 0;
		openTimeNanos = System.nanoTime();
		speedAtOpenBps = horizontalSpeed(client);
		wingToBlankSlot = false;
		inventoryAtOpen = snapshot(inventory);
		inventoryTouched = false;
		hotbarWingsuitsAtOpen = countHotbarWingsuits(inventory);
		debugTracker.reset();
		return true;
	}

	/**
	 * Records where the cursor and the inventory are the moment the inventory appears. From gameplay vanilla puts the cursor exactly on
	 * the window centre; the recipe book shifts the inventory sideways, never down.
	 */
	private void captureOpenState(MinecraftClient client, Screen screen) {
		Window window = client.getWindow();
		openCursorDx = client.mouse.getX() - window.getWidth() / 2.0;
		openCursorDy = client.mouse.getY() - window.getHeight() / 2.0;
		openScaledW = window.getScaledWidth();
		openScaledH = window.getScaledHeight();
		openGuiScale = (double) window.getScaleFactor();
		if (screen instanceof HandledScreen<?> handled) {
			HandledScreenAccessor accessor = (HandledScreenAccessor) handled;
			openGuiX = accessor.gtmaddons$getX();
			openGuiY = accessor.gtmaddons$getY();
		} else {
			openGuiX = openGuiY = 0;
		}
		openCreative = screen instanceof CreativeInventoryScreen;
		openFromScreen = lastScreenRemovedNanos >= 0 && System.nanoTime() - lastScreenRemovedNanos < 5_000_000L;
	}

	private void onInventoryFrame(MinecraftClient client, Screen screen) {
		if (!swapInProgress) return;
		if (!inventoryTouched && client.player != null && inventoryChanged(client.player.getInventory())) inventoryTouched = true;
		long now = System.nanoTime();
		// Sample the mouse before checking arrival, so this frame's movement
		// is counted as leading up to the move rather than after it.
		debugTracker.sample(client.mouse.getX(), client.mouse.getY(),
				wingsuitSlotRect(client, screen), arrivalNanos >= 0, now);
		checkWingsuitArrival(client, now);
	}

	/**
	 * Wing PvP: the swap is made the moment a wingsuit reaches the hotbar.
	 * Air PvP: the swap is made when the hotbar's jetpack/wingsuit counts
	 * reach their final values - a mouse-drag swap passes through in-between
	 * states, so the time is updated on every change and the last one wins.
	 */
	private void checkWingsuitArrival(MinecraftClient client, long nowNanos) {
		if (!swapInProgress || client.player == null) return;
		PlayerInventory inventory = client.player.getInventory();
		if (airSwap) {
			int jetpackDelta = countHotbar(inventory, PvpCategory::isJetpack) - hotbarJetpacksAtOpen;
			int wingsuitDelta = countHotbarWingsuits(inventory) - hotbarWingsuitsAtOpen;
			if (jetpackDelta != airJetpackDelta || wingsuitDelta != airWingsuitDelta) {
				airJetpackDelta = jetpackDelta;
				airWingsuitDelta = wingsuitDelta;
				arrivalNanos = jetpackDelta != 0 || wingsuitDelta != 0 ? nowNanos : -1L;
				// Momentum, as for Wing: a wingsuit that ended in a hotbar slot that was empty when you opened the inventory.
				wingToBlankSlot = false;
				if (wingsuitDelta > 0) {
					for (int i = 0; i < HOTBAR_SIZE && i < inventoryAtOpen.length; i++) {
						if (isWingsuit(inventory.getStack(i)) && inventoryAtOpen[i].isEmpty()) wingToBlankSlot = true;
					}
				}
			}
		} else if (arrivalNanos < 0 && countHotbarWingsuits(inventory) > hotbarWingsuitsAtOpen) {
			arrivalNanos = nowNanos;
			// Momentum is only recorded for a wingsuit moved into an empty hotbar slot.
			for (int i = 0; i < HOTBAR_SIZE && i < inventoryAtOpen.length; i++) {
				if (isWingsuit(inventory.getStack(i)) && inventoryAtOpen[i].isEmpty()) wingToBlankSlot = true;
			}
		}
	}

	/** Your horizontal speed in blocks per second (velocity is per tick). */
	private static double horizontalSpeed(MinecraftClient client) {
		if (client.player == null) return 0.0;
		Vec3d v = client.player.getVelocity();
		return Math.hypot(v.x, v.z) * 20.0;
	}

	/**
	 * Works out an Air swap from the hotbar changes. Something coming off
	 * your body lands in the hotbar (count goes up) and must have been in
	 * your chest slot at open; something going on leaves the hotbar (count
	 * goes down). Either way your main inventory's count must be unchanged,
	 * so the item really went to/from the chest slot. Returns null if the
	 * changes don't add up to a swap (e.g. just moving a jetpack between
	 * your inventory and hotbar).
	 */
	private SwapType airSwapType(PlayerInventory inventory) {
		if (countMain(inventory, PvpCategory::isJetpack) != mainJetpacksAtOpen
				|| countMain(inventory, GTMAddOnsClient::isWingsuit) != mainWingsuitsAtOpen) {
			return null;
		}
		boolean jetpackCameOff = airJetpackDelta > 0 && PvpCategory.isWornJetpack(chestAtOpen);
		boolean wingsuitCameOff = airWingsuitDelta > 0 && PvpCategory.isWingsuit(chestAtOpen);
		if (jetpackCameOff && airWingsuitDelta < 0) return SwapType.JP_TO_WING;
		if (wingsuitCameOff && airJetpackDelta < 0) return SwapType.WING_TO_JP;
		if (airWingsuitDelta == 0 && (jetpackCameOff || airJetpackDelta < 0)) return SwapType.JETPACK;
		if (airJetpackDelta == 0 && (wingsuitCameOff || airWingsuitDelta < 0)) return SwapType.WINGSUIT;
		return null;
	}

	private void onInventoryClosed(MinecraftClient client) {
		if (!swapInProgress) return;
		long closeNanos = System.nanoTime();
		// Catch an arrival that happened after the last rendered frame.
		checkWingsuitArrival(client, closeNanos);
		swapInProgress = false;

		double totalSeconds = (closeNanos - openTimeNanos) / 1_000_000_000.0;
		boolean canceledByButton = swapCanceledByButton;
		swapCanceledByButton = false;
		if (canceledByButton) arrivalNanos = -1L;

		SwapType type = null;
		if (canceledByButton) {
			// no type: the swap didn't finish
		} else if (airSwap) {
			type = client.player != null ? airSwapType(client.player.getInventory()) : null;
			if (type == null) arrivalNanos = -1L;
		} else if (arrivalNanos >= 0) {
			type = SwapType.WINGSUIT;
		}

		SwapResult result;
		if (arrivalNanos >= 0) {
			result = SwapResult.SUCCESS;
		} else if (canceledByButton || inventoryTouched || (client.player != null && inventoryChanged(client.player.getInventory()))) {
			result = SwapResult.CANCELED;
		} else {
			result = SwapResult.FAILED;
		}
		lastSwapType = type;

		// The corner only shows completed swaps; failed/canceled ones leave it alone.
		if (result == SwapResult.SUCCESS) {
			lastSwapMillis = System.currentTimeMillis();
			lastDurationSeconds = totalSeconds;
		}

		if (settings.showSwapTimer && client.player != null) {
			Text message = switch (result) {
				case SUCCESS -> Text.literal(String.format("Swap Timer: %.3fs", totalSeconds)).formatted(Formatting.YELLOW);
				case FAILED -> Text.literal("Swap Failed").formatted(Formatting.RED);
				case CANCELED -> Text.literal("Swap Canceled").formatted(Formatting.GRAY);
			};
			// Drawn by onHudRender (movable), not the action bar.
			swapTimerText = message;
			swapTimerMillis = System.currentTimeMillis();
		}

		SwapRecord record = buildRecord(client, result, closeNanos);
		// A Wing / Air swap that put the chest-slot item into an empty hotbar slot also has its movement keys recorded afterwards.
		boolean inputWindow = result == SwapResult.SUCCESS && wingToBlankSlot;
		if (record.speedBeforeBps() != null || inputWindow) {
			// Momentum / movement swap: held back while the speed and keys after it are measured (see momentumFrame).
			momentumRecord = record;
			momentumHasSpeed = record.speedBeforeBps() != null;
			momentumInputWindow = inputWindow;
			if (inputWindow) MovementInputTracker.INSTANCE.startSwapWindow();
			momentumInFight = FightTracker.INSTANCE.inFight();
			momentumStartNanos = momentumLastNanos = closeNanos;
			momentumLastBps = horizontalSpeed(client);
			momentumSum = 0.0;
			return;
		}
		recordSwap(record);
	}

	/** Hands a finished swap to the fight (only kept if it ends in a kill or death) and the debug report. */
	private void recordSwap(SwapRecord record) {
		FightTracker.INSTANCE.addSwap(record);
		SwapSession.INSTANCE.add(record);
		if (settings.swapDebug) {
			sendDebugReport(record);
		}
	}

	/**
	 * Every frame while a momentum swap is waiting: adds up your speed over
	 * time, and finishes it MOMENTUM_WINDOW_NANOS after the close or as soon
	 * as you land. (Not when gliding stops - taking the wingsuit off is the
	 * swap itself.)
	 */
	private void momentumFrame(MinecraftClient client) {
		if (momentumRecord == null) return;
		long now = System.nanoTime();
		momentumSum += momentumLastBps * (now - momentumLastNanos);
		momentumLastNanos = now;
		if (client.player == null) {
			finishMomentum();
			return;
		}
		momentumLastBps = horizontalSpeed(client);
		if (now - momentumStartNanos >= MOMENTUM_WINDOW_NANOS || client.player.isOnGround()) finishMomentum();
	}

	/**
	 * Ends the waiting momentum swap with the average speed so far: the
	 * window ran out, you landed, a new swap started, or the fight is
	 * ending (so the swap still counts toward it).
	 */
	private void finishMomentum() {
		SwapRecord record = momentumRecord;
		if (record == null) return;
		momentumRecord = null;
		long elapsed = momentumLastNanos - momentumStartNanos;
		SwapRecord done = momentumHasSpeed ? record.withSpeedAfterBps(elapsed > 0 ? momentumSum / elapsed : momentumLastBps) : record;
		if (momentumInputWindow) {
			MovementInputTracker.Stats keys = MovementInputTracker.INSTANCE.finishSwapWindow();
			if (keys != null) done = done.withAfterInput(Math.round(keys.w), Math.round(keys.a), Math.round(keys.s), Math.round(keys.d), Math.round(keys.ms), keys.strafeSwitches);
		}
		// The fight it was made in, or the one that started just after it (FightTracker counts the 10 s before a fight).
		FightTracker.INSTANCE.addSwap(done);
		SwapSession.INSTANCE.add(done);
		if (settings.swapDebug) {
			sendDebugReport(done);
		}
	}

	private SwapRecord buildRecord(MinecraftClient client, SwapResult result, long closeNanos) {
		boolean reached = debugTracker.reachedSlot();
		boolean arrived = arrivalNanos >= 0;
		boolean seen = debugTracker.slotSeen();
		long reachNanos = debugTracker.reachNanos();
		double deg = degreesPerPixel(client);
		// Momentum: speed before and right after a Wing swap into an empty hotbar slot.
		boolean momentum = result == SwapResult.SUCCESS && wingToBlankSlot && speedAtOpenBps >= MOMENTUM_MIN_BPS;

		return new SwapRecord(
				System.currentTimeMillis(),
				result.name(),
				millis(closeNanos - openTimeNanos),
				reached ? millis(reachNanos - openTimeNanos) : null,
				reached && arrived ? millis(arrivalNanos - reachNanos) : null,
				arrived ? millis(closeNanos - arrivalNanos) : null,
				arrived ? millis(arrivalNanos - openTimeNanos) : null,
				debugTracker.totalPath() * deg,
				seen ? debugTracker.approachPath() * deg : null,
				seen ? debugTracker.directDistance() * deg : null,
				seen ? debugTracker.efficiency() : null,
				seen ? debugTracker.awayPath() * deg : null,
				seen && reached ? debugTracker.overflickPath() * deg : null,
				seen && reached ? debugTracker.overflickPeak() * deg : null,
				arrived ? debugTracker.afterArrivalPath() * deg : null,
				swapCategory.name(),
				lastSwapType != null ? lastSwapType.name() : null,
				momentum ? speedAtOpenBps : null,
				momentum ? horizontalSpeed(client) : null,
				openCursorDx, openCursorDy,
				seen ? debugTracker.directDistance() : null,
				seen ? debugTracker.approachPath() : null,
				openGuiX, openGuiY, openScaledW, openScaledH, openGuiScale,
				openCreative ? 1.0 : 0.0, openFromScreen ? 1.0 : 0.0,
				null, null, null, null, null, null);
	}

	private void sendDebugReport(SwapRecord r) {
		SwapSession.sendSwapReport(r, airSwap, wingsuitInventoryIndex == CHEST_INVENTORY_INDEX,
				r.swapType() != null ? SwapType.valueOf(r.swapType()).label : null);
	}

	/**
	 * The on-screen hover area of the slot the wingsuit started in, in
	 * window pixels (matching Mouse.getX/getY), or null if that slot isn't
	 * part of the current screen (e.g. a non-inventory creative tab).
	 */
	private double[] wingsuitSlotRect(MinecraftClient client, Screen screen) {
		if (client.player == null || !(screen instanceof HandledScreen<?> handled)) return null;
		PlayerInventory inventory = client.player.getInventory();
		for (Slot slot : handled.getScreenHandler().slots) {
			if (slot.inventory == inventory && slot.getIndex() == wingsuitInventoryIndex) {
				HandledScreenAccessor accessor = (HandledScreenAccessor) handled;
				Window window = client.getWindow();
				double scale = window.getWidth() / (double) window.getScaledWidth();
				// Same 18x18 hover area HandledScreen uses for a 16x16 slot.
				double minX = accessor.gtmaddons$getX() + slot.x - 1;
				double minY = accessor.gtmaddons$getY() + slot.y - 1;
				return new double[] { minX * scale, minY * scale, (minX + 18) * scale, (minY + 18) * scale };
			}
		}
		return null;
	}

	/**
	 * How far your camera would turn per pixel of mouse movement at your
	 * current sensitivity - the same formula Minecraft uses for looking
	 * around - so inventory cursor movement can be read in degrees.
	 */
	private static double degreesPerPixel(MinecraftClient client) {
		double f = client.options.getMouseSensitivity().getValue() * 0.6 + 0.2;
		return f * f * f * 8.0 * 0.15;
	}

	private static double millis(long nanos) {
		return nanos / 1_000_000.0;
	}

	private static boolean isWingsuit(ItemStack stack) {
		return PvpCategory.isWingsuit(stack);
	}

	private static int countHotbarWingsuits(PlayerInventory inventory) {
		return countHotbar(inventory, GTMAddOnsClient::isWingsuit);
	}

	/** Counts matching items in the main inventory (not the hotbar, armor or offhand). */
	private static int countMain(PlayerInventory inventory, Predicate<ItemStack> matches) {
		int count = 0;
		for (int i = HOTBAR_SIZE; i < PlayerInventory.MAIN_SIZE; i++) {
			if (matches.test(inventory.getStack(i))) count++;
		}
		return count;
	}

	private static int countHotbar(PlayerInventory inventory, Predicate<ItemStack> matches) {
		int count = 0;
		for (int i = 0; i < HOTBAR_SIZE; i++) {
			if (matches.test(inventory.getStack(i))) count++;
		}
		return count;
	}

	private static ItemStack[] snapshot(PlayerInventory inventory) {
		ItemStack[] stacks = new ItemStack[inventory.size()];
		for (int i = 0; i < stacks.length; i++) {
			stacks[i] = inventory.getStack(i).copy();
		}
		return stacks;
	}

	private boolean inventoryChanged(PlayerInventory inventory) {
		if (inventory.size() != inventoryAtOpen.length) return true;
		for (int i = 0; i < inventoryAtOpen.length; i++) {
			if (!ItemStack.areEqual(inventoryAtOpen[i], inventory.getStack(i))) return true;
		}
		return false;
	}

	private void onHudRender(DrawContext drawContext, RenderTickCounter tickCounter) {
		long hudStart = System.nanoTime();
		drawHud(drawContext, tickCounter);
		DevLogger.INSTANCE.perf(DevLogger.PERF_HUD, System.nanoTime() - hudStart);
	}

	private void drawHud(DrawContext drawContext, RenderTickCounter tickCounter) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.options.hudHidden || client.textRenderer == null) return;
		// Each element is drawn where Settings > Move HUD put it (see HudLayout).
		// Most important first: anything overlapping an earlier one is skipped.
		TextRenderer font = client.textRenderer;
		long nowMillis = System.currentTimeMillis();
		HudLayout.beginFrame();
		// The combo timers check their own setting, and only one shows at a time.
		HudLayout.draw(drawContext, font, HudLayout.Element.COMBO_LOCK, ComboTracker.INSTANCE.lockText());
		HudLayout.draw(drawContext, font, HudLayout.Element.COMBO_HIT, ComboTracker.INSTANCE.hitText());
		if (settings.combatTimer) HudLayout.draw(drawContext, font, HudLayout.Element.COMBAT_TIMER, CombatTracker.INSTANCE.timerText());
		if (settings.boostAngle) HudLayout.draw(drawContext, font, HudLayout.Element.BOOST_ANGLE, BoostAngleHud.text(client));
		if (settings.boostHeight) HudLayout.draw(drawContext, font, HudLayout.Element.BOOST_HEIGHT, BoostHeightHud.text(client));
		if (settings.showSwapTimer && swapTimerText != null && nowMillis - swapTimerMillis < SWAP_TIMER_DISPLAY_MS) {
			HudLayout.draw(drawContext, font, HudLayout.Element.SWAP_TIMER, swapTimerText);
		}
		// Only completed swaps are shown in the corner - no live timer, and
		// failed/canceled swaps don't replace the last good time.
		if (settings.cornerSwapText && lastDurationSeconds >= 0 && nowMillis - lastSwapMillis < RESULT_DISPLAY_MS) {
			HudLayout.draw(drawContext, font, HudLayout.Element.LAST_SWAP,
					Text.literal(String.format("Last Swap: %.3fs", lastDurationSeconds)));
		}
	}
}
