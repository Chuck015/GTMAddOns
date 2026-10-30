package com.example.gtmaddons;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * The setting "Dropped item names": an item lying on the ground shows its name above it (with the count for a stack).
 *
 * It must never show an item you cannot see. Two things make sure of that, and both have to pass:
 *   1. Line of sight: every CHECK_NANOS, for each item within RANGE blocks, a ray is cast from your eyes to the item and
 *      another to where the label goes. Any block with a collision shape in the way of either (walls, floors, doors, glass
 *      too) means no label.
 *   2. Depth: the label is drawn the way a sneaking player's nametag is - one depth-tested pass and no see-through pass
 *      (DroppedItemLabelMixin) - so whatever is drawn in front of it hides it, pixel for pixel.
 * So a label can only ever be where the item itself is in plain view.
 */
public final class DroppedItemLabels {

	/** Items further away than this get no label. */
	private static final double RANGE = 16.0;
	private static final long CHECK_NANOS = 100_000_000L;
	/** How far above the top of the item the label sits (what the nametag renderer adds). */
	private static final double LABEL_RISE = 0.5;

	private static Settings settings;
	/** Entity id -> label, for the items in plain view at the last check. */
	private static final Map<Integer, Component> visible = new HashMap<>();
	private static long lastCheckNanos = 0L;

	private DroppedItemLabels() {}

	public static void init(Settings modSettings) {
		settings = modSettings;
	}

	/** The label to draw above this item, or null (setting off, too far, or not in plain view). */
	public static Component labelFor(ItemEntity item) {
		return visible.isEmpty() ? null : visible.get(item.getId());
	}

	public static void onFrame(Minecraft client) {
		if (settings == null || !settings.droppedItemNames || client.level == null || client.player == null) {
			visible.clear();
			return;
		}
		long now = System.nanoTime();
		if (now - lastCheckNanos < CHECK_NANOS) return;
		lastCheckNanos = now;
		visible.clear();

		Vec3 eyes = client.player.getEyePosition(1.0f);
		AABB around = client.player.getBoundingBox().inflate(RANGE);
		for (ItemEntity item : client.level.getEntitiesOfClass(ItemEntity.class, around, e -> true)) {
			ItemStack stack = item.getItem();
			if (stack.isEmpty() || item.distanceToSqr(client.player) > RANGE * RANGE) continue;
			Vec3 body = new Vec3(item.getX(), item.getY() + item.getBbHeight() / 2.0, item.getZ());
			Vec3 label = new Vec3(item.getX(), item.getY() + item.getBbHeight() + LABEL_RISE, item.getZ());
			if (!clear(client, eyes, body) || !clear(client, eyes, label)) continue;
			visible.put(item.getId(), stack.getCount() > 1 ? stack.getHoverName().copy().append(" x" + stack.getCount()) : stack.getHoverName());
		}
	}

	/** True when no block with a collision shape is between the two points. */
	private static boolean clear(Minecraft client, Vec3 from, Vec3 to) {
		return client.level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player))
				.getType() == HitResult.Type.MISS;
	}
}
