package com.example.gtmaddons.stats;

import com.example.gtmaddons.stats.PlayerStats.FightData;
import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A copy of the fights already read from the backend, per player, in config/gtmaddons-cache/<uuid>.json.
 *
 * A stored fight never changes, so the fight log and custom filters only need to ask for the fights stored after the
 * last one held here (GET /players/:uuid/fights?since=cursor, see StatsClient.fetchFights) instead of reading a whole
 * history (up to 500 fights per PvP category) every time a player's page is opened.
 *
 * It holds nothing that any logged-in player cannot already read. Two things can remove fights on the backend:
 *   - a new fight pushing the oldest out past the limit: merge() applies the same rule here (newest maxFights per category);
 *   - an admin deleting data: the backend then changes the player's "epoch", and the copy is thrown away (StatsClient).
 * Any trouble reading or writing a file just means the history is read in full again.
 */
public final class FightCache {

	/** Raise when FightData (or anything else stored here) changes shape, so old files are ignored. */
	private static final int SCHEMA = 2;
	/** Players kept; the ones not looked at for the longest go first. */
	private static final int MAX_PLAYERS = 40;

	private static final Gson GSON = new GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create();

	/** What is kept for one player: where to ask from next (cursor), and their fights newest first. */
	public record Entry(int schema, long epoch, long cursor, String uuid, String name, long firstSeen, long lastSeen, List<FightData> fights) {}

	/**
	 * One answer to GET /players/:uuid/fights?since=cursor: up to a page of fights stored after `since`, oldest first;
	 * `more` says to ask again from `cursor`. A backend from before this sends none of epoch / maxFights / cursor / more
	 * (they read as 0 and false) and all of the player's fights at once.
	 */
	public record Page(String uuid, String name, long firstSeen, long lastSeen, long epoch, int maxFights, long cursor, boolean more, List<FightData> fights) {}

	private FightCache() {}

	private static Path dir() {
		return FabricLoader.getInstance().getConfigDir().resolve("gtmaddons-cache");
	}

	private static Path file(String uuid) {
		return dir().resolve(uuid + ".json");
	}

	/** The copy held for this player, or null (none, unreadable, or from another version of the mod). */
	public static Entry load(String uuid) {
		if (uuid == null || !uuid.matches("[0-9a-f]{32}")) return null;
		try {
			Path file = file(uuid);
			if (!Files.isRegularFile(file)) return null;
			Entry entry = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Entry.class);
			if (entry == null || entry.schema() != SCHEMA || entry.fights() == null || !uuid.equals(entry.uuid())) return null;
			return entry;
		} catch (Exception e) {
			return null;
		}
	}

	public static Entry entry(Page page, long cursor, List<FightData> fights) {
		return new Entry(SCHEMA, page.epoch(), cursor, page.uuid(), page.name(), page.firstSeen(), page.lastSeen(), fights);
	}

	/** Writes the copy (to a temporary file first, so a crash cannot leave half of one), then drops the oldest players past the limit. */
	public static void save(Entry entry) {
		if (entry.uuid() == null || !entry.uuid().matches("[0-9a-f]{32}")) return;
		try {
			Files.createDirectories(dir());
			Path file = file(entry.uuid()), temp = dir().resolve(entry.uuid() + ".tmp");
			Files.writeString(temp, GSON.toJson(entry), StandardCharsets.UTF_8);
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
			prune();
		} catch (Exception e) {
			// no copy: the next visit reads everything again
		}
	}

	public static void forget(String uuid) {
		if (uuid == null || !uuid.matches("[0-9a-f]{32}")) return;
		try {
			Files.deleteIfExists(file(uuid));
		} catch (IOException e) {
			// see save
		}
	}

	private static void prune() throws IOException {
		List<Path> files;
		try (Stream<Path> list = Files.list(dir())) {
			files = new ArrayList<>(list.filter(p -> p.getFileName().toString().endsWith(".json")).toList());
		}
		if (files.size() <= MAX_PLAYERS) return;
		Map<Path, Long> modified = new HashMap<>();
		for (Path p : files) modified.put(p, Files.getLastModifiedTime(p).toMillis());
		files.sort(Comparator.comparingLong(modified::get));
		for (int i = 0; i < files.size() - MAX_PLAYERS; i++) Files.deleteIfExists(files.get(i));
	}

	/**
	 * The fights held plus the ones just read, as the backend keeps them: each fight once (the later copy wins), newest
	 * first, and only the newest maxFights of each PvP category.
	 */
	public static List<FightData> merge(List<FightData> held, List<FightData> fresh, int maxFights) {
		Map<String, FightData> byKey = new LinkedHashMap<>();
		for (FightData f : held) if (f != null && f.fightKey() != null) byKey.put(f.fightKey(), f);
		for (FightData f : fresh) if (f != null && f.fightKey() != null) byKey.put(f.fightKey(), f);
		List<FightData> all = new ArrayList<>(byKey.values());
		all.sort(Comparator.comparingLong(FightData::endedAt).thenComparingLong(f -> f.id() != null ? f.id() : 0L).reversed());
		Map<String, Integer> perCategory = new HashMap<>();
		List<FightData> kept = new ArrayList<>(all.size());
		for (FightData f : all) {
			int n = perCategory.merge(f.category() != null ? f.category() : "", 1, Integer::sum);
			if (n <= maxFights) kept.add(f);
		}
		return kept;
	}
}
