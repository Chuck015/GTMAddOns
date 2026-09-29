package com.example.gtmaddons;

import com.example.gtmaddons.gun.DevFilter;
import com.example.gtmaddons.gun.DevLogger;
import com.example.gtmaddons.gun.ShotTracker;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

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

	private record Line(Vec3d from, Vec3d to, long nanos) {}

	private final List<Line> lines = new ArrayList<>();

	private TrajectoryLines() {}

	/** Called as the game handles a right click (use item), before it is sent to the server. */
	public void onUse(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (!DevLogger.INSTANCE.wants(DevFilter.TRAJECTORY) || player == null || client.world == null) return;
		ItemStack held = player.getMainHandStack();
		if (held.isEmpty() || ShotTracker.parseAmmo(held.getName().getString()) == null) return;

		Vec3d eye = player.getEyePos();
		Vec3d end = eye.add(player.getRotationVec(1.0f).multiply(MAX_RANGE));
		BlockHitResult block = client.world.raycast(new RaycastContext(
				eye, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player));
		if (block.getType() != HitResult.Type.MISS) end = block.getPos();

		lines.add(new Line(eye, end, System.nanoTime()));
		while (lines.size() > MAX_LINES) lines.removeFirst();
	}

	/** Draws the lines. Vertices are relative to the camera, as the world renderer expects. */
	public void render(WorldRenderContext context) {
		if (lines.isEmpty()) return;
		// Turned off (or dev/QA mode ended): drop what's left instead of showing it.
		if (!DevLogger.INSTANCE.wants(DevFilter.TRAJECTORY)) {
			lines.clear();
			return;
		}
		long now = System.nanoTime();
		lines.removeIf(line -> now - line.nanos() > LIFETIME_NANOS);
		if (lines.isEmpty()) return;

		MinecraftClient client = MinecraftClient.getInstance();
		Vec3d camera = client.gameRenderer.getCamera().getCameraPos();
		MatrixStack.Entry entry = context.matrices().peek();
		VertexConsumer buffer = context.consumers().getBuffer(RenderLayers.linesTranslucent());

		for (Line line : lines) {
			long age = now - line.nanos();
			double fade = age > LIFETIME_NANOS - FADE_NANOS ? (double) (LIFETIME_NANOS - age) / FADE_NANOS : 1.0;
			int alpha = (int) Math.round(255 * Math.max(0.0, Math.min(1.0, fade)));

			Vec3d from = line.from().subtract(camera), to = line.to().subtract(camera);
			Vec3d direction = line.to().subtract(line.from()).normalize();
			float nx = (float) direction.x, ny = (float) direction.y, nz = (float) direction.z;

			buffer.vertex(entry, (float) from.x, (float) from.y, (float) from.z)
					.color(RED, GREEN, BLUE, alpha).normal(entry, nx, ny, nz).lineWidth(LINE_WIDTH);
			buffer.vertex(entry, (float) to.x, (float) to.y, (float) to.z)
					.color(RED, GREEN, BLUE, alpha).normal(entry, nx, ny, nz).lineWidth(LINE_WIDTH);
		}
	}
}
