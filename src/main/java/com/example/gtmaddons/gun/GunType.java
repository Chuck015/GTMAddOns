package com.example.gtmaddons.gun;

import java.util.Locale;
import java.util.Map;

/**
 * Kinds of GTM gun that the stats group together (Rifle, Sniper, SMG...).
 * Known guns come from the dev logs' gun-sound groups (see GunSounds);
 * anything unknown is guessed from its name. One-of-a-kind guns (the Net
 * Launcher, the Musket) aren't grouped and show on their own.
 */
public enum GunType {
	RIFLE("Rifle"),
	SNIPER("Sniper"),
	SMG("SMG"),
	PISTOL("Pistol"),
	SHOTGUN("Shotgun"),
	MACHINE_GUN("Machine gun"),
	LAUNCHER("Launcher");

	private static final Map<String, GunType> KNOWN = Map.ofEntries(
			Map.entry("advanced rifle", RIFLE), Map.entry("assault rifle", RIFLE), Map.entry("bullpup rifle", RIFLE),
			Map.entry("carbine rifle", RIFLE), Map.entry("m4", RIFLE), Map.entry("special carbine", RIFLE),
			Map.entry("assault sniper", SNIPER), Map.entry("heavy sniper", SNIPER), Map.entry("sniper rifle", SNIPER),
			Map.entry("assault smg", SMG), Map.entry("combat pdw", SMG), Map.entry("gusenberg sweeper", SMG),
			Map.entry("micro smg", SMG), Map.entry("smg", SMG), Map.entry("tokyo's lego smg", SMG),
			Map.entry("combat pistol", PISTOL), Map.entry("heavy pistol", PISTOL), Map.entry("heavy revolver", PISTOL),
			Map.entry("marksman pistol", PISTOL), Map.entry("pistol", PISTOL),
			Map.entry("assault shotgun", SHOTGUN), Map.entry("heavy shotgun", SHOTGUN), Map.entry("pump shotgun", SHOTGUN),
			Map.entry("sawed-off shotgun", SHOTGUN),
			Map.entry("combat mg", MACHINE_GUN), Map.entry("mg", MACHINE_GUN), Map.entry("browning m2", MACHINE_GUN),
			Map.entry("minigun", MACHINE_GUN),
			Map.entry("homing launcher", LAUNCHER), Map.entry("rpg", LAUNCHER), Map.entry("grenade launcher", LAUNCHER));

	public final String label;

	GunType(String label) {
		this.label = label;
	}

	/** The gun's type, or null for a one-of-a-kind gun (shown on its own). */
	public static GunType of(String gunName) {
		String name = gunName.toLowerCase(Locale.ROOT).trim();
		GunType known = KNOWN.get(name);
		if (known != null) return known;
		if (name.contains("net launcher") || name.contains("musket")) return null;
		// Unknown gun: go by its name. Sniper first - "Sniper Rifle" is a sniper;
		// SMG before machine gun - "smg" ends in "mg".
		if (name.contains("sniper")) return SNIPER;
		if (name.contains("smg") || name.contains("pdw")) return SMG;
		if (name.contains("rifle") || name.contains("carbine")) return RIFLE;
		if (name.contains("pistol") || name.contains("revolver")) return PISTOL;
		if (name.contains("shotgun")) return SHOTGUN;
		if (name.endsWith(" mg") || name.contains("minigun") || name.contains("machine gun")) return MACHINE_GUN;
		if (name.contains("launcher") || name.contains("rpg")) return LAUNCHER;
		return null;
	}
}
