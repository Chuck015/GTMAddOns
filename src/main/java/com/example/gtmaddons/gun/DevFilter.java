package com.example.gtmaddons.gun;

/**
 * Everything dev mode and QA mode can show. Each mode keeps its own on/off
 * choice per filter (Settings > Dev mode / QA mode menus, saved in
 * config/gtmaddons.json); a filter that's off hides that output from both
 * chat and the dev log. New filters are on by default.
 *
 * The check is DevLogger.wants(filter), which is also false whenever the
 * mode itself is off. Each filter has a Group, the tab it is on in the menu.
 */
public enum DevFilter {
	TRAJECTORY(Group.GENERAL, "Trajectory line", "A line in the world from your crosshair the moment you right-click with a gun, to compare with GTM's particle stream"),
	SHOTS(Group.GENERAL, "Shots & results", "Each shot, ShotTracker's verdict (hit / miss, headshot, kill, sounds, ping) and the per-gun summary in chat"),
	AIM(Group.GENERAL, "Crosshair aim", "Where your crosshair ray went at each shot: first block and every player it crosses"),
	DAMAGE(Group.GENERAL, "Damage & health", "Damage events and who caused them, entity status events, and health changes after a shot"),
	SOUNDS(Group.GENERAL, "Sounds", "Every sound the server plays, with its distance from you"),
	PARTICLES(Group.GENERAL, "Particles", "Particle counts for each burst"),
	TITLES(Group.GENERAL, "Titles", "Titles and subtitles (WASTED, headshot marker...)"),
	MESSAGES(Group.GENERAL, "Chat & action bar", "Every chat and action-bar message, spelled out with fonts"),
	COOLDOWNS(Group.GENERAL, "Item cooldowns", "The server setting a weapon's cooldown (the sweep over the hotbar item)"),
	COMBOS(Group.JP, "Melee combos", "Finished combos and melee exchange summaries"),
	MELEE_DAMAGE(Group.JP, "Melee damage", "Each melee hit: damage actually dealt against what vanilla would deal (attack damage, charge, armor), flagged LOW DAMAGE when far under; plus the weapon's attribute modifiers and attack damage changes"),
	NET_LAUNCHER(Group.WING, "Net Launcher", "Each Net Launcher shot: counted as a hit or a miss and why (who was hit, wingsuit or not, timing), then a one-second follow-up on the target and the cobwebs around it"),
	PERFORMANCE(Group.GENERAL, "Performance", "Every 10 s: how much time the mod itself takes per frame (frame hooks and HUD), and the dev tools' own cost; chat only if it is 3% of a frame or more"),
	SWINGS(Group.JP, "Swing checks", "Left clicks that hit without a fresh swing animation, and weapon details"),
	WING_BOOST(Group.WING, "Wing boosts", "Each shift press while gliding: boost OK / NONE, angle, height and the height-limit estimate"),
	JETPACK(Group.JP, "Jetpack starts", "Double-tap jump with a jetpack: start OK / FAILED and the samples that led to it"),
	NEAR(Group.GENERAL, "/near capture", "GTM's reply to /near, with timing and hover / click details"),
	FIGHTS(Group.GENERAL, "Fights & combat tag", "Fight started / recorded / dropped and combat tag start / end, with reasons"),
	CATEGORY(Group.GENERAL, "PvP category", "A line whenever your PvP category changes, and what it's based on");

	/** The tab of the dev / QA menu a filter is on (same sections as the PVP settings). */
	public enum Group {
		WING("Wing"), JP("JP"), GROUND("Ground"), GENERAL("General");

		public final String label;

		Group(String label) {
			this.label = label;
		}
	}

	public final Group group;
	public final String label;
	public final String description;

	DevFilter(Group group, String label, String description) {
		this.group = group;
		this.label = label;
		this.description = description;
	}
}
