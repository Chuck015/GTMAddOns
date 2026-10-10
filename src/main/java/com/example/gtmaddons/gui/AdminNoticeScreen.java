package com.example.gtmaddons.gui;

import com.example.gtmaddons.GTMAddOnsClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Admin mode > Notify players: send the mods a message. "All outdated players" goes to everyone running an older version of the
 * mod than the newest one in use; "Specific players" to the names typed. The message starts as DEFAULT_MESSAGE and can be rewritten
 * (up to 240 characters, one line); every player gets it once, in chat with an update button, with their next heartbeat (within
 * 15 minutes) - and only if their mod is new enough to read notices. The backend only answers admin accounts (HTTP 403 otherwise).
 */
public class AdminNoticeScreen extends Screen {

	private static final int WIDTH = 280;
	/** The wording used when nothing else is written (the backend sends this too when it gets no message). */
	public static final String DEFAULT_MESSAGE = "A new GTMAddOns version is out. Please update: click UPDATE NOW below (or type /gao update), then restart your game once it has downloaded.";
	private static final int LABEL = 0xFF9AA0AE, TEXT = 0xFFFFFFFF, GOOD = 0xFF55FF55, WARN = 0xFFFFAA00;

	/** Kept while the screen is rebuilt (a resize) and between visits. */
	private static boolean specific = false;
	private static String lastNames = "";
	private static String lastMessage = DEFAULT_MESSAGE;

	private final Screen parent;
	private final GTMAddOnsClient mod;
	private String status = null;
	private int statusColor = WARN;
	private boolean sending = false;

	public AdminNoticeScreen(Screen parent, GTMAddOnsClient mod) {
		super(Text.literal("GTMAddOns - Notify players (admin)"));
		this.parent = parent;
		this.mod = mod;
	}

	private int fieldsTop() {
		return 34;
	}

	@Override
	protected void init() {
		int x = width / 2 - WIDTH / 2;
		int y = fieldsTop();
		addDrawableChild(ButtonWidget.builder(Text.literal(specific ? "Send to: specific players" : "Send to: all outdated players"), b -> {
			specific = !specific;
			status = null;
			clearAndInit();
		}).dimensions(x, y, WIDTH, 20).build());
		y += 26;
		if (specific) {
			TextFieldWidget names = addDrawableChild(new TextFieldWidget(textRenderer, x, y, WIDTH, 20, Text.literal("Player names")));
			names.setMaxLength(400);
			names.setPlaceholder(Text.literal("Player names, separated by commas"));
			names.setText(lastNames);
			names.setChangedListener(text -> lastNames = text);
			y += 26;
		}
		y += 12;
		TextFieldWidget message = addDrawableChild(new TextFieldWidget(textRenderer, x, y, WIDTH, 20, Text.literal("Message")));
		message.setMaxLength(240);
		message.setText(lastMessage);
		message.setChangedListener(text -> lastMessage = text);
		y += 26;
		int half = (WIDTH - 4) / 2;
		addDrawableChild(ButtonWidget.builder(Text.literal("Default message"), b -> {
			lastMessage = DEFAULT_MESSAGE;
			message.setText(DEFAULT_MESSAGE);
		}).dimensions(x, y, half, 20).build());
		ButtonWidget send = addDrawableChild(ButtonWidget.builder(Text.literal("Send"), b -> send()).dimensions(x + half + 4, y, half, 20).build());
		send.active = !sending;
		addDrawableChild(ButtonWidget.builder(ScreenTexts.BACK, b -> close()).dimensions(width / 2 - 75, height - 28, 150, 20).build());
	}

	private void send() {
		String message = lastMessage.trim().isEmpty() ? DEFAULT_MESSAGE : lastMessage.trim();
		if (!specific) {
			client.setScreen(new ConfirmScreen(yes -> {
				client.setScreen(this);
				if (yes) post(List.of(), message);
			}, Text.literal("Message every outdated player?"),
					Text.literal("This sends the message below to everyone running an older version of the mod than the newest one in use.\n\n" + message)));
			return;
		}
		List<String> names = new ArrayList<>();
		for (String name : lastNames.split("[,\\s]+")) if (!name.isEmpty()) names.add(name);
		if (names.isEmpty()) {
			fail("Type at least one player name.");
			return;
		}
		for (String name : names) {
			if (!name.matches("[A-Za-z0-9_]{1,16}")) {
				fail(name + " is not a valid player name (1-16 letters, digits or _).");
				return;
			}
		}
		post(names, message);
	}

	private void fail(String text) {
		status = text;
		statusColor = WARN;
	}

	private void post(List<String> names, String message) {
		sending = true;
		status = "Sending...";
		statusColor = WARN;
		clearAndInit();
		mod.sendAdminNotice(names, message).whenComplete((result, error) -> client.execute(() -> {
			sending = false;
			if (client.currentScreen != this) return;
			if (error != null) {
				String why = Format.error(error);
				fail(why.contains("403") ? "Only admin accounts can send notices (HTTP 403)."
						: why.contains("404") ? "None of those players have used the stats server."
						: why.contains("400") ? "The stats server has no mod versions to compare yet."
						: "Couldn't send it: " + why);
			} else if (result.targeted() == 0) {
				status = "Nobody is on an older version than " + result.targetVersion() + ", so nobody was sent it.";
				statusColor = WARN;
			} else {
				String who = "specific".equals(result.mode()) || "players".equals(result.mode())
						? result.targeted() + (result.targeted() == 1 ? " player" : " players")
						: result.targeted() + (result.targeted() == 1 ? " player" : " players") + " on a version older than " + result.targetVersion();
				status = "Sent to " + who + " (" + result.online() + " online now). They see it when their mod next checks in, within about 15 minutes, "
						+ "if their mod is new enough to show notices."
						+ (result.unknownNames() != null && !result.unknownNames().isEmpty() ? " Not found: " + String.join(", ", result.unknownNames()) + "." : "");
				statusColor = GOOD;
			}
			clearAndInit();
		}));
	}

	@Override
	public void close() {
		client.setScreen(parent);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		super.render(context, mouseX, mouseY, deltaTicks);
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 14, 0xFFFFFFFF);
		int x = width / 2 - WIDTH / 2;
		int y = fieldsTop() + 26;
		if (specific) y += 26;
		context.drawTextWithShadow(textRenderer, "Message (one line, shown in chat):", x, y, LABEL);
		y = fieldsTop() + 26 + (specific ? 26 : 0) + 12 + 26 + 28;
		if (status != null) {
			for (String line : Ui.wrap(textRenderer, status, WIDTH)) {
				context.drawTextWithShadow(textRenderer, line, x, y, statusColor);
				y += 12;
			}
		} else {
			for (String line : Ui.wrap(textRenderer, specific ? "Only the players named get it." : "Everyone on a version older than the newest one in use gets it, once.", WIDTH)) {
				context.drawTextWithShadow(textRenderer, line, x, y, LABEL);
				y += 12;
			}
		}
	}
}
