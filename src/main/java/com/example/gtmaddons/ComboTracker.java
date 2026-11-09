package com.example.gtmaddons;

import com.example.gtmaddons.gun.DevFilter;
import com.example.gtmaddons.gun.DevLogger;
import com.example.gtmaddons.gun.ShotTracker;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.example.gtmaddons.probe.Probes;
import com.example.gtmaddons.probe.Remote;

/**
 * GTM melee combos, worked out from the dev log (2026-09-26/27):
 *   - Every registered melee hit locks the player who was hit for LOCK_NANOS
 *     (0.7s). While locked, their swings "blank" - they send no damage.
 *   - The attacker can't land another hit on them until their weapon's
 *     cooldown (the white sweep over the hotbar item) runs out - 0.5s for
 *     most weapons, shorter for some, like chanclas.
 *   - The server shows "⚔ Combo'd - 0.6s" to a locked player and "Target
 *     recovering - 0.1s" to an attacker swinging too early, but only when
 *     they swing, so the numbers never count down on their own.
 *
 * This does two things:
 *
 * 1. Live timers (Settings > Better combo timers): as soon as you're hit,
 *    or land a hit, a countdown above the crosshair (movable in Settings >
 *    Move HUD) shows how long until you can hit back / hit again. GTM's
 *    frozen messages are hidden. Your
 *    hit-again time comes from the server's cooldown for the weapon you hit
 *    with, remembered per weapon, so weapons with shorter cooldowns are
 *    timed right; until one has been seen, it's HIT_COOLDOWN_TICKS.
 *
 *    GTM changed its combo rules on 2026-10-30: a blank swing now shortens the wait, and the base times may have moved. So the timers no
 *    longer trust fixed numbers. Every blank makes the server send its message with the real time left, which corrects the running timer
 *    (it was hidden and ignored before), and the first message after a hit teaches the base time, per weapon for hit-again. How much a
 *    blank takes off is not assumed anywhere: it is measured in our dev log (the "Combo timing" filter) and in each fight's click numbers
 *    (ClickTracker), until the rule is known exactly.
 *
 * 2. Combo breaks: a combo is a player's run of registered melee hits on
 *    someone. It's broken when the victim lands a registered melee hit on
 *    the attacker, and ends unbroken if nobody lands a hit on the other for
 *    CHAIN_TIMEOUT_NANOS or someone dies. Both enemy combos on you and your
 *    combos on others are tracked, per PvpCategory at the combo's start.
 *    Only player-vs-player hits with a melee weapon count (see isMeleeItem:
 *    not guns, bare hands, wingsuits, jetpacks or food). The live timers are
 *    tied to melee too: they only start from such hits, and only show while
 *    you're holding a melee weapon.
 *
 *    Every hit is one of three things: a break (it ends the other player's
 *    combo), a hold (it continues your own combo), or a first hit (neither -
 *    nobody had a combo going between the two). Each combo starts with a
 *    break or a first hit, and ComboResult.firstHit says which.
 */
public final class ComboTracker {

	public static final ComboTracker INSTANCE = new ComboTracker();

	private static final long LOCK_NANOS = 700_000_000L;
	/** The damage type of a hit with whatever is in hand (guns too, on GTM). */
	private static final String MELEE_DAMAGE_TYPE = "minecraft:player_attack";
	/** Further apart than this (blocks, between the two players), a hit is not melee: vanilla reach is 3, the rest allows for lag and fast flying. */
	private static final double MELEE_RANGE_BLOCKS = 10.0;
	/** Thrown items: not melee weapons even though nothing else rules them out. */
	private static final java.util.List<String> THROWABLES = java.util.List.of("grenade", "molotov", "bomb", "c4", "flashbang", "smoke", "snowball", "pearl", "potion", "egg");
	/** Hit-again cooldown for a weapon whose real cooldown hasn't been seen yet. */
	private static final int HIT_COOLDOWN_TICKS = 10;
	private static final long TICK_NANOS = 50_000_000L;
	/** A cooldown packet this soon after your hit is that hit's cooldown. */
	private static final long COOLDOWN_MATCH_NANOS = 300_000_000L;
	/** How long "can hit" stays up after a timer runs out. */
	private static final long READY_NANOS = 400_000_000L;
	/** A combo ends unbroken after this long with no hit from the attacker. */
	private static final long CHAIN_TIMEOUT_NANOS = 2_000_000_000L;
	private static final Pattern COMBO_MESSAGE = Pattern.compile("combo'?d\\s*-\\s*([0-9]+(?:\\.[0-9]+)?)s", Pattern.CASE_INSENSITIVE);
	private static final Pattern RECOVERING_MESSAGE = Pattern.compile("recovering\\s*-\\s*([0-9]+(?:\\.[0-9]+)?)s", Pattern.CASE_INSENSITIVE);
	private static final String SWORD = "⚔";
	private static final int BAR_LENGTH = 20;
	/** The server's times are in tenths of a second: a timer this close to one is left alone. */
	private static final long MESSAGE_SLACK_NANOS = 60_000_000L;
	/** A melee click that brought no hit and no blank message by now was a miss. */
	private static final long CLICK_RESOLVE_NANOS = 350_000_000L;
	/** Base times seen, per timer, and how many it takes before they replace the known ones. */
	private static final int SAMPLES_KEPT = 15, SAMPLES_NEEDED = 3;

