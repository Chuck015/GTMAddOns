package com.example.gtmaddons;

import com.example.gtmaddons.gun.DevFilter;
import com.example.gtmaddons.gun.DevLogger;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Locale;

/**
 * Follows GTM's combat tag, so items picked up from kills mid-fight don't
 * change your PvP category.
 *
 * On GTM you can bring a jetpack or wingsuit out of your backpack before a
 * fight, but the backpack can't be opened while combat-tagged. The only way
 * new jetpacks/wingsuits reach your inventory during a fight is looting
 * kills. So while tagged, PvpCategory only counts as many jetpacks and
 * wingsuits as you had when the tag started.
 *
 * The tag starts only on the server's "[COMBATTAG] You are now in combat!"
 * message and ends on "[COMBATTAG] You are no longer in combat.", GTM's
 * death message ("He got you good. [PEEK INVENTORY]"), GTM's "WASTED"
 * death title, or when you die or
 * leave the world. GTM extends the tag timer without saying so, so there's
 * no local countdown - only a long safety net (FALLBACK_NANOS with no hits
 * between you and another player) in case the end message is ever missed.
 *
 * Things that are only possible out of combat also clear the tag, to fix it
 * if it ever gets stuck: GTM's taxi ("[TAXI] You called a taxi!" from /spawn)
 * and your backpack's GUI opening. In dev mode, every start and end is
 * reported in chat with its reason.
 */
public final class CombatTracker {

	public static final CombatTracker INSTANCE = new CombatTracker();

	private static final long FALLBACK_NANOS = 180_000_000_000L;

	private boolean tagged = false;
	private long lastActivityNanos = 0L;
	private int jetpacksAtStart = 0;
	private int wingsuitsAtStart = 0;

	private CombatTracker() {}

	public boolean isTagged() {
		return tagged;
	}

	public int jetpacksAtStart() {
		return jetpacksAtStart;
	}

	public int wingsuitsAtStart() {
		return wingsuitsAtStart;
	}

	public void onFrame(MinecraftClient client) {
		if (!tagged) return;
		ClientPlayerEntity player = client.player;
		if (player == null) end("left the world");
		else if (player.isDead()) end("you died");
		else if (System.nanoTime() - lastActivityNanos > FALLBACK_NANOS) end("3 minutes with no player hits (safety net)");
	}

	/** A screen opened. Your backpack can't be opened in combat, so its GUI means you're out. */
	public void onScreenOpened(Screen screen) {
		if (tagged && screen.getTitle().getString().toLowerCase(Locale.ROOT).contains("backpack")) {
			end("backpack opened");
		}
	}

	private void end(String reason) {
		if (!tagged) return;
		tagged = false;
		if (DevLogger.INSTANCE.wants(DevFilter.FIGHTS)) DevLogger.chat("Combat tag cleared: " + reason, Formatting.GREEN);
	}

	/**
	 * GTM's messages:
	 *   "[COMBATTAG] You are now in combat! Do not log out for 20 seconds!"
	 *   "[COMBATTAG] You are no longer in combat. You may log out safely."
	 *   "He got you good. [PEEK INVENTORY]" - you died, which also ends the
	 *   tag (tags don't last over death).
	 */
	/** GTM's "WASTED" death title - you died, and tags don't last over death. */
	public void onTitle(Text text) {
		if (text.getString().trim().equalsIgnoreCase("WASTED")) end("died (WASTED)");
	}

	public void onMessage(Text text) {
		String message = text.getString().toLowerCase(Locale.ROOT);
		if (isDeathMessage(message)) {
			end("death message");
			return;
		}
		// Calling a taxi (/spawn) only works out of combat.
		if (message.contains("[taxi]") && message.contains("you called a taxi")) {
			end("taxi called");
			return;
		}
		if (!message.contains("[combattag]")) return;
		if (message.contains("you are now in combat")) {
			refresh();
		} else if (message.contains("you are no longer in combat")) {
			end("combat tag ended message");
		}
	}

	/**
	 * GTM's "you died" message. Matched on "got you good" (in case the wording
	 * before it varies), or on "[PEEK INVENTORY]" as long as it isn't about a
	 * kill you made.
	 */
	private static boolean isDeathMessage(String message) {
		return message.contains("got you good")
				|| (message.contains("[peek inventory]") && !message.contains("you killed"));
	}

	/**
	 * Hits between you and another player keep the safety-net timer going,
	 * but never start a tag - only GTM's message does. (Starting one on any
	 * damage made fall damage from a wingsuit landing "tag" you, and GTM
	 * never sends an end message for a tag it didn't start.)
	 */
	public void onDamage(int targetId, int causeId) {
		MinecraftClient client = MinecraftClient.getInstance();
		ClientPlayerEntity player = client.player;
		if (!tagged || player == null || client.world == null) return;
		int otherId = targetId == player.getId() ? causeId : causeId == player.getId() ? targetId : -1;
		if (otherId >= 0 && otherId != player.getId()
				&& client.world.getEntityById(otherId) instanceof net.minecraft.entity.player.PlayerEntity) {
			lastActivityNanos = System.nanoTime();
		}
	}

	private void refresh() {
		ClientPlayerEntity player = MinecraftClient.getInstance().player;
		if (!tagged && player != null) {
			tagged = true;
			if (DevLogger.INSTANCE.wants(DevFilter.FIGHTS)) DevLogger.chat("Combat tag started", Formatting.RED);
			// Snapshot what you brought into the fight.
			PlayerInventory inventory = player.getInventory();
			jetpacksAtStart = 0;
			wingsuitsAtStart = 0;
			for (int i = 0; i < inventory.size(); i++) {
				ItemStack stack = inventory.getStack(i);
				if (PvpCategory.isJetpack(stack) || (i == PvpCategory.CHEST_INVENTORY_INDEX && PvpCategory.isWornJetpack(stack))) jetpacksAtStart++;
				if (PvpCategory.isWingsuit(stack)) wingsuitsAtStart++;
			}
		}
		lastActivityNanos = System.nanoTime();
	}
}
