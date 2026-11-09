package com.example.gtmaddons;

import com.example.gtmaddons.gun.DevFilter;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Toggles that should survive a restart, saved to config/gtmaddons.json. A new install starts with every feature off.
 * Dev mode isn't saved - it needs an access check with the backend each
 * time it's turned on.
 */
public final class Settings {

	private static final Logger LOGGER = LoggerFactory.getLogger("gtmaddons");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Swap time in the action bar. */
	public boolean showSwapTimer = false;
	/** "Last Swap" text in the top-left corner. */
	public boolean cornerSwapText = false;
	/** Advanced swap info: full breakdown in chat after each swap. */
	public boolean swapDebug = false;
	/** Latency tester: gun and wing boost response times in chat (see LatencyTester). */
	public boolean latencyTester = false;
	/** Live Combo'd and hit-again timers in place of GTM's frozen messages (see ComboTracker). */
	public boolean comboTimer = false;
	/** JP: Better swing - every swing of yours restarts the animation, at betterSwingSpeed % of its normal speed (BetterSwing; visual only). */
	public boolean betterSwing = false;
	public int betterSwingSpeed = 100;
	/** Better swing's "Always swing": restart the swing on every click (the speed applies either way). */
	public boolean betterSwingRestart = true;
	/** Look angle under the crosshair while gliding, green when a boost would work (see BoostAngleHud). */
	public boolean boostAngle = false;
	/** Height under the crosshair while gliding, green when low enough to boost (see BoostHeightHud). */
	public boolean boostHeight = false;
	/** Boost height limit: the height line under the crosshair (its original function) and the grid at the limit (BoostLimitView). */
	public boolean boostHeightReadout = true;
	public boolean boostLimitView = false;
	/** How many seconds the readout and the limit view stay up after you stop gliding (0 = they go at once). */
	public int boostLingerSeconds = 3;
	/** Misc > Menu keybind: a key that opens the GTMAddOns menu (MenuKey; a GLFW key code, -1 = none). */
	public boolean menuKeyOn = false;
	public int menuKey = -1;
	/** /near's reply as a sorted list, one player per line, in place of GTM's one long line (see NearList). */
	public boolean betterNear = false;
	/** Better near: keep a player's job tag (cop, hitman) in front of their name. */
	public boolean nearJobTags = true;
	/** Hide the recipes inside the recipe book of the survival inventory; the book itself still opens and closes (RecipeBookHider). */
	public boolean hideRecipeBook = false;
	/** Wing: Armor hotkey lock - hotbar / offhand swap keys do nothing over the helmet, leggings and boots slots of your inventory (ChestHotkeys). */
	public boolean chestHotkeysOnly = false;
	/** Misc: Extra tracking - run the tracking rules from the remote config (Probes) and upload their numbers with each fight. */
	public boolean extraTracking = true;
	/** Names above items lying on the ground, only for items in plain view (DroppedItemLabels). */
	public boolean droppedItemNames = false;
	/** Dropped item names: only name items whose name contains one of droppedItemFilter (ignoring case); off = every item. */
	public boolean droppedItemFilterOn = false;
	public java.util.List<String> droppedItemFilter = new java.util.ArrayList<>();
	/** Dropped item size (DroppedItemSizes): draw items on the ground at droppedItemSizePercent; only the listed ones while the filter is on. */
	public boolean droppedItemSize = false;
	public int droppedItemSizePercent = 200;
	public boolean droppedItemSizeFilterOn = true;
	public java.util.List<String> droppedItemSizeFilter = new java.util.ArrayList<>();
	/** The enchantment glint on the item named Black Ops Boots (BlackOpsGlint). */
	public boolean blackOpsGlint = false;
	/** An icon after the name of players who run GTMAddOns (tab list and nametags). */
	public boolean showModUsers = false;

	/** A Start / Stop recording button beside the inventory for the swap recording (SwapSession). */
	public boolean swapRecordButton = false;
	/** Swap information (Settings > Wing): the switch over the recording button, advanced swap info and auto playback. */
	public boolean swapInfo = false;
	/** Set once swapInfo has been switched on for players who already used one of its parts. */
	public boolean swapInfoMigrated = false;
	/** Swap recording's auto playback: the swap averages of every recorded fight in chat when it ends (SwapSession.sendFight). */
	public boolean swapAutoPlayback = false;

	/**
	 * Better jetpack particles (JetpackParticles): the flame and cloud particles of flying jetpacks are hidden (jetpackParticlesHide)
	 * or drawn at jetpackParticleSize %. The field keeps its old name so the old "Hide jetpack particles" switch carries over.
	 */
	public boolean hideJetpackParticles = false;
	public boolean jetpackParticlesHide = true;
	public int jetpackParticleSize = 100;

	/** Gun tracers (GunTracers): the size of everyone's gun tracers, All x category x gun, each 0-100% (0 hides them). */
	public boolean gunTracers = false;
	public int tracerSizeAll = 100;
	public Map<String, Integer> tracerSizeCategory = new HashMap<>();
	public Map<String, Integer> tracerSizeGun = new HashMap<>();

	/** 0-100: how much of the cobweb texture is made see-through (CobwebTransparency). */
	public int cobwebTransparency = 0;
	/** QOL: how see-through the survival inventory's panel is, 0-100% (100 = fully clear; see InventoryTransparency). */
	public int inventoryTransparency = 0;
	/** The percentages the two transparency settings go back to when switched on again (their sliders set them). */
	public int cobwebTransparencyLast = 50;
	public int inventoryTransparencyLast = 50;

	/** Crouch while flying and sneaking, like 1.8 (OldSneaking). */
	public boolean oldSneaking = false;

