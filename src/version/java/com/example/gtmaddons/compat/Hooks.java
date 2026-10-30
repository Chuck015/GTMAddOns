package com.example.gtmaddons.compat;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** The Fabric events that have different names per version (the 1.21.11 side of the version layer). */
public final class Hooks {
	private Hooks() {}

	/** A client command's first word. */
	public static LiteralArgumentBuilder<FabricClientCommandSource> literal(String name) {
		return ClientCommandManager.literal(name);
	}

	/** Every frame's HUD, except while it is hidden (F1). */
	public static void onHud(BiConsumer<Gfx, DeltaTracker> hud) {
		HudRenderCallback.EVENT.register((context, tickCounter) -> {
			if (!Minecraft.getInstance().options.hideGui) hud.accept(new Gfx(context), tickCounter);
		});
	}

	/** The start of every world frame. */
	public static void onFrameStart(Runnable frame) {
		WorldRenderEvents.START_MAIN.register(context -> frame.run());
	}

	public static void beforeFrame(Screen screen, Consumer<Screen> frame) {
		ScreenEvents.beforeRender(screen).register((s, context, mouseX, mouseY, tickDelta) -> frame.accept(s));
	}

	public static void afterFrame(Screen screen, Consumer<Screen> frame) {
		ScreenEvents.afterRender(screen).register((s, context, mouseX, mouseY, tickDelta) -> frame.accept(s));
	}

	public static void addWidget(Screen screen, AbstractWidget widget) {
		Screens.getButtons(screen).add(widget);
	}
}
