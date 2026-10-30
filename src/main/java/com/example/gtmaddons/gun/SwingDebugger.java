package com.example.gtmaddons.gun;

import com.example.gtmaddons.mixin.LivingEntityInvoker;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwingAnimationType;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Dev mode: flags left clicks that don't show a fresh swing, for finding
 * why a hit can register while your sword doesn't swing. For each click,
 * the state before and after it's handled is compared (vanilla 1.21.11
 * swings before sending the attack):
 *
 *   - Mid-swing: a swing only restarts once the current one is half done
 *     (tick >= duration/2), so a fast click still attacks but the animation
 *     just carries on.
 *   - No swing animation: the item's swing_animation type is NONE.
 *   - Dropped: no swing and no attack at all. Vanilla sets a 10-tick block
 *     after a swing at air, but clears it on any tick the button is up - so
 *     normal clicking never hits it (1.8 works the same). It only drops a
 *     click that's released and pressed again within one game tick (50ms,
 *     e.g. jitter clicking or a double-clicking mouse). Also the item's
 *     minimum attack charge. A hit right after a dropped click came from an
 *     earlier one.
 *   - Item update: the server changed the held item (lore, damage, count)
 *     just after the click; the re-equip animation can hide the swing.
 *
 * A click is judged RESOLVE_NANOS later, once any hit it caused has come
 * back. With LOG_ALL_SWINGS every click is logged; chat only gets hits that
 * registered without a fresh swing. A gun's ammo count changing in
 * its name doesn't count as an item change; any other change names the
 * item components that changed.
 */
final class SwingDebugger {

	private static final long RESOLVE_NANOS = 400_000_000L;
	/** A held item change this soon after a click can hide its swing. */
	private static final long ITEM_CHANGE_NANOS = 250_000_000L;
	/**
	 * Log every click, not just hits without a fresh swing (asked for
	 * 2026-09-27, until further notice). Turn off to log only that case.
	 */
	private static final boolean LOG_ALL_SWINGS = true;

	private static final class Click {
		final long nanos;
		final String problem;
		final String details;
		long hitNanos = -1L;
		String hitTarget = null;
		String itemChange = null;
		/** Whether an attack packet went to the server (only for clicks on an entity). */
		boolean attackSent = false;
		/** The server's attack sounds at your feet after this click, e.g. "knockback strong". */
		final Set<String> serverSounds = new LinkedHashSet<>();

		Click(long nanos, String problem, String details) {
			this.nanos = nanos;
			this.problem = problem;
			this.details = details;
		}

		/**
		 * What the server did with the attack, from Minecraft's own attack code
		 * (Player.attack): strong/weak/crit/sweep only play when damage is
		 * dealt; nodamage when it got the attack but dealt none; knockback plays
		 * before the damage check, so on its own it's unclear. No sound means
		 * the server never ran the attack (not sent, or rejected before it -
		 * e.g. by a plugin).
		 */
		String serverResponse() {
			if (!attackSent) return "no attack sent";
			String sounds = String.join(" ", serverSounds);
			if (sounds.matches(".*\\b(strong|weak|crit|sweep)\\b.*")) return "server DEALT damage (" + sounds + ")";
			if (sounds.contains("nodamage")) return "server got it, REFUSED damage (" + sounds + ")";
			if (!sounds.isEmpty()) return "server got it (" + sounds + " only)";
			return "NO server response (attack never ran)";
		}
	}

	/** The server's attack sounds play at your feet: about eye height below your eyes. */
	private static final double OWN_SOUND_MIN = 1.1, OWN_SOUND_MAX = 1.75;
	/** A server attack sound this soon after a click belongs to it. */
	private static final long SOUND_MATCH_NANOS = 250_000_000L;

	private final DevLogger log;
	private final List<Click> pending = new ArrayList<>();

	// State just before the click is handled
	private boolean swingingBefore;
	private int swingTickBefore;
	private int attackCooldownBefore;
	private long lastClickNanos = -1L;

	// Held item, to notice the server changing it
	private ItemStack lastHeld = ItemStack.EMPTY;
	private int lastSlot = -1;

