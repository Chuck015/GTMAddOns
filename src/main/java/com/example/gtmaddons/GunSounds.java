package com.example.gtmaddons;

import com.example.gtmaddons.gun.ShotResult;
import com.example.gtmaddons.gun.ShotTracker;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings > Sounds: per-sound volume for GTM's gunshot, wingsuit and
 * jetpack sounds. GTM plays vanilla sounds for these (rifles are an item
 * breaking, the jetpack is fire being put out...), and several guns can
 * share one sound - so volumes are set per sound, and each gun sound is
 * shown with every gun that's been heard using it.
 *
 * Movement sounds (wingsuit boost, jetpack thrust, wingsuit equip) are
 * built in, from the dev logs; one slider can cover several sounds. Two more sliders turn down Minecraft's own hit sounds: Being hit (the hurt
 * sound at your own position) and Default hit sounds (melee attack sounds and the hurt sound of whoever you damage).
 *
 * Which gun uses which sound is learned from ShotTracker: the sound that
 * plays at your position in the same tick as a shot. A pairing is only
 * listed once it's been seen MIN_USES times, so a one-off sound (e.g. a hit
 * sound that happened to line up) doesn't show up as a gun sound.
 *
 * Volumes apply to that sound wherever it plays - other players' shots with
 * the same guns, and anything else on the server that uses it.
 */
public final class GunSounds {

	public static final GunSounds INSTANCE = new GunSounds();

	private static final int MIN_USES = 3;

	/** The loudest a slider can be set (250%). Minecraft never plays above 100%, so the part above it is played as extra copies (LouderSounds). */
	public static final float MAX_VOLUME = 2.5f;

	/**
	 * Every GTM gun's shot sound, from the dev logs (2026-09-26, 361 shots), so they're
	 * all listed before you've fired them yourself. Each sound is a category
	 * named after the kind of gun that uses it.
	 */
	private static final Map<String, String> KNOWN_NAMES = new LinkedHashMap<>();
	private static final Map<String, List<String>> KNOWN_GUNS = new LinkedHashMap<>();

	private static void known(String soundId, String name, String... guns) {
		KNOWN_NAMES.put("minecraft:" + soundId, name);
		KNOWN_GUNS.put("minecraft:" + soundId, List.of(guns));
	}

	static {
		known("entity.item.break", "Rifles",
				"Advanced Rifle", "Assault Rifle", "Bullpup Rifle", "Carbine Rifle", "M4", "Special Carbine");
		known("entity.blaze.hurt", "SMGs",
				"Assault SMG", "Combat PDW", "Gusenberg Sweeper", "Micro SMG", "SMG", "Tokyo's Lego Smg");
		known("entity.firework_rocket.blast", "Pistols",
				"Combat Pistol", "Heavy Pistol", "Heavy Revolver", "Marksman Pistol", "Pistol");
		known("entity.zombie.attack_wooden_door", "Shotguns & Musket",
				"Assault Shotgun", "Heavy Shotgun", "Musket", "Pump Shotgun", "Sawed-off Shotgun");
		known("entity.iron_golem.hurt", "Snipers", "Assault Sniper", "Heavy Sniper", "Sniper Rifle");
		known("block.note_block.snare", "Machine Guns", "Combat MG", "MG");
		known("entity.firework_rocket.launch", "Launchers", "Homing Launcher", "RPG");
		known("entity.armor_stand.break", "Browning M2", "Browning M2");
		known("entity.zombie.attack_iron_door", "Minigun", "Minigun");
		known("entity.chicken.egg", "Grenade Launcher", "Grenade Launcher");
	}

	/**
	 * Wingsuit and jetpack sounds, from the dev logs (2026-09-26): name, what
	 * else uses the sound (shown under it), and the sounds on its slider.
	 */
	private static final List<Category> MOVEMENT = List.of(
			new Category("Wingsuit boost", List.of("minecraft:entity.arrow.shoot", "minecraft:entity.item.pickup"),
					"Boost thrust and start - also bows and picking up items"),
			new Category("Jetpack thrust", List.of("minecraft:block.fire.extinguish"),
					"Plays while flying - also fire being put out"),
			new Category("Wingsuit equip", List.of("minecraft:item.armor.equip_elytra"),
					"Putting a wingsuit on"));

