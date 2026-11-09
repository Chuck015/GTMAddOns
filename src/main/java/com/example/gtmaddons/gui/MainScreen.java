package com.example.gtmaddons.gui;

import com.example.gtmaddons.CreditsAudio;
import com.example.gtmaddons.FightTracker;
import com.example.gtmaddons.GTMAddOnsClient;
import com.example.gtmaddons.SessionStats;
import com.example.gtmaddons.compat.Gfx;
import com.example.gtmaddons.compat.Mc;
import com.example.gtmaddons.compat.ModScreen;
import com.example.gtmaddons.update.Updater;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * The GTMAddOns menu (/gao), in the stats pages' look:
 *
 *   [ GTMAddOns               v1.5.1 - MC 1.21.11 ]   header card: version, dev / QA / admin tags
 *   [ Player Stats        ] [ Personal Stats      ]   tiles (both stats tiles are locked while you're combat-tagged)
 *   [ Mods                                          ]   the settings
 *   [ THIS SESSION: kills, deaths, average swap, last fight ]   from memory only (SessionStats), no backend reads
 *   [ Update to x.y.z ]   only when there is one
 *   [ Reset session ] [ Done ]                    [Credits]   reset session = start This session's numbers over
 */
public class MainScreen extends ModScreen {

	private static final int PANEL_MAX = 320;
	private static final int HEADER_H = 34, TILE_H = 36, SESSION_H = 38, GAP = 6;
	private static final int CONTENT_H = HEADER_H + GAP + TILE_H * 2 + GAP + GAP + SESSION_H + GAP + 20 + GAP + 20;
	private static final String DOT = "  ·  ";
	private static final Tooltip COMBAT_LOCKED = Tooltip.create(Component.literal("Locked while you're in combat"));

	private final GTMAddOnsClient mod;
	private Button playerStats;
	private Button personalStats;
	private Button update;
	private Button credits;
	private String status = null;
	private int left, top, panelW;

	public MainScreen(GTMAddOnsClient mod) {
		super(Component.literal("GTMAddOns"));
		this.mod = mod;
	}

	@Override
	protected void init() {
		Updater.INSTANCE.recheckSoon();
		ThemedButton.accent = Ui.BLUE;
		panelW = Math.min(PANEL_MAX, width - 16);
		left = (width - panelW) / 2;
		top = Math.max(6, (height - CONTENT_H) / 2);
		int tileW = (panelW - GAP) / 2, rightX = left + panelW - tileW;
		int tilesY = top + HEADER_H + GAP;

		playerStats = addRenderableWidget(ThemedButton.create(Component.literal("Player Stats"),
				b -> mod.openStatsScreen(this, message -> status = message))
				.subtitle("Leaderboards, everyone's stats").bounds(left, tilesY, tileW, TILE_H).build());
		personalStats = addRenderableWidget(ThemedButton.create(Component.literal("Personal Stats"),
				b -> mod.openPersonalStats(this, message -> status = message))
				.subtitle("Your fights, swaps, ratings").bounds(rightX, tilesY, tileW, TILE_H).build());
		addRenderableWidget(ThemedButton.create(Component.literal("Mods"), b -> Mc.setScreen(minecraft, new SettingsScreen(this, mod)))
				.subtitle("PvP, QOL, sounds and more").bounds(left, tilesY + TILE_H + GAP, panelW, TILE_H).build());

		int updateY = tilesY + TILE_H * 2 + GAP + GAP + SESSION_H + GAP;
		update = addRenderableWidget(ThemedButton.create(Component.literal("Update"), b -> Updater.INSTANCE.requestUpdate())
				.accent(Ui.ORANGE).bounds(left, updateY, panelW, 20).build());
		addRenderableWidget(ThemedButton.create(Component.literal("Reset session"), b -> SessionStats.INSTANCE.reset())
				.tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Start This session's kills, deaths, swaps and last fight over")))
				.bounds(width / 2 - 102, updateY + 20 + GAP, 100, 20).build());
		addRenderableWidget(ThemedButton.create(CommonComponents.GUI_DONE, b -> onClose())
				.bounds(width / 2 + 2, updateY + 20 + GAP, 100, 20).build());
		// Bottom-right corner: plays the credits audio, press again to stop (CreditsAudio).
		credits = addRenderableWidget(ThemedButton.create(Component.literal(creditsLabel()), b -> {
			CreditsAudio.INSTANCE.toggle(Mc.masterVolume(minecraft));
			b.setMessage(Component.literal(creditsLabel()));
		}).bounds(width - 66, height - 26, 60, 20).build());
		updateCombatLock();
		updateUpdateButton();
	}

	@Override
	public void tick() {
		updateCombatLock();
		updateUpdateButton();
		credits.setMessage(Component.literal(creditsLabel()));
	}

	private static String creditsLabel() {
		return CreditsAudio.INSTANCE.isPlaying() ? "Stop" : "Credits";
	}

	/** Shown only when there's an update: offers it, then shows its progress. */
	private void updateUpdateButton() {
		Updater.State state = Updater.INSTANCE.state();
		String version = Updater.INSTANCE.latestVersion();
		update.visible = state == Updater.State.AVAILABLE || state == Updater.State.DOWNLOADING || state == Updater.State.READY
				|| (state == Updater.State.FAILED && version != null);
		update.active = state == Updater.State.AVAILABLE || state == Updater.State.FAILED;
		String label = switch (state) {
			case DOWNLOADING -> "Downloading " + version + "...";
			case READY -> "Update " + version + " ready - restart to install";
			case FAILED -> "Update failed - retry";
			default -> "Update to " + version;
		};
		update.setMessage(Component.literal(label));
	}

	private void updateCombatLock() {
		boolean locked = mod.isInCombat();
		for (Button button : new Button[] { playerStats, personalStats }) {
			button.active = !locked;
			button.setTooltip(locked ? COMBAT_LOCKED : null);
		}
	}

	@Override
	public void draw(Gfx context, int mouseX, int mouseY, float deltaTicks) {
		context.fill(0, 0, width, height, Ui.PAGE);
		super.draw(context, mouseX, mouseY, deltaTicks);

		// Header: name, version and any special modes.
		Ui.panel(context, left, top, panelW, HEADER_H, Ui.BLUE);
		Ui.bigText(context, font, "GTMAddOns", left + 8, top + 9, 1.5f, Ui.TEXT, false);
		Ui.textRight(context, font, "v" + Updater.modVersion() + DOT + "MC " + Updater.minecraftVersion(), left + panelW - 8, top + 7, Ui.MUTED);
		int tagRight = left + panelW - 8;
		for (Tag tag : tags()) {
			Ui.textRight(context, font, tag.text(), tagRight, top + 20, tag.color());
			tagRight -= font.width(tag.text()) + 8;
		}

		// This session at a glance.
		int sessionY = top + HEADER_H + GAP + TILE_H * 2 + GAP + GAP;
		Ui.panel(context, left, sessionY, panelW, SESSION_H, Ui.ORANGE);
		context.text(font, Component.literal("THIS SESSION").withStyle(ChatFormatting.BOLD), left + 6, sessionY + 7, Ui.ORANGE);
		SessionStats session = SessionStats.INSTANCE;
		String counts = session.kills() + " kills" + DOT + session.deaths() + " deaths"
				+ (session.averageSwapMs() >= 0 ? DOT + "avg swap " + Math.round(session.averageSwapMs()) + " ms" : "");
		Ui.textRight(context, font, counts, left + panelW - 6, sessionY + 7, Ui.TEXT);
		FightTracker.Fight last = session.lastFight();
		String lastLine = last == null ? "No fights yet this session"
				: "Last fight: " + last.outcome() + " vs " + last.opponent() + " (" + last.category().label + ")" + DOT + ago(last.endedAt());
		context.text(font, Ui.fit(font, lastLine, panelW - 12), left + 6, sessionY + 23,
				last == null ? Ui.MUTED : "KILL".equals(last.outcome()) ? Ui.GOOD : "DEATH".equals(last.outcome()) ? Ui.BAD : Ui.LABEL);

		// A message from opening the stats (or the combat lock), where the update banner goes when there isn't one.
		String line = mod.isInCombat() ? "In combat - stats are locked" : status;
		if (line != null && !update.visible) {
			context.centeredText(font, Component.literal(Ui.fit(font, line, panelW)), width / 2, sessionY + SESSION_H + GAP + 6, Ui.OK);
		}
	}

	private record Tag(String text, int color) {}

	/** The header's tags, drawn right to left: admin / QA / dev mode. */
	private java.util.List<Tag> tags() {
		java.util.List<Tag> tags = new java.util.ArrayList<>();
		if (mod.isAdminModeOn()) tags.add(new Tag("ADMIN", Ui.BAD));
		if (mod.isQaModeOn()) tags.add(new Tag("QA", Ui.OK));
		else if (mod.isDevModeOn()) tags.add(new Tag("DEV", Ui.OK));
		return tags;
	}

	private static String ago(long millis) {
		long s = Math.max(0, (System.currentTimeMillis() - millis) / 1000);
		return s < 60 ? s + "s ago" : s < 3600 ? (s / 60) + "m ago" : (s / 3600) + "h ago";
	}
}
