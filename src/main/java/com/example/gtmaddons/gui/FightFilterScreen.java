package com.example.gtmaddons.gui;

import com.example.gtmaddons.stats.FightFilter;
import com.example.gtmaddons.stats.FightFilter.Opponent;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;

/**
 * Filter (from a player's stats page, or the Player Stats leaderboard): pick how
 * many fights (1 to 100) and, if you like, which opponents - as many as you want.
 * Applied to the PvP tab you came from: the newest N fights of that PvP against
 * the chosen opponents (see FightFilter). With no opponent chosen it's everyone.
 * The opponents to choose from are handed in, so any page can use it.
 *
 *            Fights: [ slider 1-100 ] [-][+]
 *            [ search opponents...        ]
 *            [x Alice (5)] [  Bob (3)  ]
 *            [  Cara (2) ] [ (unknown) ]
 *            [< Prev]  page 1/3  [Next >]
 *            12 fights match
 *            [ Everyone ] [ Apply ] [ Cancel ]
 */
public class FightFilterScreen extends Screen {

	private static final int WIDTH = 224;
	private static final int ROW = 22;
	private static final int GAP = 4;

	private final Screen parent;
	/** What the selection is applied to, for the header, e.g. "Wing". */
	private final String scope;
	/** How many fights a selection picks, for the live line; null when that isn't known (the leaderboard). */
	private final ToIntFunction<FightFilter> matches;
	private final Consumer<FightFilter> onApply;
	private final List<Opponent> everyone;
	private final Set<String> selected = new HashSet<>();
	private int limit;
	private String search = "";
	private int page = 0;
	private LimitSlider slider;
	/** The opponent buttons and pager, rebuilt when the search, page or choices change. */
	private final List<ClickableWidget> gridWidgets = new ArrayList<>();

	public FightFilterScreen(Screen parent, String scope, List<Opponent> opponents, FightFilter current,
			ToIntFunction<FightFilter> matches, Consumer<FightFilter> onApply) {
		super(Text.literal("Filter fights - " + scope));
		this.parent = parent;
		this.scope = scope;
		this.matches = matches;
		this.onApply = onApply;
		this.limit = current.limit();
		this.selected.addAll(current.opponents());
		this.everyone = opponents;
	}

	private int left() {
		return width / 2 - WIDTH / 2;
	}

	private int gridTop() {
		return 92;
	}

	private int rowsPerPage() {
		return Math.max(1, Math.min(8, (height - 70 - gridTop() - 28) / ROW));
	}

