package com.example.gtmaddons;

import com.example.gtmaddons.gun.DevFilter;
import com.example.gtmaddons.gun.DevLogger;
import com.example.gtmaddons.gun.ShotResult;
import com.example.gtmaddons.stats.SwapRecord;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stats are only recorded during fights. A fight starts when GTM's combat
 * tag starts (see CombatTracker) and ends with your kill or your death:
 *
 *   Kill:  "[GTM] You killed X! $X was dropped on the ground!"
 *   Death: GTM's "WASTED" title (shown only when you die; the subtitle
 *          names the killer) - the main signal, since GTM has many death
 *          messages ("baked like a potato", "a preview of hell"...). Also
 *          "<you> was killed/murdered/shanked by X!", "<you> took a trip
 *          through an oven", "<you> got his ass kicked by X!", GTM's "He got
 *          you good." message, or actually dying.
 *
 * Swaps, shots and melee combos made during the fight are held here, then
 * handed to the listeners (stats upload, Personal Stats' recent views) when
 * it ends. If the combat tag ends without a kill or death, the fight is
 * dropped and nothing from it is kept. After a kill while still tagged, a
 * new fight starts straight away.
 *
 * Call onMessage and onFrame before CombatTracker's, so a death is seen
 * before it clears the tag.
 */
public final class FightTracker {

	public static final FightTracker INSTANCE = new FightTracker();

	private static final Pattern KILL = Pattern.compile("\\[GTM\\]\\s*You killed ([A-Za-z0-9_]{1,16})!", Pattern.CASE_INSENSITIVE);
	/** Death messages naming a killer, after your name. */
	private static final String DEATH_BY = " (?:was killed by|was murdered by|was shanked by|got \\w+ ass kicked by) ([A-Za-z0-9_]{1,16})";
	/** The title GTM shows when you die - and only then (our dev log, 14 of 14). */
	private static final String DEATH_TITLE = "WASTED";
	/**
	 * The killer in the subtitle under it: "sntrice killed you", "Killed by
	 * sntrice", "Shanked by sntrice", "Rekt by PhillyRoll", "2Good4God clapped yo ass".
	 */
	private static final Pattern DEATH_SUBTITLE = Pattern.compile(
			"(?:\\bby ([A-Za-z0-9_]{1,16}))|(?:^\\s*([A-Za-z0-9_]{1,16}) (?:killed|clapped|shanked|murdered|rekt))",
			Pattern.CASE_INSENSITIVE);
	/** How long to wait after WASTED for the subtitle naming the killer. */
	private static final long SUBTITLE_WAIT_NANOS = 250_000_000L;
	/** Death messages without a killer, after your name. */
	private static final String DEATH_ALONE = " took a trip through an oven";

	/**
	 * A finished fight: everything recorded between combat tag and kill/death.
	 * category is the PvpCategory you were in when it started - each stats
	 * tab shows only that kind of fight.
	 */
	public record Fight(String key, long startedAt, long endedAt, String outcome, String opponent, PvpCategory category,
			List<SwapRecord> swaps, List<ShotResult> shots, List<ComboTracker.ComboResult> combos) {}

	private static final class Current {
		final long startedAt = System.currentTimeMillis();
		final PvpCategory category;

		Current(PvpCategory category) {
			this.category = category;
		}

		final List<SwapRecord> swaps = new ArrayList<>();
		final List<ShotResult> shots = new ArrayList<>();
		final List<ComboTracker.ComboResult> combos = new ArrayList<>();
	}

	/** A fight that has ended but is held briefly, so shot results still on their way can join it. */
	private static final class Closing {
		final Current fight;
		final String outcome, opponent;
		final long endedAt, nanos;

		Closing(Current fight, String outcome, String opponent, long endedAt, long nanos) {
			this.fight = fight;
			this.outcome = outcome;
			this.opponent = opponent;
			this.endedAt = endedAt;
			this.nanos = nanos;
		}
	}

	/**
	 * A shot's result is produced ~150 ms after it is fired (ShotTracker.SETTLE_NANOS), but the kill
	 * message that ends a fight arrives within a few ms of the killing shot - so a closed fight waits
	 * this long before it is handed over, and takes shots fired up to SHOT_GRACE_MILLIS after it ended.
	 */
	private static final long CLOSE_GRACE_NANOS = 400_000_000L;
	private static final long SHOT_GRACE_MILLIS = 100L;

	private final List<Consumer<Fight>> listeners = new ArrayList<>();
	private final List<Closing> closing = new ArrayList<>();
	private Current current = null;
	/** When WASTED showed, while waiting for the subtitle naming the killer (-1 = not waiting). */
	private long deathTitleNanos = -1L;
	private boolean wasTagged = false;
	/** Run just before a fight ends or is dropped, to add anything still being measured (a swap's momentum). */
	private Runnable beforeFightEnds = () -> {};

	private FightTracker() {}

	public void addListener(Consumer<Fight> listener) {
		listeners.add(listener);
	}

	public void setBeforeFightEnds(Runnable hook) {
		beforeFightEnds = hook;
	}

	public boolean inFight() {
		return current != null;
	}

	// ---- What gets recorded (only during a fight) ----

	public void addSwap(SwapRecord swap) {
		if (current != null) current.swaps.add(swap);
	}

	public void addShot(ShotResult shot) {
		// The killing shot (or the last ones before a death) finish after the fight closed.
		for (int i = closing.size() - 1; i >= 0; i--) {
			Closing c = closing.get(i);
			if (shot.timestampMillis() <= c.endedAt + SHOT_GRACE_MILLIS && shot.timestampMillis() >= c.fight.startedAt) {
				c.fight.shots.add(shot);
				return;
			}
		}
		if (current != null) current.shots.add(shot);
	}

	public void addCombo(ComboTracker.ComboResult combo) {
		if (current != null) current.combos.add(combo);
	}

	// ---- Start and end ----

	public void onFrame(MinecraftClient client) {
		long nowNanos = System.nanoTime();
		while (!closing.isEmpty() && nowNanos - closing.get(0).nanos >= CLOSE_GRACE_NANOS) finish(closing.remove(0));
		ClientPlayerEntity player = client.player;
		// WASTED with no subtitle in time: still a death, just no killer name.
		if (deathTitleNanos >= 0 && System.nanoTime() - deathTitleNanos > SUBTITLE_WAIT_NANOS) {
			deathTitleNanos = -1L;
			if (current != null) end("DEATH", null);
		}
		if (current != null && player != null && player.isDead()) end("DEATH", null);

		boolean tagged = CombatTracker.INSTANCE.isTagged();
		if (tagged && !wasTagged && current == null) start();
		// Tag over without a kill or death: not a finished fight, so drop it
		// (unless WASTED just showed - that death is still being settled).
		if (!tagged && current != null && deathTitleNanos < 0) drop("combat ended without a kill or death");
		wasTagged = tagged;
	}

	/**
	 * Titles. "WASTED" is GTM's death screen - the surest sign you died, with
	 * the killer in the subtitle that follows (see onSubtitle).
	 */
	public void onTitle(Text text) {
		if (text.getString().trim().equalsIgnoreCase(DEATH_TITLE)) deathTitleNanos = System.nanoTime();
	}

	public void onSubtitle(Text text) {
		if (deathTitleNanos < 0) return;
		deathTitleNanos = -1L;
		if (current == null) return;
		Matcher m = DEATH_SUBTITLE.matcher(text.getString());
		String killer = m.find() ? (m.group(1) != null ? m.group(1) : m.group(2)) : null;
		end("DEATH", killer);
	}

	/** Chat messages (not the action bar). */
	public void onMessage(Text text) {
		if (current == null) return;
		String message = text.getString();

		Matcher kill = KILL.matcher(message);
		if (kill.find()) {
			end("KILL", kill.group(1));
			// Still tagged, so still fighting: that's the next fight.
			if (CombatTracker.INSTANCE.isTagged()) start();
			return;
		}

		String name = myName();
		if (name != null) {
			String me = "(?:^|[^A-Za-z0-9_])" + Pattern.quote(name);
			Matcher by = Pattern.compile(me + DEATH_BY, Pattern.CASE_INSENSITIVE).matcher(message);
			if (by.find()) {
				end("DEATH", by.group(1));
				return;
			}
			if (Pattern.compile(me + DEATH_ALONE, Pattern.CASE_INSENSITIVE).matcher(message).find()) {
				end("DEATH", null);
				return;
			}
		}
		if (message.toLowerCase(Locale.ROOT).contains("got you good")) end("DEATH", null);
	}

	private void start() {
		ClientPlayerEntity player = MinecraftClient.getInstance().player;
		PvpCategory category = player != null ? PvpCategory.classify(player) : PvpCategory.GROUND;
		current = new Current(category);
		if (DevLogger.INSTANCE.wants(DevFilter.FIGHTS)) DevLogger.chat("Fight started (" + category.label + ") - recording stats", Formatting.YELLOW);
	}

	private void end(String outcome, String opponent) {
		beforeFightEnds.run();
		closing.add(new Closing(current, outcome, opponent, System.currentTimeMillis(), System.nanoTime()));
		current = null;
	}

	/** Hands a closed fight to the listeners (stats upload, Personal Stats), once late shot results have joined it. */
	private void finish(Closing c) {
		Current fight = c.fight;
		String outcome = c.outcome, opponent = c.opponent;
		Fight done = new Fight(UUID.randomUUID().toString().replace("-", ""), fight.startedAt, c.endedAt,
				outcome, opponent, fight.category, List.copyOf(fight.swaps), List.copyOf(fight.shots), List.copyOf(fight.combos));
		if (DevLogger.INSTANCE.wants(DevFilter.FIGHTS)) {
			DevLogger.chat(String.format("Fight recorded (%s): %s%s | %.0fs | %d swaps, %d shots, %d combos",
					done.category().label, outcome.equals("KILL") ? "kill" : "death", opponent != null ? (outcome.equals("KILL") ? " on " : " by ") + opponent : "",
					(done.endedAt() - done.startedAt()) / 1000.0, done.swaps().size(), done.shots().size(), done.combos().size()),
					Formatting.GREEN);
		}
		for (Consumer<Fight> listener : listeners) listener.accept(done);
	}

	private void drop(String reason) {
		beforeFightEnds.run();
		current = null;
		if (DevLogger.INSTANCE.wants(DevFilter.FIGHTS)) DevLogger.chat("Fight dropped, nothing recorded: " + reason, Formatting.GRAY);
	}

	private static String myName() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player != null) return client.player.getName().getString();
		return client.getSession() != null ? client.getSession().getUsername() : null;
	}
}
