package com.example.gtmaddons.mixin;

import com.example.gtmaddons.ModUsers;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Adds the GTMAddOns icon after the name of players who run the mod, in the tab list (see ModUsers). */
@Mixin(PlayerTabOverlay.class)
public abstract class PlayerListHudMixin {

	@ModifyReturnValue(method = "getNameForDisplay", at = @At("RETURN"), require = 0)
	private Component gtmaddons$icon(Component original, @Local(argsOnly = true) PlayerInfo entry) {
		return ModUsers.decorate(original, entry.getProfile().id());
	}
}
