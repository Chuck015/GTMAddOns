package com.example.gtmaddons.gun;

/**
 * Everything dev mode and QA mode can show. Each mode keeps its own on/off
 * choice per filter (Settings > Dev mode / QA mode menus, saved in
 * config/gtmaddons.json); a filter that's off hides that output from both
 * chat and the dev log. New filters are on by default.
 *
 * The check is DevLogger.wants(filter), which is also false whenever the
 * mode itself is off.
 */
public enum DevFilter {
	TRAJECTORY("Trajectory line", "A line in the world from your crosshair the moment you right-click with a gun, to compare with GTM's particle stream"),
	SHOTS("Shots & results", "Each shot, ShotTracker's verdict (hit / miss, headshot, kill, sounds, ping) and the per-gun summary in chat"),
	AIM("Crosshair aim", "Where your crosshair ray went at each shot: first block and every player it crosses"),
	DAMAGE("Damage & health", "Damage events and who caused them, entity status events, and health changes after a shot"),
	SOUNDS("Sounds", "Every sound the server plays, with its distance from you"),
	PARTICLES("Particles", "Particle counts for each burst"),
	TITLES("Titles", "Titles and subtitles (WASTED, headshot marker...)"),
	MESSAGES("Chat & action bar", "Every chat and action-bar message, spelled out with fonts"),
	COOLDOWNS("Item cooldowns", "The server setting a weapon's cooldown (the sweep over the hotbar item)"),
	COMBOS("Melee combos", "Finished combos and melee exchange summaries"),
	SWINGS("Swing checks", "Left clicks that hit without a fresh swing animation, and weapon details"),
	WING_BOOST("Wing boosts", "Each shift press while gliding: boost OK / NONE, angle, height and the height-limit estimate"),
	JETPACK("Jetpack starts", "Double-tap jump with a jetpack: start OK / FAILED and the samples that led to it"),
	NEAR("/near capture", "GTM's reply to /near, with timing and hover / click details"),
	FIGHTS("Fights & combat tag", "Fight started / recorded / dropped and combat tag start / end, with reasons"),
	CATEGORY("PvP category", "A line whenever your PvP category changes, and what it's based on");

	public final String label;
	public final String description;

	DevFilter(String label, String description) {
		this.label = label;
		this.description = description;
	}
}
