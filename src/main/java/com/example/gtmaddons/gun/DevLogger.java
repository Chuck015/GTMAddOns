package com.example.gtmaddons.gun;

import com.example.gtmaddons.Settings;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dev mode: detailed logging of everything around each gun shot, for
 * checking and tuning ShotTracker. Only accounts listed in the backend's
 * DEV_UUIDS can turn it on (Settings in the /gao menu).
 *
 * A shot is detected the same way ShotTracker does it: the held gun's ammo
 * readout drops while its name stays the same. For a couple of seconds
 * after each shot, everything that could be hit feedback is written to
 * gtmaddons-dev.log in the game folder: damage events (and who caused
 * them), entity status events, other entities' health changes, sounds,
 * particles, titles/subtitles, and action-bar/chat messages, plus what the
 * crosshair ray passes through. Melee hits, combo messages, wing boosts
 * and jetpack starts (see MovementDebugger) open a window too. ShotTracker's verdict for each shot is
 * added as a RESULT line, and a summary of each burst goes to chat.
 *
 * QA mode is the same thing without the log file: the chat lines still
 * appear, but nothing is written to disk.
 *
 * Network hooks (ClientPlayNetworkHandlerMixin) call the on* methods on
 * the game thread.
 */
public final class DevLogger {

	public static final DevLogger INSTANCE = new DevLogger();

	private static final Logger LOGGER = LoggerFactory.getLogger("gtmaddons");
	private static final long WINDOW_NANOS = 2_000_000_000L;
	private static final double AIM_RANGE = 256.0;
	private static final double HEALTH_RANGE = 128.0;
	private static final String LOG_FILE = "gtmaddons-dev.log";

	private Settings settings;
	private boolean enabled = false;
	private boolean saveLog = true;
	private final MovementDebugger movement = new MovementDebugger(this);
	private final SwingDebugger swing = new SwingDebugger(this);
	private final MeleeDamageDebugger melee = new MeleeDamageDebugger(this);
	private final NetLauncherDebugger netLauncher = new NetLauncherDebugger(this);
	private final NearLogger near = new NearLogger(this);
	private com.example.gtmaddons.PvpCategory lastCategory = null;

	// Shot detection
	private String lastGun = null;
	private Integer lastAmmo = null;

	// Burst summary for chat, built from ShotTracker's results
	private final List<ShotResult> burstResults = new ArrayList<>();
	// Melee hits in the current window, for the chat line
	private int meleeDealt = 0;
	private int meleeTaken = 0;

	// Current logging window (open for a while after each shot)
	private int shotNumber = 0;
	private long windowStartNanos = 0L;
	private long windowEndNanos = 0L;
	private final List<String> lines = new ArrayList<>();
	private final Map<Integer, Float> healthAtShot = new HashMap<>();
	private final Map<String, Integer> particleCounts = new HashMap<>();

	private DevLogger() {}

	public boolean isEnabled() {
		return enabled;
	}

	/** False in QA mode: the same chat lines, but nothing written to disk. */
	public boolean isSavingLog() {
		return saveLog;
	}

	/** Dev mode (chat + log file) - see setEnabled(boolean, boolean) for QA mode. */
	public void setEnabled(boolean enabled) {
		setEnabled(enabled, true);
	}

	/** saveLog false is QA mode: chat lines only, no log file. */
	public void setEnabled(boolean enabled, boolean saveLog) {
		// Finish the current window under the old mode (switching dev <-> QA too).
		if (!enabled || saveLog != this.saveLog) flushWindow();
		if (enabled && !this.enabled) movement.reset();
		this.enabled = enabled;
		this.saveLog = saveLog;
		lastAmmo = null;
	}

	public Path logPath() {
		return MinecraftClient.getInstance().runDirectory.toPath().resolve(LOG_FILE);
	}

	/** Where the on/off choice for each filter is saved. */
	public void init(Settings settings) {
		this.settings = settings;
	}

