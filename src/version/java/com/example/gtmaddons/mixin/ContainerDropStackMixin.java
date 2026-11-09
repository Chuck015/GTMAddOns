package com.example.gtmaddons.mixin;

import com.example.gtmaddons.ControlKeys;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Control + drop over a slot in an inventory throws the whole stack, as in vanilla. On some Fabric setups the key's Control modifier
 * arrives empty, so only one item was thrown; and changing just that check inside the game's keyPressed did not take on every setup.
 * So the whole case is handled here first: drop key + Control (from the event or read straight from the keyboard, ControlKeys) over a
 * slot with an item sends the same "throw whole stack" click vanilla sends, and the key is used up. Without Control nothing changes.
 * DropStackMixin does the same for the item in your hand outside inventories.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class ContainerDropStackMixin {

	@Shadow
	protected Slot hoveredSlot;

	@Shadow
	protected abstract void slotClicked(Slot slot, int slotId, int button, ClickType clickType);

	@Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true, require = 0)
	private void gtmaddons$dropWholeStack(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
		Slot slot = hoveredSlot;
		if (slot == null || !slot.hasItem()) return;
		if (!Minecraft.getInstance().options.keyDrop.matches(event)) return;
		if (!event.hasControlDown() && !ControlKeys.down()) return;
		// Button 1 with THROW = the whole stack (button 0 = one item).
		slotClicked(slot, slot.index, 1, ClickType.THROW);
		cir.setReturnValue(true);
	}
}