	/**
	 * One finished combo. enemy = someone's combo on you; otherwise yours on
	 * them. firstHit = it started with a first hit, not by breaking a combo.
	 */
	public record ComboResult(boolean enemy, boolean broken, int hits, String opponent, PvpCategory category, boolean firstHit) {}

	private static final class Chain {
		final String opponent;
		final PvpCategory category;
		final boolean firstHit;
		long lastHitNanos;
		int hits;

		Chain(String opponent, PvpCategory category, boolean firstHit, long now) {
			this.opponent = opponent;
			this.category = category;
			this.firstHit = firstHit;
			this.lastHitNanos = now;
		}
	}

	private final List<Consumer<ComboResult>> listeners = new ArrayList<>();
	/** Enemy combos on you, by attacker entity id. */
	private final Map<Integer, Chain> enemyChains = new HashMap<>();
	/** Your combos, by victim entity id. */
	private final Map<Integer, Chain> myChains = new HashMap<>();

	private boolean timersEnabled = true;
	// Combo'd: you were hit and can't hit back yet
	private long lockStartNanos = -1L;
	private long lockEndNanos = 0L;
	// Hit again: you landed a hit and your weapon is cooling down
	private long hitStartNanos = -1L;
	private long hitEndNanos = 0L;
	private Identifier hitGroup = null;
	/** Cooldown length (ticks) the server last set for each weapon's cooldown group. */
	private final Map<Identifier, Integer> cooldownTicks = new HashMap<>();

	/**
	 * The combo timing numbers of one fight, uploaded with it for everyone running the mod (information only, shown in Raw info), so
	 * GTM's combo rules can be worked out from many players instead of one dev log. Times in ms:
	 *   locks / hits          melee hits on you / by you;
	 *   lock_blanks / recover_blanks   your blank swings while combo'd / on a recovering target;
	 *   lock_first            how long the lock was, from the first "Combo'd" message after a hit (time since the hit + time left);
	 *   lock_drop             between two "Combo'd" messages of one lock, how much more the time left fell than the time that passed
	 *                         (what a blank takes off);
	 *   recover_first, recover_drop   the same for "Target recovering" after your hit;
	 *   clear                 when the server ended your weapon's cooldown (a 0-tick cooldown packet), measured from your hit.
	 * Each measured value is kept as count, sum, min and max.
	 */
	public static final class Stats {
		int locks, hits, lockBlanks, recoverBlanks;
		final Sample lockFirst = new Sample(), lockDrop = new Sample(), recoverFirst = new Sample(), recoverDrop = new Sample(), clear = new Sample();

		void addTo(com.google.gson.JsonObject o) {
			o.addProperty("combo_locks", locks);
			o.addProperty("combo_hits", hits);
			o.addProperty("combo_lock_blanks", lockBlanks);
			o.addProperty("combo_recover_blanks", recoverBlanks);
			lockFirst.addTo(o, "combo_lock_first");
			lockDrop.addTo(o, "combo_lock_drop");
			recoverFirst.addTo(o, "combo_recover_first");
			recoverDrop.addTo(o, "combo_recover_drop");
			clear.addTo(o, "combo_clear");
		}
	}

	/** Count, sum, min and max of one measured time (ms). */
	static final class Sample {
		int n;
		double sum, min = Double.MAX_VALUE, max = -Double.MAX_VALUE;

