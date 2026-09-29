package com.example.gtmaddons.gui;

import com.example.gtmaddons.GTMAddOnsClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;

/**
 * The GTMAddOns menu (/gao):
 *
 *            [  Player Stats   ]   everyone's stats
 *            [ Personal Stats  ]   your stats over your last 25/50/100 fights
 *            [    Settings     ]
 *            [      Done       ]
 *
 * Both stats sections are locked while you're combat-tagged.
 */
public class MainScreen extends Screen {

	private static final int BUTTON_WIDTH = 200;
	private static final Tooltip COMBAT_LOCKED = Tooltip.of(Text.literal("Locked while you're in combat"));

	private final GTMAddOnsClient mod;
	private ButtonWidget playerStats;
	private ButtonWidget personalStats;
	private String status = null;

	public MainScreen(GTMAddOnsClient mod) {
		super(Text.literal("GTMAddOns"));
		this.mod = mod;
	}

	@Override
	protected void init() {
		int x = width / 2 - BUTTON_WIDTH / 2;
		int top = height / 2 - 44;

		playerStats = addDrawableChild(ButtonWidget.builder(Text.literal("Player Stats"),
				b -> mod.openStatsScreen(this, message -> status = message))
				.dimensions(x, top, BUTTON_WIDTH, 20).build());
		personalStats = addDrawableChild(ButtonWidget.builder(Text.literal("Personal Stats"),
				b -> mod.openPersonalStats(this, message -> status = message))
				.dimensions(x, top + 24, BUTTON_WIDTH, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Settings"), b -> client.setScreen(new SettingsScreen(this, mod)))
				.dimensions(x, top + 48, BUTTON_WIDTH, 20).build());
		addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, b -> close())
				.dimensions(x, top + 84, BUTTON_WIDTH, 20).build());
		updateCombatLock();
	}

	@Override
	public void tick() {
		updateCombatLock();
	}

	private void updateCombatLock() {
		boolean locked = mod.isInCombat();
		for (ButtonWidget button : new ButtonWidget[] { playerStats, personalStats }) {
			button.active = !locked;
			button.setTooltip(locked ? COMBAT_LOCKED : null);
		}
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		super.render(context, mouseX, mouseY, deltaTicks);
		int top = height / 2 - 44;
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, top - 22, 0xFFFFFFFF);

		String line = mod.isInCombat() ? "In combat - stats are locked" : status;
		if (line != null) {
			context.drawCenteredTextWithShadow(textRenderer, Text.literal(line), width / 2, top + 72, 0xFFFFFF55);
		}
	}
}
