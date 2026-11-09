package com.example.gtmaddons.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.Vec3;

import java.util.function.Consumer;

/**
 * Lines in the world, drawn every frame (the 1.21.11 side of the version layer: vertices relative to the camera). The render
 * context's matrices and buffers are only touched once a drawer actually has a line, and with shader mods (Iris, Sodium) they can
 * be missing: the lines are then skipped for that frame instead of failing.
 */
public final class WorldLines {
	private WorldLines() {}

	/** Where a drawer puts its lines: world coordinates, ARGB colour, width. */
	public interface Sink {
		void line(Vec3 from, Vec3 to, int argb, float width);
	}

	public static void register(Consumer<Sink> drawer) {
		WorldRenderEvents.BEFORE_DEBUG_RENDER.register(context -> drawer.accept(new LazySink(context)));
	}

	/** Finds the matrices, buffer and camera at the first line of the frame; a missing one turns the rest of the frame's lines off. */
	private static final class LazySink implements Sink {
		private final WorldRenderContext context;
		private boolean ready = false, unavailable = false;
		private PoseStack.Pose entry;
		private VertexConsumer buffer;
		private Vec3 camera;

		LazySink(WorldRenderContext context) {
			this.context = context;
		}

		@Override
		public void line(Vec3 from, Vec3 to, int argb, float width) {
			if (unavailable) return;
			if (!ready) {
				PoseStack matrices = context.matrices();
				if (matrices == null || context.consumers() == null) {
					unavailable = true;
					return;
				}
				entry = matrices.last();
				buffer = context.consumers().getBuffer(RenderTypes.linesTranslucent());
				camera = Minecraft.getInstance().gameRenderer.getMainCamera().position();
				ready = true;
			}
			Vec3 a = from.subtract(camera), b = to.subtract(camera);
			Vec3 n = to.subtract(from).normalize();
			int alpha = argb >>> 24, red = (argb >> 16) & 0xFF, green = (argb >> 8) & 0xFF, blue = argb & 0xFF;
			buffer.addVertex(entry, (float) a.x, (float) a.y, (float) a.z).setColor(red, green, blue, alpha)
					.setNormal(entry, (float) n.x, (float) n.y, (float) n.z).setLineWidth(width);
			buffer.addVertex(entry, (float) b.x, (float) b.y, (float) b.z).setColor(red, green, blue, alpha)
					.setNormal(entry, (float) n.x, (float) n.y, (float) n.z).setLineWidth(width);
		}
	}
}
