package com.example.gtmaddons.mixin;

import com.example.gtmaddons.InventoryTransparency;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Draws the survival inventory's background texture see-through for the "Inventory transparency" setting (InventoryTransparency). */
@Mixin(InventoryScreen.class)
public abstract class InventoryTransparencyMixin {

	@WrapOperation(method = "renderBg", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphics;blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIII)V"), require = 0)
	private void gtmaddons$clearPanel(GuiGraphics context, RenderPipeline pipeline, Identifier texture, int x, int y, float u, float v,
			int width, int height, int textureWidth, int textureHeight, Operation<Void> original) {
		float amount = InventoryTransparency.amount();
		if (amount <= 0f) original.call(context, pipeline, texture, x, y, u, v, width, height, textureWidth, textureHeight);
		else if (amount < 1f) context.blit(pipeline, texture, x, y, u, v, width, height, textureWidth, textureHeight, InventoryTransparency.tint());
	}
}
