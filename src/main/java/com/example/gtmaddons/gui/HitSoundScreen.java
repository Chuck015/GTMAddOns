package com.example.gtmaddons.gui;

import com.example.gtmaddons.GTMAddOnsClient;
import com.example.gtmaddons.HitSounds;
import com.example.gtmaddons.HitSounds.Kind;
import com.example.gtmaddons.Settings;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Settings > Hit sound: turn hit sounds on, then pick a sound for each kind of hit - headshot, body
 * shot and melee. Each kind has its own tab with its own on/off switch, sound, volume and pitch, so a
 * headshot can play a different sound from a body shot. Every change is saved as it's made; picking a
 * sound, or clicking its name, plays it so you can hear it.
 *
 *            [ Hit sound: ON ]
 *            [Headshot] [Body shot] [Melee]
 *            [ Headshot sound: ON ]
 *            [<] [   Ding   ] [>]
 *            [ Volume: 100%  ]
 *            [ Pitch: 1.00   ]
 *            [    Back    ]
 */
public class HitSoundScreen extends Screen {

	private static final int ROW = 24;
	private static final int BUTTON_WIDTH = 200;
	private static final int ARROW_WIDTH = 20;
	private static final int GAP = 4;
	private static final float PITCH_MIN = 0.5f, PITCH_MAX = 2.0f;

	/** The tab last open, so coming back to the screen shows the same one. */
	private static Kind selected = Kind.HEADSHOT;

	private final Screen parent;
	private final Settings settings;

	public HitSoundScreen(Screen parent, GTMAddOnsClient mod) {
		super(Text.literal("GTMAddOns - Hit sound"));
		this.parent = parent;
		this.settings = mod.settings();
	}

	private int contentHeight() {
		return ROW * 7;
	}

	private int top() {
		return Math.max(34, (height - contentHeight()) / 2 + 6);
	}

	@Override
	protected void init() {
		int x = width / 2 - BUTTON_WIDTH / 2;
		int y = top();
		int third = (BUTTON_WIDTH - 2 * GAP) / 3;

		addDrawableChild(ButtonWidget.builder(onOff("Hit sound", settings.hitSound), b -> {
			settings.hitSound = !settings.hitSound;
			settings.save();
			b.setMessage(onOff("Hit sound", settings.hitSound));
		}).dimensions(x, y, BUTTON_WIDTH, 20)
				.build());
		y += ROW;

		// One tab per kind of hit; the open one is highlighted.
		Kind[] kinds = Kind.values();
		for (int i = 0; i < kinds.length; i++) {
			Kind kind = kinds[i];
			Text label = kind == selected ? Text.literal(kind.label).formatted(Formatting.YELLOW) : Text.literal(kind.label);
			addDrawableChild(ButtonWidget.builder(label, b -> {
				selected = kind;
				clearAndInit();
			}).dimensions(x + i * (third + GAP), y, third, 20).build());
		}
		y += ROW;

		addDrawableChild(ButtonWidget.builder(onOff(selected.label + " sound", HitSounds.INSTANCE.enabledOf(selected)), b -> {
			HitSounds.INSTANCE.setEnabled(selected, !HitSounds.INSTANCE.enabledOf(selected));
			settings.save();
			b.setMessage(onOff(selected.label + " sound", HitSounds.INSTANCE.enabledOf(selected)));
		}).dimensions(x, y, BUTTON_WIDTH, 20).build());
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
		y += ROW + 8;

		addDrawableChild(ButtonWidget.builder(ScreenTexts.BACK, b -> close())
				.dimensions(x, y, BUTTON_WIDTH, 20).build());
	}

	private Text soundName() {
		return Text.literal(HitSounds.CHOICES.get(HitSounds.INSTANCE.selectedIndex(selected)).label());
	}

	private void stepSound(int delta) {
		int n = HitSounds.CHOICES.size();
		int next = (HitSounds.INSTANCE.selectedIndex(selected) + delta + n) % n;
		HitSounds.INSTANCE.setId(selected, HitSounds.CHOICES.get(next).id());
		settings.save();
		preview();
		clearAndInit();
	}

	private void preview() {
		HitSounds.play(HitSounds.INSTANCE.idOf(selected), HitSounds.INSTANCE.volumeOf(selected), HitSounds.INSTANCE.pitchOf(selected));
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
	}

	/** 0-100% volume for the open tab, saved as it's dragged. */
	private final class VolumeSlider extends SliderWidget {
		VolumeSlider(int x, int y) {
			super(x, y, BUTTON_WIDTH, 20, Text.empty(), HitSounds.INSTANCE.volumeOf(selected));
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			int percent = (int) Math.round(value * 100);
			setMessage(Text.literal(percent == 0 ? "Volume: Muted" : "Volume: " + percent + "%"));
		}

		@Override
		protected void applyValue() {
			HitSounds.INSTANCE.setVolume(selected, (float) value);
			settings.save();
		}
	}

	/** Pitch 0.5-2.0 for the open tab, saved as it's dragged. */
	private final class PitchSlider extends SliderWidget {
		PitchSlider(int x, int y) {
			super(x, y, BUTTON_WIDTH, 20, Text.empty(), (HitSounds.INSTANCE.pitchOf(selected) - PITCH_MIN) / (PITCH_MAX - PITCH_MIN));
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
			HitSounds.INSTANCE.setPitch(selected, pitch());
			settings.save();
		}
	}
}
