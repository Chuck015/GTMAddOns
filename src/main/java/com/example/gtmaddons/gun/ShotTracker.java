package com.example.gtmaddons.gun;

import com.example.gtmaddons.GunSounds;
import com.example.gtmaddons.PvpCategory;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects gun shots and whether they hit, headshot or killed.
 *
 * Worked out from the server's behaviour (see DevLogger):
 *   - Shot:     the held gun's ammo readout ("Combat MG «49/29897»") drops
 *               while the gun's name stays the same. (Switching guns also
 *               changes the number, so the name must match.)
 *   - Hit:      a damage event caused by you arrives in the same server
 *               tick as the ammo drop - within a few ms of it.
 *   - Headshot: an empty title with the subtitle "⊕" arrives with the hit.
 *   - Kill:     the chat message "You killed <name>!" arrives with the killing hit. It is tied to the last
 *               damage you dealt to <name> just before it, and the gun of the shot that damage belongs to
 *               gets the kill (see killingShot) - not the shot nearest the message in time, which in a burst
 *               of automatic fire can be the one before, and not the gun you hold when the message arrives,
 *               which with ping can already be another gun.
 *   - Net Launcher: only a hit on a player wearing a wingsuit counts (it nets
 *               them); a hit on anyone else is just a damage tick, so it's a
 *               miss. See countsOnlyWingsuitHits.
 * The gunshot sound also arrives in that tick, at your own position.
 * Each shot is labeled with the PvpCategory you were in when it was fired.
 *
 * Movement guns (see isMovementGun) launch you, so their shots also record
 * your horizontal speed: the peak over the SETTLE_NANOS after the shot, and
 * your speed the frame before it for comparison.
 *
 * Events are matched to the nearest shot within MATCH_NANOS, and a shot is
 * only finalized SETTLE_NANOS after it's seen, so events arriving just
 * after it are still counted. Automatic guns fire ~50ms apart, so the
 * match window is kept below that.
 */
public final class ShotTracker {

	public static final ShotTracker INSTANCE = new ShotTracker();

	private static final Pattern NOT_NAME = Pattern.compile("[^\\p{L}\\p{N} '\\-]");
	private static final Pattern SPACES = Pattern.compile("\\s+");
	private static final Pattern AMMO_PATTERN = Pattern.compile("(\\d+)\\s*/\\s*(\\d+)");
	/** GTM's own message, at the start of the line - not a player typing it in chat ("Name> [GTM] You killed X!"). */
	private static final Pattern KILL_PATTERN = Pattern.compile("^\\s*\\[GTM\\]\\s*You killed (\\w{1,16})");
	/** The killing damage arrives within a few ms of the kill message; allow this much either way. */
	private static final long KILL_DAMAGE_BEFORE_NANOS = 250_000_000L, KILL_DAMAGE_AFTER_NANOS = 5_000_000L;
	private static final String HEADSHOT_MARKER = "⊕";
	private static final long MATCH_NANOS = 40_000_000L;
	private static final long SETTLE_NANOS = 150_000_000L;
	private static final long KEEP_NANOS = 1_000_000_000L;
	private static final double OWN_SOUND_RANGE = 2.0;
	/** Sounds GTM plays for reloading, seen in the dev log - never a gunshot. */
	private static final Set<String> RELOAD_SOUNDS = Set.of(
			"minecraft:block.iron_trapdoor.open", "minecraft:block.iron_trapdoor.close",
			"minecraft:block.lever.click", "minecraft:block.piston.extend", "minecraft:block.piston.contract",
			"minecraft:block.note_block.hat", "minecraft:entity.skeleton.step", "minecraft:entity.skeleton.ambient",
			"minecraft:item.flintandsteel.use", "minecraft:entity.iron_golem.attack");
	/** Guns used to launch yourself around; their shots record your speed. */
	private static final Set<String> MOVEMENT_GUNS = Set.of("sawed-off shotgun", "pump shotgun", "heavy revolver");

	private enum Kind { DAMAGE, HEADSHOT, KILL, SOUND }

	private static final class Event {
		final long nanos;
		final Kind kind;
		final String detail;
		final double distance;
		/** DAMAGE only: the target was a player wearing a wingsuit (or gliding). */
		final boolean onWingsuit;
		/** DAMAGE only: the target's chest item and gliding state then (dev mode's Net Launcher output). */
		final String gear;
		boolean claimed;

		Event(long nanos, Kind kind, String detail, double distance) {
			this(nanos, kind, detail, distance, false);
		}

		Event(long nanos, Kind kind, String detail, double distance, boolean onWingsuit) {
			this(nanos, kind, detail, distance, onWingsuit, "");
		}

