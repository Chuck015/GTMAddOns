package com.example.gtmaddons.mixin;

import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets PlayerFishHeadMixin add a layer to a renderer (addLayer is protected). */
@Mixin(LivingEntityRenderer.class)
public interface LivingEntityRendererInvoker {

	@Invoker("addLayer")
	boolean gtmaddons$addLayer(RenderLayer layer);
}
