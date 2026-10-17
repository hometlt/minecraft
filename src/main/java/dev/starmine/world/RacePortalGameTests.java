package dev.starmine.world;

import static dev.starmine.ability.TestArena.ARENA;

import dev.starmine.ability.TestArena;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveSavedData;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * Owner 17.10: a race's main building fallen, a portal to its sky ship opens where it stood (no keys); the
 * portal hall under the base sleeps until then.
 */
public class RacePortalGameTests implements FabricGameTest {
	@GameTest(template = ARENA, batch = "racePortal", timeoutTicks = 300)
	public void aFallenMainBaseOpensAPortal(GameTestHelper helper) {
		TestArena.clear(helper);
		var level = helper.getLevel();
		BlockPos shipPad = helper.absolutePos(new BlockPos(42, 2, 42)), hallPortal = helper.absolutePos(new BlockPos(4, 2, 4));
		level.setBlock(shipPad, Teleporter.BLOCKS.get(UnitKind.Race.TERRAN).defaultBlockState(), 3);
		level.setBlock(hallPortal, Teleporter.BLOCKS.get(UnitKind.Race.TERRAN).defaultBlockState(), 3);
		Hive base = new Hive(UUID.randomUUID(), level.dimension().location().toString(), helper.absolutePos(new BlockPos(24, 2, 24)));
		base.race = UnitKind.Race.TERRAN;
		base.shipPad = shipPad;
		HiveSavedData.get(level.getServer()).add(base);
		Teleporter.linkPortal(level, List.of(hallPortal), hallPortal, shipPad);
		Teleporter.lock(level, List.of(hallPortal), base.id);
		StarUnit cc = TestArena.unit(helper, UnitKind.COMMAND_CENTER, 24, 24, null);
		cc.joinHive(base);
		base.join(UnitKind.COMMAND_CENTER);
		BlockPos where = cc.blockPosition();
		helper.assertTrue(level.getBlockEntity(hallPortal) instanceof Teleporter.Pad pad && Teleporter.locked(level, pad), "the hall's portal is awake with the base standing");
		helper.runAfterDelay(5, cc::kill);
		helper.succeedWhen(() -> {
			helper.assertTrue(level.getBlockState(where).getBlock() instanceof Teleporter, "no portal where the command center stood");
			helper.assertTrue(level.getBlockEntity(where) instanceof Teleporter.Pad portal && shipPad.equals(portal.pair()), "the portal does not lead to the ship");
			helper.assertTrue(level.getBlockEntity(shipPad) instanceof Teleporter.Pad pad && pad.pair() != null && pad.pair().distSqr(where) < 4,
				"the ship's pad does not lead back to the new portal");
			helper.assertTrue(level.getBlockEntity(hallPortal) instanceof Teleporter.Pad pad && !Teleporter.locked(level, pad), "the hall's portal still sleeps");
			HiveSavedData.get(level.getServer()).remove(base.id);
		});
	}

}
