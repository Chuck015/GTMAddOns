package com.example.gtmaddons.mixin;

import com.example.gtmaddons.ControlKeys;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Control + drop throws the whole stack, as in vanilla. Some Fabric setups lose the Control part on the way (the game asks the key's
 * modifiers, which arrive empty), so the held item drops one at a time; here the Control keys are also read straight from the
 * keyboard (ControlKeys). ContainerDropStackMixin does the same inside inventories.
 */
@Mixin(LocalPlayer.class)
public abstract class DropStackMixin {

	@ModifyVariable(method = "drop(Z)Z", at = @At("HEAD"), argsOnly = true, require = 0)
	private boolean gtmaddons$wholeStack(boolean fullStack) {
		return fullStack || ControlKeys.down();
	}
}
