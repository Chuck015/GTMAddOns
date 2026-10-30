package com.example.gtmaddons.mixin;

import com.example.gtmaddons.ModUsers;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Adds the GTMAddOns icon after the name on the nametag of players who run the mod (see ModUsers). */
@Mixin(EntityRenderer.class)
public abstract class NameTagMixin {

	@ModifyReturnValue(method = "getNameTag", at = @At("RETURN"), require = 0)
	private Component gtmaddons$icon(Component original, @Local(argsOnly = true) Entity entity) {
		return entity instanceof Player ? ModUsers.decorate(original, entity.getUUID()) : original;
	}
}
