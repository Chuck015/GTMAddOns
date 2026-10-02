package com.example.gtmaddons.gun;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.EntityHitResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Dev mode, "Melee damage": for finding why a katana or other melee item only does about one
 * heart. It shows the three places the damage can go wrong, side by side:
 *
 *   ITEM    - when you start holding a melee item: what the item itself says it adds
 *             (attribute modifiers on the item), what your attack damage attribute really is,
 *             and the modifiers on that attribute. If the item says +7 but the attribute
 *             stayed at 1, the server never applied the weapon's damage.
 *   ATTACK  - when your attack damage attribute changes (the server sends it a moment after a
 *             weapon swap), with what you hold. Shows a late or missing update.
 *   HIT     - every melee hit you land: how much health the target really lost (read about
 *             HIT_RESOLVE_NANOS after the hit, once the server's health update is in), against
 *             what vanilla would deal from your attack damage, your charge at the click, and the
 *             target's armor, toughness and resistance. A hit that did well under what vanilla
 *             would is flagged LOW DAMAGE and goes to chat; every hit goes to the dev log.
 *
 * The expected figure is vanilla's: attack damage x (0.2 + charge^2 x 0.8), then armor (with
 * toughness) and resistance. It ignores enchantments and anything GTM does on its own, so a
 * gap means "something other than vanilla changed this hit", not necessarily a bug by itself.
 */
final class MeleeDamageDebugger {

	/** How long after a hit the target's health is read (the server's health update comes within a tick or two). */
	private static final long HIT_RESOLVE_NANOS = 200_000_000L;
	/** A click's charge is used for a hit that comes within this long after it. */
	private static final long CLICK_MATCH_NANOS = 500_000_000L;
	private static final double LOW_FRACTION = 0.6;
	private static final double NEARBY = 12.0;

	private final DevLogger log;

	private static final class Click {
		final long nanos;
		final float charge;
		final int targetId;
		final float targetHealthBefore;

		Click(long nanos, float charge, int targetId, float targetHealthBefore) {
			this.nanos = nanos;
			this.charge = charge;
			this.targetId = targetId;
			this.targetHealthBefore = targetHealthBefore;
		}
	}

	private static final class Hit {
		final long nanos = System.nanoTime();
		final int targetId;
		final String targetName, weapon, damageType;
		final float healthBefore;
		final double attackDamage, charge, expected, raw, armor, toughness;
		final int resistanceLevel;
		final String attackerState;

		Hit(int targetId, String targetName, String weapon, String damageType, float healthBefore, double attackDamage,
				double charge, double raw, double expected, double armor, double toughness, int resistanceLevel, String attackerState) {
			this.targetId = targetId;
			this.targetName = targetName;
			this.weapon = weapon;
			this.damageType = damageType;
			this.healthBefore = healthBefore;
			this.attackDamage = attackDamage;
			this.charge = charge;
			this.raw = raw;
			this.expected = expected;
			this.armor = armor;
			this.toughness = toughness;
			this.resistanceLevel = resistanceLevel;
			this.attackerState = attackerState;
		}
	}

	private Click lastClick = null;
	private final List<Hit> pending = new ArrayList<>();
	/** Health + absorption of players near you, as of the previous frame (before the server's updates for this tick). */
	private final Map<Integer, Float> healthLastFrame = new HashMap<>();
	private String lastHeldKey = null;
	private double lastAttackDamage = Double.NaN;

	MeleeDamageDebugger(DevLogger log) {
		this.log = log;
	}

	void reset() {
		lastClick = null;
		pending.clear();
		healthLastFrame.clear();
		lastHeldKey = null;
		lastAttackDamage = Double.NaN;
	}

	// ---- Hooks (game thread) ----

	/** A left click, just before the game handles it: the charge at this moment is what the hit will use. */
	void beforeAttack(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (player == null) return;
		int targetId = -1;
		float health = Float.NaN;
		if (client.crosshairTarget instanceof EntityHitResult hit && hit.getEntity() instanceof LivingEntity living) {
			targetId = living.getId();
			health = living.getHealth() + living.getAbsorptionAmount();
		}
		lastClick = new Click(System.nanoTime(), player.getAttackCooldownProgress(0.5f), targetId, health);
	}