	/**
	 * Whether this filter's output should show right now: dev or QA mode is on
	 * AND the filter is switched on in that mode's menu. Every chat line and
	 * log line goes through this, so an off filter hides it from both.
	 */
	public boolean wants(DevFilter filter) {
		return enabled && (settings == null || settings.filterOn(!saveLog, filter));
	}

	private boolean windowOpen() {
		return enabled && windowEndNanos != 0L && System.nanoTime() < windowEndNanos;
	}

	// ---- Per frame ----

	// ---- Performance (dev filter "Performance") ----

	public static final int PERF_HOOKS = 0, PERF_DEV = 1, PERF_HUD = 2;
	private static final long PERF_REPORT_NANOS = 10_000_000_000L;
	private final long[] perfTotal = new long[3];
	private final long[] perfMax = new long[3];
	private int perfFrames = 0;
	private long perfWindowStart = 0L;

	/** Time one of the mod's per-frame parts took (see PERF_*); a report is written every 10 seconds. */
	public void perf(int part, long nanos) {
		if (!wants(DevFilter.PERFORMANCE)) return;
		perfTotal[part] += nanos;
		perfMax[part] = Math.max(perfMax[part], nanos);
		if (part == PERF_HOOKS) perfFrames++;
		long now = System.nanoTime();
		if (perfWindowStart == 0L) perfWindowStart = now;
		if (now - perfWindowStart >= PERF_REPORT_NANOS && perfFrames > 0) reportPerf(now);
	}

	private void reportPerf(long now) {
		double seconds = (now - perfWindowStart) / 1e9;
		int frames = perfFrames;
		double frameMs = 1000.0 * seconds / frames;
		double hooks = perfTotal[PERF_HOOKS] / 1e6 / frames, hooksMax = perfMax[PERF_HOOKS] / 1e6;
		double dev = perfTotal[PERF_DEV] / 1e6 / frames, devMax = perfMax[PERF_DEV] / 1e6;
		double hud = perfTotal[PERF_HUD] / 1e6 / frames, hudMax = perfMax[PERF_HUD] / 1e6;
		double mod = hooks + hud;
		double share = 100.0 * mod / frameMs;
		String line = String.format("PERF  last %.0f s, %d frames (%.0f fps): the mod costs %.3f ms a frame = %.2f%% of a %.1f ms frame"
				+ " | frame hooks %.3f ms avg / %.2f max, HUD %.3f ms avg / %.2f max"
				+ " | dev tools themselves %.3f ms avg / %.2f max (not counted above)",
				seconds, frames, frames / seconds, mod, share, frameMs, hooks, hooksMax, hud, hudMax, dev, devMax);
		openWindow(MinecraftClient.getInstance());
		add(line);
		// Chat only when it matters.
		if (share >= 3.0) chat(String.format("Performance: the mod uses %.2f%% of each frame (%.3f ms) - see the dev log", share, mod), Formatting.RED);
		java.util.Arrays.fill(perfTotal, 0L);
		java.util.Arrays.fill(perfMax, 0L);
		perfFrames = 0;
		perfWindowStart = now;
	}

	public void onFrame(MinecraftClient client) {
		if (!enabled) return;
		if (windowEndNanos != 0L && System.nanoTime() >= windowEndNanos) flushWindow();
		movement.onFrame(client);
		if (wants(DevFilter.SWINGS)) swing.onFrame(client);
		if (wants(DevFilter.MELEE_DAMAGE)) melee.onFrame(client);
		if (wants(DevFilter.NET_LAUNCHER)) netLauncher.onFrame(client);
		near.onFrame();
		reportCategoryChange(client);

		ClientPlayerEntity player = client.player;
		if (player == null) {
			lastAmmo = null;
			return;
		}

		ItemStack held = player.getStackInHand(Hand.MAIN_HAND);
		String name = held.isEmpty() ? "" : held.getName().getString();
		Integer ammo = ShotTracker.parseAmmo(name);
		String gun = ammo != null ? ShotTracker.gunName(name) : null;
		if (gun != null && gun.equals(lastGun) && lastAmmo != null && ammo < lastAmmo) {
			onShot(client, held, lastAmmo, ammo);
		}
		if (windowOpen() && wants(DevFilter.DAMAGE)) checkHealthChanges(client);

		lastGun = gun;
		lastAmmo = ammo;
	}

