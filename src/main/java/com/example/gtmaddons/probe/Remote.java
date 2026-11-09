package com.example.gtmaddons.probe;

import com.example.gtmaddons.Settings;
import com.example.gtmaddons.stats.StatsClient;
import com.example.gtmaddons.update.Updater;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The remote config: a small JSON the backend serves at GET /config (kept in its remote_config table, changed without a mod or backend
 * release). It holds
 *   switches  on/off for trackers that are built into the mod (combo_stats, click_stats, aim_stats, move_states, probes);
 *   values    numbers the mod would otherwise hard-code (melee_range);
 *   patterns  regexes for GTM's own messages (combo_msg, recovering_msg), so a reworded message is fixed on the server;
 *   probes    tracking rules for the Probes engine (see Probes): data only, never code.
 * Fetched 5 s after start and every 30 min, cached in config/gtmaddons-remote.json so it applies offline and from the first fight.
 * Anything missing or broken falls back to the mod's own defaults. Probes only run while Settings.extraTracking is on.
 */
public final class Remote {

	private static final Logger LOGGER = LoggerFactory.getLogger("gtmaddons");
	private static final long REFRESH_MINUTES = 30;
	private static final int MAX_BYTES = 64 * 1024;
	private static final int MAX_PATTERN_LENGTH = 200;

	/** One parsed config. Never changed after parsing; replaced whole. */
	public static final class Config {
		static final Config EMPTY = new Config();
		int version;
		final Map<String, Boolean> switches = new HashMap<>();
		final Map<String, Double> values = new HashMap<>();
		final Map<String, Pattern> patterns = new HashMap<>();
		List<Probes.Rule> rules = List.of();

		public int version() {
			return version;
		}

		public List<Probes.Rule> rules() {
			return rules;
		}
	}

	private static volatile Config current = Config.EMPTY;
	private static Settings settings;
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private static ScheduledExecutorService worker;

	private Remote() {}

	public static void init(Settings modSettings) {
		settings = modSettings;
		try {
			Path cache = cacheFile();
			if (Files.exists(cache)) apply(Files.readString(cache, StandardCharsets.UTF_8));
		} catch (Exception e) {
			LOGGER.warn("GTMAddOns: cached remote config unreadable: {}", e.toString());
		}
		if (!StatsClient.isConfigured()) return;
		worker = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "GTMAddOns remote config");
			t.setDaemon(true);
			return t;
		});
		worker.scheduleWithFixedDelay(Remote::refresh, 5, REFRESH_MINUTES * 60, TimeUnit.SECONDS);
	}

	private static Path cacheFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("gtmaddons-remote.json");
	}

	private static void refresh() {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(StatsClient.BACKEND_URL + "/config"))
					.timeout(Duration.ofSeconds(15))
					.header("X-GTMAddOns-Version", Updater.modVersion())
					.GET().build();
			HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200 || response.body().length() > MAX_BYTES) return;
			if (apply(response.body())) Files.writeString(cacheFile(), response.body(), StandardCharsets.UTF_8);
		} catch (Throwable e) {
			// Never let it escape: it would cancel the schedule. Offline is normal; the cached config stays.
			LOGGER.debug("GTMAddOns: remote config fetch failed: {}", e.toString());
		}
	}

	/** Parses and switches to a config; false (and the old one kept) if it is not a JSON object. */
	static boolean apply(String text) {
		try {
			JsonElement root = JsonParser.parseString(text);
			if (!root.isJsonObject()) return false;
			current = parse(root.getAsJsonObject());
			return true;
		} catch (Exception e) {
			LOGGER.warn("GTMAddOns: bad remote config: {}", e.toString());
			return false;
		}
	}

	private static Config parse(JsonObject o) {
		Config c = new Config();
		if (o.has("version") && o.get("version").isJsonPrimitive()) c.version = o.get("version").getAsInt();
		if (o.has("switches") && o.get("switches").isJsonObject()) {
			for (var e : o.getAsJsonObject("switches").entrySet()) {
				if (e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isBoolean()) c.switches.put(e.getKey(), e.getValue().getAsBoolean());
			}
		}
		if (o.has("values") && o.get("values").isJsonObject()) {
			for (var e : o.getAsJsonObject("values").entrySet()) {
				if (e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isNumber()) {
					double v = e.getValue().getAsDouble();
					if (Double.isFinite(v)) c.values.put(e.getKey(), v);
				}
			}
		}
		if (o.has("patterns") && o.get("patterns").isJsonObject()) {
			for (var e : o.getAsJsonObject("patterns").entrySet()) {
				try {
					String re = e.getValue().getAsString();
					if (re.length() <= MAX_PATTERN_LENGTH) c.patterns.put(e.getKey(), Pattern.compile(re, Pattern.CASE_INSENSITIVE));
				} catch (Exception ignored) {
					// A broken pattern is left out: the mod's own one is used.
				}
			}
		}
		if (o.has("probes") && o.get("probes").isJsonArray()) {
			List<Probes.Rule> rules = new ArrayList<>();
			for (JsonElement e : o.getAsJsonArray("probes")) {
				if (rules.size() >= Probes.MAX_RULES) break;
				Probes.Rule rule = e.isJsonObject() ? Probes.Rule.parse(e.getAsJsonObject()) : null;
				if (rule != null && rule.forThisMod() && rules.stream().noneMatch(r -> r.id.equals(rule.id))) rules.add(rule);
			}
			c.rules = Collections.unmodifiableList(rules);
		}
		return c;
	}

	public static Config config() {
		return current;
	}

	/** A built-in tracker's switch: on unless the config turns it off. */
	public static boolean on(String name, boolean fallback) {
		return current.switches.getOrDefault(name, fallback);
	}

	public static double num(String name, double fallback) {
		return current.values.getOrDefault(name, fallback);
	}

	public static Pattern pattern(String name, Pattern fallback) {
		return current.patterns.getOrDefault(name, fallback);
	}

	/** Whether probe rules run: the player's own setting (Mods > Misc > Extra tracking) and the config's master switch. */
	public static boolean probesOn() {
		return settings != null && settings.extraTracking && on("probes", true);
	}
}