	/** A HUD line with the seconds left of the combat tag (CombatTracker.timerText). */
	public boolean combatTimer = false;
	/** Combat timer: a sound when the combat tag ends (CombatTracker). */
	public boolean combatTimerSound = false;

	/** Draw every player's head as a fish cat (FishHeads). */
	public boolean fishHeads = false;

	/** The 25 / 50 / 100 fights choice of the stats screens (FightViews). */
	public int statsFights = 500;
	/** Hit sound: play a sound when you hit a player (see HitSounds). Off by default. */
	public boolean hitSound = false;
	/** The sound's vanilla id, its volume (0-1) and pitch (0.5-2). */
	public String hitSoundId = "minecraft:entity.experience_orb.pickup";
	public float hitSoundVolume = 1.0f;
	public float hitSoundPitch = 1.0f;
	/** The headshot and melee sounds' own choices; null / -1 = follow the body shot sound above (the only one that used to exist). */
	public String hitSoundHeadshotId = null;
	public float hitSoundHeadshotVolume = -1.0f;
	public float hitSoundHeadshotPitch = -1.0f;
	public String hitSoundMeleeId = null;
	public float hitSoundMeleeVolume = -1.0f;
	public float hitSoundMeleePitch = -1.0f;
	/** Which hits play it: a headshot, a body shot (a gun hit that wasn't a headshot), a melee hit. */
	public boolean hitSoundHeadshot = true;
	public boolean hitSoundBody = true;
	public boolean hitSoundMelee = true;
	/**
	 * Dev mode's and QA mode's filters that are switched OFF (DevFilter names). Stored as the
	 * off ones so a filter added later is on by default. Each mode has its own list.
	 */
	public Set<String> devFiltersOff = new HashSet<>();
	public Set<String> qaFiltersOff = new HashSet<>();
	/** The Player Stats leaderboard tab (a PvpCategory name) last opened, so it opens there next time. */
	public String leaderboardTab = "WING";
	/** The Personal Stats tab (a PvpCategory name) last opened, so it opens there next time. */
	public String statsTab = "WING";
	/** On-screen element -> {x, y} as fractions of the screen, for elements that were moved. See HudLayout. */
	public Map<String, double[]> hudPositions = new HashMap<>();
	/** Gunshot sound id -> volume (0-1); sounds at full volume aren't stored. See GunSounds. */
	public Map<String, Float> gunSoundVolumes = new HashMap<>();
	/** Gunshot sound id -> gun name -> times heard together. See GunSounds. */
	public Map<String, Map<String, Integer>> gunSoundUses = new HashMap<>();

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("gtmaddons.json");
	}

	public static Settings load() {
		try {
			if (Files.exists(path())) {
				Settings settings = GSON.fromJson(Files.readString(path(), StandardCharsets.UTF_8), Settings.class);
				if (settings != null) {
					// Files saved before these settings existed leave them null.
					if (settings.gunSoundVolumes == null) settings.gunSoundVolumes = new HashMap<>();
					if (settings.gunSoundUses == null) settings.gunSoundUses = new HashMap<>();
					if (settings.hudPositions == null) settings.hudPositions = new HashMap<>();
					if (settings.devFiltersOff == null) settings.devFiltersOff = new HashSet<>();
					if (settings.qaFiltersOff == null) settings.qaFiltersOff = new HashSet<>();
					if (settings.tracerSizeCategory == null) settings.tracerSizeCategory = new HashMap<>();
					if (settings.tracerSizeGun == null) settings.tracerSizeGun = new HashMap<>();
					if (settings.droppedItemFilter == null) settings.droppedItemFilter = new java.util.ArrayList<>();
					if (settings.droppedItemSizeFilter == null) settings.droppedItemSizeFilter = new java.util.ArrayList<>();
					return settings;
				}
			}
		} catch (Exception e) {
			LOGGER.warn("GTMAddOns: couldn't read settings, using defaults: {}", e.toString());
			// Keep the unreadable file: the next save would otherwise overwrite it with defaults.
			try {
				Files.copy(path(), path().resolveSibling("gtmaddons.json.broken"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			} catch (Exception ignored) {
				// nothing more to do
			}
		}
		return new Settings();
	}

	/** Whether a dev-mode (qa = false) or QA-mode (qa = true) filter is on. */
	public boolean filterOn(boolean qa, DevFilter filter) {
		return !(qa ? qaFiltersOff : devFiltersOff).contains(filter.name());
	}

	public void setFilter(boolean qa, DevFilter filter, boolean on) {
		Set<String> off = qa ? qaFiltersOff : devFiltersOff;
		if (on) off.remove(filter.name());
		else off.add(filter.name());
		save();
	}

	/**
	 * Writes the settings, keeping every key in the file that this version doesn't know: a newer version's settings survive an older
	 * version (or another Minecraft version of the mod sharing the same config) saving. A setting that is null here (so Gson leaves it
	 * out) is removed from the file, so clearing a choice still sticks.
	 */
	public void save() {
		try {
			JsonObject out = new JsonObject();
			if (Files.exists(path())) {
				try {
					JsonElement existing = JsonParser.parseString(Files.readString(path(), StandardCharsets.UTF_8));
					if (existing.isJsonObject()) out = existing.getAsJsonObject();
				} catch (Exception e) {
					// an unreadable file is replaced by what we have
				}
			}
			JsonObject mine = GSON.toJsonTree(this).getAsJsonObject();
			for (java.lang.reflect.Field field : Settings.class.getDeclaredFields()) {
				if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
				if (mine.has(field.getName())) out.add(field.getName(), mine.get(field.getName()));
				else out.remove(field.getName());
			}
			Files.writeString(path(), GSON.toJson(out), StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOGGER.warn("GTMAddOns: couldn't save settings: {}", e.toString());
		}
	}
}
