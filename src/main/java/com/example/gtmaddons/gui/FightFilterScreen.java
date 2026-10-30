package com.example.gtmaddons.gui;

import com.example.gtmaddons.compat.Gfx;
import com.example.gtmaddons.compat.Mc;
import com.example.gtmaddons.compat.ModScreen;
import com.example.gtmaddons.stats.FightFilter.Opponent;
import com.example.gtmaddons.stats.FightFilter;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

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
public class FightFilterScreen extends ModScreen {

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
	private AbstractWidget minLabel;
	private final List<Opponent> everyone;
	private final Set<String> selected = new HashSet<>();
	private int limit;
	private String search = "";
	private int page = 0;
	private LimitSlider slider;
	/** The opponent buttons and pager, rebuilt when the search, page or choices change. */
	private final List<AbstractWidget> gridWidgets = new ArrayList<>();

	public FightFilterScreen(Screen parent, String scope, List<Opponent> opponents, FightFilter current,
			ToIntFunction<FightFilter> matches, Consumer<FightFilter> onApply) {
		this(parent, scope, opponents, current, matches, onApply, null, null);
	}

	/** For the leaderboard: also Rank by, All ratings and Min fights; onOptions is told the picks on Apply. */
	public FightFilterScreen(Screen parent, String scope, List<Opponent> opponents, FightFilter current,
			ToIntFunction<FightFilter> matches, Consumer<FightFilter> onApply, LeaderboardOptions options, Consumer<LeaderboardOptions> onOptions) {
		super(Component.literal("Filter fights - " + scope));
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

		slider = addRenderableWidget(new LimitSlider(x, 30, WIDTH - 2 * (20 + GAP)));
		addRenderableWidget(ThemedButton.create(Component.literal("-"), b -> slider.set(limit - 1))
				.bounds(x + WIDTH - 2 * 20 - GAP, 30, 20, 20).build());
		addRenderableWidget(ThemedButton.create(Component.literal("+"), b -> slider.set(limit + 1))
				.bounds(x + WIDTH - 20, 30, 20, 20).build());

		if (options != null) {
			int half = (WIDTH - GAP) / 2;
			addRenderableWidget(ThemedButton.create(Component.literal("Rank by: " + options.sorts().get(sort)), b -> {
				sort = (sort + 1) % options.sorts().size();
				b.setMessage(Component.literal("Rank by: " + options.sorts().get(sort)));
			}).bounds(x, 56, half, 20).build());
			addRenderableWidget(ThemedButton.create(Component.literal("All ratings: " + (showAll ? "ON" : "OFF")), b -> {
				showAll = !showAll;
				b.setMessage(Component.literal("All ratings: " + (showAll ? "ON" : "OFF")));
			}).bounds(x + half + GAP, 56, half, 20).build());
			addRenderableWidget(ThemedButton.create(Component.literal("-"), b -> stepMinFights(-1)).bounds(x, 82, 20, 20).build());
			minLabel = addRenderableWidget(ThemedButton.create(Component.literal(minFightsText()), b -> stepMinFights(1)).bounds(x + 20 + GAP, 82, WIDTH - 2 * (20 + GAP), 20).build());
			addRenderableWidget(ThemedButton.create(Component.literal("+"), b -> stepMinFights(1)).bounds(x + WIDTH - 20, 82, 20, 20).build());
		}

		EditBox field = addRenderableWidget(new EditBox(font, x, searchY(), WIDTH, 20, Component.literal("Search opponents")));
		field.setMaxLength(16);
		field.setHint(Component.literal("Search opponents...").withStyle(ChatFormatting.GRAY));
		field.setValue(search);
		field.setResponder(text -> {
			search = text.trim().toLowerCase(Locale.ROOT);
			page = 0;
			buildGrid();
		});

		int bottom = height - 28;
		int third = (WIDTH - 2 * GAP) / 3;
		addRenderableWidget(ThemedButton.create(Component.literal("Everyone"), b -> {
			selected.clear();
			buildGrid();
		}).bounds(x, bottom, third, 20)
				.build());
		addRenderableWidget(ThemedButton.create(Component.literal("Apply"), b -> apply())
				.bounds(x + third + GAP, bottom, third, 20).build());
		addRenderableWidget(ThemedButton.create(CommonComponents.GUI_CANCEL, b -> onClose())
				.bounds(x + 2 * (third + GAP), bottom, third, 20).build());

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
		for (AbstractWidget widget : gridWidgets) removeWidget(widget);
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
			String name = font.plainSubstrByWidth(o.name(), colWidth - 44);
			Component label = Component.literal(on ? "✔ " : "").withStyle(ChatFormatting.GREEN)
					.append(Component.literal(name).withStyle(on ? ChatFormatting.GREEN : ChatFormatting.WHITE))
					.append(Component.literal(" (" + o.inCategory() + ")").withStyle(o.inCategory() > 0 ? ChatFormatting.GRAY : ChatFormatting.DARK_GRAY));
			gridWidgets.add(addRenderableWidget(ThemedButton.create(label, b -> {
				if (!selected.remove(o.key())) selected.add(o.key());
				buildGrid();
			}).bounds(left() + col * (colWidth + GAP), gridTop() + row * ROW, colWidth, 20)
					.build()));
		}

