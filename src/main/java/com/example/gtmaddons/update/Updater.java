package com.example.gtmaddons.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the mod up to date from the GitHub releases of Chuck015/GTMAddOns.
 *
 * On launch it asks GitHub for the latest release (once; no request to our backend). A release
 * carries one jar per Minecraft version, named gtmaddons-MCVERSION-MODVERSION.jar; the one for
 * this game's version is picked. If it is newer than this mod:
 *   - normally the player is told, and /gao update or the Update button in /gao downloads it;
 *   - if the release notes contain AUTO_MARKER, or the backend says this version is too old to
 *     upload stats (see onUpdateRequired), it is downloaded straight away.
 * The download sits next to the running jar as NAME.pending (Fabric ignores it) and is checked
 * against GitHub's SHA-256. When the game closes, the old jar is removed and the new one put in
 * its place: on Windows the running jar is locked, so a small hidden PowerShell script waits for
 * the game to exit and does the swap. If that swap never happened (the next launch still finds
 * the .pending file), it is tried again at the next exit.
 *
 * Nothing here uses Minecraft classes, so this file is identical in every version of the mod.
 * The game side shows takeNotice() in chat and the state on the /gao screen.
 */
public final class Updater {

	public static final Updater INSTANCE = new Updater();

	/** In a release's notes, makes every copy of the mod download it without asking. */
	public static final String AUTO_MARKER = "[auto-update]";

	private static final Logger LOGGER = LoggerFactory.getLogger("GTMAddOns");
	private static final String RELEASES = "https://api.github.com/repos/Chuck015/GTMAddOns/releases?per_page=30";
	private static final String PENDING_SUFFIX = ".pending";

	public enum State { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, READY, FAILED }

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	private volatile State state = State.IDLE;
	private volatile String latestVersion;
	private volatile String notice;
	private volatile boolean required;
	private String assetUrl, assetName, assetSha256;
	private Path pending;
	private boolean installScheduled;
	/** GitHub's ETag for the release list: a repeat check with it costs nothing when nothing changed (HTTP 304). */
	private String etag;
	/** The version the player was last told about, so a re-check doesn't repeat the message. */
	private String notifiedVersion;
	private volatile long lastCheckNanos = 0L;
	private ScheduledExecutorService scheduler;
	/** While the game runs: how often to look again, and the shortest gap when /gao is opened. */
	private static final long RECHECK_MINUTES = 30, RECHECK_JITTER_MINUTES = 8;
	private static final long OPEN_RECHECK_NANOS = 5L * 60 * 1_000_000_000L;

	private Updater() {}

	// ---- What the game side reads ----

	public State state() {
		return state;
	}

	public String latestVersion() {
		return latestVersion;
	}

	/** A line to show in chat, once (null if none). */
	public String takeNotice() {
		String n = notice;
		notice = null;
		return n;
	}

	/** This mod's version, e.g. "1.1.0". Also sent to the backend with every request. */
	public static String modVersion() {
		return FabricLoader.getInstance().getModContainer("gtmaddons")
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("0");
	}

	/** The game's version, e.g. "1.21.11" - which jar of a release is ours. */
	public static String minecraftVersion() {
		return FabricLoader.getInstance().getModContainer("minecraft")
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
	}

	// ---- Start ----

	/** Once, at startup: finish off an earlier update, then look for a new one in the background. */
	public void start() {
		Thread thread = new Thread(() -> {
			try {
				resumeEarlierDownload();
				check();
			} catch (Throwable t) {
				LOGGER.warn("GTMAddOns: update check failed: {}", t.toString());
				if (state == State.IDLE || state == State.CHECKING) state = State.FAILED;
			}
			scheduleRecheck();
		}, "GTMAddOns updater");
		thread.setDaemon(true);
		thread.start();
	}

