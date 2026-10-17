package dev.starmine.world;

import static dev.starmine.ability.TestArena.ARENA;

import dev.starmine.ability.TestArena;
import dev.starmine.mineral.Minerals;
import java.util.Arrays;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/** A laid-over map (owner 17.10): its columns, and its mineral fields made into crystals. */
public class MapOverlayGameTests implements FabricGameTest {
	/** A 16 x 16 map at `origin` whose ground top is a block under `origin`, with one mineral field marker. */
	private static MapOverlay overlay(BlockPos origin, String state, String variant) {
		CompoundTag tag = new CompoundTag();
		tag.putString("Format", MapOverlay.FORMAT);
		tag.putInt("Version", 1);
		tag.putString("Name", "test");
		tag.putInt("MinX", origin.getX());
		tag.putInt("MinZ", origin.getZ());
		tag.putInt("SizeX", 16);
		tag.putInt("SizeZ", 16);
		tag.putInt("BaseY", origin.getY());
		ListTag palette = new ListTag();
		palette.add(StringTag.valueOf("minecraft:grass_block"));
		palette.add(StringTag.valueOf("minecraft:dirt"));
		tag.put("Palette", palette);
		int[] top = new int[256], zero = new int[256], none = new int[256];
		Arrays.fill(top, origin.getY() - 1);
		Arrays.fill(none, MapOverlay.NONE);
		int[] under = new int[256];
		Arrays.fill(under, 1);
		top[255] = MapOverlay.NONE; // a column without ground
		tag.putIntArray("Top", top);
		tag.putIntArray("TopBlock", zero);
		tag.putIntArray("Under", under);
		tag.putIntArray("Water", none);
		tag.putIntArray("Fluid", zero);
		ListTag markers = new ListTag();
		CompoundTag field = new CompoundTag();
		field.putString("Role", "resource");
		field.putString("Kind", "minerals");
		field.putString("State", state);
		if (variant != null) field.putString("Variant", variant);
		field.putInt("X", origin.getX() + 6);
		field.putInt("Y", origin.getY());
		field.putInt("Z", origin.getZ() + 6);
		field.putInt("MinX", origin.getX() + 4);
		field.putInt("MinZ", origin.getZ() + 6);
		field.putInt("MaxX", origin.getX() + 9);
		field.putInt("MaxZ", origin.getZ() + 8);
		markers.add(field);
		tag.put("Markers", markers);
		return MapOverlay.of(tag);
	}

	@GameTest(template = ARENA, batch = "map_overlay", timeoutTicks = 100)
	public void mineralFieldBecomesCrystalsOnTheMapGround(GameTestHelper helper) {
		TestArena.clear(helper);
		ServerLevel level = helper.getLevel();
		BlockPos origin = helper.absolutePos(new BlockPos(16, 2, 16));
		for (BlockPos pos : BlockPos.betweenClosed(origin.offset(0, -1, 0), origin.offset(15, -1, 15))) level.setBlock(pos, Blocks.DIRT.defaultBlockState(), 2);
		MapOverlay overlay = overlay(origin, "reduced", "rich");
		helper.assertTrue(overlay.ground(origin.getX() + 3, origin.getZ() + 3) == origin.getY(), "the ground is not over the top block");
		helper.assertTrue(overlay.ground(origin.getX() + 15, origin.getZ() + 15) == MapOverlay.NONE, "a column without ground has ground");
		helper.assertTrue(overlay.ground(origin.getX() - 1, origin.getZ()) == MapOverlay.NONE, "outside the map is ground");
		MapOverlay.place(level, overlay, overlay.markers.getFirst());
		int crystals = 0;
		for (BlockPos pos : BlockPos.betweenClosed(origin.offset(3, 0, 5), origin.offset(10, 0, 9)))
			if (level.getBlockEntity(pos) instanceof Minerals.Core core) {
				crystals++;
				helper.assertTrue(core.amount() == Minerals.AMOUNT * 3 / 5 * 3 / 2, "a rich reduced field holds " + core.amount());
			}
		helper.assertTrue(crystals == 2, "a 6-block field made " + crystals + " crystals, not 2");
		helper.succeed();
	}
}