	/** A damage event caused by you on another entity. */
	void onHit(MinecraftClient client, int targetId, String damageType) {
		ClientPlayerEntity player = client.player;
		if (player == null || client.world == null) return;
		Entity target = client.world.getEntityById(targetId);
		if (!(target instanceof LivingEntity living)) return;
		ItemStack held = player.getMainHandStack();
		// Gun hits are damage events too; only melee items are looked at here.
		if (!com.example.gtmaddons.ComboTracker.isMeleeItem(held)) return;

		long now = System.nanoTime();
		Click click = lastClick != null && now - lastClick.nanos <= CLICK_MATCH_NANOS ? lastClick : null;
		double charge = click != null ? click.charge : player.getAttackCooldownProgress(0.5f);

		float before = Float.NaN;
		if (click != null && click.targetId == targetId && !Float.isNaN(click.targetHealthBefore)) before = click.targetHealthBefore;
		Float lastFrame = healthLastFrame.get(targetId);
		// The health at the click can be older than the previous hit on the same target; prefer the later reading.
		if (lastFrame != null && (Float.isNaN(before) || pendingFor(targetId) == null)) before = lastFrame;
		if (Float.isNaN(before)) before = living.getHealth() + living.getAbsorptionAmount();

		double attackDamage = player.getAttributeValue(EntityAttributes.ATTACK_DAMAGE);
		double armor = living.getArmor();
		double toughness = living.getAttributeValue(EntityAttributes.ARMOR_TOUGHNESS);
		StatusEffectInstance resistance = living.getStatusEffect(StatusEffects.RESISTANCE);
		int resistanceLevel = resistance != null ? resistance.getAmplifier() + 1 : 0;

		double raw = attackDamage * (0.2 + charge * charge * 0.8);
		double expected = afterArmor(raw, armor, toughness);
		if (resistanceLevel > 0) expected *= Math.max(0.0, 1.0 - 0.2 * resistanceLevel);

		pending.add(new Hit(targetId, living.getName().getString(), held.isEmpty() ? "empty hand" : held.getName().getString(), damageType,
				before, attackDamage, charge, raw, expected, armor, toughness, resistanceLevel, attackerState(player)));
	}

	private Hit pendingFor(int targetId) {
		for (Hit hit : pending) if (hit.targetId == targetId) return hit;
		return null;
	}

	/** Every frame: read finished hits, watch the attack damage attribute, announce a newly held melee item. */
	void onFrame(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (player == null || client.world == null) {
			reset();
			return;
		}
		resolveHits(client);
		watchAttackDamage(player);
		watchHeldItem(client, player);

		// After resolving, so a hit read this frame still compares against the earlier reading.
		healthLastFrame.clear();
		for (PlayerEntity other : client.world.getPlayers()) {
			if (other != player && other.squaredDistanceTo(player) <= NEARBY * NEARBY) {
				healthLastFrame.put(other.getId(), other.getHealth() + other.getAbsorptionAmount());
			}
		}
	}

	// ---- Hits ----

	private void resolveHits(MinecraftClient client) {
		long now = System.nanoTime();
		for (Iterator<Hit> it = pending.iterator(); it.hasNext(); ) {
			Hit hit = it.next();
			if (now - hit.nanos < HIT_RESOLVE_NANOS) continue;
			it.remove();
			Entity target = client.world.getEntityById(hit.targetId);
			boolean gone = !(target instanceof LivingEntity living) || !target.isAlive();
			double after = gone ? 0.0 : ((LivingEntity) target).getHealth() + ((LivingEntity) target).getAbsorptionAmount();
			double dealt = Math.max(0.0, hit.healthBefore - after);
			report(hit, dealt, gone);
		}
	}

	private void report(Hit hit, double dealt, boolean targetGone) {
		boolean low = !targetGone && hit.expected > 0.5 && dealt < hit.expected * LOW_FRACTION;
		String text = String.format(
				"MELEE DAMAGE  \"%s\" on %s: dealt %.1f (%.1f hearts)%s | vanilla would deal %.1f (attack damage %.1f x charge %.0f%% = %.1f, target armor %.0f, toughness %.0f%s) | health %.1f -> %.1f | %s%s",
				hit.weapon, hit.targetName, dealt, dealt / 2.0, targetGone ? " [target died or left]" : "",
				hit.expected, hit.attackDamage, hit.charge * 100, hit.raw, hit.armor, hit.toughness,
				hit.resistanceLevel > 0 ? ", resistance " + hit.resistanceLevel : "",
				hit.healthBefore, hit.healthBefore - dealt, hit.damageType, hit.attackerState);
		log.openWindow(MinecraftClient.getInstance());
		log.add((low ? "LOW DAMAGE  " : "") + text);
		if (low) {
			DevLogger.chat(String.format("Melee damage LOW: %s on %s dealt %.1f (%.1f hearts), vanilla ~%.1f. Attack damage %.1f, charge %.0f%%",
					hit.weapon, hit.targetName, dealt, dealt / 2.0, hit.expected, hit.attackDamage, hit.charge * 100), Formatting.RED);
		}
	}