	/**
	 * A game left open for hours would never hear about a release made after it started, so look
	 * again every half hour or so (a little random, so players don't all ask at the same moment).
	 */
	private synchronized void scheduleRecheck() {
		if (scheduler == null) {
			scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
				Thread t = new Thread(r, "GTMAddOns updater");
				t.setDaemon(true);
				return t;
			});
		}
		long minutes = RECHECK_MINUTES + ThreadLocalRandom.current().nextLong(-RECHECK_JITTER_MINUTES, RECHECK_JITTER_MINUTES + 1);
		scheduler.schedule(() -> {
			try {
				check();
			} catch (Throwable t) {
				LOGGER.info("GTMAddOns: update re-check failed: {}", t.toString());
			}
			scheduleRecheck();
		}, minutes, TimeUnit.MINUTES);
	}

	/** Opening the /gao menu: look again if the last check was a while ago (never while downloading or ready). */
	public void recheckSoon() {
		if (state == State.DOWNLOADING || state == State.READY || state == State.IDLE || state == State.CHECKING) return;
		if (System.nanoTime() - lastCheckNanos < OPEN_RECHECK_NANOS) return;
		lastCheckNanos = System.nanoTime();
		Thread thread = new Thread(() -> {
			try {
				check();
			} catch (Throwable t) {
				LOGGER.info("GTMAddOns: update re-check failed: {}", t.toString());
			}
		}, "GTMAddOns updater");
		thread.setDaemon(true);
		thread.start();
	}

	/** A .pending jar from last time (the swap at exit didn't run): keep it if it's still newer. */
	private void resumeEarlierDownload() throws Exception {
		Path jar = currentJar();
		if (jar == null) return;
		try (DirectoryStream<Path> files = Files.newDirectoryStream(jar.getParent(), "gtmaddons-*" + PENDING_SUFFIX)) {
			for (Path file : files) {
				String name = file.getFileName().toString();
				String version = versionInAssetName(name.substring(0, name.length() - PENDING_SUFFIX.length()));
				if (version != null && compare(version, modVersion()) > 0 && pending == null) {
					synchronized (this) {
						pending = file;
						latestVersion = version;
						state = State.READY;
					}
					scheduleInstall();
					notice = "Update " + version + " is downloaded but not installed yet - close the game fully to install it.";
				} else {
					Files.deleteIfExists(file);
				}
			}
		}
	}

	private void check() throws Exception {
		if (state == State.READY || state == State.DOWNLOADING) return;
		lastCheckNanos = System.nanoTime();
		// The first check shows "checking"; later ones leave the state alone so the Update button doesn't flicker.
		if (state == State.IDLE) state = State.CHECKING;
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(RELEASES))
				.timeout(Duration.ofSeconds(15))
				.header("Accept", "application/vnd.github+json")
				.header("User-Agent", "GTMAddOns/" + modVersion());
		if (etag != null) request.header("If-None-Match", etag);
		HttpResponse<String> response = http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() == 304) return; // nothing changed since the last look
		if (response.statusCode() != 200) {
			LOGGER.info("GTMAddOns: no release info (HTTP {})", response.statusCode());
			if (state == State.CHECKING) state = State.FAILED;
			return;
		}
		etag = response.headers().firstValue("ETag").orElse(null);
		// The releases are one per Minecraft version (tag = the version), so look through them all
		// and take the highest version that has a jar for this game. A jar is ours if its name starts
		// with gtmaddons-<minecraft version>- , or it is in the release tagged with that version.
		String mc = minecraftVersion();
		String bestVersion = null, bestName = null, bestUrl = null, bestSha = null, bestBody = "";
		for (JsonElement releaseElement : JsonParser.parseString(response.body()).getAsJsonArray()) {
			JsonObject release = releaseElement.getAsJsonObject();
			if (release.get("draft").getAsBoolean() || release.get("prerelease").getAsBoolean()) continue;
			String tag = release.has("tag_name") && !release.get("tag_name").isJsonNull() ? release.get("tag_name").getAsString() : "";
			JsonArray assets = release.has("assets") ? release.getAsJsonArray("assets") : new JsonArray();
			for (JsonElement element : assets) {
				JsonObject asset = element.getAsJsonObject();
				String name = asset.get("name").getAsString();
				boolean ours = name.startsWith("gtmaddons-" + mc + "-") || (tag.equals(mc) && name.startsWith("gtmaddons-"));
				String version = ours ? versionInAssetName(name) : null;
				if (version == null || (bestVersion != null && compare(version, bestVersion) <= 0)) continue;
				bestVersion = version;
				bestName = name;
				bestUrl = asset.get("browser_download_url").getAsString();
				String digest = asset.has("digest") && !asset.get("digest").isJsonNull() ? asset.get("digest").getAsString() : null;
				bestSha = digest != null && digest.startsWith("sha256:") ? digest.substring(7).toLowerCase(Locale.ROOT) : null;
				bestBody = release.has("body") && !release.get("body").isJsonNull() ? release.get("body").getAsString() : "";
			}
		}
		if (bestVersion == null || compare(bestVersion, modVersion()) <= 0) {
			state = State.UP_TO_DATE;
			return;
		}
		synchronized (this) {
			latestVersion = bestVersion;
			assetName = bestName;
			assetUrl = bestUrl;
			assetSha256 = bestSha;
			state = State.AVAILABLE;
		}
		if (bestBody.contains(AUTO_MARKER) || required) {
			download();
		} else if (!bestVersion.equals(notifiedVersion)) {
			notifiedVersion = bestVersion;
			notice = "GTMAddOns " + bestVersion + " is out (you have " + modVersion() + "). Run /gao update or click Update in /gao.";
		}
	}

	/**
	 * The backend refused an upload because this version is too old. Fetch the update now,
	 * without asking; fights stay queued and upload after the restart.
	 */
	public void onUpdateRequired() {
		if (required) return;
		required = true;
		notice = "This GTMAddOns version is too old to upload stats - updating. Your fights are kept and upload after a restart.";
		if (state == State.AVAILABLE) new Thread(this::download, "GTMAddOns updater").start();
	}

	// ---- Download ----

	/** /gao update and the Update button: download if there's an update, else say why not. */
	public void requestUpdate() {
		State s = state;
		if (s == State.AVAILABLE || (s == State.FAILED && assetUrl != null)) {
			notice = "Downloading GTMAddOns " + latestVersion + "...";
			downloadAsync();
		} else if (s == State.DOWNLOADING) {
			notice = "Already downloading the update.";
		} else if (s == State.READY) {
			notice = "GTMAddOns " + latestVersion + " is ready - close the game to install it.";
		} else if (s == State.FAILED) {
			notice = "Couldn't reach GitHub to check for updates.";
		} else if (s == State.UP_TO_DATE) {
			notice = "You're on the latest version (" + modVersion() + ").";
		} else {
			notice = "Still checking for updates - try again in a moment.";
		}
	}

	/** Downloads the new jar (for /gao update and the Update button). Safe to call from any thread. */
	public void downloadAsync() {
		if (state != State.AVAILABLE && state != State.FAILED) return;
		Thread thread = new Thread(this::download, "GTMAddOns updater");
		thread.setDaemon(true);
		thread.start();
	}

	private void download() {
		String url, name, sha;
		synchronized (this) {
			if (assetUrl == null || state == State.DOWNLOADING || state == State.READY) return;
			url = assetUrl;
			name = assetName;
			sha = assetSha256;
			state = State.DOWNLOADING;
		}
		try {
			Path jar = currentJar();
			if (jar == null) throw new IllegalStateException("can't find the mod's jar (a dev build?)");
			Path target = jar.resolveSibling(name + PENDING_SUFFIX);
			Path part = jar.resolveSibling(name + ".part");
			if (!name.startsWith("gtmaddons-" + minecraftVersion() + "-")) {
				String renamed = "gtmaddons-" + minecraftVersion() + "-" + latestVersion + ".jar";
				target = jar.resolveSibling(renamed + PENDING_SUFFIX);
				part = jar.resolveSibling(renamed + ".part");
			}
			HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder(URI.create(url))
					.timeout(Duration.ofMinutes(2))
					.header("User-Agent", "GTMAddOns/" + modVersion())
					.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
			if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode());
			try (InputStream in = response.body()) {
				Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
			}
			if (sha != null) {
				String got = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(part)));
				if (!got.equals(sha)) {
					Files.deleteIfExists(part);
					throw new IllegalStateException("download was corrupted (checksum mismatch)");
				}
			}
			Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
			synchronized (this) {
				pending = target;
				state = State.READY;
			}
			scheduleInstall();
			notice = "GTMAddOns " + latestVersion + " downloaded - it's installed when you close the game.";
		} catch (Exception e) {
			LOGGER.warn("GTMAddOns: update download failed: {}", e.toString());
			state = State.FAILED;
			notice = "Update download failed: " + e.getMessage();
		}
	}

	// ---- Install (when the game closes) ----

	private synchronized void scheduleInstall() {
		if (installScheduled) return;
		installScheduled = true;
		// Also on a crash or a closed window: the JVM's shutdown hook still runs.
		Runtime.getRuntime().addShutdownHook(new Thread(this::installNow, "GTMAddOns update install"));
	}

	/** Called as the game shuts down: put the downloaded jar in place of the running one. */
	public synchronized void installNow() {
		if (pending == null || !Files.exists(pending)) return;
		Path jar = currentJar();
		if (jar == null) return;
		String pendingName = pending.getFileName().toString();
		Path finalJar = pending.resolveSibling(pendingName.substring(0, pendingName.length() - PENDING_SUFFIX.length()));
		try {
			if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
				// Windows keeps the running jar locked: swap it once this process has exited.
				String script = "$p = " + ProcessHandle.current().pid() + "\n"
						+ "while (Get-Process -Id $p -ErrorAction SilentlyContinue) { Start-Sleep -Milliseconds 250 }\n"
						+ "for ($i = 0; $i -lt 40 -and (Test-Path -LiteralPath " + ps(jar) + "); $i++) {\n"
						+ "  try { Remove-Item -LiteralPath " + ps(jar) + " -Force -ErrorAction Stop } catch { Start-Sleep -Milliseconds 250 }\n"
						+ "}\n"
						+ "if (-not (Test-Path -LiteralPath " + ps(jar) + ")) { Move-Item -LiteralPath " + ps(pending)
						+ " -Destination " + ps(finalJar) + " -Force }\n";
				String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
				new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand", encoded)
						.redirectErrorStream(true)
						.redirectOutput(ProcessBuilder.Redirect.DISCARD)
						.start();
			} else {
				// Other systems allow replacing a file that's in use.
				Files.deleteIfExists(jar);
				Files.move(pending, finalJar, StandardCopyOption.REPLACE_EXISTING);
			}
			pending = null;
		} catch (Exception e) {
			LOGGER.warn("GTMAddOns: couldn't install the update: {}", e.toString());
		}
	}

	private static String ps(Path path) {
		return "'" + path.toAbsolutePath().toString().replace("'", "''") + "'";
	}

	// ---- Helpers ----

	/** The jar this mod was loaded from, or null when it isn't a jar file (running from the IDE). */
	static Path currentJar() {
		Optional<ModContainer> mod = FabricLoader.getInstance().getModContainer("gtmaddons");
		if (mod.isEmpty() || mod.get().getOrigin().getKind() != net.fabricmc.loader.api.metadata.ModOrigin.Kind.PATH) return null;
		for (Path path : mod.get().getOrigin().getPaths()) {
			if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar")) return path;
		}
		return null;
	}

	/** "gtmaddons-1.21.11-1.2.0.jar" -> "1.2.0" (the part after the last dash). */
	static String versionInAssetName(String name) {
		if (!name.endsWith(".jar")) return null;
		String stem = name.substring(0, name.length() - 4);
		int dash = stem.lastIndexOf('-');
		String version = dash >= 0 ? stem.substring(dash + 1) : null;
		return version != null && version.matches("\\d+(\\.\\d+)*") ? version : null;
	}

	/** Compares dotted version numbers: "1.10.0" > "1.9.2". */
	public static int compare(String a, String b) {
		String[] x = a.split("[^0-9]+"), y = b.split("[^0-9]+");
		for (int i = 0; i < Math.max(x.length, y.length); i++) {
			long p = i < x.length && !x[i].isEmpty() ? Long.parseLong(x[i]) : 0;
			long q = i < y.length && !y[i].isEmpty() ? Long.parseLong(y[i]) : 0;
			if (p != q) return Long.compare(p, q);
		}
		return 0;
	}
}
