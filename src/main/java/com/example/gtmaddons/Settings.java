package com.example.gtmaddons;

import com.example.gtmaddons.gun.DevFilter;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
	/** Look angle under the crosshair while gliding, green when a boost would work (see BoostAngleHud). */
	public boolean boostAngle = false;
	/** Height under the crosshair while gliding, green when low enough to boost (see BoostHeightHud). */
	public boolean boostHeight = false;
	/** /near's reply as a sorted list, one player per line, in place of GTM's one long line (see NearList). */
	public boolean betterNear = false;
	/** An icon after the name of players who run GTMAddOns (tab list and nametags). */
	public boolean showModUsers = false;

	/** A Start / Stop recording button beside the inventory for the swap recording (SwapSession). */
	public boolean swapRecordButton = false;

	/** Hide the flame and cloud particles of flying jetpacks (JetpackParticles). */
	public boolean hideJetpackParticles = false;

	/** 0-100: how much of the cobweb texture is made see-through (CobwebTransparency). */
	public int cobwebTransparency = 0;

	/** Crouch while flying and sneaking, like 1.8 (OldSneaking). */
	public boolean oldSneaking = false;

	/** A HUD line with the seconds left of the combat tag (CombatTracker.timerText). */
	public boolean combatTimer = false;

	/** Draw every player's head as a fish (FishHeads; 1.19.4 only for now). */
	public boolean fishHeads = false;

	/** The 25 / 50 / 100 fights choice of the stats screens (FightViews). */
	public int statsFights = 25;
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
					return settings;
				}
			}
		} catch (Exception e) {
			LOGGER.warn("GTMAddOns: couldn't read settings, using defaults: {}", e.toString());
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

	public void save() {
		try {
			Files.writeString(path(), GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOGGER.warn("GTMAddOns: couldn't save settings: {}", e.toString());
		}
	}
}
