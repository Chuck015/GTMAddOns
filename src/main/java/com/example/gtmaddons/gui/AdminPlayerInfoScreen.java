package com.example.gtmaddons.gui;

import com.example.gtmaddons.GTMAddOnsClient;
import com.example.gtmaddons.compat.Gfx;
import com.example.gtmaddons.compat.Mc;
import com.example.gtmaddons.compat.ModScreen;
import com.example.gtmaddons.stats.PlayerStats.AdminPlayerInfo;
import com.example.gtmaddons.stats.PlayerStats;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Admin mode > Player info: type a player's name and see what the backend knows about them - mod version, last seen, whether
 * they are online with the mod, their fights, kills and deaths, swaps and roles. The backend only answers admin accounts
 * (ADMIN_UUIDS), so an account that is not one gets "HTTP 403". The mod version is the one the player's mod last sent; players
 * not seen since the backend started keeping it show "unknown".
 */
public class AdminPlayerInfoScreen extends ModScreen {

	private static final int WIDTH = 260;
	private static final int ROW = 12;
	private static final int LABEL = 0xFF9AA0AE, TEXT = 0xFFFFFFFF, GOOD = 0xFF55FF55, WARN = 0xFFFFAA00;

	private final Screen parent;
	private final GTMAddOnsClient mod;
	/** The name last typed, kept when the screen is rebuilt (a resize). */
	private static String lastName = "";
	private EditBox field;
	private String status = null;
	/** Opened from a player's page: look this name up straight away. */
	private boolean autoLookup = false;
	/** The Flag rules view (what each flag needs) instead of a player's info. */
	private boolean showRules = false;
	private List<PlayerStats.FlagRule> rules = null;
	private String rulesStatus = null;
	private int ruleScroll = 0;
	private Button rulesButton;
	private final List<String[]> lines = new ArrayList<>();
	private final List<Integer> colors = new ArrayList<>();

	public AdminPlayerInfoScreen(Screen parent, GTMAddOnsClient mod) {
		super(Component.literal("GTMAddOns - Player info (admin)"));
		this.parent = parent;
		this.mod = mod;
	}

	/** Opens with this player already looked up (from their stats page). */
	public AdminPlayerInfoScreen(Screen parent, GTMAddOnsClient mod, String name) {
		this(parent, mod);
		lastName = name;
		autoLookup = true;
	}

	@Override
	protected void init() {
		int x = width / 2 - WIDTH / 2;
		field = addRenderableWidget(new EditBox(font, x, 34, WIDTH - 84, 20, Component.literal("Player name")));
		field.setMaxLength(16);
		field.setHint(Component.literal("Player name"));
		field.setValue(lastName);
		addRenderableWidget(ThemedButton.create(Component.literal("Look up"), b -> lookUp())
				.bounds(x + WIDTH - 80, 34, 80, 20).build());
		addRenderableWidget(ThemedButton.create(CommonComponents.GUI_BACK, b -> onClose())
				.bounds(width / 2 - 75, height - 28, 150, 20).build());
		addRenderableWidget(ThemedButton.create(Component.literal("Stat flags"), b -> Mc.setScreen(minecraft, new AdminFlaggedScreen(this, mod)))
				.bounds(width / 2 - 75 - 124, height - 28, 120, 20).build());
		rulesButton = addRenderableWidget(ThemedButton.create(Component.literal(showRules ? "Player" : "Stat flag rules"), b -> toggleRules())
				.bounds(x + WIDTH + 4, 34, 104, 20).build());
		if (autoLookup) {
			autoLookup = false;
			lookUp();
		}
	}

	private void toggleRules() {
		showRules = !showRules;
		rulesButton.setMessage(Component.literal(showRules ? "Player" : "Stat flag rules"));
		ruleScroll = 0;
		if (showRules && rules == null) {
			rulesStatus = "Loading the stat flag rules...";
			mod.fetchAdminFlagRules().whenComplete((answer, error) -> minecraft.execute(() -> {
				if (Mc.screen(minecraft) != this) return;
				if (error != null) {
					rulesStatus = "Couldn't load the rules: " + Format.error(error);
					return;
				}
				rules = answer.rules();
				rulesStatus = null;
			}));
		}
	}

	private void lookUp() {
		String name = field.getValue().trim();
		lastName = name;
		if (showRules) toggleRules();
		lines.clear();
		colors.clear();
		if (!name.matches("[A-Za-z0-9_]{1,16}")) {
			status = "Type a player name (1-16 letters, digits or _).";
			return;
		}
		status = "Looking up " + name + "...";
		mod.fetchAdminPlayerInfo(name).whenComplete((info, error) -> minecraft.execute(() -> {
			if (Mc.screen(minecraft) != this) return;
			if (error != null) {
				String why = Format.error(error);
				status = why.contains("404") ? "No player called " + name + " has used the stats server."
						: why.contains("403") ? "Only admin accounts can look players up (HTTP 403)."
						: "Couldn't look " + name + " up: " + why;
				return;
			}
			status = null;
			show(info);
		}));
	}

