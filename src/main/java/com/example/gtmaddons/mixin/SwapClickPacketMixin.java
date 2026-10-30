package com.example.gtmaddons.mixin;

import com.example.gtmaddons.SwapInputs;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Counts the slot-click packets the client sends while a swap's inventory is open (SwapInputs; information only). */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class SwapClickPacketMixin {

	@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), require = 0)
	private void gtmaddons$countClicks(Packet<?> packet, CallbackInfo ci) {
		if (packet instanceof ServerboundContainerClickPacket) SwapInputs.onClickPacket();
	}
}