	/** Keys of the two hit sliders in Settings.gunSoundVolumes (they are not sound ids: one slider covers sounds by where they play). */
	private static final String BEING_HIT = "gtmaddons:being_hit", DEFAULT_HIT = "gtmaddons:default_hit";
	/** The hurt sounds a player makes. entity.blaze.hurt and entity.iron_golem.hurt are gun sounds and are not in this list. */
	private static final java.util.Set<String> HURT_SOUNDS = java.util.Set.of("minecraft:entity.player.hurt", "minecraft:entity.player.hurt_on_fire",
			"minecraft:entity.player.hurt_drown", "minecraft:entity.player.hurt_freeze", "minecraft:entity.player.hurt_sweet_berry_bush");
	/** Vanilla melee sounds: entity.player.attack.strong / weak / crit / knockback / sweep / nodamage. */
	private static final String ATTACK_PREFIX = "minecraft:entity.player.attack.";
	/** A hurt sound this close to you is yours; a little further counts if you were damaged a moment ago (the sound plays where the server has you). */
	private static final double OWN_SOUND_BLOCKS = 1.0, OWN_SOUND_AFTER_DAMAGE_BLOCKS = 10.0;
	private static final long OWN_DAMAGE_WINDOW_NANOS = 500_000_000L;

	private static final List<Category> HITS = List.of(
			new Category("Being hit", List.of(BEING_HIT), "The hurt sound when you take damage"),
			new Category("Default hit sounds", List.of(DEFAULT_HIT), "Minecraft's own hit sounds: your melee attack sounds and the hurt sound of whoever you damage"));

	/** Whether a sound is a wingsuit/jetpack sound - never a gunshot. */
	public static boolean isMovementSound(String soundId) {
		for (Category category : MOVEMENT) {
			if (category.soundIds().contains(soundId)) return true;
		}
		return false;
	}

	/** Whether a sound is one of GTM's known gunshot sounds. */
	public static boolean isKnownGunshot(String soundId) {
		return KNOWN_NAMES.containsKey(soundId);
	}

	/** One slider: its name, the sounds it controls, and a line describing what uses them. */
	public record Category(String name, List<String> soundIds, String detail) {}

	private Settings settings;
	/** Where you are, updated every frame (setPlayer), and when you last took damage (onOwnDamage). */
	private volatile boolean playerKnown = false;
	private volatile double playerX, playerY, playerZ;
	private volatile long ownDamageNanos = 0L;

	private GunSounds() {}

	public void init(Settings settings) {
		this.settings = settings;
		// Older versions could mistake a single-shot gun's reload sound for its
		// gunshot; drop those so reload sounds never get a slider.
		// Movement sounds could also have been learned as a gunshot by a shot
		// fired mid-boost; they have their own sliders instead.
		boolean changed = settings.gunSoundUses.keySet().removeIf(ShotTracker::isReloadSound)
				| settings.gunSoundVolumes.keySet().removeIf(ShotTracker::isReloadSound)
				| settings.gunSoundUses.keySet().removeIf(GunSounds::isMovementSound);
		for (Map.Entry<String, List<String>> known : KNOWN_GUNS.entrySet()) {
			Map<String, Integer> guns = settings.gunSoundUses.computeIfAbsent(known.getKey(), k -> new HashMap<>());
			for (String gun : known.getValue()) {
				if (guns.getOrDefault(gun, 0) < MIN_USES) {
					guns.put(gun, MIN_USES);
					changed = true;
				}
			}
		}
		if (changed) settings.save();
	}

	/** Volume multiplier (0-1) for a sound; 1 for anything that isn't a turned-down gun sound. */
	public float volumeFor(Identifier soundId) {
		if (settings == null || settings.gunSoundVolumes.isEmpty()) return 1.0f;
		Float volume = settings.gunSoundVolumes.get(soundId.toString());
		return volume != null ? volume : 1.0f;
	}

	/** Every frame: where the player is (known = false with no player in a world). */
	public void setPlayer(boolean known, double x, double y, double z) {
		playerKnown = known;
		playerX = x;
		playerY = y;
		playerZ = z;
	}

