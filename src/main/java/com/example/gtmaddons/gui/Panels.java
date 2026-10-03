package com.example.gtmaddons.gui;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.List;

/**
 * Stat panels for the insight pages (see InsightScreen): a dark panel with
 * the category's accent strip and title, then rows - label and value,
 * meters with a bar, notes, tables and timelines. Columns of panels are
 * drawn top-down at full height; the screen scrolls them.
 */
final class Panels {
	private Panels() {}

	static final int ROW = 11;
	static final int PAD = 6;
	/** Panel header: accent strip plus title. */
	static final int HEADER = 16;
	static final int GAP = 6;
	/** Width of each number column in a table, after the name. */
	static final int COLUMN_W = 36;

	record Section(String title, List<Row> rows) {
		int height() {
			int h = HEADER + 2 + PAD - 2;
			for (Row row : rows) h += row.height();
			return h;
		}
	}

	sealed interface Row permits Stat, Meter, Note, Gap, TableRow, TimeRow {
		default int height() {
			return ROW;
		}
	}

	/** Label on the left, value on the right. */
	record Stat(String label, String value, int color) implements Row {}

	/** A Stat with a bar underneath. */
	record Meter(String label, String value, int color, double fraction) implements Row {
		@Override
		public int height() {
			return ROW + 5;
		}
	}

	record Note(String text, int color) implements Row {}

	record Gap() implements Row {
		static final Gap INSTANCE = new Gap();

		@Override
		public int height() {
			return 4;
		}
	}

	/** A name, then right-aligned number columns; header draws a line under itself. */
	record TableRow(String[] cells, int color, boolean header) implements Row {
		@Override
		public int height() {
			return header ? ROW + 2 : ROW;
		}
	}

	/** Colored marker, time, details, and the result on the right. */
	record TimeRow(String time, String extra, String value, int color) implements Row {}

	/** Height of a column of panels. */
	static int height(List<Section> sections) {
		int h = 0;
		for (Section section : sections) h += section.height() + GAP;
		return Math.max(0, h - GAP);
	}

	/** Draws the panels top-down from (x, y), w wide. */
	static void drawColumn(DrawContext context, TextRenderer font, int x, int y, int w, List<Section> sections, int accent) {
		for (Section section : sections) {
			int h = section.height();
			Ui.panel(context, x, y, w, h, accent);
			// Same title style as the overview cards: bold caps in the category's accent.
			context.drawTextWithShadow(font, net.minecraft.text.Text.literal(Ui.fit(font, section.title().toUpperCase(java.util.Locale.ROOT), w - 2 * PAD)).formatted(net.minecraft.util.Formatting.BOLD), x + PAD, y + 6, accent);
			int rowY = y + HEADER + 2;
			for (Row row : section.rows()) {
				drawRow(context, font, row, x + PAD, rowY, w - 2 * PAD);
				rowY += row.height();
			}
			y += h + GAP;
		}
	}

	private static void drawRow(DrawContext context, TextRenderer font, Row row, int x, int y, int w) {
		switch (row) {
			case Stat s -> {
				int valueWidth = font.getWidth(s.value());
				context.drawTextWithShadow(font, Ui.fit(font, s.label(), w - valueWidth - 6), x, y, Ui.LABEL);
				Ui.textRight(context, font, s.value(), x + w, y, s.color());
			}
			case Meter m -> {
				int valueWidth = font.getWidth(m.value());
				context.drawTextWithShadow(font, Ui.fit(font, m.label(), w - valueWidth - 6), x, y, Ui.LABEL);
				Ui.textRight(context, font, m.value(), x + w, y, m.color());
				Ui.bar(context, x, y + 10, w, 2, m.fraction(), m.color());
			}
			case Note n -> context.drawTextWithShadow(font, Ui.fit(font, n.text(), w), x, y, n.color());
			case Gap g -> { }
			case TableRow t -> {
				int columns = t.cells().length - 1;
				int nameWidth = w - columns * COLUMN_W - 4;
				context.drawTextWithShadow(font, Ui.fit(font, t.cells()[0], nameWidth), x, y, t.color());
				for (int i = 1; i < t.cells().length; i++) {
					Ui.textRight(context, font, t.cells()[i], x + w - (columns - i) * COLUMN_W, y, t.color());
				}
				if (t.header()) context.fill(x, y + 10, x + w, y + 11, Ui.CARD_BORDER);
			}
			case TimeRow s -> {
				context.fill(x, y + 2, x + 4, y + 6, s.color());
				context.drawTextWithShadow(font, s.time(), x + 8, y, Ui.MUTED);
				int timeEnd = x + 8 + font.getWidth(s.time()) + 6;
				int valueWidth = font.getWidth(s.value());
				context.drawTextWithShadow(font, Ui.fit(font, s.extra(), x + w - valueWidth - 6 - timeEnd), timeEnd, y, Ui.LABEL);
				Ui.textRight(context, font, s.value(), x + w, y, s.color());
			}
		}
	}
}