		Event(long nanos, Kind kind, String detail, double distance, boolean onWingsuit, String gear) {
			this.nanos = nanos;
			this.kind = kind;
			this.detail = detail;
			this.distance = distance;
			this.onWingsuit = onWingsuit;
			this.gear = gear;
		}
	}

	private static final class Shot {
		final long nanos;
		final long wallMillis;
		final String gun;
		final int ammoBefore;
		final int ammoAfter;
		final Long responseNanos;
		final PvpCategory category;
		/** Movement guns only (else null): horizontal speed in blocks/s the frame before the shot. */
		final Double speedBeforeBps;
		/** Movement guns only: the peak horizontal speed since the shot. */
		double peakBps;
		boolean finalized;

		Shot(long nanos, String gun, int ammoBefore, int ammoAfter, Long responseNanos, PvpCategory category, Double speedBeforeBps) {
			this.nanos = nanos;
			this.wallMillis = System.currentTimeMillis();
			this.gun = gun;
			this.ammoBefore = ammoBefore;
			this.ammoAfter = ammoAfter;
			this.responseNanos = responseNanos;
			this.category = category;
			this.speedBeforeBps = speedBeforeBps;
		}
	}

	private final List<Consumer<ShotResult>> listeners = new ArrayList<>();
	private final List<Shot> shots = new ArrayList<>();
	private final List<Event> events = new ArrayList<>();

	private String lastGun = null;
	private Object parsedNameKey = null;
	private Integer parsedAmmo = null;
	private String parsedGun = null;
	private Integer lastAmmo = null;
	private boolean useWasPressed = false;
	private long triggerPulledNanos = -1L;
	private double lastFrameBps = 0.0;

	/** Why the latest Net Launcher shot was a hit or a miss (null if the last shot wasn't one); read by dev mode. */
	private String netNote = null;
	/** A Net Launcher hit on a wingsuit player nets them for 25 ticks: shots fired at them in that time are "netted" (see resolve). */
	private static final long NETTED_MILLIS = 25 * 50L;
	private long nettedStartMillis = 0L, nettedUntilMillis = 0L;
	private String nettedTarget = null;

	public String takeNetNote() {
		String note = netNote;
		netNote = null;
		return note;
	}

	private ShotTracker() {}

	public static boolean isReloadSound(String soundId) {
		return RELOAD_SOUNDS.contains(soundId);
	}

	/** Sawed-off Shotgun, Pump Shotgun and Heavy Revolver: guns used for movement. */
	public static boolean isMovementGun(String gun) {
		return MOVEMENT_GUNS.contains(gun.toLowerCase(Locale.ROOT));
	}

	public void addListener(Consumer<ShotResult> listener) {
		listeners.add(listener);
	}

	// ---- Per frame ----

	public void onFrame(MinecraftClient client) {
		long now = System.nanoTime();
		ClientPlayerEntity player = client.player;
		// Velocity is blocks per tick; x20 for blocks per second.
		double bps = player != null ? Math.hypot(player.getVelocity().x, player.getVelocity().z) * 20.0 : 0.0;
		if (player == null) {
			lastGun = null;
			lastAmmo = null;
			useWasPressed = false;
		} else {
			ItemStack held = player.getStackInHand(Hand.MAIN_HAND);
			Object nameKey = held.isEmpty() ? null : held.getName();
			// The name only changes when the item or its ammo count does: parse it then, not every frame.
			if (nameKey != parsedNameKey) {
				parsedNameKey = nameKey;
				String name = nameKey == null ? "" : held.getName().getString();
				parsedAmmo = parseAmmo(name);
				parsedGun = parsedAmmo != null ? gunName(name) : null;
			}
			Integer ammo = parsedAmmo;
			String gun = parsedGun;

			boolean usePressed = client.options.useKey.isPressed();
			if (usePressed && !useWasPressed && gun != null) triggerPulledNanos = now;
			useWasPressed = usePressed;

			if (gun != null && gun.equals(lastGun) && lastAmmo != null && ammo < lastAmmo) {
				// Response time only means something for the first shot of a trigger pull.
				Long response = triggerPulledNanos >= 0 ? now - triggerPulledNanos : null;
				triggerPulledNanos = -1L;
				shots.add(new Shot(now, gun, lastAmmo, ammo, response, PvpCategory.classify(player),
						isMovementGun(gun) ? lastFrameBps : null));
			}
			lastGun = gun;
			lastAmmo = ammo;
		}

		lastFrameBps = bps;

		for (Shot shot : shots) {
			if (!shot.finalized && shot.speedBeforeBps != null) shot.peakBps = Math.max(shot.peakBps, bps);
			if (!shot.finalized && now - shot.nanos >= SETTLE_NANOS) {
				shot.finalized = true;
				ShotResult result = resolve(client, shot);
				for (Consumer<ShotResult> listener : listeners) listener.accept(result);
			}
		}
		shots.removeIf(shot -> shot.finalized && now - shot.nanos > KEEP_NANOS);
		events.removeIf(event -> now - event.nanos > KEEP_NANOS);
	}

