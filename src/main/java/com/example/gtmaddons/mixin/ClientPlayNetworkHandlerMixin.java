package com.example.gtmaddons.mixin;

import com.example.gtmaddons.CombatTracker;
import com.example.gtmaddons.ComboTracker;
import com.example.gtmaddons.FightTracker;
import com.example.gtmaddons.HitSounds;
import com.example.gtmaddons.JetpackParticles;
import com.example.gtmaddons.LatencyTester;
import com.example.gtmaddons.NetFightEnd;
import com.example.gtmaddons.gun.DevLogger;
import com.example.gtmaddons.gun.ShotTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundCooldownPacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds packets that signal gun hits and combat into CombatTracker and
 * ShotTracker (always), and DevLogger (when dev mode is on).
 *
 * These handlers first run on the network thread, which re-queues them
 * onto the game thread and bails out; only the game-thread run is used,
 * so each packet is seen once and the world is safe to read.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPlayNetworkHandlerMixin {

	@Unique
	private static boolean gtmaddons$onGameThread() {
		return Minecraft.getInstance().isSameThread();
	}

	@Unique
	private static String gtmaddons$soundId(Holder<SoundEvent> sound) {
		return sound.value().location().toString();
	}

	@Inject(method = "handleDamageEvent", at = @At("HEAD"))
	private void gtmaddons$onEntityDamage(ClientboundDamageEventPacket packet, CallbackInfo ci) {
		if (!gtmaddons$onGameThread()) return;
		Minecraft client = Minecraft.getInstance();
		CombatTracker.INSTANCE.onDamage(packet.entityId(), packet.sourceCauseId());
		if (client.player != null && packet.entityId() == client.player.getId()) com.example.gtmaddons.GunSounds.INSTANCE.onOwnDamage();
		ComboTracker.INSTANCE.onDamage(packet.entityId(), packet.sourceCauseId());
		NetFightEnd.INSTANCE.onDamage(client.level != null ? client.level.getEntity(packet.entityId()) : null, packet.sourceCauseId());
		HitSounds.INSTANCE.onDamage(client.level != null ? client.level.getEntity(packet.entityId()) : null, packet.sourceCauseId());
		ShotTracker.INSTANCE.onDamage(client.level != null ? client.level.getEntity(packet.entityId()) : null,
				packet.sourceCauseId());
		if (DevLogger.INSTANCE.isEnabled()) {
			String type = packet.sourceType().unwrapKey().map(key -> key.identifier().toString()).orElse("?");
			DevLogger.INSTANCE.onEntityDamage(packet.entityId(), type, packet.sourceCauseId(), packet.sourceDirectId());
		}
	}

	@Inject(method = "handleEntityEvent", at = @At("HEAD"))
	private void gtmaddons$onEntityStatus(ClientboundEntityEventPacket packet, CallbackInfo ci) {
		if (!gtmaddons$onGameThread() || !DevLogger.INSTANCE.isEnabled()) return;
		Minecraft client = Minecraft.getInstance();
		if (client.level != null) {
			DevLogger.INSTANCE.onEntityStatus(packet.getEntity(client.level), packet.getEventId());
		}
	}

	@Inject(method = "handleSoundEvent", at = @At("HEAD"))
	private void gtmaddons$onPlaySound(ClientboundSoundPacket packet, CallbackInfo ci) {
		if (!gtmaddons$onGameThread()) return;
		Minecraft client = Minecraft.getInstance();
		String id = gtmaddons$soundId(packet.getSound());
		Vec3 position = new Vec3(packet.getX(), packet.getY(), packet.getZ());
		if (client.player != null) {
			double distance = client.player.getEyePosition().distanceTo(position);
			ShotTracker.INSTANCE.onSound(id, distance);
			LatencyTester.INSTANCE.onSound(id, distance);
		}
		if (DevLogger.INSTANCE.isEnabled()) {
			DevLogger.INSTANCE.onSound(id, packet.getSource().getName(), position, packet.getVolume(), packet.getPitch());
		}
	}

	@Inject(method = "handleSoundEntityEvent", at = @At("HEAD"))
	private void gtmaddons$onPlaySoundFromEntity(ClientboundSoundEntityPacket packet, CallbackInfo ci) {
		if (!gtmaddons$onGameThread()) return;
		Minecraft client = Minecraft.getInstance();
		String id = gtmaddons$soundId(packet.getSound());
		if (client.player != null && packet.getId() == client.player.getId()) {
			ShotTracker.INSTANCE.onSound(id, 0.0);
			LatencyTester.INSTANCE.onSound(id, 0.0);
		}
		if (DevLogger.INSTANCE.isEnabled()) {
			DevLogger.INSTANCE.onEntitySound(id, packet.getSource().getName(),
					packet.getId(), packet.getVolume(), packet.getPitch());
		}
	}

	@Inject(method = "handleParticleEvent", at = @At("HEAD"), cancellable = true)
	private void gtmaddons$onParticle(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
		if (!gtmaddons$onGameThread()) return;
		boolean hide = JetpackParticles.shouldHide(packet);
		if (DevLogger.INSTANCE.isEnabled()) {
			String id = String.valueOf(BuiltInRegistries.PARTICLE_TYPE.getKey(packet.getParticle().getType()));
			DevLogger.INSTANCE.onParticle(hide ? id + " (hidden)" : id, packet.getCount());
		}
		if (hide) ci.cancel();
	}

	@Inject(method = "handleItemCooldown", at = @At("HEAD"))
	private void gtmaddons$onCooldownUpdate(ClientboundCooldownPacket packet, CallbackInfo ci) {
		if (!gtmaddons$onGameThread()) return;
		ComboTracker.INSTANCE.onCooldown(packet.cooldownGroup(), packet.duration());
		if (DevLogger.INSTANCE.isEnabled()) DevLogger.INSTANCE.onCooldown(packet.cooldownGroup().toString(), packet.duration());
	}

	@Inject(method = "setTitleText", at = @At("HEAD"))
	private void gtmaddons$onTitle(ClientboundSetTitleTextPacket packet, CallbackInfo ci) {
		if (!gtmaddons$onGameThread()) return;
		// "WASTED" is GTM's death screen: ends the fight and the combat tag.
		FightTracker.INSTANCE.onTitle(packet.text());
		CombatTracker.INSTANCE.onTitle(packet.text());
		if (DevLogger.INSTANCE.isEnabled()) DevLogger.INSTANCE.onTitle("TITLE", packet.text());
	}

	@Inject(method = "setSubtitleText", at = @At("HEAD"))
	private void gtmaddons$onSubtitle(ClientboundSetSubtitleTextPacket packet, CallbackInfo ci) {
		if (!gtmaddons$onGameThread()) return;
		ShotTracker.INSTANCE.onSubtitle(packet.text());
		HitSounds.INSTANCE.onSubtitle(packet.text());
		FightTracker.INSTANCE.onSubtitle(packet.text());
		if (DevLogger.INSTANCE.isEnabled()) DevLogger.INSTANCE.onTitle("SUBTITLE", packet.text());
	}
}