	SwingDebugger(DevLogger log) {
		this.log = log;
	}

	void beforeAttack(Minecraft client, int attackCooldown) {
		LocalPlayer player = client.player;
		if (player == null) return;
		swingingBefore = player.swinging;
		swingTickBefore = player.swingTime;
		attackCooldownBefore = attackCooldown;
	}

	void afterAttack(Minecraft client) {
		LocalPlayer player = client.player;
		if (player == null) return;
		long now = System.nanoTime();
		ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
		int duration = ((LivingEntityInvoker) player).gtmaddons$getHandSwingDuration();
		SwingAnimation animation = stack.getSwingAnimation();
		boolean restarted = player.swinging && player.swingTime == -1;

		String problem = null;
		if (restarted && animation.type() == SwingAnimationType.NONE) {
			problem = "item has no swing animation (swing_animation type NONE)";
		} else if (!restarted && swingingBefore && swingTickBefore >= 0 && swingTickBefore < duration / 2) {
			problem = String.format("mid-swing: tick %d of %d, a new swing only starts from tick %d", swingTickBefore, duration, duration / 2);
		} else if (!restarted && attackCooldownBefore > 0) {
			problem = String.format("click dropped: re-clicked within one game tick of a miss, so the game never saw the button released and its miss block (%d ticks left) was still on", attackCooldownBefore);
		} else if (!restarted && player.cannotAttackWithItem(stack, 0)) {
			problem = String.format("click dropped: attack charge %.0f%% is below the item's minimum", player.getAttackStrengthScale(0f) * 100);
		} else if (!restarted) {
			problem = "no swing (riding, no target, or item disabled)";
		}

		String sinceLast = lastClickNanos >= 0 ? String.format("%dms since last click", (now - lastClickNanos) / 1_000_000L) : "first click";
		lastClickNanos = now;
		// Was an attack actually sent? Only for a click on an entity that wasn't
		// dropped - and if the item has an attack_range, only within it (the
		// client swings but sends nothing otherwise; see Minecraft.doAttack).
		boolean onEntity = client.hitResult instanceof EntityHitResult;
		boolean dropped = problem != null && problem.startsWith("click dropped");
		AttackRange range = stack.get(DataComponents.ATTACK_RANGE);
		boolean inItemRange = !onEntity || range == null || range.isInRange(player, client.hitResult.getLocation());
		String itemRange = onEntity && !inItemRange ? " | item attack_range: target out of range, swung without sending an attack" : "";

		String details = String.format("\"%s\", aiming at %s, charge %.0f%%, swing %d ticks%s, %s%s%s",
				stack.isEmpty() ? "empty hand" : stack.getHoverName().getString(), target(client), player.getAttackStrengthScale(0f) * 100,
				duration, effects(player), sinceLast, reach(client, player), itemRange);
		logWeapon(client, player, stack);
		Click click = new Click(now, problem, details);
		click.attackSent = onEntity && !dropped && inItemRange && !player.isHandsBusy();
		pending.add(click);
		// Open the log now, so the server's attack sounds a moment later are recorded.
		log.openWindow(client);
	}

	/**
	 * Once per weapon: the settings that change how its attacks work in
	 * Minecraft's code - attack speed (the 1.9 cooldown; 1.8-style servers set
	 * it very high), attack_range (the client won't send an attack outside it),
	 * minimum_attack_charge (clicks below it are dropped), and its cooldown group.
	 */
	private void logWeapon(Minecraft client, LocalPlayer player, ItemStack stack) {
		String name = stack.isEmpty() ? "empty hand" : stack.getHoverName().getString();
		if (!loggedWeapons.add(ShotTracker.parseAmmo(name) != null ? ShotTracker.gunName(name) : name)) return;
		log.openWindow(client);
		log.add(String.format("WEAPON  \"%s\" (%s): attack speed %.1f/s (full charge every %.1f ticks), attack damage %.1f, attack_range %s, minimum_attack_charge %s, cooldown group %s",
				name, BuiltInRegistries.ITEM.getKey(stack.getItem()), player.getAttributeValue(Attributes.ATTACK_SPEED),
				20.0 / Math.max(0.01, player.getAttributeValue(Attributes.ATTACK_SPEED)), player.getAttributeValue(Attributes.ATTACK_DAMAGE),
				stack.get(DataComponents.ATTACK_RANGE), stack.get(DataComponents.MINIMUM_ATTACK_CHARGE),
				stack.isEmpty() ? "-" : player.getCooldowns().getCooldownGroup(stack)));
	}