	@Override
	protected void init() {
		gridWidgets.clear();
		int x = left();

		slider = addDrawableChild(new LimitSlider(x, 30, WIDTH - 2 * (20 + GAP)));
		addDrawableChild(ButtonWidget.builder(Text.literal("-"), b -> slider.set(limit - 1))
				.dimensions(x + WIDTH - 2 * 20 - GAP, 30, 20, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.literal("+"), b -> slider.set(limit + 1))
				.dimensions(x + WIDTH - 20, 30, 20, 20).build());

		TextFieldWidget field = addDrawableChild(new TextFieldWidget(textRenderer, x, 62, WIDTH, 20, Text.literal("Search opponents")));
		field.setMaxLength(16);
		field.setPlaceholder(Text.literal("Search opponents...").formatted(Formatting.GRAY));
		field.setText(search);
		field.setChangedListener(text -> {
			search = text.trim().toLowerCase(Locale.ROOT);
			page = 0;
			buildGrid();
		});

		int bottom = height - 28;
		int third = (WIDTH - 2 * GAP) / 3;
		addDrawableChild(ButtonWidget.builder(Text.literal("Everyone"), b -> {
			selected.clear();
			buildGrid();
		}).dimensions(x, bottom, third, 20)
				.build());
		addDrawableChild(ButtonWidget.builder(Text.literal("Apply"), b -> apply())
				.dimensions(x + third + GAP, bottom, third, 20).build());
		addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, b -> close())
				.dimensions(x + 2 * (third + GAP), bottom, third, 20).build());

		buildGrid();
	}

	/** Opponents matching the search. */
	private List<Opponent> shown() {
		if (search.isEmpty()) return everyone;
		List<Opponent> out = new ArrayList<>();
		for (Opponent o : everyone) if (o.name().toLowerCase(Locale.ROOT).contains(search)) out.add(o);
		return out;
	}

	private void buildGrid() {
		for (ClickableWidget widget : gridWidgets) remove(widget);
		gridWidgets.clear();

		List<Opponent> list = shown();
		int perPage = rowsPerPage() * 2;
		int pages = Math.max(1, (list.size() + perPage - 1) / perPage);
		page = Math.max(0, Math.min(page, pages - 1));
		int colWidth = (WIDTH - GAP) / 2;
		int start = page * perPage, end = Math.min(list.size(), start + perPage);
		for (int i = start; i < end; i++) {
			Opponent o = list.get(i);
			int col = (i - start) % 2, row = (i - start) / 2;
			boolean on = selected.contains(o.key());
			String name = textRenderer.trimToWidth(o.name(), colWidth - 44);
			Text label = Text.literal(on ? "✔ " : "").formatted(Formatting.GREEN)
					.append(Text.literal(name).formatted(on ? Formatting.GREEN : Formatting.WHITE))
					.append(Text.literal(" (" + o.inCategory() + ")").formatted(o.inCategory() > 0 ? Formatting.GRAY : Formatting.DARK_GRAY));
			gridWidgets.add(addDrawableChild(ButtonWidget.builder(label, b -> {
				if (!selected.remove(o.key())) selected.add(o.key());
				buildGrid();
			}).dimensions(left() + col * (colWidth + GAP), gridTop() + row * ROW, colWidth, 20)
					.build()));
		}

		int y = gridTop() + rowsPerPage() * ROW + 2;
		ButtonWidget prev = addDrawableChild(ButtonWidget.builder(Text.literal("< Prev"), b -> {
			page--;
			buildGrid();
		}).dimensions(left(), y, 60, 20).build());
		prev.active = page > 0;
		ButtonWidget next = addDrawableChild(ButtonWidget.builder(Text.literal("Next >"), b -> {
			page++;
			buildGrid();
		}).dimensions(left() + WIDTH - 60, y, 60, 20).build());
		next.active = page < pages - 1;
		gridWidgets.add(prev);
		gridWidgets.add(next);
	}

	private void apply() {
		client.setScreen(parent);
		onApply.accept(new FightFilter(limit, selected));
	}

	@Override
	public void close() {
		client.setScreen(parent);
	}

	@Override
	public void tick() {
		CombatLock.closeIfInCombat(client);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		super.render(context, mouseX, mouseY, deltaTicks);
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 10, Ui.TEXT);

		List<Opponent> list = shown();
		int perPage = rowsPerPage() * 2;
		int pages = Math.max(1, (list.size() + perPage - 1) / perPage);
		int y = gridTop() + rowsPerPage() * ROW + 8;
		context.drawCenteredTextWithShadow(textRenderer, Text.literal("page " + (page + 1) + " of " + pages), width / 2, y, Ui.MUTED);
		if (list.isEmpty()) {
			context.drawCenteredTextWithShadow(textRenderer, Text.literal(everyone.isEmpty() ? "No fights yet." : "No opponent matches."),
					width / 2, gridTop() + 6, Ui.MUTED);
		}

		String who = selected.isEmpty() ? "everyone" : selected.size() + (selected.size() == 1 ? " opponent" : " opponents");
		if (matches != null) {
			int count = matches.applyAsInt(new FightFilter(limit, selected));
			context.drawCenteredTextWithShadow(textRenderer,
					Text.literal(count + (count == 1 ? " fight matches" : " fights match") + " (" + who + ")"),
					width / 2, height - 42, count > 0 ? Ui.GOOD : Ui.BAD);
		}
	}

	/** Fights 1-100. */
	private final class LimitSlider extends SliderWidget {
		LimitSlider(int x, int y, int w) {
			super(x, y, w, 20, Text.empty(), (limit - 1) / (double) (FightFilter.MAX_FIGHTS - 1));
			updateMessage();
		}

		void set(int fights) {
			limit = Math.max(1, Math.min(FightFilter.MAX_FIGHTS, fights));
			value = (limit - 1) / (double) (FightFilter.MAX_FIGHTS - 1);
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			setMessage(Text.literal("Fights: " + limit));
		}

		@Override
		protected void applyValue() {
			limit = 1 + (int) Math.round(value * (FightFilter.MAX_FIGHTS - 1));
		}
	}
}