	/** Vanilla armor reduction (with toughness). */
	private static double afterArmor(double damage, double armor, double toughness) {
		double f = 2.0 + toughness / 4.0;
		double g = Math.min(20.0, Math.max(armor * 0.2, armor - damage / f));
		return damage * (1.0 - g / 25.0);
	}

	private static String attackerState(ClientPlayerEntity player) {
		StringBuilder state = new StringBuilder("you:");
		state.append(player.isSprinting() ? " sprinting" : "").append(player.isOnGround() ? " on-ground" : " airborne");
		if (player.isGliding()) state.append(" gliding");
		for (StatusEffectInstance effect : player.getStatusEffects()) {
			if (effect.getEffectType().matches(StatusEffects.WEAKNESS) || effect.getEffectType().matches(StatusEffects.STRENGTH)) {
				state.append(' ').append(effect.getEffectType().getIdAsString()).append(' ').append(effect.getAmplifier() + 1);
			}
		}
		return state.toString();
	}

	// ---- The attack damage attribute and the held item ----

	/** Logs a change of your attack damage attribute: the server sends it after a weapon swap, so a late or missing one shows here. */
	private void watchAttackDamage(ClientPlayerEntity player) {
		double value = player.getAttributeValue(EntityAttributes.ATTACK_DAMAGE);
		if (!Double.isNaN(lastAttackDamage) && Math.abs(value - lastAttackDamage) > 0.001) {
			ItemStack held = player.getMainHandStack();
			log.openWindow(MinecraftClient.getInstance());
			log.add(String.format("ATTACK DAMAGE  attribute %.2f -> %.2f while holding \"%s\"  %s",
					lastAttackDamage, value, held.isEmpty() ? "empty hand" : held.getName().getString(), attributeBreakdown(player)));
		}
		lastAttackDamage = value;
	}

	/** When you start holding a different item: what it says it adds, against what your attribute really is. */
	private void watchHeldItem(MinecraftClient client, ClientPlayerEntity player) {
		ItemStack held = player.getMainHandStack();
		String key = held.isEmpty() ? "" : Registries.ITEM.getId(held.getItem()) + "|" + ShotTracker.gunName(held.getName().getString());
		if (key.equals(lastHeldKey)) return;
		lastHeldKey = key;
		if (!com.example.gtmaddons.ComboTracker.isMeleeItem(held)) return;

		double baseFist = 1.0;
		double fromItem = 0.0;
		StringBuilder mods = new StringBuilder();
		AttributeModifiersComponent component = held.get(DataComponentTypes.ATTRIBUTE_MODIFIERS);
		if (component != null) {
			for (AttributeModifiersComponent.Entry entry : component.modifiers()) {
				EntityAttributeModifier modifier = entry.modifier();
				String attribute = entry.attribute().getIdAsString();
				mods.append(String.format("%s %s %+.2f (%s); ", attribute, modifier.operation(), modifier.value(), entry.slot()));
				if (entry.attribute().matches(EntityAttributes.ATTACK_DAMAGE) && modifier.operation() == EntityAttributeModifier.Operation.ADD_VALUE) {
					fromItem += modifier.value();
				}
			}
		}
		double actual = player.getAttributeValue(EntityAttributes.ATTACK_DAMAGE);
		boolean mismatch = Math.abs((baseFist + fromItem) - actual) > 0.05;
		log.openWindow(client);
		log.add(String.format("MELEE ITEM  \"%s\" (%s): item modifiers [%s] -> expected attack damage %.2f; your attribute is %.2f%s  %s",
				held.getName().getString(), Registries.ITEM.getId(held.getItem()), mods.length() == 0 ? "none on the item" : mods.toString().trim(),
				baseFist + fromItem, actual, mismatch ? "  MISMATCH (re-check in a moment: the server may still be sending it)" : "", attributeBreakdown(player)));
	}

	/** The attack damage attribute's base value and every modifier on it. */
	private static String attributeBreakdown(ClientPlayerEntity player) {
		EntityAttributeInstance instance = player.getAttributeInstance(EntityAttributes.ATTACK_DAMAGE);
		if (instance == null) return "(no attack damage attribute)";
		StringBuilder out = new StringBuilder(String.format("[base %.2f", instance.getBaseValue()));
		for (EntityAttributeModifier modifier : instance.getModifiers()) {
			out.append(String.format("; %s %s %+.2f", modifier.id(), modifier.operation(), modifier.value()));
		}
		return out.append(']').toString();
	}
}
