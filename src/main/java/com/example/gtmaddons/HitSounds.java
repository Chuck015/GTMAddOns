package com.example.gtmaddons;

import com.example.gtmaddons.gun.ShotTracker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;

/**
 * Settings > Hit sound: plays a sound of your choosing (played only on your
 * own client) when you hit another player. Which kinds of hit play it is set
 * per kind: headshot, body shot (a gun hit that wasn't a headshot) and melee.
 * Turning on all three plays it for every hit.
 *
 * A hit is a damage event caused by you on another player, the same signal
 * ShotTracker uses:
 *   - Gun: you're holding something with an ammo readout in its name (see
 *     ShotTracker.parseAmmo). A headshot is the server's "&#8853;" subtitle
 *     arriving with the hit. The Net Launcher only counts on a player who's
 *     wearing a wingsuit or gliding, like in the stats.
 *   - Melee: anything else.
 *
 * The damage packet can arrive just before its headshot marker, so a hit is
 * held for RESOLVE_NANOS (a frame or two) before it's judged and played.
 * Hits inside that window (a shotgun's pellets) play once.
 *
 * The sounds on offer are vanilla sounds that GTM's gun, boost and jetpack
 * sounds don't use, so the Sounds volume sliders never change them. They're
 * played locally, so ShotTracker never mistakes one for a gunshot.
 */
public final class HitSounds {

	public static final HitSounds INSTANCE = new HitSounds();

	/** A sound you can pick: what it's called in the menu, and its vanilla id. */
	public record Choice(String label, String id) {}

	public static final List<Choice> CHOICES = List.of(
			new Choice("Ding", "minecraft:entity.experience_orb.pickup"),
			new Choice("Hitmarker", "minecraft:block.note_block.hat"),
			new Choice("Bell", "minecraft:block.note_block.bell"),
			new Choice("Pling", "minecraft:block.note_block.pling"),
			new Choice("Chime", "minecraft:block.note_block.chime"),
			new Choice("Bit", "minecraft:block.note_block.bit"),
			new Choice("Xylophone", "minecraft:block.note_block.xylophone"),
			new Choice("Kick", "minecraft:block.note_block.basedrum"),
			new Choice("Level up", "minecraft:entity.player.levelup"),
			new Choice("Crit", "minecraft:entity.player.attack.crit"),
			new Choice("Anvil", "minecraft:block.anvil.land"),
			new Choice("Glass", "minecraft:block.glass.break"));

	private static final long RESOLVE_NANOS = 35_000_000L;
	/** A headshot marker this close to the hit (either side) belongs to it. */
	private static final long HEADSHOT_MATCH_NANOS = 100_000_000L;
	private static final String HEADSHOT_MARKER = "⊕";

	private Settings settings;
	/** When the hit being held was seen (-1 = none), and whether it was melee. */
	private long pendingNanos = -1L;
	private boolean pendingMelee = false;
	private long headshotNanos = -1L;

	private HitSounds() {}

	public void init(Settings settings) {
		this.settings = settings;
		// The old Hitmarker (entity.arrow.hit_player) is the same recording as Ding; keep those players on Ding.
		if ("minecraft:entity.arrow.hit_player".equals(settings.hitSoundId)) {
			settings.hitSoundId = "minecraft:entity.experience_orb.pickup";
			settings.save();
		}
	}

	/** The kinds of hit that can each have their own sound. */
	public enum Kind {
		HEADSHOT("Headshot"), BODY("Body shot"), MELEE("Melee");

		public final String label;

		Kind(String label) {
			this.label = label;
		}
	}

	/** Whether this kind of hit plays a sound at all. */
	public boolean enabledOf(Kind kind) {
		return switch (kind) {
			case HEADSHOT -> settings.hitSoundHeadshot;
			case BODY -> settings.hitSoundBody;
			case MELEE -> settings.hitSoundMelee;
		};
	}

	public void setEnabled(Kind kind, boolean on) {
		switch (kind) {
			case HEADSHOT -> settings.hitSoundHeadshot = on;
			case BODY -> settings.hitSoundBody = on;
			case MELEE -> settings.hitSoundMelee = on;
		}
	}

