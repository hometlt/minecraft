package dev.starmine.world;

import dev.starmine.entity.UnitKind;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The dungeon of a race's portal (owner 16.10, instead of a teleporter pad on the surface): a hall under
 * the base, 19 x 19 and 9 high, in the race's stone, lit; in its middle a portal like the End's — 3 x 3
 * portal blocks ({@link Teleporter}) in a ring of frame blocks — that is off while its guards live. A stair
 * leads down to it from the surface ({@link Dungeons#stairsUp}).
 */
public final class PortalHall {
	/** Half the hall's width, its height inside, how deep its floor is under the ground. */
	public static final int HALF = 9, HEIGHT = 9, DEPTH = 18;

	private PortalHall() {
	}

	/** What a hall is: its portal blocks, their middle, and its guards. */
	public record Hall(List<BlockPos> portal, BlockPos centre, List<Spawn> guards) {
	}

	/** Builds a hall whose floor middle (feet level) is `centre`, the stair going up toward `out`. */
	public static Hall build(Painter painter, UnitKind.Race race, BlockPos centre, Direction out) {
		BlockState wall, floor, trim, light;
		switch (race) {
			case ZERG -> {
				wall = dev.starmine.block.StarMineBlocks.ZERG_FLESH.defaultBlockState();
				floor = Blocks.CRIMSON_NYLIUM.defaultBlockState();
				trim = Blocks.BONE_BLOCK.defaultBlockState();
				light = dev.starmine.block.StarMineBlocks.GLOWING_ZERG_FLESH.defaultBlockState();
			}
			case TERRAN -> {
				wall = Blocks.POLISHED_DEEPSLATE.defaultBlockState();
				floor = Blocks.GRAY_CONCRETE.defaultBlockState();
				trim = Blocks.YELLOW_CONCRETE.defaultBlockState();
				light = Blocks.OCHRE_FROGLIGHT.defaultBlockState();
			}
			default -> {
				wall = Blocks.CUT_SANDSTONE.defaultBlockState();
				floor = Blocks.SMOOTH_SANDSTONE.defaultBlockState();
				trim = Blocks.DARK_PRISMARINE.defaultBlockState();
				light = Blocks.SEA_LANTERN.defaultBlockState();
			}
		}
		// the shell, then the inside
		painter.box(centre.offset(-HALF - 1, -1, -HALF - 1), centre.offset(HALF + 1, HEIGHT, HALF + 1), pos -> {
			int y = pos.getY() - centre.getY();
			if (y == -1) return floor;
			boolean corner = Math.abs(pos.getX() - centre.getX()) == HALF + 1 && Math.abs(pos.getZ() - centre.getZ()) == HALF + 1;
			if (y == 3 && !corner) return trim;
			return (pos.getX() + pos.getZ()) % 6 == 0 && y == HEIGHT ? light : wall;
		});
		painter.hollow(centre.offset(-HALF, 0, -HALF), centre.offset(HALF, HEIGHT - 1, HALF), wall);
		for (int[] corner : new int[][] {{-HALF, -HALF}, {HALF, -HALF}, {-HALF, HALF}, {HALF, HALF}})
			for (int y = 0; y < HEIGHT; y++) painter.set(centre.offset(corner[0], y, corner[1]), y % 4 == 2 ? light : trim);
		// the portal: frame blocks round 3 x 3 portal blocks, a step up, with lights at its corners
		BlockState frame = Teleporter.FRAMES.get(race).defaultBlockState(), portalBlock = Teleporter.BLOCKS.get(race).defaultBlockState();
		List<BlockPos> portal = new ArrayList<>();
		for (int dx = -2; dx <= 2; dx++)
			for (int dz = -2; dz <= 2; dz++) {
				BlockPos pos = centre.offset(dx, 0, dz);
				boolean ring = Math.abs(dx) == 2 || Math.abs(dz) == 2;
				if (ring) {
					painter.set(pos, Math.abs(dx) == 2 && Math.abs(dz) == 2 ? light : frame);
				} else {
					painter.set(pos.below(), frame);
					painter.set(pos, portalBlock);
					portal.add(pos.immutable());
				}
			}
		// the door and the stair up
		BlockPos door = centre.relative(out, HALF + 1);
		Direction side = out.getClockWise();
		for (int s = -1; s <= 1; s++)
			for (int y = 0; y < 4; y++) painter.carve(door.relative(side, s).above(y), wall);
		Dungeons.stairsUp(painter, door.relative(out), out, wall, trim);
		// its guards
		UnitKind[] kinds = switch (race) {
			case ZERG -> new UnitKind[] {UnitKind.ZERGLING, UnitKind.ROACH, UnitKind.ZERGLING, UnitKind.HYDRALISK, UnitKind.ROACH};
			case TERRAN -> new UnitKind[] {UnitKind.MARINE, UnitKind.MARAUDER, UnitKind.MARINE, UnitKind.MARINE, UnitKind.GHOST};
			case PROTOSS -> new UnitKind[] {UnitKind.ZEALOT, UnitKind.STALKER, UnitKind.ZEALOT, UnitKind.ADEPT, UnitKind.SENTRY};
		};
		List<Spawn> guards = new ArrayList<>();
		for (int i = 0; i < kinds.length; i++) {
			double angle = i * Math.PI * 2 / kinds.length + Math.PI / 5;
			BlockPos at = centre.offset((int) Math.round(Math.cos(angle) * 6), 0, (int) Math.round(Math.sin(angle) * 6));
			guards.add(Spawn.guard(kinds[i], at, centre));
		}
		return new Hall(portal, centre.immutable(), guards);
	}
}