	private final Set<String> loggedWeapons = new LinkedHashSet<>();

	/** Every sound; the server's attack sounds at your feet go to the latest click. */
	void onSound(String soundId, double distance) {
		if (!soundId.startsWith("minecraft:entity.player.attack.") || distance < OWN_SOUND_MIN || distance > OWN_SOUND_MAX) return;
		long now = System.nanoTime();
		for (int i = pending.size() - 1; i >= 0; i--) {
			Click click = pending.get(i);
			if (click.attackSent && now - click.nanos <= SOUND_MATCH_NANOS) {
				click.serverSounds.add(soundId.substring("minecraft:entity.player.attack.".length()));
				return;
			}
		}
	}

	/** A registered hit by you. */
	void onHit(String target) {
		long now = System.nanoTime();
		// Credit the latest click that hasn't got a hit yet.
		for (int i = pending.size() - 1; i >= 0; i--) {
			Click click = pending.get(i);
			if (click.hitNanos < 0) {
				click.hitNanos = now;
				click.hitTarget = target;
				return;
			}
		}
	}

	void onFrame(Minecraft client) {
		LocalPlayer player = client.player;
		if (player == null) return;
		long now = System.nanoTime();

		ItemStack held = player.getItemInHand(InteractionHand.MAIN_HAND);
		int slot = player.getInventory().getSelectedSlot();
		if (slot == lastSlot && !ItemStack.matches(held, lastHeld) && !onlyAmmoChanged(lastHeld, held)) {
			String change = describeChange(lastHeld, held);
			for (Click click : pending) {
				if (click.itemChange == null && now - click.nanos <= ITEM_CHANGE_NANOS) {
					click.itemChange = String.format("%s %dms after the click", change, (now - click.nanos) / 1_000_000L);
				}
			}
		}
		lastHeld = held.copy();
		lastSlot = slot;

		Iterator<Click> it = pending.iterator();
		while (it.hasNext()) {
			Click click = it.next();
			if (now - click.nanos < RESOLVE_NANOS) break;
			it.remove();
			report(client, click);
		}
	}

	/**
	 * With LOG_ALL_SWINGS on, every click goes to the dev log - fresh swing or
	 * not, hit or not. Chat only ever gets the case being chased: a hit that
	 * registered without a fresh swing. With it off, only that case is logged.
	 */
	private void report(Minecraft client, Click click) {
		boolean noFreshSwing = click.problem != null || click.itemChange != null;
		boolean hit = click.hitNanos >= 0;
		if (!LOG_ALL_SWINGS && !(hit && noFreshSwing)) return;
		List<String> why = new ArrayList<>();
		if (click.problem != null) why.add(click.problem);
		if (click.itemChange != null) why.add("server changed the held item (" + click.itemChange + "), its re-equip animation can hide the swing");
		String hitText = hit
				? String.format("hit %s %dms later", click.hitTarget, (click.hitNanos - click.nanos) / 1_000_000L)
				: "no hit registered";
		String line = (noFreshSwing ? "Click with no fresh swing | " : "Click with fresh swing | ") + hitText
				+ (click.attackSent ? " | " + click.serverResponse() : "")
				+ (why.isEmpty() ? "" : " | " + String.join(" + ", why)) + " | " + click.details;
		log.openWindow(client);
		log.add("SWING  " + line);
		if (hit && noFreshSwing) DevLogger.chat(line, ChatFormatting.GOLD);
	}