	/** Better near hid a /near reply that came without a command (see NearList.isUnsolicitedReply). */
	public void noteHiddenNear(Text text) {
		if (!enabled || !wants(DevFilter.NEAR)) return;
		openWindow(client());
		add("HIDDEN near reply (no command was run in the 15 s before it): " + describe(text));
	}

	private static MinecraftClient client() {
		return MinecraftClient.getInstance();
	}

	/** A command you sent (without the slash) - /near's reply gets captured (see NearLogger). */
	public void onCommand(String command) {
		if (wants(DevFilter.NEAR)) near.onCommand(command);
	}

	/** A left click, just before the game handles it (see SwingDebugger). */
	public void beforeAttack(MinecraftClient client, int attackCooldown) {
		if (wants(DevFilter.SWINGS)) swing.beforeAttack(client, attackCooldown);
		if (wants(DevFilter.MELEE_DAMAGE)) melee.beforeAttack(client);
	}

	/** The same left click, just after. */
	public void afterAttack(MinecraftClient client) {
		if (wants(DevFilter.SWINGS)) swing.afterAttack(client);
	}

	/** Says in chat whenever your PvP category changes, and what it's based on. */
	private long lastCategoryCheckNanos = 0L;

	private void reportCategoryChange(MinecraftClient client) {
		if (client.player == null) return;
		// The category only changes when the inventory does: a few checks a second are plenty.
		long checkedAt = System.nanoTime();
		if (checkedAt - lastCategoryCheckNanos < 250_000_000L) return;
		lastCategoryCheckNanos = checkedAt;
		com.example.gtmaddons.PvpCategory category = com.example.gtmaddons.PvpCategory.classify(client.player);
		if (category == lastCategory) return;
		lastCategory = category;
		if (!wants(DevFilter.CATEGORY)) return;
		String why = com.example.gtmaddons.PvpCategory.explain(client.player);
		chat("PvP category: " + category.label + " | " + why, Formatting.AQUA);
		if (windowOpen()) add("CATEGORY  " + category.label + "  " + why);
	}

