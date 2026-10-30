package com.example.gtmaddons;

import com.example.gtmaddons.gun.DevFilter;
import com.example.gtmaddons.gun.DevLogger;
import com.example.gtmaddons.gun.ShotTracker;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Dev / QA mode > Trajectory line (see DevFilter.TRAJECTORY): a bug-testing aid. The moment the client
 * registers a right click while you hold a gun, a straight line is drawn in
 * the world from your eye along your look direction at that instant - where
 * the crosshair pointed when the shot was fired - so it can be compared
 * with the particle stream GTM draws once the server registers the shot.
 *
 * The line runs to the first block it hits, or MAX_RANGE blocks. It stays in
 * the world (so you can look at it from the side) for LIFETIME_NANOS, fading
 * out over the last FADE_NANOS. It only shows where you aimed: nothing
 * about other players, and nothing is recorded or uploaded.
 *
 * A gun is any held item with an ammo readout in its name (see ShotTracker).
 */
public final class TrajectoryLines {

	public static final TrajectoryLines INSTANCE = new TrajectoryLines();

	private static final double MAX_RANGE = 256.0;
	private static final long LIFETIME_NANOS = 6_000_000_000L;
	private static final long FADE_NANOS = 1_500_000_000L;
	private static final int MAX_LINES = 40;
	private static final float LINE_WIDTH = 2.0f;
	/** Aqua, so it stands out from the particle stream. */
	private static final int RED = 0x55, GREEN = 0xFF, BLUE = 0xFF;

	private record Line(Vec3 from, Vec3 to, long nanos) {}

	private final List<Line> lines = new ArrayList<>();

	private TrajectoryLines() {}

	/** Called as the game handles a right click (use item), before it is sent to the server. */
	public void onUse(Minecraft client) {
		LocalPlayer player = client.player;
		if (!DevLogger.INSTANCE.wants(DevFilter.TRAJECTORY) || player == null || client.level == null) return;
		ItemStack held = player.getMainHandItem();
		if (held.isEmpty() || ShotTracker.parseAmmo(held.getHoverName().getString()) == null) return;

		Vec3 eye = player.getEyePosition();
		Vec3 end = eye.add(player.getViewVector(1.0f).scale(MAX_RANGE));
		BlockHitResult block = client.level.clip(new ClipContext(
				eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		if (block.getType() != HitResult.Type.MISS) end = block.getLocation();

		lines.add(new Line(eye, end, System.nanoTime()));
		while (lines.size() > MAX_LINES) lines.removeFirst();
	}

	/** Draws the lines. Vertices are relative to the camera, as the world renderer expects. */
	/** Draws the lines in every frame. */
	public void register() {
		WorldRenderEvents.BEFORE_DEBUG_RENDER.register(this::render);
	}

	private void render(WorldRenderContext context) {
		if (lines.isEmpty()) return;
		// Turned off (or dev/QA mode ended): drop what's left instead of showing it.
		if (!DevLogger.INSTANCE.wants(DevFilter.TRAJECTORY)) {
			lines.clear();
			return;
		}
		long now = System.nanoTime();
		lines.removeIf(line -> now - line.nanos() > LIFETIME_NANOS);
		if (lines.isEmpty()) return;

		Minecraft client = Minecraft.getInstance();
		Vec3 camera = client.gameRenderer.getMainCamera().position();
		PoseStack.Pose entry = context.matrices().last();
		VertexConsumer buffer = context.consumers().getBuffer(RenderTypes.linesTranslucent());

		for (Line line : lines) {
			long age = now - line.nanos();
			double fade = age > LIFETIME_NANOS - FADE_NANOS ? (double) (LIFETIME_NANOS - age) / FADE_NANOS : 1.0;
			int alpha = (int) Math.round(255 * Math.max(0.0, Math.min(1.0, fade)));

			Vec3 from = line.from().subtract(camera), to = line.to().subtract(camera);
			Vec3 direction = line.to().subtract(line.from()).normalize();
			float nx = (float) direction.x, ny = (float) direction.y, nz = (float) direction.z;

			buffer.addVertex(entry, (float) from.x, (float) from.y, (float) from.z)
					.setColor(RED, GREEN, BLUE, alpha).setNormal(entry, nx, ny, nz).setLineWidth(LINE_WIDTH);
			buffer.addVertex(entry, (float) to.x, (float) to.y, (float) to.z)
					.setColor(RED, GREEN, BLUE, alpha).setNormal(entry, nx, ny, nz).setLineWidth(LINE_WIDTH);
		}
	}
}
