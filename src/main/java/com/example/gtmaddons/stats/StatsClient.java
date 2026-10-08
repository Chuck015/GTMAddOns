package com.example.gtmaddons.stats;

import com.example.gtmaddons.update.Updater;
import com.example.gtmaddons.ComboTracker;
import com.example.gtmaddons.FightTracker;
import com.example.gtmaddons.PvpCategory;
import com.example.gtmaddons.gun.ShotResult;
import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.session.Session;
import net.minecraft.network.encryption.PlayerKeyPair;
import net.minecraft.network.encryption.PlayerPublicKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Talks to the GTMAddOns stats backend (see swapinfo-backend/).
 *
 * Finished fights (see FightTracker) are queued and uploaded one at a time
 * from a background thread, so nothing here ever blocks the game. Logging in proves account ownership
 * with the Mojang-issued profile key Minecraft uses to sign chat: the
 * backend hands out a one-time challenge, we sign it with the key's
 * private half, and the backend checks that signature plus Mojang's
 * certificate for the key. (Mojang's session server blocks requests from
 * Cloudflare, so the usual joinServer/hasJoined check can't be used.)
 *
 * All state (queue, token) is only touched on the single worker thread.
 */
public final class StatsClient {

	/** Your deployed Worker's URL - see swapinfo-backend/README.md. */
	public static final String BACKEND_URL = "https://swapinfo-backend.swapinfo-backend.workers.dev";

	private static final Logger LOGGER = LoggerFactory.getLogger("gtmaddons");
	private static final Gson GSON = new GsonBuilder()
			.setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
			.create();

	private static final long FLUSH_INTERVAL_SECONDS = 15;
	private static final long LOGIN_RETRY_MS = 60_000;
	/** Finished fights waiting to upload; the oldest are dropped past this. */
	private static final int MAX_QUEUED = 50;

	/** A finished fight waiting to be uploaded, and the account that fought it. */
	private record Pending(UUID account, FightTracker.Fight fight) {}

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread thread = new Thread(r, "GTMAddOns Stats");
		thread.setDaemon(true);
		return thread;
	});

	private final List<Pending> queue = new ArrayList<>();
	// The login token and which account it's for. Switching accounts in-game
	// means logging in again, so fights land on the account that fought them.
	private String token;
	private UUID tokenAccount;
	private UUID lastLoginAttemptAccount;
	private long nextLoginAttemptMillis;

	public static boolean isConfigured() {
		return !BACKEND_URL.contains("YOUR-SUBDOMAIN");
	}

	public void start() {
		if (!isConfigured()) {
			LOGGER.warn("GTMAddOns: StatsClient.BACKEND_URL isn't set, so stats won't be uploaded.");
			return;
		}
		worker.scheduleWithFixedDelay(this::flush, FLUSH_INTERVAL_SECONDS, FLUSH_INTERVAL_SECONDS, TimeUnit.SECONDS);
		worker.scheduleWithFixedDelay(this::presenceTick, 20, 10, TimeUnit.SECONDS);
	}

	/**
	 * Queues a finished fight (see FightTracker) under whichever account is
	 * signed in right now. Stats are only recorded per fight.
	 */
	public void submitFight(FightTracker.Fight fight) {
		if (!isConfigured()) return;
		UUID account = currentAccount();
		if (account == null) return;
		worker.execute(() -> {
			queue.add(new Pending(account, fight));
			while (queue.size() > MAX_QUEUED) queue.removeFirst();
		});
	}

	/** Best-effort upload of anything still queued, e.g. when the game closes. */
	public void flushBlocking(long timeoutMillis) {
		if (!isConfigured()) return;
		try {
			worker.submit(this::flush).get(timeoutMillis, TimeUnit.MILLISECONDS);
		} catch (Exception e) {
			LOGGER.warn("GTMAddOns: final stats upload didn't finish: {}", e.toString());
		}
	}

	/** One player's stats over their last `fights` fights (25, 50 or 100) of one PvP category. */
	public CompletableFuture<PlayerStats.PlayerDetail> fetchPlayer(String uuid, int fights, PvpCategory category) {
		return get("/players/" + uuid + "?fights=" + fights + "&category=" + category.name(), PlayerStats.PlayerDetail.class);
	}

	/**
	 * One player's raw last 100 fights (all PvP categories), for the fight log
	 * and custom filters. Needs the backend's /players/:uuid/fights route;
	 * an older backend answers HTTP 404.
	 */
	public CompletableFuture<PlayerStats.FightHistory> fetchFights(String uuid) {
		return get("/players/" + uuid + "/fights", PlayerStats.FightHistory.class);
	}

	/**
	 * Everyone's stats for one PvP category over their newest `fights` fights (1-100), counting only
	 * fights against `opponents` (names, any case) if that isn't empty. See Leaderboard for ranking them.
	 * Needs the backend's /leaderboard route; an older backend answers HTTP 404.
	 */
	public CompletableFuture<PlayerStats.LeaderboardData> fetchLeaderboard(PvpCategory category, int fights, java.util.Set<String> opponents) {
		String names = opponents.stream().filter(o -> !o.isEmpty()).sorted().collect(java.util.stream.Collectors.joining(","));
		return get("/leaderboard?category=" + category.name() + "&fights=" + fights
				+ (names.isEmpty() ? "" : "&opponents=" + java.net.URLEncoder.encode(names, StandardCharsets.UTF_8)), PlayerStats.LeaderboardData.class);
	}

	/** Admin mode: deletes ALL of a player's stats data. Only works for accounts in the backend's ADMIN_UUIDS. */
	public CompletableFuture<PlayerStats.DeleteResult> deletePlayerData(String uuid) {
		return call("DELETE", "/admin/players/" + uuid, PlayerStats.DeleteResult.class);
	}

	/** Admin mode: deletes one of a player's fights (by its fight key). Only works for admin accounts. */
	public CompletableFuture<PlayerStats.DeleteResult> deleteFight(String uuid, String fightKey) {
		return call("DELETE", "/admin/players/" + uuid + "/fights/" + fightKey, PlayerStats.DeleteResult.class);
	}

	/** ": reason" from the backend's {"error": "reason"} answer, or "". */
	private static String errorSuffix(String body) {
		try {
			JsonObject json = GSON.fromJson(body, JsonObject.class);
			return json != null && json.has("error") ? ": " + json.get("error").getAsString() : "";
		} catch (Exception e) {
			return "";
		}
	}

	/** Who the backend thinks we are, including whether we're allowed dev mode or admin mode. */
	public CompletableFuture<PlayerStats.Me> fetchMe() {
		return get("/me", PlayerStats.Me.class);
	}

	/** Admin mode: what the backend knows about a player, by name. Fails with HTTP 403 if this account is not an admin, 404 if unknown. */
	public CompletableFuture<PlayerStats.AdminPlayerInfo> fetchAdminPlayerInfo(String name) {
		return get("/admin/player-info?name=" + java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8), PlayerStats.AdminPlayerInfo.class);
	}

	/** Admin mode: every player with a warning flag (the backend keeps the list for 15 minutes). */
	public CompletableFuture<PlayerStats.FlaggedPlayers> fetchAdminFlagged() {
		return get("/admin/flagged", PlayerStats.FlaggedPlayers.class);
	}

	/** Admin mode: the minimum requirements of every flag, in words. */
	public CompletableFuture<PlayerStats.FlagRules> fetchAdminFlagRules() {
		return get("/admin/flag-rules", PlayerStats.FlagRules.class);
	}

	/**
	 * A player's complete stored data as plain lines (GET /players/:uuid/raw, see RawInfo). Asks for gzip: the reply is long and
	 * repeats itself, so this is a fraction of the size on the wire.
	 */
	public CompletableFuture<java.util.List<String>> fetchRaw(String uuid) {
		CompletableFuture<java.util.List<String>> future = new CompletableFuture<>();
		if (!isConfigured()) {
			future.completeExceptionally(new IOException("stats backend isn't configured"));
			return future;
		}
		UUID account = currentAccount();
		worker.execute(() -> {
			try {
				if (account == null) throw new IOException("no Minecraft account signed in");
				nextLoginAttemptMillis = 0;
				HttpResponse<String> response = authedRequest(account, "GET", "/players/" + uuid + "/raw", null, true);
				if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + errorSuffix(response.body()));
				future.complete(RawInfo.lines(GSON.fromJson(response.body(), JsonObject.class)));
			} catch (Throwable e) {
				future.completeExceptionally(e);
			}
		});
		return future;
	}

	private <T> CompletableFuture<T> get(String path, Class<T> type) {
		return call("GET", path, type);
	}

	/** A request (any method) that answers HTTP 200 with JSON of this type. Runs on the worker thread. */
	private <T> CompletableFuture<T> call(String method, String path, Class<T> type) {
		CompletableFuture<T> future = new CompletableFuture<>();
		if (!isConfigured()) {
			future.completeExceptionally(new IOException("stats backend isn't configured"));
			return future;
		}
		UUID account = currentAccount();
		worker.execute(() -> {
			try {
				if (account == null) throw new IOException("no Minecraft account signed in");
				// A failed login a moment ago shouldn't block someone opening
				// the screen on purpose - let them retry straight away.
				nextLoginAttemptMillis = 0;
				HttpResponse<String> response = authedRequest(account, method, path, null);
				if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + errorSuffix(response.body()));
				future.complete(GSON.fromJson(response.body(), type));
			} catch (Throwable e) {
				// Throwable, not Exception: an Error here would otherwise leave
				// the caller waiting for an answer that never comes.
				future.completeExceptionally(e);
			}
		});
		return future;
	}

	/**
	 * Uploads the signed-in account's finished fights, oldest first. Fights
	 * from another account (before a switch) stay queued until that account
	 * signs back in, since only it can prove they're its own.
	 */
	/** The backend refused this mod version (HTTP 426); uploads wait for the updated mod. */
	private volatile boolean updateRequired = false;

	private void flush() {
		if (updateRequired) return;
		try {
			UUID account = currentAccount();
			if (account == null) return;
			for (Pending pending : List.copyOf(queue)) {
				if (!pending.account().equals(account)) continue;
				HttpResponse<String> response = authedRequest(account, "POST", "/fights", fightJson(pending.fight()).toString());
				if (response.statusCode() == 400) {
					// The backend rejected it outright - retrying won't help.
					LOGGER.warn("GTMAddOns: backend rejected a fight: {}", response.body());
				} else if (response.statusCode() == 426) {
					// This version is too old for the backend: keep the fights for after the update.
					updateRequired = true;
					Updater.INSTANCE.onUpdateRequired();
					return;
				} else if (response.statusCode() != 200) {
					LOGGER.warn("GTMAddOns: fight upload failed (HTTP {}), will retry", response.statusCode());
					return;
				}
				queue.remove(pending);
			}
		} catch (Exception e) {
			// Never let an exception escape - it would cancel the scheduled flush.
			LOGGER.warn("GTMAddOns: stats upload failed, will retry: {}", e.toString());
		}
	}

	/**
	 * { fight_key, started_at, ended_at, outcome, opponent, category, swaps, guns, combos }.
	 * Shots are sent as totals per category and gun, combos as totals per
	 * category. fight_key lets the backend ignore a retried upload.
	 */
	private static JsonObject fightJson(FightTracker.Fight fight) {
		Map<String, GunTotals> guns = new LinkedHashMap<>();
		for (ShotResult shot : fight.shots()) {
			guns.computeIfAbsent(shot.category().name() + "|" + shot.gun(), k -> new GunTotals(shot.category().name(), shot.gun()))
					.add(shot);
		}
		Map<String, ComboTotals> combos = new LinkedHashMap<>();
		for (ComboTracker.ComboResult combo : fight.combos()) {
			combos.computeIfAbsent(combo.category().name(), ComboTotals::new).add(combo);
		}
		JsonObject body = new JsonObject();
		body.addProperty("fight_key", fight.key());
		body.addProperty("started_at", fight.startedAt());
		body.addProperty("ended_at", fight.endedAt());
		body.addProperty("outcome", fight.outcome());
		body.addProperty("category", fight.category().name());
		if (fight.opponent() != null) body.addProperty("opponent", fight.opponent());
		if (fight.opponentCategory() != null) body.addProperty("opponent_category", fight.opponentCategory());
		if (fight.opponentGear() != null) body.addProperty("opponent_gear", fight.opponentGear());
		if (fight.movementInput() != null) body.add("movement_input", fight.movementInput().toJson());
		body.add("swaps", GSON.toJsonTree(fight.swaps()));
		body.add("guns", GSON.toJsonTree(List.copyOf(guns.values())));
		body.add("combos", GSON.toJsonTree(List.copyOf(combos.values())));
		return body;
	}

	/**
	 * Combo totals for one PvP category in a fight, serialized as
	 * { category, enemy_combos, enemy_broken, own_combos, own_broken,
	 *   enemy_first_hits, own_first_hits }.
	 */
	private static final class ComboTotals {
		final String category;
		int enemyCombos, enemyBroken, ownCombos, ownBroken, enemyFirstHits, ownFirstHits;

		ComboTotals(String category) {
			this.category = category;
		}

		void add(ComboTracker.ComboResult combo) {
			if (combo.enemy()) {
				enemyCombos++;
				if (combo.broken()) enemyBroken++;
				if (combo.firstHit()) enemyFirstHits++;
			} else {
				ownCombos++;
				if (combo.broken()) ownBroken++;
				if (combo.firstHit()) ownFirstHits++;
			}
		}
	}

	/** Totals for one gun in one PvP category in a fight, serialized as { category, gun, shots, hits, headshots, kills }. */
	private static final class GunTotals {
		final String category;
		final String gun;
		int shots, hits, headshots, kills;
		// Movement guns only: shots with a speed, their summed and best speed after the shot (blocks/s).
		// Left null otherwise, so Gson leaves them out.
		Integer speedShots;
		Double speedTotalBps, speedBestBps;

		GunTotals(String category, String gun) {
			this.category = category;
			this.gun = gun;
		}

		void add(ShotResult shot) {
			shots++;
			if (shot.hit()) hits++;
			if (shot.headshot()) headshots++;
			if (shot.kill()) kills++;
			Double bps = shot.speedAfterBps();
			if (bps != null) {
				speedShots = speedShots == null ? 1 : speedShots + 1;
				speedTotalBps = speedTotalBps == null ? bps : speedTotalBps + bps;
				speedBestBps = speedBestBps == null ? bps : Math.max(speedBestBps, bps);
			}
		}
	}

	// ---- Who runs the mod (the icon next to their name, see ModUsers) ----

	/** How often to tell the backend this player is running the mod. */
	private static final long HEARTBEAT_MS = 15 * 60_000L;
	/**
	 * When to ask the backend whether a player runs the mod: right after we join the server for the players already on it, and
	 * JOIN_CHECK_DELAY_MS after someone else joins, so their own mod has time to announce itself first. Each player is asked about once.
	 */
	private static final long FIRST_CHECK_DELAY_MS = 3_000L, JOIN_CHECK_DELAY_MS = 20_000L, INITIAL_WINDOW_MS = 10_000L;
	private static final int CHECK_BATCH = 90;
	private volatile boolean showUsers = true;
	private volatile UUID ownAccount;
	private volatile long lastHeartbeatMillis = 0L;
	/** Players the check found running the mod: they keep the icon until they leave the server. */
	private final java.util.Set<String> confirmedModUsers = java.util.concurrent.ConcurrentHashMap.newKeySet();
	/** Players already asked about (found or not): not asked again until they leave and come back. */
	private final java.util.Set<String> checkedPlayers = java.util.concurrent.ConcurrentHashMap.newKeySet();
	/** Players waiting for their check: undashed uuid -> when to ask. */
	private final java.util.Map<String, Long> pendingChecks = new java.util.concurrent.ConcurrentHashMap<>();
	private boolean hadPlayers = false;
	private long initialUntilMillis = 0L;

	/** Off: nobody is checked (the heartbeat is still sent, so others can see you). */
	public void setShowUsers(boolean on) {
		showUsers = on;
	}

	/** Whether this player runs the mod: you always do; others once the check found them. */
	public boolean isModUser(UUID uuid) {
		return uuid.equals(ownAccount) || confirmedModUsers.contains(uuid.toString().replace("-", ""));
	}

	/**
	 * Called every couple of seconds with everyone on the server (the tab list). Forgets players who left, and queues a check for each
	 * new one. The first time the list fills (we just joined) the mod also announces itself to the backend at once.
	 */
	public void updateOnline(java.util.Collection<UUID> online) {
		long now = System.currentTimeMillis();
		java.util.Set<String> keys = new java.util.HashSet<>();
		for (UUID id : online) keys.add(id.toString().replace("-", ""));
		boolean joined = !hadPlayers && !keys.isEmpty();
		hadPlayers = !keys.isEmpty();
		if (joined) {
			// Joining a server: also look for a new mod version (once per join, see Updater.onJoin).
			Updater.INSTANCE.onJoin();
			lastHeartbeatMillis = 0L;
			initialUntilMillis = now + INITIAL_WINDOW_MS;
		}
		confirmedModUsers.retainAll(keys);
		checkedPlayers.retainAll(keys);
		pendingChecks.keySet().retainAll(keys);
		if (!showUsers) return;
		UUID account = ownAccount;
		String own = account != null ? account.toString().replace("-", "") : null;
		for (String key : keys) {
			if (key.equals(own) || checkedPlayers.contains(key) || pendingChecks.containsKey(key)) continue;
			pendingChecks.put(key, now + (now < initialUntilMillis ? FIRST_CHECK_DELAY_MS : JOIN_CHECK_DELAY_MS));
		}
	}

	/** Every 10 seconds on the stats thread: send the heartbeat when due, and ask about the players whose check is due. */
	private void presenceTick() {
		try {
			UUID account = currentAccount();
			ownAccount = account;
			if (account == null) return;
			long now = System.currentTimeMillis();
			if (now - lastHeartbeatMillis >= HEARTBEAT_MS) {
				if (authedRequest(account, "POST", "/presence", "{}").statusCode() == 200) lastHeartbeatMillis = now;
			}
			if (!showUsers || pendingChecks.isEmpty()) return;
			com.google.gson.JsonArray due = new com.google.gson.JsonArray();
			java.util.List<String> asked = new java.util.ArrayList<>();
			for (java.util.Map.Entry<String, Long> entry : pendingChecks.entrySet()) {
				if (entry.getValue() <= now && asked.size() < CHECK_BATCH) {
					asked.add(entry.getKey());
					due.add(entry.getKey());
				}
			}
			if (asked.isEmpty()) return;
			JsonObject body = new JsonObject();
			body.add("uuids", due);
			HttpResponse<String> response = authedRequest(account, "POST", "/users/check", body.toString());
			if (response.statusCode() == 200) {
				for (com.google.gson.JsonElement id : com.google.gson.JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("uuids")) {
					confirmedModUsers.add(id.getAsString());
				}
				for (String key : asked) {
					pendingChecks.remove(key);
					checkedPlayers.add(key);
				}
			}
		} catch (Exception e) {
			LOGGER.debug("GTMAddOns: presence update failed: {}", e.toString());
		}
	}

	private HttpResponse<String> authedRequest(UUID account, String method, String path, String jsonBody) throws Exception {
		return authedRequest(account, method, path, jsonBody, false);
	}

	private HttpResponse<String> authedRequest(UUID account, String method, String path, String jsonBody, boolean gzip) throws Exception {
		ensureLoggedIn(account);
		HttpResponse<String> response = send(method, path, jsonBody, token, gzip);
		if (response.statusCode() == 401) {
			// Session expired - log in again once and retry.
			token = null;
			ensureLoggedIn(account);
			response = send(method, path, jsonBody, token, gzip);
		}
		return response;
	}

	private void ensureLoggedIn(UUID account) throws Exception {
		if (token != null && account.equals(tokenAccount)) return;
		token = null;
		// Only wait out the retry delay if this same account just failed.
		if (account.equals(lastLoginAttemptAccount) && System.currentTimeMillis() < nextLoginAttemptMillis) {
			throw new IOException("waiting to retry login");
		}
		lastLoginAttemptAccount = account;
		try {
			token = login(account);
			tokenAccount = account;
		} catch (Exception e) {
			nextLoginAttemptMillis = System.currentTimeMillis() + LOGIN_RETRY_MS;
			throw e;
		}
	}

	private static UUID currentAccount() {
		return MinecraftClient.getInstance().getSession().getUuidOrNull();
	}

	private String login(UUID uuid) throws Exception {
		MinecraftClient client = MinecraftClient.getInstance();
		Session session = client.getSession();
		if (!uuid.equals(session.getUuidOrNull())) throw new IOException("account changed while logging in");

		// The game's own key if it has one; else the one Mojang issues for the account (see mojangKey).
		LoginKey key = null;
		try {
			java.util.Optional<PlayerKeyPair> own = client.getProfileKeys().fetchKeyPair().get(15, TimeUnit.SECONDS);
			if (own.isPresent()) {
				PlayerKeyPair keyPair = own.get();
				PlayerPublicKey.PublicKeyData keyData = keyPair.publicKey().data();
				key = new LoginKey(keyPair.privateKey(), keyData.key().getEncoded(), keyData.expiresAt().toEpochMilli(), keyData.keySignature());
			}
		} catch (Exception e) {
			LOGGER.info("GTMAddOns: the game's profile key wasn't available: {}", e.toString());
		}
		if (key == null) key = mojangKey(session, uuid);

		HttpResponse<String> challenge = send("POST", "/auth/challenge", "{}", null);
		if (challenge.statusCode() != 200) throw new IOException("challenge failed: HTTP " + challenge.statusCode());
		String serverId = GSON.fromJson(challenge.body(), JsonObject.class).get("server_id").getAsString();

		String undashedUuid = uuid.toString().replace("-", "");
		Signature signer = Signature.getInstance("SHA256withRSA");
		signer.initSign(key.privateKey());
		signer.update(loginMessage(serverId, undashedUuid).getBytes(StandardCharsets.UTF_8));

		Base64.Encoder base64 = Base64.getEncoder();
		JsonObject body = new JsonObject();
		body.addProperty("username", session.getUsername());
		body.addProperty("uuid", undashedUuid);
		body.addProperty("server_id", serverId);
		body.addProperty("public_key", base64.encodeToString(key.publicKey()));
		body.addProperty("expires_at", key.expiresAtMs());
		body.addProperty("key_signature", base64.encodeToString(key.keySignature()));
		body.addProperty("signature", base64.encodeToString(signer.sign()));
		HttpResponse<String> login = send("POST", "/auth/login", body.toString(), null);
		if (login.statusCode() != 200) throw new IOException("login failed: HTTP " + login.statusCode() + " " + login.body());
		return GSON.fromJson(login.body(), JsonObject.class).get("token").getAsString();
	}

	/** What Mojang's certificate service answering with this HTTP status means, in one line for the player. */
	private static String explainStatus(int status) {
		return switch (status) {
			case 200 -> "Mojang has a key for you but the game didn't load it - restart Lunar, then delete the 'profilekeys' folder if it persists";
			case 401 -> "Your Minecraft login expired - log out and back in to your account in Lunar, then try again";
			case 403 -> "Mojang won't give this account a chat key - check Xbox privacy settings (multiplayer and chat allowed)";
			case 429 -> "Mojang is limiting key requests right now - wait a few minutes and try again";
			default -> "Mojang's key service answered HTTP " + status + " - try again later";
		};
	}

	/** What the login needs from a profile key: the private key to sign with, the public key (DER), its expiry and Mojang's signature on it. */
	private record LoginKey(java.security.PrivateKey privateKey, byte[] publicKey, long expiresAtMs, byte[] keySignature) {}

	/** The key last fetched from Mojang, kept until an hour before it expires so a retry doesn't ask Mojang again. */
	private LoginKey mojangKeyCache;
	private UUID mojangKeyAccount;

	/**
	 * The profile key Mojang issues for this account (POST api.minecraftservices.com/player/certificates with the account's own
	 * access token, which goes only to Mojang). Used when the game has none loaded. Throws with advice if Mojang won't give one.
	 */
	private LoginKey mojangKey(Session session, UUID account) throws Exception {
		if (mojangKeyCache != null && account.equals(mojangKeyAccount) && mojangKeyCache.expiresAtMs() > System.currentTimeMillis() + 3_600_000L) {
			return mojangKeyCache;
		}
		String accessToken = session.getAccessToken();
		if (accessToken == null || accessToken.length() < 20) {
			throw new IOException("This looks like an offline account - the stats need a real Microsoft Minecraft account");
		}
		HttpResponse<String> response;
		try {
			response = http.send(HttpRequest.newBuilder(URI.create("https://api.minecraftservices.com/player/certificates"))
					.timeout(Duration.ofSeconds(10))
					.header("Authorization", "Bearer " + accessToken)
					.POST(HttpRequest.BodyPublishers.noBody())
					.build(), HttpResponse.BodyHandlers.ofString());
		} catch (java.io.IOException e) {
			throw new IOException("Can't reach Mojang's key service - a VPN, firewall or antivirus may be blocking it");
		}
		if (response.statusCode() != 200) {
			LOGGER.warn("GTMAddOns: Mojang's certificate service answered HTTP {}", response.statusCode());
			throw new IOException(explainStatus(response.statusCode()));
		}
		JsonObject answer = GSON.fromJson(response.body(), JsonObject.class);
		JsonObject pair = answer.getAsJsonObject("keyPair");
		LoginKey key = new LoginKey(
				java.security.KeyFactory.getInstance("RSA").generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(pemBytes(pair.get("privateKey").getAsString()))),
				pemBytes(pair.get("publicKey").getAsString()),
				java.time.Instant.parse(answer.get("expiresAt").getAsString()).toEpochMilli(),
				Base64.getMimeDecoder().decode(answer.get("publicKeySignatureV2").getAsString()));
		LOGGER.info("GTMAddOns: the game had no profile key loaded; logging in with the key Mojang issued for the account");
		mojangKeyCache = key;
		mojangKeyAccount = account;
		return key;
	}

	/** The bytes inside a PEM block (the key's DER encoding): header and footer lines and line breaks removed. */
	static byte[] pemBytes(String pem) {
		return Base64.getMimeDecoder().decode(pem.replaceAll("-----(BEGIN|END)[A-Z ]*-----", "").replaceAll("\\s", ""));
	}

	/**
	 * The game had no profile key for this account. Ask Mojang's certificate service the same question the game does
	 * and say what its answer means, so the player knows what to fix. Short on purpose: it is shown in one line on the
	 * stats screens (the same text is written to the game log).
	 */
	private String explainMissingKey(Session session) {
		String advice;
		try {
			String accessToken = session.getAccessToken();
			if (accessToken == null || accessToken.length() < 20) {
				advice = "This looks like an offline account - the stats need a real Microsoft Minecraft account";
			} else {
				HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("https://api.minecraftservices.com/player/certificates"))
						.timeout(Duration.ofSeconds(10))
						.header("Authorization", "Bearer " + accessToken)
						.POST(HttpRequest.BodyPublishers.noBody())
						.build(), HttpResponse.BodyHandlers.ofString());
				advice = explainStatus(response.statusCode());
				LOGGER.warn("GTMAddOns: no profile key; Mojang's certificate service answered HTTP {}", response.statusCode());
			}
		} catch (java.io.IOException | InterruptedException e) {
			if (e instanceof InterruptedException) Thread.currentThread().interrupt();
			advice = "Can't reach Mojang's key service - a VPN, firewall or antivirus may be blocking it";
			LOGGER.warn("GTMAddOns: no profile key; couldn't reach Mojang's certificate service: {}", e.toString());
		}
		return advice;
	}

	/** What gets signed to log in. Must match loginMessage in the backend. */
	private static String loginMessage(String serverId, String undashedUuid) {
		return "swapinfo-login\n" + serverId + "\n" + undashedUuid;
	}

	private HttpResponse<String> send(String method, String path, String jsonBody, String bearer) throws Exception {
		return send(method, path, jsonBody, bearer, false);
	}

	private HttpResponse<String> send(String method, String path, String jsonBody, String bearer, boolean gzip) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(BACKEND_URL + path))
				.timeout(Duration.ofSeconds(15))
				.header("Content-Type", "application/json")
				.header("X-GTMAddOns-Version", Updater.modVersion())
				.method(method, jsonBody == null
						? HttpRequest.BodyPublishers.noBody()
						: HttpRequest.BodyPublishers.ofString(jsonBody));
		if (bearer != null) request.header("Authorization", "Bearer " + bearer);
		if (gzip) {
			request.header("Accept-Encoding", "gzip");
			HttpResponse<byte[]> raw = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
			byte[] bytes = raw.body();
			if ("gzip".equalsIgnoreCase(raw.headers().firstValue("Content-Encoding").orElse(""))) {
				try (java.util.zip.GZIPInputStream in = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(bytes))) {
					bytes = in.readAllBytes();
				}
			}
			String text = new String(bytes, StandardCharsets.UTF_8);
			return new TextResponse(raw, text);
		}
		return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	/** A byte response seen as text (already gunzipped), so callers handle it like any other reply. */
	private record TextResponse(HttpResponse<byte[]> inner, String body) implements HttpResponse<String> {
		@Override public int statusCode() { return inner.statusCode(); }
		@Override public java.net.http.HttpRequest request() { return inner.request(); }
		@Override public java.util.Optional<HttpResponse<String>> previousResponse() { return java.util.Optional.empty(); }
		@Override public java.net.http.HttpHeaders headers() { return inner.headers(); }
		@Override public java.util.Optional<javax.net.ssl.SSLSession> sslSession() { return inner.sslSession(); }
		@Override public java.net.URI uri() { return inner.uri(); }
		@Override public java.net.http.HttpClient.Version version() { return inner.version(); }
	}
}
