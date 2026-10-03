package com.example.gtmaddons.mixin;

import com.example.gtmaddons.ModUsers;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Adds the GTMAddOns icon after the name of players who run the mod, in the tab list (see ModUsers). */
@Mixin(PlayerListHud.class)
public abstract class PlayerListHudMixin {

	@ModifyReturnValue(method = "getPlayerName", at = @At("RETURN"), require = 0)
	private Text gtmaddons$icon(Text original, @Local(argsOnly = true) PlayerListEntry entry) {
		return ModUsers.decorate(original, entry.getProfile().id());
	}
}
