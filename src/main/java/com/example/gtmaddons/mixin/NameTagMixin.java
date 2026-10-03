package com.example.gtmaddons.mixin;

import com.example.gtmaddons.ModUsers;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Adds the GTMAddOns icon after the name on the nametag of players who run the mod (see ModUsers). */
@Mixin(EntityRenderer.class)
public abstract class NameTagMixin {

	@ModifyReturnValue(method = "getDisplayName", at = @At("RETURN"), require = 0)
	private Text gtmaddons$icon(Text original, @Local(argsOnly = true) Entity entity) {
		return entity instanceof PlayerEntity ? ModUsers.decorate(original, entity.getUuid()) : original;
	}
}