	// ---- Incoming signals (game thread) ----

	public void onDamage(Entity target, int causeId) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || causeId != client.player.getId()) return;
		String name = target != null ? target.getName().getString() : "unknown";
		double distance = target != null ? client.player.distanceTo(target) : Double.NaN;
		events.add(new Event(System.nanoTime(), Kind.DAMAGE, name, distance, isOnWingsuit(target), describeGear(target)));
	}

	/** A player wearing a wingsuit (a glider in the chest slot), or gliding right now. */
	public static boolean isOnWingsuit(Entity entity) {
		return entity instanceof PlayerEntity player
				&& (player.isGliding() || PvpCategory.isWingsuit(player.getEquippedStack(EquipmentSlot.CHEST)));
	}

	/** For dev mode: what a damaged player had on their chest and whether they were gliding. */
	private static String describeGear(Entity entity) {
		if (!(entity instanceof PlayerEntity player)) return "not a player";
		var chest = player.getEquippedStack(EquipmentSlot.CHEST);
		return String.format("chest \"%s\", gliding %s", chest.isEmpty() ? "-" : chest.getName().getString(), player.isGliding());
	}

	/**
	 * The Net Launcher only counts as a hit on a player wearing a wingsuit -
	 * that's what nets them in cobwebs. On anyone else it's a single damage
	 * tick and nothing else, and a miss fires a fireball that webs whatever
	 * it lands on; neither counts.
	 */
	public static boolean countsOnlyWingsuitHits(String gun) {
		return gun.toLowerCase(Locale.ROOT).contains("net launcher");
	}

	public void onSubtitle(Text text) {
		if (text.getString().contains(HEADSHOT_MARKER)) {
			events.add(new Event(System.nanoTime(), Kind.HEADSHOT, null, Double.NaN));
		}
	}

	public void onMessage(Text text, boolean actionBar) {
		if (actionBar) return;
		Matcher matcher = KILL_PATTERN.matcher(text.getString());
		if (matcher.find()) events.add(new Event(System.nanoTime(), Kind.KILL, matcher.group(1), Double.NaN));
	}

	/** Only sounds at your own position can be the gunshot or a hit sound. */
	public void onSound(String soundId, double distance) {
		if (distance <= OWN_SOUND_RANGE) events.add(new Event(System.nanoTime(), Kind.SOUND, soundId, distance));
	}

	// ---- Matching ----

	private ShotResult resolve(MinecraftClient client, Shot shot) {
		boolean hit = false, headshot = false, kill = false;
		String target = null, gunshotSound = null;
		double distance = Double.NaN;
		List<String> otherSounds = new ArrayList<>();
		boolean wingsuitOnly = countsOnlyWingsuitHits(shot.gun);
		List<String> damageNotes = new ArrayList<>();

		for (Event event : events) {
			if (event.kind == Kind.KILL) {
				// Only the shot whose damage killed the target gets the kill.
				if (event.claimed || killingShot(event) != shot) continue;
				event.claimed = true;
				kill = true;
				if (target == null) target = event.detail;
				continue;
			}
			if (event.claimed || nearestShot(event) != shot) continue;
			event.claimed = true;
			switch (event.kind) {
				case DAMAGE -> {
					if (wingsuitOnly) {
						damageNotes.add(String.format("damage on %s at %.1f blocks, %d ms after the shot, %s, counts as wingsuit: %s",
								event.detail, event.distance, (event.nanos - shot.nanos) / 1_000_000L, event.gear, event.onWingsuit));
					}
					if (wingsuitOnly && !event.onWingsuit) continue;
					if (!hit) {
						target = event.detail;
						distance = event.distance;
					}
					hit = true;
				}
				case HEADSHOT -> headshot = true;
				case KILL -> { }
				case SOUND -> otherSounds.add(event.detail);
			}
		}

		if (wingsuitOnly) netNote = describeNetShot(shot, hit, damageNotes);
		// A net hit starts the 25 ticks; other guns' shots in that time at the netted player (or missing, with nobody else hit) are netted.
		boolean netted = false;
		if (wingsuitOnly) {
			if (hit) {
				nettedStartMillis = shot.wallMillis;
				nettedUntilMillis = shot.wallMillis + NETTED_MILLIS;
				nettedTarget = target;
				com.example.gtmaddons.NetFightEnd.INSTANCE.onNetHit(target, shot.wallMillis);
			}
		} else if (shot.wallMillis >= nettedStartMillis && shot.wallMillis <= nettedUntilMillis) {
			netted = !hit || target == null || nettedTarget == null || target.equalsIgnoreCase(nettedTarget);
		}
		gunshotSound = pickGunshotSound(otherSounds);
		if (gunshotSound != null) otherSounds.remove(gunshotSound);

		return new ShotResult(shot.wallMillis, shot.gun, shot.ammoBefore, shot.ammoAfter,
				hit, headshot && hit, kill && hit, target, distance, gunshotSound,
				shot.responseNanos != null ? shot.responseNanos / 1_000_000L : null,
				ping(client), otherSounds, shot.category,
				shot.speedBeforeBps, shot.speedBeforeBps != null ? shot.peakBps : null, netted);
	}

	/** The reason for a Net Launcher shot's verdict, for dev mode (see NetLauncherDebugger). */
	private String describeNetShot(Shot shot, boolean hit, List<String> damageNotes) {
		StringBuilder note = new StringBuilder();
		if (hit) note.append("counted as a HIT: the damage was on a player on a wingsuit");
		else if (damageNotes.isEmpty()) note.append("counted as a MISS: no damage event within ").append(MATCH_NANOS / 1_000_000L).append(" ms of the shot");
		else note.append("counted as a MISS: the damage was on someone not on a wingsuit (the net only catches wingsuit users)");
		if (!damageNotes.isEmpty()) note.append(" [").append(String.join("; ", damageNotes)).append("]");
		for (Event event : events) {
			if (event.kind != Kind.DAMAGE || event.claimed) continue;
			long gapMs = (event.nanos - shot.nanos) / 1_000_000L;
			if (Math.abs(gapMs) <= 300) {
				note.append(String.format(" [damage on %s %d ms from the shot is outside the %d ms match window, not counted]",
						event.detail, gapMs, MATCH_NANOS / 1_000_000L));
			}
		}
		return note.toString();
	}

	/**
	 * Which of the sounds that came with a shot is the gunshot. Reload sounds
	 * never are (single-shot guns start reloading on the tick they fire), and
	 * a known GTM gunshot sound wins over anything else - the Homing Launcher,
	 * for one, sometimes beeps just before its launch sound.
	 */
	private static String pickGunshotSound(List<String> sounds) {
		String first = null;
		for (String sound : sounds) {
			if (RELOAD_SOUNDS.contains(sound) || GunSounds.isMovementSound(sound)) continue;
			if (GunSounds.isKnownGunshot(sound)) return sound;
			if (first == null) first = sound;
		}
		return first;
	}

	/**
	 * The shot that killed: the last damage you dealt to the victim just before the kill message (they arrive in the
	 * same server tick, so their gap is the same whatever your ping), and the shot that damage belongs to. Null if no
	 * damage on that player is near the message, or no shot is near that damage - a melee or other kill.
	 */
	private Shot killingShot(Event kill) {
		Event damage = null;
		for (Event event : events) {
			if (event.kind != Kind.DAMAGE || event.detail == null || !event.detail.equalsIgnoreCase(kill.detail)) continue;
			long gap = kill.nanos - event.nanos;
			if (gap < -KILL_DAMAGE_AFTER_NANOS || gap > KILL_DAMAGE_BEFORE_NANOS) continue;
			if (damage == null || event.nanos > damage.nanos) damage = event;
		}
		return damage != null ? nearestShot(damage) : null;
	}

	private Shot nearestShot(Event event) {
		Shot best = null;
		long bestGap = MATCH_NANOS + 1;
		for (Shot shot : shots) {
			long gap = Math.abs(event.nanos - shot.nanos);
			if (gap < bestGap) {
				best = shot;
				bestGap = gap;
			}
		}
		return bestGap <= MATCH_NANOS ? best : null;
	}

	// ---- Helpers ----

	static int ping(MinecraftClient client) {
		if (client.player == null || client.getNetworkHandler() == null) return -1;
		PlayerListEntry entry = client.getNetworkHandler().getPlayerListEntry(client.player.getUuid());
		return entry != null ? entry.getLatency() : -1;
	}

	public static Integer parseAmmo(String itemName) {
		Matcher matcher = AMMO_PATTERN.matcher(itemName);
		if (!matcher.find()) return null;
		try {
			return Integer.parseInt(matcher.group(1));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** "Combat MG «49/29897»" -> "Combat MG". */
	static String gunName(String itemName) {
		String withoutAmmo = AMMO_PATTERN.matcher(itemName).replaceAll("");
		String cleaned = SPACES.matcher(NOT_NAME.matcher(withoutAmmo).replaceAll("")).replaceAll(" ").trim();
		if (cleaned.isEmpty()) cleaned = "Unknown gun";
		return cleaned.length() > 48 ? cleaned.substring(0, 48) : cleaned;
	}
}
