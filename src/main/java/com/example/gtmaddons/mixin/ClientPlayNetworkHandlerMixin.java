package com.example.gtmaddons.mixin;

import com.example.gtmaddons.CombatTracker;
import com.example.gtmaddons.FightTracker;
import com.example.gtmaddons.HitSounds;
import com.example.gtmaddons.JetpackParticles;
import com.example.gtmaddons.ComboTracker;
import com.example.gtmaddons.LatencyTester;
import com.example.gtmaddons.gun.DevLogger;
import com.example.gtmaddons.gun.ShotTracker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.CooldownUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.network.packet.s2c.play.ParticleS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundFromEntityS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.Vec3d;
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
@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {

	@Unique
	private static boolean gtmaddons$onGameThread() {
		return MinecraftClient.getInstance().isOnThread();
	}

	@Unique
	private static String gtmaddons$soundId(RegistryEntry<SoundEvent> sound) {
		return sound.value().id().toString();
	}

	@Inject(method = "onEntityDamage", at = @At("HEAD"))
	private void gtmaddons$onEntityDamage(EntityDamageS2CPacket packet, CallbackInfo ci) {
		if (!gtmaddons$onGameThread()) return;
		MinecraftClient client = MinecraftClient.getInstance();
		CombatTracker.INSTANCE.onDamage(packet.entityId(), packet.sourceCauseId());
		ComboTracker.INSTANCE.onDamage(packet.entityId(), packet.sourceCauseId());
		HitSounds.INSTANCE.onDamage(client.world != null ? client.world.getEntityById(packet.entityId()) : null, packet.sourceCauseId());
		ShotTracker.INSTANCE.onDamage(client.world != null ? client.world.getEntityById(packet.entityId()) : null,
				packet.sourceCauseId());
		if (DevLogger.INSTANCE.isEnabled()) {
			String type = packet.sourceType().getKey().map(key -> key.getValue().toString()).orElse("?");
			DevLogger.INSTANCE.onEntityDamage(packet.entityId(), type, packet.sourceCauseId(), packet.sourceDirectId());
		}
	}

	@Inject(method = "onEntityStatus", at = @At("HEAD"))
	private void gtmaddons$onEntityStatus(EntityStatusS2CPacket packet, CallbackInfo ci) {
		if (!gtmaddons$onGameThread() || !DevLogger.INSTANCE.isEnabled()) return;
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world != null) {
			DevLogger.INSTANCE.onEntityStatus(packet.getEntity(client.world), packet.getStatus());
		}
	}

	@Inject(method = "onPlaySound", at = @At("HEAD"))
	private void gtmaddons$onPlaySound(PlaySoundS2CPacket packet, CallbackInfo ci) {
		if (!gtmaddons$onGameThread()) return;
		MinecraftClient client = MinecraftClient.getInstance();
		String id = gtmaddons$soundId(packet.getSound());
		Vec3d position = new Vec3d(packet.getX(), packet.getY(), packet.getZ());
		if (client.player != null) {
			double distance = client.player.getEyePos().distanceTo(position);
			ShotTracker.INSTANCE.onSound(id, distance);
			LatencyTester.INSTANCE.onSound(id, distance);
		}
		if (DevLogger.INSTANCE.isEnabled()) {
			DevLogger.INSTANCE.onSound(id, packet.getCategory().getName(), position, packet.getVolume(), packet.getPitch());
		}
	}

	@Inject(method = "onPlaySoundFromEntity", at = @At("HEAD"))
	private void gtmaddons$onPlaySoundFromEntity(PlaySoundFromEntityS2CPacket packet, CallbackInfo ci) {
		if (!gtmaddons$onGameThread()) return;
		MinecraftClient client = MinecraftClient.getInstance();
		String id = gtmaddons$soundId(packet.getSound());
		if (client.player != null && packet.getEntityId() == client.player.getId()) {
			ShotTracker.INSTANCE.onSound(id, 0.0);
			LatencyTester.INSTANCE.onSound(id, 0.0);
		}
		if (DevLogger.INSTANCE.isEnabled()) {
			DevLogger.INSTANCE.onEntitySound(id, packet.getCategory().getName(),
					packet.getEntityId(), packet.getVolume(), packet.getPitch());
		}
	}

	@Inject(method = "onParticle", at = @At("HEAD"), cancellable = true)
	private void gtmaddons$onParticle(ParticleS2CPacket packet, CallbackInfo ci) {
		if (!gtmaddons$onGameThread()) return;
		boolean hide = JetpackParticles.shouldHide(packet);
		if (DevLogger.INSTANCE.isEnabled()) {
			String id = String.valueOf(Registries.PARTICLE_TYPE.getId(packet.getParameters().getType()));
			DevLogger.INSTANCE.onParticle(hide ? id + " (hidden)" : id, packet.getCount());
		}
		if (hide) ci.cancel();
	}

	@Inject(method = "onCooldownUpdate", at = @At("HEAD"))
	private void gtmaddons$onCooldownUpdate(CooldownUpdateS2CPacket packet, CallbackInfo ci) {
		if (!gtmaddons$onGameThread()) return;
		ComboTracker.INSTANCE.onCooldown(packet.cooldownGroup(), packet.cooldown());
		if (DevLogger.INSTANCE.isEnabled()) DevLogger.INSTANCE.onCooldown(packet.cooldownGroup().toString(), packet.cooldown());
	}

	@Inject(method = "onTitle", at = @At("HEAD"))
	private void gtmaddons$onTitle(TitleS2CPacket packet, CallbackInfo ci) {
		if (!gtmaddons$onGameThread()) return;
		// "WASTED" is GTM's death screen: ends the fight and the combat tag.
		FightTracker.INSTANCE.onTitle(packet.text());
		CombatTracker.INSTANCE.onTitle(packet.text());
		if (DevLogger.INSTANCE.isEnabled()) DevLogger.INSTANCE.onTitle("TITLE", packet.text());
	}

	@Inject(method = "onSubtitle", at = @At("HEAD"))
	private void gtmaddons$onSubtitle(SubtitleS2CPacket packet, CallbackInfo ci) {
		if (!gtmaddons$onGameThread()) return;
		ShotTracker.INSTANCE.onSubtitle(packet.text());
		HitSounds.INSTANCE.onSubtitle(packet.text());
		FightTracker.INSTANCE.onSubtitle(packet.text());
		if (DevLogger.INSTANCE.isEnabled()) DevLogger.INSTANCE.onTitle("SUBTITLE", packet.text());
	}
}