	/** The damage packet for the player arrived: the hurt sound that follows is theirs. */
	public void onOwnDamage() {
		ownDamageNanos = System.nanoTime();
	}

	private boolean isOwnHurtSound(double x, double y, double z) {
		if (!playerKnown) return false;
		double dx = x - playerX, dy = y - playerY, dz = z - playerZ;
		double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (distance <= OWN_SOUND_BLOCKS) return true;
		return System.nanoTime() - ownDamageNanos <= OWN_DAMAGE_WINDOW_NANOS && distance <= OWN_SOUND_AFTER_DAMAGE_BLOCKS;
	}

	/**
	 * Volume multiplier for a sound that starts or updates at this position: the hit sliders decide the hurt and melee attack sounds (a hurt sound
	 * at your own position is "Being hit", any other is the damage tick of someone you hit); everything else is as volumeFor(soundId).
	 */
	public float volumeFor(Identifier soundId, double x, double y, double z) {
		if (settings == null || settings.gunSoundVolumes.isEmpty()) return 1.0f;
		String id = soundId.toString();
		String key = null;
		if (HURT_SOUNDS.contains(id)) key = isOwnHurtSound(x, y, z) ? BEING_HIT : DEFAULT_HIT;
		else if (id.startsWith(ATTACK_PREFIX)) key = DEFAULT_HIT;
		if (key == null) return volumeFor(soundId);
		Float volume = settings.gunSoundVolumes.get(key);
		return volume != null ? volume : 1.0f;
	}

	/** How much louder than 100% this sound is set (0 = not louder): 1.5 means one and a half extra copies' worth. Same rules as volumeFor. */
	public float boostFor(Identifier soundId, double x, double y, double z) {
		float volume = volumeFor(soundId, x, y, z);
		return volume > 1.0f ? volume - 1.0f : 0.0f;
	}

	/** A slider's volume (all its sounds share one). */
	public float volume(Category category) {
		return settings.gunSoundVolumes.getOrDefault(category.soundIds().get(0), 1.0f);
	}

	public void setVolume(Category category, float volume) {
		for (String soundId : category.soundIds()) {
			if (Math.abs(volume - 1.0f) < 0.005f) settings.gunSoundVolumes.remove(soundId);
			else settings.gunSoundVolumes.put(soundId, volume);
		}
		settings.save();
	}

	/** Learns the gun -> sound pairing from each shot. */
	public void onShot(ShotResult shot) {
		if (settings == null || shot.gunshotSound() == null) return;
		Map<String, Integer> guns = settings.gunSoundUses.computeIfAbsent(shot.gunshotSound(), k -> new HashMap<>());
		int uses = guns.merge(shot.gun(), 1, Integer::sum);
		// Save when a pairing first becomes listed; later counts don't matter.
		if (uses == MIN_USES) settings.save();
	}

	/**
	 * Every slider: wingsuit/jetpack sounds, then gun sounds that have been
	 * heard enough (built-in categories in their listed order, then learned
	 * ones, named after their guns).
	 */
	public List<Category> categories() {
		List<Category> all = new ArrayList<>(MOVEMENT);
		all.addAll(HITS);
		all.addAll(gunCategories());
		return all;
	}

	private List<Category> gunCategories() {
		List<Category> known = new ArrayList<>(), learned = new ArrayList<>();
		for (Map.Entry<String, Map<String, Integer>> entry : settings.gunSoundUses.entrySet()) {
			List<String> guns = entry.getValue().entrySet().stream()
					.filter(g -> g.getValue() >= MIN_USES)
					.map(Map.Entry::getKey)
					.sorted()
					.toList();
			if (guns.isEmpty()) continue;
			String name = KNOWN_NAMES.get(entry.getKey());
			String gunList = String.join(", ", guns);
			if (name != null) known.add(new Category(name, List.of(entry.getKey()), gunList));
			else learned.add(new Category(gunList, List.of(entry.getKey()), gunList));
		}
		List<String> order = new ArrayList<>(KNOWN_NAMES.keySet());
		known.sort(Comparator.comparingInt(c -> order.indexOf(c.soundIds().get(0))));
		learned.sort(Comparator.comparing(Category::name, String.CASE_INSENSITIVE_ORDER));
		known.addAll(learned);
		return known;
	}
}