		void add(long nanos) {
			double ms = nanos / 1_000_000.0;
			n++;
			sum += ms;
			min = Math.min(min, ms);
			max = Math.max(max, ms);
		}

		void addTo(com.google.gson.JsonObject o, String key) {
			o.addProperty(key + "_n", n);
			if (n == 0) return;
			o.addProperty(key + "_sum", Math.round(sum));
			o.addProperty(key + "_min", Math.round(min));
			o.addProperty(key + "_max", Math.round(max));
		}
	}

	private Stats fight = null;
	/** The last "Combo'd" / "Target recovering" message's time and time left, for the drop between two of them. */
	private long lastLockMsgNanos = -1L, lastLockLeft = 0L, lastRecoverMsgNanos = -1L, lastRecoverLeft = 0L;

	public void onFightStart() {
		fight = new Stats();
	}

	public Stats onFightEnd() {
		Stats done = fight;
		fight = null;
		return done;
	}

	public void onFightDrop() {
		fight = null;
	}

	/** Melee clicks not yet known to be a hit, a blank or a miss, oldest first. */
	/** Each pending click: { when (nanos), where it was aimed (MeleeAimTracker.NO_TARGET / OFF_TARGET / ON_TARGET) }. */
	private final ArrayDeque<long[]> pendingClicks = new ArrayDeque<>();
	private long lastMyHitNanos = -1L;
	private int clicksSinceHit = 0, blanksSinceHit = 0, blanksSinceLock = 0;
	/** The full length of a timer as the server's first message after it started gave it (elapsed + time left). */
	private final ArrayDeque<Long> lockSamples = new ArrayDeque<>();
	private final Map<Identifier, ArrayDeque<Long>> hitSamples = new HashMap<>();

	private ComboTracker() {}

	public void addListener(Consumer<ComboResult> listener) {
		listeners.add(listener);
	}

	// ---- Hits ----

	/**
	 * A damage event. Only a melee hit counts: a direct player attack (damage type player_attack, dealt by the player themselves, not
	 * a projectile, fire or an explosion they caused), by someone within MELEE_RANGE_BLOCKS of their target, with a melee weapon in
	 * hand (isMeleeItem). GTM's guns also deal player_attack damage, so the held item and the distance are what tell them apart.
	 */
	public void onDamage(int targetId, int causeId, int directId, String damageType) {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer me = client.player;
		if (me == null || client.level == null || targetId == causeId) return;
		if (!MELEE_DAMAGE_TYPE.equals(damageType) || directId != causeId) return;
		Entity other = client.level.getEntity(targetId == me.getId() ? causeId : targetId);
		if (other == null || other.distanceTo(me) > Remote.num("melee_range", MELEE_RANGE_BLOCKS)) return;
		long now = System.nanoTime();

		if (targetId == me.getId()) {
			Entity attacker = client.level.getEntity(causeId);
			if (!(attacker instanceof Player player) || !isMeleeItem(player.getMainHandItem())) return;
			lockStartNanos = now;
			lastLockMsgNanos = -1L;
			if (fight != null) fight.locks++;
			if (Probes.wants("melee_hit_me")) Probes.emit("melee_hit_me", "item", Probes.itemId(player.getMainHandItem()), "dist", Probes.round(other.distanceTo(me)), "move", MoveStates.stateName(client));
			long learned = learned(lockSamples);
			lockEndNanos = now + (learned > 0 ? learned : LOCK_NANOS);
			blanksSinceLock = 0;
			logTiming(String.format("HIT ON YOU by %s with %s | locked for %s", name(player), player.getMainHandItem().getHoverName().getString(), learned > 0 ? "a learned " + ms(learned) : "the default " + ms(LOCK_NANOS)), true);
			// They landed a hit, so your combo on them (if any) is broken.
			// No combo to break and none of theirs to hold: a first hit.
			Chain broken = myChains.remove(causeId);
			resolve(broken, false, true);
			enemyChains.computeIfAbsent(causeId, id -> new Chain(name(player), PvpCategory.classify(me), broken == null, now));
			Chain chain = enemyChains.get(causeId);
			chain.hits++;
			chain.lastHitNanos = now;
		} else if (causeId == me.getId()) {
			Entity victim = client.level.getEntity(targetId);
			if (!(victim instanceof Player player) || !isMeleeItem(me.getMainHandItem())) return;
			long gapBefore = lastMyHitNanos >= 0 ? now - lastMyHitNanos : -1L;
			int clicksBefore = clicksSinceHit, blanksBefore = blanksSinceHit;
			onMyHit(me, player, now);
			startHitCooldown(me, now);
			if (Probes.wants("melee_hit")) Probes.emit("melee_hit", "item", Probes.itemId(me.getMainHandItem()), "group", hitGroup != null ? hitGroup.toString() : null,
					"dist", Probes.round(other.distanceTo(me)), "gap_ms", gapBefore >= 0 ? Probes.round(gapBefore / 1_000_000.0) : null, "clicks", clicksBefore, "blanks", blanksBefore);
			// You landed a hit, so their combo on you (if any) is broken.
			// No combo to break and none of yours to hold: a first hit.
			Chain broken = enemyChains.remove(targetId);
			resolve(broken, true, true);
			myChains.computeIfAbsent(targetId, id -> new Chain(name(player), PvpCategory.classify(me), broken == null, now));
			Chain chain = myChains.get(targetId);
			chain.hits++;
			chain.lastHitNanos = now;
		}
	}

