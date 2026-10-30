package com.example.gtmaddons.compat;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;

/** Small game calls with different names per version (the 1.21.11 side of the version layer). */
public final class Mc {
	private Mc() {}

	public static void setScreen(Minecraft client, Screen screen) {
		client.setScreen(screen);
	}

	public static Screen screen(Minecraft client) {
		return client.screen;
	}

	/** A chat line only you see. */
	public static void chat(LocalPlayer player, Component message) {
		player.displayClientMessage(message, false);
	}

	/** A line above the hotbar. */
	public static void actionBar(LocalPlayer player, Component message) {
		player.displayClientMessage(message, true);
	}

	public static int windowWidth(Window window) {
		return window.getScreenWidth();
	}

	public static int windowHeight(Window window) {
		return window.getScreenHeight();
	}

	/** The entity render type with cut-out transparency and no back-face culling. */
	public static RenderType cutoutNoCull(Identifier texture) {
		return RenderTypes.entityCutoutNoCull(texture);
	}

	/** The game's Master volume, 0-1. */
	public static float masterVolume(Minecraft client) {
		return (float) client.options.getFinalSoundSourceVolume(SoundSource.MASTER);
	}
}