	/** Dev info line in chat. */
	public static void chat(String message, Formatting color) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player != null) client.player.sendMessage(Text.literal(prefix() + message).formatted(color), false);
	}

	/** "[Dev] " in dev mode, "[QA] " in QA mode, so chat shows which one is on. */
	private static String prefix() {
		return INSTANCE.saveLog ? "[Dev] " : "[QA] ";
	}

	/** A finished melee combo (see ComboTracker). */
	public void onComboResult(com.example.gtmaddons.ComboTracker.ComboResult combo) {
		if (!wants(DevFilter.COMBOS)) return;
		openWindow(MinecraftClient.getInstance());
		String line = String.format("[%s] %s  %d %s (%s)  -> %s", combo.category().label,
				combo.enemy() ? combo.opponent() + "'s combo on you" : "your combo on " + combo.opponent(),
				combo.hits(), combo.hits() == 1 ? "hit" : "hits", combo.firstHit() ? "first hit" : "from a break",
				combo.broken() ? (combo.enemy() ? "BROKEN by you" : "BROKEN by them") : "ended unbroken");
		add("COMBO  " + line);
		chat("Combo " + line, Formatting.GRAY);
	}

	/** ShotTracker's verdict for a shot, added to the log and the chat summary. */
	public void onShotResult(ShotResult r) {
		if (!enabled) return;
		burstResults.add(r);
		String netReason = ShotTracker.INSTANCE.takeNetNote();
		if (netReason != null && wants(DevFilter.NET_LAUNCHER)) netLauncher.onResult(MinecraftClient.getInstance(), r, netReason);
		if (!windowOpen() || !wants(DevFilter.SHOTS)) return;
		StringBuilder line = new StringBuilder("RESULT  [").append(r.category().label).append("] ").append(r.gun()).append(": ");
		if (r.hit()) {
			line.append("HIT ").append(r.target());
			if (!Double.isNaN(r.distance())) line.append(String.format(" at %.1f blocks", r.distance()));
			if (r.headshot()) line.append(", HEADSHOT");
			if (r.kill()) line.append(", KILL");
		} else {
			line.append("MISS");
		}
		line.append("  gunshot sound=").append(r.gunshotSound() != null ? r.gunshotSound() : "none");
		if (!r.otherSounds().isEmpty()) line.append("  other sounds=").append(r.otherSounds());
		if (r.responseMs() != null) line.append("  response=").append(r.responseMs()).append("ms");
		line.append("  ping=").append(r.pingMs()).append("ms");
		if (r.speedAfterBps() != null) line.append(String.format("  speed %.1f -> %.1f b/s", r.speedBeforeBps(), r.speedAfterBps()));
		add(line.toString());
	}

	/** Opens (or extends) the logging window. */
	void openWindow(MinecraftClient client) {
		if (!windowOpen()) {
			flushWindow();
			windowStartNanos = System.nanoTime();
			snapshotHealth(client);
		}
		windowEndNanos = System.nanoTime() + WINDOW_NANOS;
	}

	private void onShot(MinecraftClient client, ItemStack gun, int ammoBefore, int ammoAfter) {
		openWindow(client);
		shotNumber++;
		if (wants(DevFilter.NET_LAUNCHER) && ShotTracker.countsOnlyWingsuitHits(ShotTracker.gunName(gun.getName().getString()))) netLauncher.onShot(client);

		if (wants(DevFilter.SHOTS)) {
			add(String.format("SHOT #%d  gun=\"%s\"  ammo %d -> %d", shotNumber, gun.getName().getString(), ammoBefore, ammoAfter));
		}
		if (wants(DevFilter.AIM)) describeAim(client);
	}

	// ---- Network events (game thread) ----

	/**
	 * Any hit you deal or take opens a window too (melee has no ammo to
	 * watch), so JP fights and combos get logged. Hits that don't register
	 * ("blank" hits) send no damage event, so they won't appear here.
	 */
	public void onEntityDamage(int entityId, String damageType, int causeId, int directId) {
		MinecraftClient client = MinecraftClient.getInstance();
		int me = client.player != null ? client.player.getId() : -1;
		boolean involvesMe = entityId == me || causeId == me;
		if (involvesMe) {
			openWindow(client);
			if (causeId == me && entityId != me && wants(DevFilter.SWINGS)) swing.onHit(entityName(entityId));
			if (causeId == me && entityId != me && wants(DevFilter.MELEE_DAMAGE)) melee.onHit(client, entityId, damageType);
			if (causeId == me) meleeDealt++;
			if (entityId == me) meleeTaken++;
		}
		if (!windowOpen() || !wants(DevFilter.DAMAGE)) return;
		add(String.format("DAMAGE  target=%s%s  type=%s  cause=%s%s  direct=%s%s  attacker holding=\"%s\"%s",
				entityName(entityId), entityId == me ? " (YOU)" : "", damageType,
				entityName(causeId), causeId == me ? " (YOU)" : "",
				entityName(directId), directId == me ? " (YOU)" : "",
				heldItemName(causeId), targetGear(entityId)));
	}

	/**
	 * For a player target: what's in their chest slot and whether they count
	 * as on a wingsuit (the Net Launcher only counts hits on those).
	 */
	private static String targetGear(int entityId) {
		MinecraftClient client = MinecraftClient.getInstance();
		Entity entity = entityId >= 0 && client.world != null ? client.world.getEntityById(entityId) : null;
		if (!(entity instanceof PlayerEntity player)) return "";
		ItemStack chest = player.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST);
		return String.format("  target chest=\"%s\" gliding=%s on-wingsuit=%s",
				chest.isEmpty() ? "-" : chest.getName().getString(), player.isGliding(), ShotTracker.isOnWingsuit(player));
	}

	/**
	 * Every action-bar message, straight from the HUD (server action bars
	 * don't go through the chat event). Combo and hit-cooldown messages open
	 * a window by themselves.
	 */
	public void onActionBar(Text text) {
		if (!enabled) return;
		near.onMessage(text, true);
		String lower = text.getString().toLowerCase(java.util.Locale.ROOT);
		if (lower.contains("combo") || lower.contains("recovering")) openWindow(MinecraftClient.getInstance());
		if (!windowOpen() || !wants(DevFilter.MESSAGES)) return;
		add("ACTION_BAR  " + describe(text));
	}

	private static String heldItemName(int entityId) {
		MinecraftClient client = MinecraftClient.getInstance();
		Entity entity = entityId >= 0 && client.world != null ? client.world.getEntityById(entityId) : null;
		return entity instanceof LivingEntity living && !living.getMainHandStack().isEmpty()
				? living.getMainHandStack().getName().getString()
				: "-";
	}

	public void onEntityStatus(Entity entity, byte status) {
		if (!windowOpen() || entity == null || !wants(DevFilter.DAMAGE)) return;
		add(String.format("ENTITY_STATUS  entity=%s  status=%d", describe(entity), status));
	}

	public void onSound(String soundId, String category, Vec3d position, float volume, float pitch) {
		if (!enabled) return;
		MinecraftClient client = MinecraftClient.getInstance();
		double distance = position != null && client.player != null ? client.player.getEyePos().distanceTo(position) : Double.NaN;
		movement.onSound(soundId, distance);
		if (wants(DevFilter.SWINGS)) swing.onSound(soundId, distance);
		if (!windowOpen() || !wants(DevFilter.SOUNDS)) return;
		add(String.format("SOUND  %s  category=%s  volume=%.2f  pitch=%.2f  %s", soundId, category, volume, pitch,
				Double.isNaN(distance) ? "?" : String.format("%.1f blocks away", distance)));
	}

	public void onEntitySound(String soundId, String category, int entityId, float volume, float pitch) {
		if (!enabled) return;
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player != null && entityId == client.player.getId()) movement.onSound(soundId, 0.0);
		if (!windowOpen() || !wants(DevFilter.SOUNDS)) return;
		add(String.format("SOUND  %s  category=%s  volume=%.2f  pitch=%.2f  from entity %s",
				soundId, category, volume, pitch, entityName(entityId)));
	}

	public void onParticle(String particleId, int count) {
		if (!windowOpen() || !wants(DevFilter.PARTICLES)) return;
		// Particles come in bursts, so they're tallied and listed once per window.
		particleCounts.merge(particleId, Math.max(1, count), Integer::sum);
	}

	/**
	 * The server setting an item cooldown (the white sweep over a hotbar
	 * item). GTM's weapon cooldown matches "Target recovering", and this
	 * gives its exact length in ticks.
	 */
	public void onCooldown(String group, int ticks) {
		if (!windowOpen() || !wants(DevFilter.COOLDOWNS)) return;
		add(String.format("COOLDOWN  %s  %d ticks (%dms)", group, ticks, ticks * 50));
	}

	public void onTitle(String kind, Text text) {
		if (!windowOpen() || !wants(DevFilter.TITLES)) return;
		add(String.format("%s  %s", kind, describe(text)));
	}

	/** Chat messages. Action bars are logged by onActionBar, which sees all of them. */
	public void onMessage(Text text, boolean actionBar) {
		// Action bars reach the /near capture through onActionBar instead.
		if (enabled && !actionBar) near.onMessage(text, false);
		if (actionBar || !windowOpen() || !wants(DevFilter.MESSAGES)) return;
		add("CHAT  " + describe(text));
	}

	// ---- Aim and health ----

	/** Records every player the crosshair ray passes through, and the first block in the way. */
	private void describeAim(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (player == null || client.world == null) return;
		Vec3d eye = player.getEyePos();
		Vec3d end = eye.add(player.getRotationVec(1.0f).multiply(AIM_RANGE));

		BlockHitResult block = client.world.raycast(new RaycastContext(
				eye, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player));
		double blockDistance = block.getType() == HitResult.Type.MISS ? Double.MAX_VALUE : eye.distanceTo(block.getPos());
		add(blockDistance == Double.MAX_VALUE
				? "  aim: no block within " + (int) AIM_RANGE + " blocks"
				: String.format("  aim: first block at %.1f blocks", blockDistance));

		boolean any = false;
		for (PlayerEntity other : client.world.getPlayers()) {
			if (other == player) continue;
			Box box = other.getBoundingBox();
			Optional<Vec3d> hit = box.raycast(eye, end);
			if (hit.isEmpty()) continue;
			any = true;
			double distance = eye.distanceTo(hit.get());
			double heightOnBody = hit.get().y - other.getY();
			add(String.format("  aim: crosses player %s at %.1f blocks, %.2f blocks up their body (%s)%s",
					other.getName().getString(), distance, heightOnBody,
					heightOnBody >= other.getHeight() - 0.5 ? "head area" : "body",
					distance > blockDistance ? " - BEHIND A BLOCK" : ""));
		}
		if (!any) add("  aim: no player in the crosshair ray");
	}

	private void snapshotHealth(MinecraftClient client) {
		healthAtShot.clear();
		if (client.world == null || client.player == null) return;
		for (Entity entity : client.world.getEntities()) {
			if (entity instanceof LivingEntity living && entity != client.player
					&& entity.squaredDistanceTo(client.player) < HEALTH_RANGE * HEALTH_RANGE) {
				healthAtShot.put(entity.getId(), living.getHealth());
			}
		}
	}

	private void checkHealthChanges(MinecraftClient client) {
		if (client.world == null) return;
		for (Map.Entry<Integer, Float> entry : healthAtShot.entrySet()) {
			if (client.world.getEntityById(entry.getKey()) instanceof LivingEntity living) {
				float now = living.getHealth();
				if (now != entry.getValue()) {
					add(String.format("HEALTH  %s  %.1f -> %.1f", describe(living), entry.getValue(), now));
					entry.setValue(now);
				}
			}
		}
	}

	// ---- Output ----

	void add(String line) {
		long ms = (System.nanoTime() - windowStartNanos) / 1_000_000L;
		lines.add(String.format("+%5dms  %s", ms, line));
	}

	/**
	 * Appends "==== time  title ====" and the lines to the dev log. Does
	 * nothing in QA mode, which never writes to disk.
	 */
	void writeBlock(String title, List<String> blockLines) {
		if (!saveLog) return;
		StringBuilder out = new StringBuilder();
		out.append("==== ").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
		if (title != null) out.append("  ").append(title);
		out.append(" ====\n");
		for (String line : blockLines) out.append(line).append('\n');
		out.append('\n');
		try {
			Files.writeString(logPath(), out.toString(), StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			LOGGER.warn("GTMAddOns: couldn't write dev log: {}", e.toString());
		}
	}

	private void flushWindow() {
		if (lines.isEmpty()) {
			windowEndNanos = 0L;
			return;
		}
		if (!particleCounts.isEmpty()) {
			lines.add("         PARTICLES  " + particleCounts);
		}

		writeBlock(null, lines);

		if (wants(DevFilter.SHOTS)) sendBurstSummary();
		if (burstResults.isEmpty() && meleeDealt + meleeTaken > 0 && wants(DevFilter.COMBOS)) {
			MinecraftClient client = MinecraftClient.getInstance();
			if (client.player != null) {
				// QA mode doesn't write the log, so don't say "logged".
				client.player.sendMessage(Text.literal(String.format("%s%s a melee exchange: %d hits dealt, %d taken",
						prefix(), saveLog ? "logged" : "saw", meleeDealt, meleeTaken)).formatted(Formatting.GRAY), false);
			}
		}

		burstResults.clear();
		meleeDealt = 0;
		meleeTaken = 0;
		lines.clear();
		particleCounts.clear();
		healthAtShot.clear();
		windowEndNanos = 0L;
	}

	/**
	 * One chat line per PvP category and gun fired in the burst, e.g.
	 * "[Dev] Wing | Combat MG: 12 shots, 5 hits (42%), 1 HS, 1 kill | 18.4-26.3 blocks
	 *  | gunshot note_block.snare | response 47ms | ping 45ms"
	 */
	private void sendBurstSummary() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || burstResults.isEmpty()) return;

		Map<String, List<ShotResult>> byGun = new java.util.LinkedHashMap<>();
		for (ShotResult r : burstResults) byGun.computeIfAbsent(r.category().label + " | " + r.gun(), k -> new ArrayList<>()).add(r);

		for (Map.Entry<String, List<ShotResult>> entry : byGun.entrySet()) {
			List<ShotResult> results = entry.getValue();
			int hits = 0, headshots = 0, kills = 0;
			double minDistance = Double.MAX_VALUE, maxDistance = 0;
			java.util.Set<String> sounds = new java.util.LinkedHashSet<>();
			Long response = null;
			for (ShotResult r : results) {
				if (r.hit()) {
					hits++;
					if (!Double.isNaN(r.distance())) {
						minDistance = Math.min(minDistance, r.distance());
						maxDistance = Math.max(maxDistance, r.distance());
					}
				}
				if (r.headshot()) headshots++;
				if (r.kill()) kills++;
				if (r.gunshotSound() != null) sounds.add(r.gunshotSound().replace("minecraft:", ""));
				for (String other : r.otherSounds()) sounds.add(other.replace("minecraft:", ""));
				if (response == null && r.responseMs() != null) response = r.responseMs();
			}

			StringBuilder msg = new StringBuilder(prefix()).append(entry.getKey()).append(": ")
					.append(results.size()).append(results.size() == 1 ? " shot, " : " shots, ")
					.append(hits).append(hits == 1 ? " hit" : " hits")
					.append(String.format(" (%.0f%%), %d HS, %d %s", hits * 100.0 / results.size(),
							headshots, kills, kills == 1 ? "kill" : "kills"));
			if (hits > 0 && maxDistance > 0) {
				msg.append(minDistance == maxDistance
						? String.format(" | %.1f blocks", minDistance)
						: String.format(" | %.1f-%.1f blocks", minDistance, maxDistance));
			}
			msg.append(" | sounds ").append(sounds.isEmpty() ? "none" : String.join(", ", sounds));
			if (response != null) msg.append(" | response ").append(response).append("ms");
			msg.append(" | ping ").append(results.get(results.size() - 1).pingMs()).append("ms");
			client.player.sendMessage(Text.literal(msg.toString()).formatted(Formatting.GRAY), false);
		}
	}

	// ---- Helpers ----

	private static String entityName(int id) {
		if (id < 0) return "none";
		MinecraftClient client = MinecraftClient.getInstance();
		Entity entity = client.world != null ? client.world.getEntityById(id) : null;
		return entity != null ? describe(entity) : "#" + id + "(unknown)";
	}

	private static String describe(Entity entity) {
		return entity.getName().getString() + "[" + entity.getType().getUntranslatedName() + " #" + entity.getId() + "]";
	}

	/**
	 * Text as plain characters, with anything invisible or from a custom
	 * font spelled out - hitmarkers are often a private-use character drawn
	 * from a resource-pack font, which would otherwise look empty.
	 */
	private static String describe(Text text) {
		StringBuilder plain = new StringBuilder();
		List<String> fonts = new ArrayList<>();
		text.visit((Style style, String part) -> {
			for (int i = 0; i < part.length(); ) {
				int cp = part.codePointAt(i);
				if (cp < 0x20 || cp >= 0x7F) plain.append(String.format("\\u%04X", cp));
				else plain.appendCodePoint(cp);
				i += Character.charCount(cp);
			}
			String font = String.valueOf(style.getFont());
			if (!part.isEmpty() && !fonts.contains(font)) fonts.add(font);
			return Optional.empty();
		}, Style.EMPTY);
		return "\"" + plain + "\"  fonts=" + fonts;
	}
}
