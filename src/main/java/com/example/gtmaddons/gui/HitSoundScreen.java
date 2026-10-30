package com.example.gtmaddons.gui;

import com.example.gtmaddons.GTMAddOnsClient;
import com.example.gtmaddons.HitSounds.Kind;
import com.example.gtmaddons.HitSounds;
import com.example.gtmaddons.Settings;
import com.example.gtmaddons.compat.Gfx;
import com.example.gtmaddons.compat.Mc;
import com.example.gtmaddons.compat.ModScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

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
public class HitSoundScreen extends ModScreen {

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
		super(Component.literal("GTMAddOns - Hit sound"));
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

		addRenderableWidget(ThemedButton.create(onOff("Hit sound", settings.hitSound), b -> {
			settings.hitSound = !settings.hitSound;
			settings.save();
			b.setMessage(onOff("Hit sound", settings.hitSound));
		}).bounds(x, y, BUTTON_WIDTH, 20)
				.build());
		y += ROW;

		// One tab per kind of hit; the open one is highlighted.
		Kind[] kinds = Kind.values();
		for (int i = 0; i < kinds.length; i++) {
			Kind kind = kinds[i];
			Component label = kind == selected ? Component.literal(kind.label).withStyle(ChatFormatting.YELLOW) : Component.literal(kind.label);
			addRenderableWidget(ThemedButton.create(label, b -> {
				selected = kind;
				rebuildWidgets();
			}).bounds(x + i * (third + GAP), y, third, 20).build());
		}
		y += ROW;

		addRenderableWidget(ThemedButton.create(onOff(selected.label + " sound", HitSounds.INSTANCE.enabledOf(selected)), b -> {
			HitSounds.INSTANCE.setEnabled(selected, !HitSounds.INSTANCE.enabledOf(selected));
			settings.save();
			b.setMessage(onOff(selected.label + " sound", HitSounds.INSTANCE.enabledOf(selected)));
		}).bounds(x, y, BUTTON_WIDTH, 20).build());
		y += ROW;

		// Sound: previous, name (click to hear it), next.
		addRenderableWidget(ThemedButton.create(Component.literal("<"), b -> stepSound(-1))
				.bounds(x, y, ARROW_WIDTH, 20).build());
		addRenderableWidget(ThemedButton.create(soundName(), b -> preview())
				.bounds(x + ARROW_WIDTH + GAP, y, BUTTON_WIDTH - 2 * (ARROW_WIDTH + GAP), 20)
				.build());
		addRenderableWidget(ThemedButton.create(Component.literal(">"), b -> stepSound(1))
				.bounds(x + BUTTON_WIDTH - ARROW_WIDTH, y, ARROW_WIDTH, 20).build());
		y += ROW;

		addRenderableWidget(new VolumeSlider(x, y));
		y += ROW;
		addRenderableWidget(new PitchSlider(x, y));
		y += ROW + 8;

		addRenderableWidget(ThemedButton.create(CommonComponents.GUI_BACK, b -> onClose())
				.bounds(x, y, BUTTON_WIDTH, 20).build());
	}

	private Component soundName() {
		return Component.literal(HitSounds.CHOICES.get(HitSounds.INSTANCE.selectedIndex(selected)).label());
	}

	private void stepSound(int delta) {
		int n = HitSounds.CHOICES.size();
		int next = (HitSounds.INSTANCE.selectedIndex(selected) + delta + n) % n;
		HitSounds.INSTANCE.setId(selected, HitSounds.CHOICES.get(next).id());
		settings.save();
		preview();
		rebuildWidgets();
	}

	private void preview() {
		HitSounds.play(HitSounds.INSTANCE.idOf(selected), HitSounds.INSTANCE.volumeOf(selected), HitSounds.INSTANCE.pitchOf(selected));
	}

	private static Component onOff(String label, boolean on) {
		return Component.literal(label + ": ").append(Component.literal(on ? "ON" : "OFF").withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED));
	}

	@Override
	public void onClose() {
		Mc.setScreen(minecraft, parent);
	}

	@Override
	public void draw(Gfx context, int mouseX, int mouseY, float deltaTicks) {
		context.fill(0, 0, width, height, Ui.PAGE);
		super.draw(context, mouseX, mouseY, deltaTicks);
		int top = top();
		context.centeredText(font, title, width / 2, top - 22, Ui.TEXT);
	}

	/** 0-500% volume for the open tab, saved as it's dragged. */
	private final class VolumeSlider extends ThemedSlider {
		VolumeSlider(int x, int y) {
			super(x, y, BUTTON_WIDTH, 20, Component.empty(), HitSounds.INSTANCE.volumeOf(selected) / HitSounds.MAX_VOLUME);
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			int percent = (int) Math.round(value * 100 * HitSounds.MAX_VOLUME);
			setMessage(Component.literal(percent == 0 ? "Volume: Muted" : "Volume: " + percent + "%"));
		}

		@Override
		protected void applyValue() {
			float volume = (float) value * HitSounds.MAX_VOLUME;
			// Close to 100% sits exactly on 100% (the default).
			HitSounds.INSTANCE.setVolume(selected, Math.abs(volume - 1.0f) < 0.03f ? 1.0f : volume);
			settings.save();
		}
	}

	/** Pitch 0.5-2.0 for the open tab, saved as it's dragged. */
	private final class PitchSlider extends ThemedSlider {
		PitchSlider(int x, int y) {
			super(x, y, BUTTON_WIDTH, 20, Component.empty(), (HitSounds.INSTANCE.pitchOf(selected) - PITCH_MIN) / (PITCH_MAX - PITCH_MIN));
			updateMessage();
		}

		private float pitch() {
			return PITCH_MIN + (float) value * (PITCH_MAX - PITCH_MIN);
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.literal(String.format("Pitch: %.2f", pitch())));
		}

		@Override
		protected void applyValue() {
			HitSounds.INSTANCE.setPitch(selected, pitch());
			settings.save();
		}
	}
}