	/** Ends combos that have gone quiet, or whose players are gone or dead. */
	public void onFrame(Minecraft client) {
		long now = System.nanoTime();
		while (!pendingClicks.isEmpty() && now - pendingClicks.peekFirst()[0] > CLICK_RESOLVE_NANOS) {
			long[] click = pendingClicks.removeFirst();
			ClickTracker.INSTANCE.onMeleeMiss();
			MeleeAimTracker.INSTANCE.onClickOutcome((int) click[1], 2);
		}
		boolean iAmDead = client.player == null || client.player.isDeadOrDying();
		expire(enemyChains, true, now, client, iAmDead);
		expire(myChains, false, now, client, iAmDead);
		if (iAmDead) lockStartNanos = hitStartNanos = -1L;
	}

	private void expire(Map<Integer, Chain> chains, boolean enemy, long now, Minecraft client, boolean iAmDead) {
		Iterator<Map.Entry<Integer, Chain>> it = chains.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Integer, Chain> entry = it.next();
			Entity other = client.level != null ? client.level.getEntity(entry.getKey()) : null;
			boolean otherGone = !(other instanceof Player player) || player.isDeadOrDying();
			if (iAmDead || otherGone || now - entry.getValue().lastHitNanos > CHAIN_TIMEOUT_NANOS) {
				it.remove();
				resolve(entry.getValue(), enemy, false);
			}
		}
	}

	private void resolve(Chain chain, boolean enemy, boolean broken) {
		if (chain == null) return;
		ComboResult result = new ComboResult(enemy, broken, chain.hits, chain.opponent, chain.category, chain.firstHit);
		for (Consumer<ComboResult> listener : listeners) listener.accept(result);
	}

	// ---- Hit-again cooldown ----

	/** Times your hit-again cooldown from the weapon's last known server cooldown. */
	private void startHitCooldown(LocalPlayer me, long now) {
		ItemStack weapon = me.getItemInHand(InteractionHand.MAIN_HAND);
		hitGroup = weapon.isEmpty() ? null : me.getCooldowns().getCooldownGroup(weapon);
		int ticks = hitGroup != null ? cooldownTicks.getOrDefault(hitGroup, HIT_COOLDOWN_TICKS) : HIT_COOLDOWN_TICKS;
		long learned = learned(hitSamples.get(hitGroup));
		hitStartNanos = now;
		hitEndNanos = now + (learned > 0 ? learned : ticks * TICK_NANOS);
	}

	/**
	 * The server setting an item cooldown (the hotbar sweep). GTM sets one
	 * on your weapon when you land a hit, and it's exactly how long until you
	 * can hit again - so it's remembered per weapon, and corrects the current
	 * timer if it's for the weapon you just hit with.
	 */
	public void onCooldown(Identifier group, int ticks) {
		if (ticks > 0) cooldownTicks.put(group, ticks);
		long now = System.nanoTime();
		if (ticks == 0 && fight != null && hitStartNanos >= 0 && group.equals(hitGroup) && now - hitStartNanos <= CHAIN_TIMEOUT_NANOS) fight.clear.add(now - hitStartNanos);
		if (hitStartNanos < 0 || !group.equals(hitGroup) || now > hitEndNanos) return;
		boolean fromHit = now - hitStartNanos <= COOLDOWN_MATCH_NANOS;
		logTiming(String.format("COOLDOWN %d ticks (%s) | %s after your hit | timer had %s left", ticks, ms(ticks * TICK_NANOS), ms(now - hitStartNanos), ms(hitEndNanos - now)), false);
		// The hit's own cooldown, unless the server's messages have shown a different length; a later one is the server changing the wait.
		if (fromHit && learned(hitSamples.get(group)) == 0) hitEndNanos = ticks > 0 ? hitStartNanos + ticks * TICK_NANOS : now;
		else if (!fromHit) hitEndNanos = now + ticks * TICK_NANOS;
	}

	// ---- Live timers ----

	/** Settings > Better combo timers. When off, GTM's own messages are shown unchanged. */
	public void setTimersEnabled(boolean enabled) {
		timersEnabled = enabled;
	}

	/**
	 * Called with every action-bar message. Returns true if it's GTM's
	 * "Combo'd" or "Target recovering" message, which should then be hidden -
	 * the live timers take their place.
	 */
	public boolean onActionBar(Component text) {
		String message = text.getString();
		Probes.onText("action_bar", message);
		// The patterns can be replaced by the remote config (Remote) if GTM rewords its messages; a bad one falls back to the mod's own.
		long left = seconds(Remote.pattern("combo_msg", COMBO_MESSAGE), COMBO_MESSAGE, message);
		if (left >= 0) {
			Probes.emit("combo_msg", "left_ms", left / 1_000_000.0);
			onServerLock(left);
			return timersEnabled;
		}
		left = seconds(Remote.pattern("recovering_msg", RECOVERING_MESSAGE), RECOVERING_MESSAGE, message);
		if (left >= 0) {
			Probes.emit("recover_msg", "left_ms", left / 1_000_000.0);
			onServerRecovering(left);
			return timersEnabled;
		}
		return false;
	}

	/** The time in a message (nanoseconds), or -1 if it isn't one; a remote pattern that fails falls back to the built-in one. */
	private static long seconds(Pattern pattern, Pattern fallback, String message) {
		try {
			Matcher m = pattern.matcher(message);
			if (m.find()) return (long) (Double.parseDouble(m.group(1)) * 1_000_000_000L);
			return -1L;
		} catch (RuntimeException bad) {
			return pattern == fallback ? -1L : seconds(fallback, fallback, message);
		}
	}

	/** You swung while locked: the server's time left replaces the timer's when they differ. */
	private void onServerLock(long left) {
		long now = System.nanoTime();
		resolveBlank();
		boolean running = lockStartNanos >= 0 && now <= lockEndNanos;
		logTiming(String.format("BLANK while combo'd | server says %s left | timer had %s | %s after the hit | blank %d", ms(left), running ? ms(lockEndNanos - now) : "ended", running ? ms(now - lockStartNanos) : "?", blanksSinceLock + 1), true);
		if (!running) {
			// Missed the hit itself somehow: the server's number is all there is.
			lockStartNanos = now;
			lockEndNanos = now + left;
		} else {
			if (blanksSinceLock == 0) sample(lockSamples, now - lockStartNanos + left);
			if (fight != null) {
				if (blanksSinceLock == 0) fight.lockFirst.add(now - lockStartNanos + left);
				else if (lastLockMsgNanos >= 0) fight.lockDrop.add((lastLockLeft - left) - (now - lastLockMsgNanos));
			}
			if (Math.abs(lockEndNanos - now - left) > MESSAGE_SLACK_NANOS) lockEndNanos = now + left;
		}
		if (fight != null) fight.lockBlanks++;
		lastLockMsgNanos = now;
		lastLockLeft = left;
		blanksSinceLock++;
	}

	/** You swung too early: the server's time left replaces the timer's when they differ. */
	private void onServerRecovering(long left) {
		long now = System.nanoTime();
		resolveBlank();
		boolean running = hitStartNanos >= 0 && now <= hitEndNanos;
		logTiming(String.format("BLANK on recovering target | server says %s left | timer had %s | %s after your hit | blank %d", ms(left), running ? ms(hitEndNanos - now) : "ended", running ? ms(now - hitStartNanos) : "?", blanksSinceHit + 1), true);
		if (!running) {
			hitStartNanos = now;
			hitEndNanos = now + left;
		} else {
			if (blanksSinceHit == 0 && hitGroup != null) sample(hitSamples.computeIfAbsent(hitGroup, g -> new ArrayDeque<>()), now - hitStartNanos + left);
			if (fight != null) {
				if (blanksSinceHit == 0) fight.recoverFirst.add(now - hitStartNanos + left);
				else if (lastRecoverMsgNanos >= 0) fight.recoverDrop.add((lastRecoverLeft - left) - (now - lastRecoverMsgNanos));
			}
			if (Math.abs(hitEndNanos - now - left) > MESSAGE_SLACK_NANOS) hitEndNanos = now + left;
		}
		if (fight != null) fight.recoverBlanks++;
		lastRecoverMsgNanos = now;
		lastRecoverLeft = left;
		blanksSinceHit++;
	}

	// ---- Clicks ----

	/** A left click (ClickTracker). Melee ones wait to hear what they did. */
	void onClick() {
		if (!holdingMelee()) return;
		pendingClicks.addLast(new long[] { System.nanoTime(), MeleeAimTracker.INSTANCE.aimForClick() });
		clicksSinceHit++;
	}

	private void resolveBlank() {
		long[] click = pendingClicks.pollFirst();
		if (click == null) return;
		ClickTracker.INSTANCE.onMeleeBlank();
		MeleeAimTracker.INSTANCE.onClickOutcome((int) click[1], 1);
	}

	/** You landed a melee hit: what led up to it, before the timer restarts. */
	private void onMyHit(LocalPlayer me, Player victim, long now) {
		long[] click = pendingClicks.pollFirst();
		if (click != null) {
			ClickTracker.INSTANCE.onMeleeHit();
			MeleeAimTracker.INSTANCE.onClickOutcome((int) click[1], 0);
		}
		long gap = lastMyHitNanos >= 0 ? now - lastMyHitNanos : -1L;
		if (gap > 0 && gap <= CHAIN_TIMEOUT_NANOS) ClickTracker.INSTANCE.onHitGap(gap / 1_000_000.0, blanksSinceHit > 0);
		logTiming(String.format("HIT %s with %s | %s since your last hit | %d clicks since, %d blank | timer had %s left", name(victim), me.getMainHandItem().getHoverName().getString(),
				gap > 0 ? ms(gap) : "first", clicksSinceHit, blanksSinceHit, hitStartNanos >= 0 ? ms(hitEndNanos - now) : "none"), true);
		lastMyHitNanos = now;
		lastRecoverMsgNanos = -1L;
		if (fight != null) fight.hits++;
		clicksSinceHit = blanksSinceHit = 0;
	}

	// ---- Learned times ----

	private static void sample(ArrayDeque<Long> samples, long nanos) {
		samples.addLast(nanos);
		if (samples.size() > SAMPLES_KEPT) samples.removeFirst();
	}

	/** The base time recent messages show (the longest: it is the one least shortened), or 0 with too few. */
	private static long learned(ArrayDeque<Long> samples) {
		if (samples == null || samples.size() < SAMPLES_NEEDED) return 0L;
		long longest = 0L;
		for (long nanos : samples) longest = Math.max(longest, nanos);
		return longest;
	}

	private static String ms(long nanos) {
		return (nanos / 1_000_000L) + "ms";
	}

	private static void logTiming(String line, boolean inChat) {
		DevLogger log = DevLogger.INSTANCE;
		if (log.wants(DevFilter.COMBO_TIMING)) log.onComboTiming(line, inChat);
	}

	/** The Combo'd timer (until you can hit back), or null. Drawn where HudLayout puts it. */
	public Component lockText() {
		if (!timersEnabled || !holdingMelee() || !lockShown(System.nanoTime())) return null;
		return timerText(System.nanoTime(), lockStartNanos, lockEndNanos, "Combo'd", null, "✔ Hit back");
	}

	/** The hit-again timer (until you can land another hit), or null. */
	public Component hitText() {
		if (!timersEnabled || !holdingMelee() || lockShown(System.nanoTime())) return null;
		return timerText(System.nanoTime(), hitStartNanos, hitEndNanos, "Hit again", ChatFormatting.AQUA, "✔ Hit ready");
	}

	/**
	 * Only one combo timer shows at a time. A running countdown beats a
	 * "ready" flash; between two of the same kind, the newer one wins.
	 * True if that's the Combo'd timer (false: hit again, or neither).
	 */
	private boolean lockShown(long now) {
		boolean lockVisible = lockStartNanos >= 0 && now - lockEndNanos <= READY_NANOS;
		boolean hitVisible = hitStartNanos >= 0 && now - hitEndNanos <= READY_NANOS;
		if (!lockVisible || !hitVisible) return lockVisible;
		boolean lockRunning = now < lockEndNanos, hitRunning = now < hitEndNanos;
		if (lockRunning != hitRunning) return lockRunning;
		return lockStartNanos >= hitStartNanos;
	}

	/**
	 * "⚔ Combo'd ||||||||||||.... 0.43s": a draining bar, then the ready
	 * text in green for READY_NANOS. color null = red, then gold, then yellow
	 * as it runs out. Null once there's nothing to show.
	 */
	private static Component timerText(long now, long start, long end, String label, ChatFormatting color, String ready) {
		if (start < 0 || now - end > READY_NANOS) return null;
		if (now >= end) return Component.literal(ready).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD);
		double remaining = (end - now) / 1_000_000_000.0;
		double fraction = Math.min(1.0, (double) (end - now) / Math.max(1L, end - start));
		ChatFormatting barColor = color != null ? color
				: fraction > 0.5 ? ChatFormatting.RED : fraction > 0.2 ? ChatFormatting.GOLD : ChatFormatting.YELLOW;
		int filled = (int) Math.ceil(fraction * BAR_LENGTH);
		MutableComponent text = Component.empty()
				.append(Component.literal(SWORD + " ").withStyle(barColor))
				.append(Component.literal(label + " ").withStyle(barColor, ChatFormatting.BOLD))
				.append(Component.literal("|".repeat(filled)).withStyle(barColor))
				.append(Component.literal("|".repeat(BAR_LENGTH - filled)).withStyle(ChatFormatting.DARK_GRAY))
				.append(Component.literal(String.format(" %.2fs", remaining)).withStyle(ChatFormatting.WHITE));
		return text;
	}

	// ---- Helpers ----

	/**
	 * A melee weapon - the only kind of item combos and their timers are about. Ruled out:
	 *   - bare hands;
	 *   - guns: any name with an ammo readout in it, whether "Micro SMG «7/576105»" or a
	 *     single-number one like "Stun Gun «1»" and "Flamethrower «8»" (which the old
	 *     "n/max" test let through as if they were swords);
	 *   - a wingsuit or jetpack, and food or other consumables (a "Burger");
	 *   - thrown items (THROWABLES: grenades, molotovs, ...);
	 * everything else GTM lets you hit with (Katana, Golf Club, Chanclas...) counts.
	 * From GTM's dev logs, where guns also deal "player_attack" damage, so the damage type
	 * can't tell a gun hit from a melee hit - the held item is what does.
	 */
	public static boolean isMeleeItem(ItemStack stack) {
		if (stack.isEmpty()) return false;
		String name = stack.getHoverName().getString();
		if (hasAmmoReadout(name)) return false;
		String lower = name.toLowerCase(java.util.Locale.ROOT);
		for (String thrown : THROWABLES) if (lower.contains(thrown)) return false;
		if (PvpCategory.isWingsuit(stack) || PvpCategory.isJetpack(stack) || PvpCategory.isWornJetpack(stack)) return false;
		return !stack.has(DataComponents.FOOD) && !stack.has(DataComponents.CONSUMABLE);
	}

	/** Whether an item name carries a gun's ammo readout: "«7/576105»" or a single number like "«1»". */
	static boolean hasAmmoReadout(String itemName) {
		return itemName.indexOf('«') >= 0 || ShotTracker.parseAmmo(itemName) != null;
	}

	/** Whether you're holding a melee weapon right now. */
	/** The last stack checked, and the answer: the HUD asks twice a frame, and the check reads the item's name several times. */
	private static ItemStack meleeStack = null;
	private static Object meleeName = null;
	private static boolean meleeResult = false;

	private static boolean holdingMelee() {
		LocalPlayer me = Minecraft.getInstance().player;
		if (me == null) return false;
		ItemStack held = me.getMainHandItem();
		Object name = held.isEmpty() ? null : held.getHoverName();
		if (held != meleeStack || name != meleeName) {
			meleeStack = held;
			meleeName = name;
			meleeResult = isMeleeItem(held);
		}
		return meleeResult;
	}

	private static String name(Player player) {
		return player.getName().getString();
	}
}