		int y = gridTop() + rowsPerPage() * ROW + 2;
		Button prev = addRenderableWidget(ThemedButton.create(Component.literal("< Prev"), b -> {
			page--;
			buildGrid();
		}).bounds(left(), y, 60, 20).build());
		prev.active = page > 0;
		Button next = addRenderableWidget(ThemedButton.create(Component.literal("Next >"), b -> {
			page++;
			buildGrid();
		}).bounds(left() + WIDTH - 60, y, 60, 20).build());
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
		if (minLabel != null) minLabel.setMessage(Component.literal(minFightsText()));
	}

	private void apply() {
		Mc.setScreen(minecraft, parent);
		if (onOptions != null && options != null) onOptions.accept(new LeaderboardOptions(options.sorts(), sort, minFights, showAll));
		onApply.accept(new FightFilter(limit, selected));
	}

	@Override
	public void onClose() {
		Mc.setScreen(minecraft, parent);
	}

	@Override
	public void tick() {
		CombatLock.closeIfInCombat(minecraft);
	}

	@Override
	public void draw(Gfx context, int mouseX, int mouseY, float deltaTicks) {
		context.fill(0, 0, width, height, Ui.PAGE);
		super.draw(context, mouseX, mouseY, deltaTicks);
		context.centeredText(font, title, width / 2, 10, Ui.TEXT);

		List<Opponent> list = shown();
		int perPage = rowsPerPage() * 2;
		int pages = Math.max(1, (list.size() + perPage - 1) / perPage);
		int y = gridTop() + rowsPerPage() * ROW + 8;
		context.centeredText(font, Component.literal("page " + (page + 1) + " of " + pages), width / 2, y, Ui.MUTED);
		if (list.isEmpty()) {
			context.centeredText(font, Component.literal(everyone.isEmpty() ? "No fights yet." : "No opponent matches."),
					width / 2, gridTop() + 6, Ui.MUTED);
		}

		String who = selected.isEmpty() ? "everyone" : selected.size() + (selected.size() == 1 ? " opponent" : " opponents");
		if (matches != null) {
			int count = matches.applyAsInt(new FightFilter(limit, selected));
			context.centeredText(font,
					Component.literal(count + (count == 1 ? " fight matches" : " fights match") + " (" + who + ")"),
					width / 2, height - 42, count > 0 ? Ui.GOOD : Ui.BAD);
		}
	}

	/** Fights 1-500. */
	private final class LimitSlider extends ThemedSlider {
		LimitSlider(int x, int y, int w) {
			super(x, y, w, 20, Component.empty(), (limit - 1) / (double) (FightFilter.MAX_FIGHTS - 1));
			updateMessage();
		}

		void set(int fights) {
			limit = Math.max(1, Math.min(FightFilter.MAX_FIGHTS, fights));
			value = (limit - 1) / (double) (FightFilter.MAX_FIGHTS - 1);
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.literal("Fights: " + limit));
		}

		@Override
		protected void applyValue() {
			limit = 1 + (int) Math.round(value * (FightFilter.MAX_FIGHTS - 1));
		}
	}
}
