package com.example.gtmaddons.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** How many ticks a hand swing lasts (item's swing animation, haste, mining fatigue), for dev mode. */
@Mixin(LivingEntity.class)
public interface LivingEntityInvoker {

	@Invoker("getCurrentSwingDuration")
	int gtmaddons$getHandSwingDuration();
}
