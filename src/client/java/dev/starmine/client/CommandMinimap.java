package dev.starmine.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.starmine.StarMine;
import dev.starmine.entity.StarUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.Vec3;

/**
 * The command view's minimap (owner 14.10, as in Reign of Nether): bottom left, north up, the ground
 * around the camera ({@link #RADIUS} blocks) in map colours shaded by height, under the same fog of
 * war as the view (dimmed where seen before, dark where never seen), with the base's units (green),
 * others in sight (red) and the camera's view on the ground (white frame). Left click moves the camera
 * there, right click sends the selection there (the smart command).
 */
final class CommandMinimap {
	static final int SIZE = 128, RADIUS = 128;
	private static final ResourceLocation TEXTURE = StarMine.id("command_minimap");
	private static DynamicTexture texture;
	private static int frame;
	private static double centreX, centreZ;

	private CommandMinimap() {
	}

	/** Top left of the minimap on a screen of this height. */
	static int left() {
		return 4;
	}

	static int top(int height) {
		return height - SIZE - 4;
	}

	static boolean contains(double x, double y, int height) {
		return x >= left() && x < left() + SIZE && y >= top(height) && y < top(height) + SIZE;
	}

	/** The world point (x, z) under a minimap point. */
	static Vec3 world(double x, double y, int height) {
		double scale = 2.0 * RADIUS / SIZE;
		return new Vec3(centreX + (x - left() - SIZE / 2.0) * scale, 0, centreZ + (y - top(height) - SIZE / 2.0) * scale);
	}

	private static int[] screen(double wx, double wz, int height) {
		double scale = SIZE / (2.0 * RADIUS);
		return new int[] {(int) (left() + SIZE / 2.0 + (wx - centreX) * scale), (int) (top(height) + SIZE / 2.0 + (wz - centreZ) * scale)};
	}

	static void draw(GuiGraphics graphics, int width, int height) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null) return;
		if (texture == null) {
			texture = new DynamicTexture(SIZE, SIZE, false);
			mc.getTextureManager().register(TEXTURE, texture);
		}
		if (frame++ % 10 == 0) repaint(level);
		int x0 = left(), y0 = top(height);
		graphics.fill(x0 - 2, y0 - 2, x0 + SIZE + 2, y0 + SIZE + 2, 0xE0101418);
		graphics.blit(TEXTURE, x0, y0, 0, 0, SIZE, SIZE, SIZE, SIZE);
		// units
		for (Entity entity : level.entitiesForRendering()) {
			if (!(entity instanceof StarUnit unit) || !unit.isAlive()) continue;
			boolean own = CommandView.own(unit);
			if (!own && !CommandView.visible(unit)) continue;
			int[] at = screen(unit.getX(), unit.getZ(), height);
			if (at[0] < x0 || at[1] < y0 || at[0] >= x0 + SIZE || at[1] >= y0 + SIZE) continue;
			int r = unit.kind.structure() ? 2 : 1;
			int colour = own ? (CommandView.selection.contains(unit.getId()) ? 0xFFFFFFFF : 0xFF40FF40) : 0xFFFF4040;
			graphics.fill(at[0] - r, at[1] - r, at[0] + r + 1, at[1] + r + 1, colour);
		}
		Orders.minimap(graphics, (x, z) -> screen(x, z, height));
		// the camera's view: the screen corners on the ground
		int[][] corners = new int[4][];
		double[][] screenCorners = {{0, 0}, {width, 0}, {width, height}, {0, height}};
		for (int i = 0; i < 4; i++) {
			Vec3[] ray = CommandView.ray(screenCorners[i][0], screenCorners[i][1], width, height);
			double t = ray[1].y < -0.05 ? (ray[0].y - CommandView.groundY) / -ray[1].y : 120;
			Vec3 on = ray[0].add(ray[1].scale(Math.min(t, 200)));
			corners[i] = screen(on.x, on.z, height);
		}
		graphics.enableScissor(x0, y0, x0 + SIZE, y0 + SIZE);
		for (int i = 0; i < 4; i++) line(graphics, corners[i], corners[(i + 1) % 4], 0xFFFFFFFF);
		graphics.disableScissor();
		graphics.renderOutline(x0 - 2, y0 - 2, SIZE + 4, SIZE + 4, 0xFF6080B0);
	}

	/** A thin line of dots (GUI fills) between two points. */
	private static void line(GuiGraphics graphics, int[] a, int[] b, int colour) {
		int steps = Math.max(Math.abs(b[0] - a[0]), Math.abs(b[1] - a[1]));
		for (int i = 0; i <= steps; i++) {
			int x = a[0] + (b[0] - a[0]) * i / Math.max(1, steps), y = a[1] + (b[1] - a[1]) * i / Math.max(1, steps);
			graphics.fill(x, y, x + 1, y + 1, colour);
		}
	}

	/** Redraws the ground around the camera: map colour by height, then the fog. */
	private static void repaint(ClientLevel level) {
		NativeImage image = texture.getPixels();
		if (image == null) return;
		centreX = Math.floor(CommandView.targetX);
		centreZ = Math.floor(CommandView.targetZ);
		double scale = 2.0 * RADIUS / SIZE;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int py = 0; py < SIZE; py++)
			for (int px = 0; px < SIZE; px++) {
				int wx = Mth.floor(centreX + (px - SIZE / 2.0) * scale), wz = Mth.floor(centreZ + (py - SIZE / 2.0) * scale);
				int argb;
				if (!level.hasChunk(wx >> 4, wz >> 4)) argb = 0xFF000000;
				else {
					int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz) - 1;
					BlockState state = level.getBlockState(pos.set(wx, top, wz));
					MapColor colour = state.getMapColor(level, pos);
					int north = level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz - 1) - 1;
					MapColor.Brightness shade = top > north ? MapColor.Brightness.HIGH : top < north ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
					argb = colour == MapColor.NONE ? 0xFF203018 : toAbgr(colour.calculateRGBColor(shade)); // map colours come as ABGR
					// fog: dimmed where seen before, dark where never seen, clear in sight
					float dark = CommandView.fogAt(level, wx, wz);
					if (dark > 0) argb = darken(argb, dark);
				}
				image.setPixelRGBA(px, py, toAbgr(argb));
			}
		texture.upload();
	}

	private static int darken(int argb, float dark) {
		float keep = 1 - dark;
		int r = (int) (((argb >> 16) & 255) * keep), g = (int) (((argb >> 8) & 255) * keep), b = (int) ((argb & 255) * keep);
		return 0xFF000000 | r << 16 | g << 8 | b;
	}

	/** Swaps red and blue: ARGB to ABGR (NativeImage pixels, map colours) and back. */
	private static int toAbgr(int argb) {
		return (argb & 0xFF00FF00) | (argb & 0xFF) << 16 | (argb >> 16) & 0xFF;
	}
}
