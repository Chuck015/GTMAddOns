package com.example.gtmaddons.gui;

import com.example.gtmaddons.GTMAddOnsClient;
import com.example.gtmaddons.HitSounds;
import com.example.gtmaddons.Settings;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Settings > Hit sound: turn the sound on, pick which one plays (with volume
 * and pitch), and choose which hits play it - headshots, body shots, melee -
 * each on its own, or all at once with All. Every change is saved as it's
 * made. Picking a sound, or clicking its name, plays it so you can hear it.
 *
 *            [ Hit sound: ON ]
 *            [<] [   Ding   ] [>]
 *            [ Volume: 100%  ]
 *            [ Pitch: 1.00   ]
 *              Play the sound on:
 *            [ Headshot ] [ Body shot ]
 *            [  Melee   ] [ All hits  ]
 *            [    Back    ]
 */
public class HitSoundScreen extends Screen {

	private static final int ROW = 24;
	private static final int BUTTON_WIDTH = 200;
	private static final int ARROW_WIDTH = 20;
	private static final int GAP = 4;
	/** The "Play the sound on:" caption between the sliders and the filters. */
	private static final int CAPTION = 12;
	private static final float PITCH_MIN = 0.5f, PITCH_MAX = 2.0f;

	private final Screen parent;
	private final Settings settings;

	public HitSoundScreen(Screen parent, GTMAddOnsClient mod) {
		super(Text.literal("GTMAddOns - Hit sound"));
		this.parent = parent;
		this.settings = mod.settings();
	}

	private int contentHeight() {
		return ROW * 7 + CAPTION;
	}

	private int top() {
		return Math.max(34, (height - contentHeight()) / 2 + 6);
	}

	@Override
	protected void init() {
		int x = width / 2 - BUTTON_WIDTH / 2;
		int y = top();
		int half = (BUTTON_WIDTH - GAP) / 2;

		addDrawableChild(ButtonWidget.builder(onOff("Hit sound", settings.hitSound), b -> {
			settings.hitSound = !settings.hitSound;
			settings.save();
			b.setMessage(onOff("Hit sound", settings.hitSound));
		}).dimensions(x, y, BUTTON_WIDTH, 20)
				.build());
		y += ROW;

		// Sound: previous, name (click to hear it), next.
		addDrawableChild(ButtonWidget.builder(Text.literal("<"), b -> stepSound(-1))
				.dimensions(x, y, ARROW_WIDTH, 20).build());
		addDrawableChild(ButtonWidget.builder(soundName(), b -> preview())
				.dimensions(x + ARROW_WIDTH + GAP, y, BUTTON_WIDTH - 2 * (ARROW_WIDTH + GAP), 20)
				.build());
		addDrawableChild(ButtonWidget.builder(Text.literal(">"), b -> stepSound(1))
				.dimensions(x + BUTTON_WIDTH - ARROW_WIDTH, y, ARROW_WIDTH, 20).build());
		y += ROW;

		addDrawableChild(new VolumeSlider(x, y));
		y += ROW;
		addDrawableChild(new PitchSlider(x, y));
		y += ROW + CAPTION;

		addDrawableChild(filterButton(x, y, half, "Headshot",
				() -> settings.hitSoundHeadshot, on -> settings.hitSoundHeadshot = on));
		addDrawableChild(filterButton(x + BUTTON_WIDTH - half, y, half, "Body shot",
				() -> settings.hitSoundBody, on -> settings.hitSoundBody = on));
		y += ROW;
		addDrawableChild(filterButton(x, y, half, "Melee",
				() -> settings.hitSoundMelee, on -> settings.hitSoundMelee = on));
		addDrawableChild(ButtonWidget.builder(onOff("All hits", allOn()), b -> {
			boolean on = !allOn();
			settings.hitSoundHeadshot = settings.hitSoundBody = settings.hitSoundMelee = on;
			settings.save();
			clearAndInit();
		}).dimensions(x + BUTTON_WIDTH - half, y, half, 20)
				.build());
		y += ROW + 8;

		addDrawableChild(ButtonWidget.builder(ScreenTexts.BACK, b -> close())
				.dimensions(x, y, BUTTON_WIDTH, 20).build());
	}

	private boolean allOn() {
		return settings.hitSoundHeadshot && settings.hitSoundBody && settings.hitSoundMelee;
	}

	/** One on/off button for a kind of hit; saves at once and refreshes the All button. */
	private ButtonWidget filterButton(int x, int y, int w, String label,
			java.util.function.BooleanSupplier get, java.util.function.Consumer<Boolean> set) {
		return ButtonWidget.builder(onOff(label, get.getAsBoolean()), b -> {
			set.accept(!get.getAsBoolean());
			settings.save();
			clearAndInit();
		}).dimensions(x, y, w, 20).build();
	}

	private Text soundName() {
		return Text.literal(HitSounds.CHOICES.get(HitSounds.INSTANCE.selectedIndex()).label());
	}

	private void stepSound(int delta) {
		int n = HitSounds.CHOICES.size();
		int next = (HitSounds.INSTANCE.selectedIndex() + delta + n) % n;
		settings.hitSoundId = HitSounds.CHOICES.get(next).id();
		settings.save();
		preview();
		clearAndInit();
	}

	private void preview() {
		HitSounds.play(settings.hitSoundId, settings.hitSoundVolume, settings.hitSoundPitch);
	}

	private static Text onOff(String label, boolean on) {
		return Text.literal(label + ": ").append(Text.literal(on ? "ON" : "OFF").formatted(on ? Formatting.GREEN : Formatting.RED));
	}

	@Override
	public void close() {
		client.setScreen(parent);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		super.render(context, mouseX, mouseY, deltaTicks);
		int top = top();
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, top - 22, Ui.TEXT);
		context.drawCenteredTextWithShadow(textRenderer, Text.literal("Play on:"), width / 2, top + ROW * 4 + 2, Ui.LABEL);
	}

	/** 0-100% volume, saved as it's dragged. */
	private final class VolumeSlider extends SliderWidget {
		VolumeSlider(int x, int y) {
			super(x, y, BUTTON_WIDTH, 20, Text.empty(), settings.hitSoundVolume);
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			int percent = (int) Math.round(value * 100);
			setMessage(Text.literal(percent == 0 ? "Volume: Muted" : "Volume: " + percent + "%"));
		}

		@Override
		protected void applyValue() {
			settings.hitSoundVolume = (float) value;
			settings.save();
		}
	}

	/** Pitch 0.5-2.0, saved as it's dragged. */
	private final class PitchSlider extends SliderWidget {
		PitchSlider(int x, int y) {
			super(x, y, BUTTON_WIDTH, 20, Text.empty(), (settings.hitSoundPitch - PITCH_MIN) / (PITCH_MAX - PITCH_MIN));
			updateMessage();
		}

		private float pitch() {
			return PITCH_MIN + (float) value * (PITCH_MAX - PITCH_MIN);
		}

		@Override
		protected void updateMessage() {
			setMessage(Text.literal(String.format("Pitch: %.2f", pitch())));
		}

		@Override
		protected void applyValue() {
			settings.hitSoundPitch = pitch();
			settings.save();
		}
	}
}
