package com.example.gtmaddons.gui;

import com.example.gtmaddons.compat.Gfx;
import com.example.gtmaddons.compat.Mc;
import com.example.gtmaddons.compat.ModScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * A setting's ⚙ page: a short column of its options - ON/OFF switches, percentage sliders and buttons to further pages - and Back.
 * Built where the ⚙ is clicked:
 *
 *   new OptionsScreen(this, "Better jetpack particles").toggle("Hide", get, set).slider("Size", 25, 300, get, set)
 */
public class OptionsScreen extends ModScreen {

	private static final int BUTTON_WIDTH = 200;
	private static final int ROW = 22;

	private final Screen parent;
	private final List<Row> rows = new ArrayList<>();
	private Runnable onClose = () -> { };

	private sealed interface Row permits Toggle, Slider, Link, Action, Note {}
	private record Toggle(String label, BooleanSupplier get, Consumer<Boolean> set) implements Row {}
	private record Slider(String label, int min, int max, IntSupplier get, IntConsumer set, String offAt, String unit) implements Row {}
	private record Link(String label, Runnable open) implements Row {}
	private record Action(java.util.function.Supplier<String> label, Runnable run) implements Row {}
	private record Note(String text) implements Row {}

	public OptionsScreen(Screen parent, String title) {
		super(Component.literal("GTMAddOns - " + title));
		this.parent = parent;
	}

	public OptionsScreen toggle(String label, BooleanSupplier get, Consumer<Boolean> set) {
		rows.add(new Toggle(label, get, set));
		return this;
	}

	/** A percentage from min to max (whole numbers). */
	public OptionsScreen slider(String label, int min, int max, IntSupplier get, IntConsumer set) {
		rows.add(new Slider(label, min, max, get, set, null, "%"));
		return this;
	}

	/** Whole numbers from min to max in some unit (" s", " blocks"...). */
	public OptionsScreen slider(String label, int min, int max, String unit, IntSupplier get, IntConsumer set) {
		rows.add(new Slider(label, min, max, get, set, null, unit));
		return this;
	}

	/** A percentage that reads "Off" at its minimum. */
	public OptionsScreen sliderWithOff(String label, int min, int max, IntSupplier get, IntConsumer set) {
		rows.add(new Slider(label, min, max, get, set, "Off", "%"));
		return this;
	}

	public OptionsScreen link(String label, Runnable open) {
		rows.add(new Link(label, open));
		return this;
	}

	/** A button that does something here; its label is read again after each press. */
	public OptionsScreen action(java.util.function.Supplier<String> label, Runnable run) {
		rows.add(new Action(label, run));
		return this;
	}

	/** A grey line of text under the options above it. */
	public OptionsScreen note(String text) {
		rows.add(new Note(text));
		return this;
	}

	/** Runs when the page is left (e.g. to reload a texture once). */
	public OptionsScreen onClose(Runnable action) {
		this.onClose = action;
		return this;
	}

	private int top() {
		return Math.max(30, (height - (rows.size() + 1) * ROW - 8) / 2);
	}

	@Override
	protected void init() {
		int x = width / 2 - BUTTON_WIDTH / 2, y = top();
		for (Row row : rows) {
			switch (row) {
				case Toggle t -> addRenderableWidget(ThemedButton.create(onOff(t.label(), t.get().getAsBoolean()), b -> {
					t.set().accept(!t.get().getAsBoolean());
					b.setMessage(onOff(t.label(), t.get().getAsBoolean()));
				}).bounds(x, y, BUTTON_WIDTH, 20).build());
				case Slider s -> addRenderableWidget(new ThemedSlider(x, y, BUTTON_WIDTH, 20, Component.empty(),
						(s.get().getAsInt() - s.min()) / (double) (s.max() - s.min())) {
					{
						updateMessage();
					}

					private int percent() {
						return s.min() + (int) Math.round(value * (s.max() - s.min()));
					}

					@Override
					protected void updateMessage() {
						setMessage(Component.literal(s.label() + ": " + (s.offAt() != null && percent() == s.min() ? s.offAt() : percent() + s.unit())));
					}

					@Override
					protected void applyValue() {
						s.set().accept(percent());
					}
				});
				case Link l -> addRenderableWidget(ThemedButton.create(Component.literal(l.label() + "..."), b -> l.open().run())
						.bounds(x, y, BUTTON_WIDTH, 20).build());
				case Action a -> addRenderableWidget(ThemedButton.create(Component.literal(a.label().get()), b -> {
					a.run().run();
					b.setMessage(Component.literal(a.label().get()));
				}).bounds(x, y, BUTTON_WIDTH, 20).build());
				case Note n -> { }
			}
			y += ROW;
		}
		addRenderableWidget(ThemedButton.create(CommonComponents.GUI_BACK, b -> onClose()).bounds(x, y + 8, BUTTON_WIDTH, 20).build());
	}

	private static Component onOff(String label, boolean on) {
		return Component.literal(label + ": " + (on ? "ON" : "OFF"));
	}

	@Override
	public void onClose() {
		onClose.run();
		Mc.setScreen(minecraft, parent);
	}

	@Override
	public void draw(Gfx context, int mouseX, int mouseY, float deltaTicks) {
		context.fill(0, 0, width, height, Ui.PAGE);
		super.draw(context, mouseX, mouseY, deltaTicks);
		context.centeredText(font, title, width / 2, top() - 16, 0xFFFFFFFF);
		int y = top();
		for (Row row : rows) {
			if (row instanceof Note n) context.centeredText(font, Component.literal(Ui.fit(font, n.text(), width - 16)), width / 2, y + 6, Ui.MUTED);
			y += ROW;
		}
	}
}
