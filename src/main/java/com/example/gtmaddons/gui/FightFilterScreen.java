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
 * many fights (1 to 500) and, if you like, which opponents - as many as you want.
 * Applied to the PvP tab you came from: the newest N fights of that PvP against
 * the chosen opponents (see FightFilter). With no opponent chosen it's everyone.
 * The opponents to choose from are handed in, so any page can use it.
 *
 *            Fights: [ slider 1-500 ] [-][+]
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
	/** What the leaderboard's Filter screen adds (null anywhere else): the column to rank by (index into sorts), the fewest fights a player needs to be listed, and whether to show every rating. */
	public record LeaderboardOptions(List<String> sorts, int sort, int minFights, boolean showAll) {}
	/** The values the leaderboard's min-fights buttons step through. */
	private static final int[] MIN_FIGHTS_STEPS = { 0, 1, 3, 5, 10, 15, 20, 25, 30, 40, 50, 75, 100, 150, 200, 250, 300, 400, 500 };
	private final LeaderboardOptions options;
	private final Consumer<LeaderboardOptions> onOptions;
	private int sort, minFights;
	private boolean showAll;
	private ClickableWidget minLabel;
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
		this(parent, scope, opponents, current, matches, onApply, null, null);
	}

	/** For the leaderboard: also Rank by, All ratings and Min fights; onOptions is told the picks on Apply. */
	public FightFilterScreen(Screen parent, String scope, List<Opponent> opponents, FightFilter current,
			ToIntFunction<FightFilter> matches, Consumer<FightFilter> onApply, LeaderboardOptions options, Consumer<LeaderboardOptions> onOptions) {
		super(Text.literal("Filter fights - " + scope));
		this.parent = parent;
		this.scope = scope;
		this.matches = matches;
		this.onApply = onApply;
		this.limit = current.limit();
		this.selected.addAll(current.opponents());
		this.everyone = opponents;
		this.options = options != null && !options.sorts().isEmpty() ? options : null;
		this.onOptions = onOptions;
		if (this.options != null) {
			this.sort = Math.max(0, Math.min(this.options.sorts().size() - 1, this.options.sort()));
			this.minFights = Math.max(0, this.options.minFights());
			this.showAll = this.options.showAll();
		}
	}

	private int left() {
		return width / 2 - WIDTH / 2;
	}

	/** The leaderboard's extra buttons (two rows) sit between the slider and the search box and push the rest down. */
	private int searchY() {
		return options != null ? 108 : 62;
	}

	private int gridTop() {
		return searchY() + 30;
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

		if (options != null) {
			int half = (WIDTH - GAP) / 2;
			addDrawableChild(ButtonWidget.builder(Text.literal("Rank by: " + options.sorts().get(sort)), b -> {
				sort = (sort + 1) % options.sorts().size();
				b.setMessage(Text.literal("Rank by: " + options.sorts().get(sort)));
			}).dimensions(x, 56, half, 20).build());
			addDrawableChild(ButtonWidget.builder(Text.literal("All ratings: " + (showAll ? "ON" : "OFF")), b -> {
				showAll = !showAll;
				b.setMessage(Text.literal("All ratings: " + (showAll ? "ON" : "OFF")));
			}).dimensions(x + half + GAP, 56, half, 20).build());
			addDrawableChild(ButtonWidget.builder(Text.literal("-"), b -> stepMinFights(-1)).dimensions(x, 82, 20, 20).build());
			minLabel = addDrawableChild(ButtonWidget.builder(Text.literal(minFightsText()), b -> stepMinFights(1)).dimensions(x + 20 + GAP, 82, WIDTH - 2 * (20 + GAP), 20).build());
			addDrawableChild(ButtonWidget.builder(Text.literal("+"), b -> stepMinFights(1)).dimensions(x + WIDTH - 20, 82, 20, 20).build());
		}

		TextFieldWidget field = addDrawableChild(new TextFieldWidget(textRenderer, x, searchY(), WIDTH, 20, Text.literal("Search opponents")));
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

	private String minFightsText() {
		return minFights == 0 ? "Min fights: any" : "Min fights: " + minFights;
	}

	/** Moves the fewest-fights value to the next (or previous) step; stepping up past the last wraps to "any". */
	private void stepMinFights(int direction) {
		int at = 0;
		for (int i = 0; i < MIN_FIGHTS_STEPS.length; i++) if (MIN_FIGHTS_STEPS[i] <= minFights) at = i;
		int next = at + direction;
		if (next < 0) next = 0;
		if (next >= MIN_FIGHTS_STEPS.length) next = 0;
		minFights = MIN_FIGHTS_STEPS[next];
		if (minLabel != null) minLabel.setMessage(Text.literal(minFightsText()));
	}

	private void apply() {
		client.setScreen(parent);
		if (onOptions != null && options != null) onOptions.accept(new LeaderboardOptions(options.sorts(), sort, minFights, showAll));
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

	/** Fights 1-500. */
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