	/** The sound id for this kind: its own, or the body shot sound's until one is picked. */
	public String idOf(Kind kind) {
		String own = kind == Kind.HEADSHOT ? settings.hitSoundHeadshotId : kind == Kind.MELEE ? settings.hitSoundMeleeId : null;
		return own != null ? own : settings.hitSoundId;
	}

	public float volumeOf(Kind kind) {
		float own = kind == Kind.HEADSHOT ? settings.hitSoundHeadshotVolume : kind == Kind.MELEE ? settings.hitSoundMeleeVolume : -1.0f;
		return own >= 0.0f ? own : settings.hitSoundVolume;
	}

	public float pitchOf(Kind kind) {
		float own = kind == Kind.HEADSHOT ? settings.hitSoundHeadshotPitch : kind == Kind.MELEE ? settings.hitSoundMeleePitch : -1.0f;
		return own > 0.0f ? own : settings.hitSoundPitch;
	}

	public void setId(Kind kind, String id) {
		switch (kind) {
			case HEADSHOT -> settings.hitSoundHeadshotId = id;
			case MELEE -> settings.hitSoundMeleeId = id;
			case BODY -> settings.hitSoundId = id;
		}
	}

	public void setVolume(Kind kind, float volume) {
		switch (kind) {
			case HEADSHOT -> settings.hitSoundHeadshotVolume = volume;
			case MELEE -> settings.hitSoundMeleeVolume = volume;
			case BODY -> settings.hitSoundVolume = volume;
		}
	}

	public void setPitch(Kind kind, float pitch) {
		switch (kind) {
			case HEADSHOT -> settings.hitSoundHeadshotPitch = pitch;
			case MELEE -> settings.hitSoundMeleePitch = pitch;
			case BODY -> settings.hitSoundPitch = pitch;
		}
	}

	/** Index into CHOICES of this kind's sound (the first if it's unknown). */
	public int selectedIndex(Kind kind) {
		String id = idOf(kind);
		for (int i = 0; i < CHOICES.size(); i++) {
			if (CHOICES.get(i).id().equals(id)) return i;
		}
		return 0;
	}

	/** A damage event from the server (game thread). */
	public void onDamage(Entity target, int causeId) {
		MinecraftClient client = MinecraftClient.getInstance();
		ClientPlayerEntity me = client.player;
		if (settings == null || !settings.hitSound || me == null || causeId != me.getId()) return;
		if (!(target instanceof PlayerEntity) || target == me) return;

		String held = me.getMainHandStack().isEmpty() ? "" : me.getMainHandStack().getName().getString();
		boolean gun = ShotTracker.parseAmmo(held) != null;
		if (gun && ShotTracker.countsOnlyWingsuitHits(held) && !ShotTracker.isOnWingsuit(target)) return;

		if (pendingNanos < 0) {
			pendingNanos = System.nanoTime();
			pendingMelee = !gun;
		}
	}

	/** A subtitle from the server: the headshot marker arrives with the hit. */
	public void onSubtitle(Text text) {
		if (text.getString().contains(HEADSHOT_MARKER)) headshotNanos = System.nanoTime();
	}

	/** Judges and plays a held hit once its headshot marker had time to arrive. */
	public void onFrame(MinecraftClient client) {
		if (pendingNanos < 0) return;
		long now = System.nanoTime();
		if (now - pendingNanos < RESOLVE_NANOS) return;

		boolean melee = pendingMelee;
		long hitNanos = pendingNanos;
		pendingNanos = -1L;
		if (settings == null || !settings.hitSound) return;

		boolean headshot = !melee && headshotNanos >= 0 && Math.abs(headshotNanos - hitNanos) <= HEADSHOT_MATCH_NANOS;
		Kind kind = melee ? Kind.MELEE : headshot ? Kind.HEADSHOT : Kind.BODY;
		if (enabledOf(kind)) play(idOf(kind), volumeOf(kind), pitchOf(kind));
	}

	/** Plays a sound to you only, at the given volume (0-1) and pitch. Also used to preview in the menu. */
	public static void play(String soundId, float volume, float pitch) {
		if (volume <= 0.0f) return;
		Identifier id = Identifier.tryParse(soundId);
		if (id == null) return;
		MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.ui(SoundEvent.of(id), pitch, volume));
	}
}
