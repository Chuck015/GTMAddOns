package com.example.gtmaddons.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The key a binding is set to, for telling attack and use clicks apart (ClickTracker). */
@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {

	@Accessor("key")
	InputConstants.Key gtmaddons$key();
}
