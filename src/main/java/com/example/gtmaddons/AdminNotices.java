package com.example.gtmaddons;

import com.example.gtmaddons.update.Updater;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Messages an admin sent to players (Admin mode > Notify players). The stats backend hands the pending ones to the mod with the
 * answer to its heartbeat (POST /presence, every 15 minutes); they are shown once in chat with an update button
 * (GTMAddOnsClient.showUpdateNotice). A notice for "everyone on an older version" carries the version it is about and is
 * dropped here if this mod is already at it.
 */
public final class AdminNotices {

	public static final AdminNotices INSTANCE = new AdminNotices();

	private final ConcurrentLinkedQueue<String> waiting = new ConcurrentLinkedQueue<>();
	/** Notice ids already queued or shown this session, so a repeat is ignored. */
	private final Set<Integer> seen = new HashSet<>();

	private AdminNotices() {}

	/** The body of a heartbeat answer: {"ok":true,"notices":[{"id":1,"message":"...","target_version":"1.5.0"|null}]}. Never throws. */
	public void receive(String heartbeatAnswer) {
		try {
			JsonObject answer = JsonParser.parseString(heartbeatAnswer).getAsJsonObject();
			if (!answer.has("notices") || !answer.get("notices").isJsonArray()) return;
			boolean any = false;
			for (JsonElement element : answer.getAsJsonArray("notices")) {
				JsonObject notice = element.getAsJsonObject();
				int id = notice.get("id").getAsInt();
				String message = notice.get("message").getAsString();
				String target = notice.has("target_version") && !notice.get("target_version").isJsonNull() ? notice.get("target_version").getAsString() : null;
				if (message.isBlank()) continue;
				if (target != null && Updater.compare(Updater.modVersion(), target) >= 0) continue;
				synchronized (seen) {
					if (!seen.add(id)) continue;
				}
				waiting.add(message);
				any = true;
			}
			// Make sure the update the message talks about is known (the update button works once a new version has been found).
			if (any) Updater.INSTANCE.recheckSoon();
		} catch (Exception e) {
			// not a notice answer (an older backend): nothing to show
		}
	}

	/** The next message to show, or null. */
	public String take() {
		return waiting.poll();
	}
}
