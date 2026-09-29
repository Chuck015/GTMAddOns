package com.example.gtmaddons;

import com.example.gtmaddons.gun.ShotTracker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
	private static final Pattern RECOVERING_MESSAGE = Pattern.compile("recovering\\s*-\\s*[0-9.]+s", Pattern.CASE_INSENSITIVE);
	private static final String SWORD = "⚔";
	private static final int BAR_LENGTH = 20;

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

	private ComboTracker() {}

	public void addListener(Consumer<ComboResult> listener) {
		listeners.add(listener);
	}

	// ---- Hits ----

	public void onDamage(int targetId, int causeId) {
		MinecraftClient client = MinecraftClient.getInstance();
		ClientPlayerEntity me = client.player;
		if (me == null || client.world == null || targetId == causeId) return;
		long now = System.nanoTime();

		if (targetId == me.getId()) {
			Entity attacker = client.world.getEntityById(causeId);
			if (!(attacker instanceof PlayerEntity player) || !isMeleeItem(player.getMainHandStack())) return;
			lockStartNanos = now;
			lockEndNanos = now + LOCK_NANOS;
			// They landed a hit, so your combo on them (if any) is broken.
			// No combo to break and none of theirs to hold: a first hit.
			Chain broken = myChains.remove(causeId);
			resolve(broken, false, true);
			enemyChains.computeIfAbsent(causeId, id -> new Chain(name(player), PvpCategory.classify(me), broken == null, now));
			Chain chain = enemyChains.get(causeId);
			chain.hits++;
			chain.lastHitNanos = now;
		} else if (causeId == me.getId()) {
			Entity victim = client.world.getEntityById(targetId);
			if (!(victim instanceof PlayerEntity player) || !isMeleeItem(me.getMainHandStack())) return;
			startHitCooldown(me, now);
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
	public void onFrame(MinecraftClient client) {
		long now = System.nanoTime();
		boolean iAmDead = client.player == null || client.player.isDead();
		expire(enemyChains, true, now, client, iAmDead);
		expire(myChains, false, now, client, iAmDead);
		if (iAmDead) lockStartNanos = hitStartNanos = -1L;
	}

	private void expire(Map<Integer, Chain> chains, boolean enemy, long now, MinecraftClient client, boolean iAmDead) {
		Iterator<Map.Entry<Integer, Chain>> it = chains.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Integer, Chain> entry = it.next();
			Entity other = client.world != null ? client.world.getEntityById(entry.getKey()) : null;
			boolean otherGone = !(other instanceof PlayerEntity player) || player.isDead();
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
	private void startHitCooldown(ClientPlayerEntity me, long now) {
		ItemStack weapon = me.getStackInHand(Hand.MAIN_HAND);
		hitGroup = weapon.isEmpty() ? null : me.getItemCooldownManager().getGroup(weapon);
		int ticks = hitGroup != null ? cooldownTicks.getOrDefault(hitGroup, HIT_COOLDOWN_TICKS) : HIT_COOLDOWN_TICKS;
		hitStartNanos = now;
		hitEndNanos = now + ticks * TICK_NANOS;
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
		if (hitStartNanos >= 0 && group.equals(hitGroup) && now - hitStartNanos <= COOLDOWN_MATCH_NANOS) {
			hitEndNanos = ticks > 0 ? hitStartNanos + ticks * TICK_NANOS : now;
		}
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
	public boolean onActionBar(Text text) {
		if (!timersEnabled) return false;
		String message = text.getString();
		Matcher combo = COMBO_MESSAGE.matcher(message);
		if (combo.find()) {
			long now = System.nanoTime();
			// Missed the hit itself somehow: fall back to the server's number.
			if (lockStartNanos < 0 || now > lockEndNanos) {
				lockStartNanos = now;
				lockEndNanos = now + (long) (Double.parseDouble(combo.group(1)) * 1_000_000_000L);
			}
			return true;
		}
		return RECOVERING_MESSAGE.matcher(message).find();
	}

	/** The Combo'd timer (until you can hit back), or null. Drawn where HudLayout puts it. */
	public Text lockText() {
		if (!timersEnabled || !holdingMelee() || !lockShown(System.nanoTime())) return null;
		return timerText(System.nanoTime(), lockStartNanos, lockEndNanos, "Combo'd", null, "✔ Hit back");
	}

	/** The hit-again timer (until you can land another hit), or null. */
	public Text hitText() {
		if (!timersEnabled || !holdingMelee() || lockShown(System.nanoTime())) return null;
		return timerText(System.nanoTime(), hitStartNanos, hitEndNanos, "Hit again", Formatting.AQUA, "✔ Hit ready");
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
	private static Text timerText(long now, long start, long end, String label, Formatting color, String ready) {
		if (start < 0 || now - end > READY_NANOS) return null;
		if (now >= end) return Text.literal(ready).formatted(Formatting.GREEN, Formatting.BOLD);
		double remaining = (end - now) / 1_000_000_000.0;
		double fraction = Math.min(1.0, (double) (end - now) / Math.max(1L, end - start));
		Formatting barColor = color != null ? color
				: fraction > 0.5 ? Formatting.RED : fraction > 0.2 ? Formatting.GOLD : Formatting.YELLOW;
		int filled = (int) Math.ceil(fraction * BAR_LENGTH);
		MutableText text = Text.empty()
				.append(Text.literal(SWORD + " ").formatted(barColor))
				.append(Text.literal(label + " ").formatted(barColor, Formatting.BOLD))
				.append(Text.literal("|".repeat(filled)).formatted(barColor))
				.append(Text.literal("|".repeat(BAR_LENGTH - filled)).formatted(Formatting.DARK_GRAY))
				.append(Text.literal(String.format(" %.2fs", remaining)).formatted(Formatting.WHITE));
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
	 * everything else GTM lets you hit with (Katana, Golf Club, Chanclas...) counts.
	 * From GTM's dev logs, where guns also deal "player_attack" damage, so the damage type
	 * can't tell a gun hit from a melee hit - the held item is what does.
	 */
	public static boolean isMeleeItem(ItemStack stack) {
		if (stack.isEmpty()) return false;
		if (hasAmmoReadout(stack.getName().getString())) return false;
		if (PvpCategory.isWingsuit(stack) || PvpCategory.isJetpack(stack) || PvpCategory.isWornJetpack(stack)) return false;
		return !stack.contains(DataComponentTypes.FOOD) && !stack.contains(DataComponentTypes.CONSUMABLE);
	}

	/** Whether an item name carries a gun's ammo readout: "«7/576105»" or a single number like "«1»". */
	static boolean hasAmmoReadout(String itemName) {
		return itemName.indexOf('«') >= 0 || ShotTracker.parseAmmo(itemName) != null;
	}

	/** Whether you're holding a melee weapon right now. */
	private static boolean holdingMelee() {
		ClientPlayerEntity me = MinecraftClient.getInstance().player;
		return me != null && isMeleeItem(me.getMainHandStack());
	}

	private static String name(PlayerEntity player) {
		return player.getName().getString();
	}
}
