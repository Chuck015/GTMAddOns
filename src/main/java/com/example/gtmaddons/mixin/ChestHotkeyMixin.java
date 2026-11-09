package com.example.gtmaddons.mixin;

import com.example.gtmaddons.ChestHotkeys;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The Wing mod "Armor hotkey lock" (ChestHotkeys): in your survival inventory, a hotbar key or the offhand swap key (on the keyboard or
 * a mouse button) does nothing while
 * the cursor is on your helmet, leggings or boots slot, so swapping your chestplate can't swap other armor by a wrong hover. Hotkeys
 * on the chestplate and every other slot, and all clicking (left, right, shift, dragging), work as normal.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class ChestHotkeyMixin {

	/** Your inventory's armor slots: boots 36, leggings 37, chestplate 38 (left alone), helmet 39. */
	@Unique
	private static final int BOOTS = 36, LEGGINGS = 37, HELMET = 39;

	@Shadow
	protected Slot hoveredSlot;

	@Inject(method = "checkHotbarKeyPressed", at = @At("HEAD"), cancellable = true, require = 0)
	private void gtmaddons$lockArmorHotkeys(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
		// A swap key over helmet, leggings or boots: used up, nothing swapped.
		if (gtmaddons$overLockedArmor() && gtmaddons$isSwapKey(event)) cir.setReturnValue(true);
	}

	/** The same for a hotbar or offhand swap bound to a mouse button (those come in as clicks, not key presses). */
	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true, require = 0)
	private void gtmaddons$lockArmorMouseHotkeys(MouseButtonEvent event, boolean doubled, CallbackInfoReturnable<Boolean> cir) {
		if (gtmaddons$overLockedArmor() && gtmaddons$isSwapButton(event)) cir.setReturnValue(true);
	}

	@Unique
	private boolean gtmaddons$overLockedArmor() {
		if (!ChestHotkeys.on() || !((Object) this instanceof InventoryScreen)) return false;
		Slot slot = hoveredSlot;
		if (slot == null || !(slot.container instanceof Inventory)) return false;
		int index = slot.getContainerSlot();
		return index == BOOTS || index == LEGGINGS || index == HELMET;
	}

	@Unique
	private static boolean gtmaddons$isSwapButton(MouseButtonEvent event) {
		var options = Minecraft.getInstance().options;
		if (options.keySwapOffhand.matchesMouse(event)) return true;
		for (KeyMapping hotbar : options.keyHotbarSlots) {
			if (hotbar.matchesMouse(event)) return true;
		}
		return false;
	}

	@Unique
	private static boolean gtmaddons$isSwapKey(KeyEvent event) {
		var options = Minecraft.getInstance().options;
		if (options.keySwapOffhand.matches(event)) return true;
		for (KeyMapping hotbar : options.keyHotbarSlots) {
			if (hotbar.matches(event)) return true;
		}
		return false;
	}
}
