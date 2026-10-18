package dev.starmine.world;

import static dev.starmine.ability.TestArena.ARENA;
import static dev.starmine.ability.TestArena.clear;
import static dev.starmine.ability.TestArena.point;
import static dev.starmine.ability.TestArena.unit;

import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import java.util.EnumSet;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.phys.Vec3;

/**
 * The world (owner 17.10): beyond the first three bases the races' bases are rare, far apart, one region in
 * a few, never near the spawn, of every race; monsters do not spawn within 30 blocks of any unit or building.
 */
public class FarBaseGameTests implements FabricGameTest {
	@GameTest(template = ARENA, batch = "farBases", timeoutTicks = 20)
	public void farBasesAreRareFarApartAndOfEveryRace(GameTestHelper helper) {
		BlockPos origin = new BlockPos(100, 70, -40);
		int bases = 0, regions = 0;
		Set<UnitKind.Race> races = EnumSet.noneOf(UnitKind.Race.class);
		for (int rx = -6; rx <= 6; rx++)
			for (int rz = -6; rz <= 6; rz++) {
				regions++;
				RaceBases.Far far = RaceBases.far(1234L, origin, rx, rz);
				RaceBases.Far again = RaceBases.far(1234L, origin, rx, rz);
				helper.assertTrue(far == null ? again == null : far.equals(again), "region " + rx + "," + rz + " is not the same twice");
				if (far == null) continue;
				bases++;
				races.add(far.race());
				helper.assertTrue(Math.floorDiv(far.x(), RaceBases.REGION) == rx && Math.floorDiv(far.z(), RaceBases.REGION) == rz, "a base outside its region");
				double fromSpawn = Math.hypot(far.x() - origin.getX(), far.z() - origin.getZ());
				helper.assertTrue(fromSpawn >= RaceBases.FAR_FROM_SPAWN, "a far base " + (int) fromSpawn + " blocks from the spawn");
			}
		helper.assertTrue(bases > regions * RaceBases.FAR_CHANCE / 2 && bases < regions * RaceBases.FAR_CHANCE * 1.5, // owner 18.10: rarer
			bases + " far bases in " + regions + " regions");
		helper.assertTrue(races.size() == 3, "far bases of only " + races);
		helper.succeed();
	}

	/** Owner 18.10: a player's unit at a base's site scouts it (the body stays home while the player commands); a wild one does not. */
	@GameTest(template = ARENA, batch = "farBases", timeoutTicks = 20)
	public void aPlayersUnitScoutsASite(GameTestHelper helper) {
		clear(helper);
		Vec3 site = point(helper, 24, 24);
		unit(helper, UnitKind.ZEALOT, 10, 10, null);
		helper.assertTrue(!RaceBases.scouted(helper.getLevel(), site.x + 200, site.z, 20), "a wild unit scouted a site");
		unit(helper, UnitKind.ZEALOT, 30, 24, java.util.UUID.randomUUID());
		helper.assertTrue(RaceBases.scouted(helper.getLevel(), site.x, site.z, 20), "a player's unit 6 blocks off did not scout the site");
		helper.assertTrue(!RaceBases.scouted(helper.getLevel(), site.x + 200, site.z, 20), "a site 200 blocks off was scouted");
		helper.succeed();
	}

	@GameTest(template = ARENA, batch = "farGuard", timeoutTicks = 40)
	public void noMonstersNearAnyUnit(GameTestHelper helper) {
		clear(helper);
		var level = helper.getLevel();
		StarUnit marine = unit(helper, UnitKind.MARINE, 4, 4, null);
		marine.setNoAi(true);
		helper.runAfterDelay(2, () -> {
			Zombie zombie = EntityType.ZOMBIE.create(level);
			Vec3 near = point(helper, 30, 4);
			boolean refused = SpawnGuard.refuses(level, zombie, BlockPos.containing(near));
			boolean far = SpawnGuard.refuses(level, zombie, BlockPos.containing(near.add(40, 0, 40)));
			zombie.discard();
			marine.discard();
			helper.assertTrue(refused, "a monster may spawn 26 blocks from a marine");
			helper.assertFalse(far, "no monster may spawn 60 blocks from the marine");
			helper.succeed();
		});
	}
}