	private void add(String label, String value, int color) {
		lines.add(new String[] { label, value });
		colors.add(color);
	}

	private void show(AdminPlayerInfo info) {
		add("Player", info.name() + "  (" + info.uuid() + ")", TEXT);
		add("Mod version", info.modVersion() != null ? info.modVersion() : "unknown - not seen since versions were recorded", info.modVersion() != null ? GOOD : WARN);
		add("Online now", info.online() ? "yes (heard from in the last 40 minutes)" : "no", info.online() ? GOOD : TEXT);
		add("Last seen", Format.time(info.lastSeen()) + "  (" + ago(info.lastSeen()) + ")", TEXT);
		add("First seen", Format.time(info.firstSeen()) + "  (" + ago(info.firstSeen()) + ")", TEXT);
		StringBuilder categories = new StringBuilder();
		if (info.fightsByCategory() != null) {
			for (Map.Entry<String, Long> e : info.fightsByCategory().entrySet()) {
				if (categories.length() > 0) categories.append(", ");
				categories.append(e.getKey().charAt(0)).append(e.getKey().substring(1).toLowerCase(java.util.Locale.ROOT)).append(' ').append(e.getValue());
			}
		}
		add("Fights stored", info.fights() + (categories.length() > 0 ? "  (" + categories + ")" : ""), TEXT);
		add("Kills / deaths", info.kills() + " / " + info.deaths(), TEXT);
		add("Last fight", info.lastFightAt() != null ? Format.time(info.lastFightAt()) + "  (" + ago(info.lastFightAt()) + ")" : "-", TEXT);
		add("Swaps stored", String.valueOf(info.swaps()), TEXT);
		add("Roles", (info.dev() ? "dev " : "") + (info.admin() ? "admin " : "") + (!info.dev() && !info.admin() ? "player" : ""), TEXT);

		// Flags: stats out of the norm, chosen by the backend (with a minimum sample size for each).
		add("Stat flags", info.flags() == null || info.flags().isEmpty() ? "none - nothing unusual with enough data" : info.flags().size() + (info.flags().size() == 1 ? " found" : " found"),
				info.flags() == null || info.flags().isEmpty() ? GOOD : WARN);
		if (info.flags() != null) {
			for (PlayerStats.Flag flag : info.flags()) add("", (flag.level().equals("warn") ? "! " : "- ") + flag.text(), flag.level().equals("warn") ? WARN : TEXT);
		}
	}

	/** "5 min ago", "3 h ago", "2 days ago". */
	private static String ago(long epochMillis) {
		long minutes = Math.max(0, (System.currentTimeMillis() - epochMillis) / 60_000L);
		if (minutes < 60) return minutes + " min ago";
		if (minutes < 60 * 48) return (minutes / 60) + " h ago";
		return (minutes / (60 * 24)) + " days ago";
	}

	@Override
	public void onClose() {
		Mc.setScreen(minecraft, parent);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (!showRules) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
		ruleScroll = Math.max(0, ruleScroll - (int) Math.signum(verticalAmount) * 3);
		return true;
	}

	@Override
	public void draw(Gfx context, int mouseX, int mouseY, float deltaTicks) {
		context.fill(0, 0, width, height, Ui.PAGE);
		super.draw(context, mouseX, mouseY, deltaTicks);
		context.centeredText(font, title, width / 2, 14, 0xFFFFFFFF);
		int x = width / 2 - WIDTH / 2 - 40;
		int y = 68;
		if (showRules) {
			if (rulesStatus != null || rules == null) {
				context.text(font, rulesStatus != null ? rulesStatus : "", x, y, 0xFFFFFF55);
				return;
			}
			List<String[]> rows = new ArrayList<>();
			for (PlayerStats.FlagRule rule : rules) {
				List<String> wrapped = Ui.wrap(font, rule.requirement(), width - x - 100);
				for (int j = 0; j < wrapped.size(); j++) rows.add(new String[] { j == 0 ? rule.title() : "", wrapped.get(j) });
			}
			int visible = Math.max(1, (height - 40 - y) / (ROW + 2));
			ruleScroll = Math.min(ruleScroll, Math.max(0, rows.size() - visible));
			for (int i = ruleScroll; i < rows.size() && i < ruleScroll + visible; i++) {
				context.text(font, Ui.fit(font, rows.get(i)[0], 90), x, y, rows.get(i)[0].isEmpty() ? LABEL : WARN);
				context.text(font, rows.get(i)[1], x + 92, y, TEXT);
				y += ROW + 2;
			}
			return;
		}
		if (status != null) {
			context.centeredText(font, Component.literal(status), width / 2, y, 0xFFFFFF55);
		}
		for (int i = 0; i < lines.size(); i++) {
			context.text(font, lines.get(i)[0], x, y, LABEL);
			context.text(font, Ui.fit(font, lines.get(i)[1], width - x - 100), x + 92, y, colors.get(i));
			y += ROW + 2;
		}
	}
}
