package com.example.gtmaddons;

import com.example.gtmaddons.gun.DevFilter;
import com.example.gtmaddons.gun.DevLogger;
import com.example.gtmaddons.gun.ShotTracker;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

/**
 * Nets almost always mean a kill, so a player caught in a Net Launcher net often gives up: nobody kills, and the two just
 * restart inside the same combat tag, which would make the fight look far longer than it was. When a net lands on a wingsuit
 * player (you netted them, or you were netted) and the netted player deals no damage during the 25 ticks of the web, the
 * fight is cut at the end of the web: a KILL for the netter and a DEATH for the netted player, marked net_ended. The next
 * exchange is then a new fight.
 *
 * Exceptions: if the netted player does go for damage, nothing is cut. If the netter had under 2.5 hearts when the net
 * landed and neither player dealt damage, the fight is dropped with no winner.
 *
 * The net's own damage (a 0.5 hp tick on the netted player, see ShotTracker.onDamage) is not "dealing damage": it is skipped
 * when you netted (the shot already took it) and when you were netted (damage on you around the moment the web appears).
 * You can only tell you were netted by a cobweb at your feet or head just after a player damaged you while you wear a wingsuit.
 */
public final class NetFightEnd {

	public static final NetFightEnd INSTANCE = new NetFightEnd();

	/** The web holds for 25 ticks; a little extra for latency. */
	private static final long WINDOW_MILLIS = 25 * 50L + 150L;
	/** Damage on you within this long of the web appearing is the net's own tick. */
	private static final long NET_TICK_MILLIS = 300L;
	/** A cobweb counts as a net only with a player's damage on you this recent. */
	private static final long DAMAGE_THEN_WEB_MILLIS = 1000L;
	/** Under 2.5 hearts. */
	private static final float LOW_HEALTH = 5.0f;

	private boolean active = false;
	private boolean youWereNetted = false;
	private long startMillis = 0L;
	private String other = null;
	/** The netter's health when the net landed; NaN when unknown. */
	private float netterHealth = Float.NaN;
	private boolean netterDamaged = false, nettedDamaged = false;

	/** A net of yours that landed while no fight was on yet (the tag starts a moment after the hit): taken up when the fight starts. */
	private String waitingTarget = null;
	private long waitingMillis = 0L;
	private float waitingHealth = Float.NaN;

	private long lastPlayerDamageMillis = 0L;
	private String lastPlayerDamageName = null;

	private NetFightEnd() {}

	/** You landed a Net Launcher hit on a wingsuit player (ShotTracker, a moment after the shot). */
	public void onNetHit(String target, long shotMillis) {
		if (active) return;
		ClientPlayerEntity player = MinecraftClient.getInstance().player;
		if (player == null) return;
		if (!FightTracker.INSTANCE.inFight()) {
			waitingTarget = target;
			waitingMillis = shotMillis;
			waitingHealth = player.getHealth();
			return;
		}
		start(false, target, shotMillis, player.getHealth());
	}

	/** Damage to an entity, with whoever caused it (from the damage packet). */
	public void onDamage(Entity target, int causeId) {
		MinecraftClient client = MinecraftClient.getInstance();
		ClientPlayerEntity me = client.player;
		if (me == null || target == null) return;
		long now = System.currentTimeMillis();
		if (target.getId() == me.getId()) {
			Entity cause = causeId >= 0 && client.world != null ? client.world.getEntityById(causeId) : null;
			if (!(cause instanceof PlayerEntity) || cause.getId() == me.getId()) return;
			if (active && youWereNetted && now - startMillis <= NET_TICK_MILLIS) return; // the net's own tick
			lastPlayerDamageMillis = now;
			lastPlayerDamageName = cause.getName().getString();
			if (active) {
				if (youWereNetted) netterDamaged = true;
				else nettedDamaged = true;
			}
		} else if (active && causeId == me.getId()) {
			if (youWereNetted) nettedDamaged = true;
			else netterDamaged = true;
		}
	}

	public void onFrame(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (player == null || client.world == null || !FightTracker.INSTANCE.inFight()) {
			active = false;
			return;
		}
		long now = System.currentTimeMillis();
		if (!active && waitingTarget != null) {
			if (now - waitingMillis < WINDOW_MILLIS) start(false, waitingTarget, waitingMillis, waitingHealth);
			waitingTarget = null;
		}
		if (!active) {
			if (lastPlayerDamageName != null && now - lastPlayerDamageMillis <= DAMAGE_THEN_WEB_MILLIS
					&& ShotTracker.isOnWingsuit(player) && inWeb(client, player)) {
				float health = Float.NaN;
				for (PlayerEntity p : client.world.getPlayers()) {
					if (p.getName().getString().equalsIgnoreCase(lastPlayerDamageName)) health = p.getHealth();
				}
				start(true, lastPlayerDamageName, now, health);
				lastPlayerDamageName = null;
			}
			return;
		}
		if (now - startMillis < WINDOW_MILLIS) return;
		active = false;
		if (nettedDamaged) {
			log("net was answered with damage - the fight goes on");
		} else if (!netterDamaged && netterHealth < LOW_HEALTH) {
			log(String.format("netter at %.1f hearts and nobody dealt damage - fight dropped, no winner", netterHealth / 2.0f));
			FightTracker.INSTANCE.dropAtNet("net with the netter under 2.5 hearts and no damage");
		} else {
			log("netted player did not go for damage - fight ended at the net");
			FightTracker.INSTANCE.endAtNet(youWereNetted ? "DEATH" : "KILL", other);
		}
	}

	private void start(boolean netted, String otherName, long millis, float netterHp) {
		active = true;
		youWereNetted = netted;
		other = otherName;
		startMillis = millis;
		netterHealth = netterHp;
		netterDamaged = false;
		nettedDamaged = false;
		log(String.format("net %s %s (netter health %s)", netted ? "from" : "on", otherName, Float.isNaN(netterHp) ? "unknown" : String.format("%.1f hearts", netterHp / 2.0f)));
	}

	private static boolean inWeb(MinecraftClient client, ClientPlayerEntity player) {
		BlockPos feet = player.getBlockPos();
		return client.world.getBlockState(feet).isOf(Blocks.COBWEB) || client.world.getBlockState(feet.up()).isOf(Blocks.COBWEB);
	}

	private static void log(String message) {
		if (DevLogger.INSTANCE.wants(DevFilter.FIGHTS)) DevLogger.chat("[Net] " + message, Formatting.GOLD);
	}
}