	/**
	 * For a click on a player: how far their hitbox was from your eyes, your
	 * ping, and how both of you were moving - for "ghost swings", attacks that
	 * were sent but didn't count. Fast flyers can be somewhere else on the
	 * server by the time the attack arrives (about a ping later).
	 */
	private static String reach(Minecraft client, LocalPlayer player) {
		if (!(client.hitResult instanceof EntityHitResult hit) || !(hit.getEntity() instanceof Player target)) return "";
		Vec3 eye = player.getEyePosition();
		AABB box = target.getBoundingBox();
		double reach = eye.distanceTo(new Vec3(
				Mth.clamp(eye.x, box.minX, box.maxX), Mth.clamp(eye.y, box.minY, box.maxY), Mth.clamp(eye.z, box.minZ, box.maxZ)));
		// Other players' velocity isn't sent to the client; their movement since last tick is.
		double targetSpeed = new Vec3(target.getX() - target.xo, target.getY() - target.yo, target.getZ() - target.zo).length() * 20;
		return String.format(" | reach %.2f blocks, ping %dms, you: %s %.1f b/s, target: %s %.1f b/s",
				reach, ShotTracker.ping(client), movement(player), player.getDeltaMovement().length() * 20, movement(target), targetSpeed);
	}

	private static String movement(Player player) {
		if (player.isFallFlying()) return "gliding";
		if (player.getAbilities().flying) return "flying";
		return player.onGround() ? "on ground" : "in the air";
	}

	private static String target(Minecraft client) {
		HitResult hit = client.hitResult;
		if (hit == null || hit.getType() == HitResult.Type.MISS) return "air";
		if (hit instanceof EntityHitResult entityHit) {
			Entity entity = entityHit.getEntity();
			return entity.getName().getString();
		}
		return "a block";
	}

	/** Haste and mining fatigue change how long a swing takes. */
	private static String effects(LocalPlayer player) {
		StringBuilder out = new StringBuilder();
		MobEffectInstance haste = player.getEffect(MobEffects.HASTE);
		MobEffectInstance fatigue = player.getEffect(MobEffects.MINING_FATIGUE);
		if (haste != null) out.append(" (haste ").append(haste.getAmplifier() + 1).append(")");
		if (fatigue != null) out.append(" (mining fatigue ").append(fatigue.getAmplifier() + 1).append(")");
		return out.toString();
	}

	private static String describeChange(ItemStack before, ItemStack after) {
		if (!ItemStack.isSameItem(before, after)) {
			return String.format("item swapped \"%s\" -> \"%s\"", before.getHoverName().getString(), after.getHoverName().getString());
		}
		if (before.getCount() != after.getCount()) return String.format("count %d -> %d", before.getCount(), after.getCount());
		if (before.getDamageValue() != after.getDamageValue()) return String.format("durability damage %d -> %d", before.getDamageValue(), after.getDamageValue());
		if (!Objects.equals(before.getHoverName().getString(), after.getHoverName().getString())) {
			return String.format("name \"%s\" -> \"%s\"", before.getHoverName().getString(), after.getHoverName().getString());
		}
		return "item data changed: " + changedComponents(before, after);
	}

	/** Which of the item's components differ, e.g. "minecraft:lore, minecraft:custom_data". */
	private static String changedComponents(ItemStack before, ItemStack after) {
		Set<DataComponentType<?>> types = new LinkedHashSet<>(before.getComponents().keySet());
		types.addAll(after.getComponents().keySet());
		List<String> changed = new ArrayList<>();
		for (DataComponentType<?> type : types) {
			if (!Objects.equals(before.get(type), after.get(type))) changed.add(String.valueOf(BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type)));
		}
		return changed.isEmpty() ? "unknown" : String.join(", ", changed);
	}

	/** A gun's name changing only in its ammo readout ("«14/576970»" -> "«13/...»") - normal, not worth flagging. */
	private static boolean onlyAmmoChanged(ItemStack before, ItemStack after) {
		String a = before.getHoverName().getString(), b = after.getHoverName().getString();
		return ItemStack.isSameItem(before, after) && before.getCount() == after.getCount()
				&& ShotTracker.parseAmmo(a) != null && ShotTracker.parseAmmo(b) != null
				&& ShotTracker.gunName(a).equals(ShotTracker.gunName(b));
	}
}
