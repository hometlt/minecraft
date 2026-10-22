package dev.starmine.entity;

import dev.starmine.ability.TestArena;
import dev.starmine.colony.Engineering;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveSavedData;
import java.util.ArrayList;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/** Real moving units, excavation, bridges and automatic loading/unloading. */
public class LogisticsGameTests implements FabricGameTest {
	@GameTest(template = TestArena.ARENA, batch = "wildOverlordCargo", timeoutTicks = 300)
	public void wildOverlordsNeedTheirTransportUpgradeToo(GameTestHelper h) {
		TestArena.clear(h);
		var level = h.getLevel();
		Hive base = new Hive(UUID.randomUUID(), level.dimension().location().toString(), BlockPos.containing(TestArena.point(h, 24, 24)));
		HiveSavedData.get(level.getServer()).add(base);
		StarUnit overlord = TestArena.member(h, base, UnitKind.OVERLORD, 24, 24);
		h.assertTrue(dev.starmine.ability.Transport.capacity(overlord) == 0, "wild overlord bypassed its upgrade");
		base.tier = 2; base.minerals = 50; base.vespene = 50;
		overlord.setMode(dev.starmine.ability.Modes.CREEPING); overlord.setNoAi(true);
		h.assertTrue(dev.starmine.ability.Abilities.cast(overlord, level, dev.starmine.ability.Ability.VENTRAL_SACS, null, null), "creep mode blocked the transport upgrade");
		h.runAfterDelay(dev.starmine.ability.Ability.VENTRAL_SACS.delayTicks + 2, () -> {
			h.assertTrue(overlord.mode() == dev.starmine.ability.Modes.CREEPING_SACS && dev.starmine.ability.Transport.capacity(overlord) == 8,
				"transport upgrade lost creep or cargo");
			h.assertTrue(base.minerals == 25 && base.vespene == 25, "transport upgrade did not pay its normal cost");
			h.succeed();
		});
	}
	@GameTest(template = TestArena.ARENA, batch = "logisticsTunnel", timeoutTicks = 2400)
	public void workersTunnelForAnArmy(GameTestHelper h) { cross(h, false, false); }
	@GameTest(template = TestArena.ARENA, batch = "logisticsBridge", timeoutTicks = 3000)
	public void workersBridgeForAnArmy(GameTestHelper h) { cross(h, true, false); }
	@GameTest(template = TestArena.ARENA, batch = "logisticsFerry", timeoutTicks = 3000)
	public void medivacFerriesAnArmyAcrossWater(GameTestHelper h) { cross(h, true, true); }
	@GameTest(template = TestArena.ARENA, batch = "logisticsWallFerry", timeoutTicks = 3000)
	public void medivacBypassesAnUnbreakableWall(GameTestHelper h) { cross(h, false, true); }

	private static void cross(GameTestHelper h, boolean water, boolean transport) {
		TestArena.clear(h);
		var level = h.getLevel(); level.getServer().tickRateManager().setTickRate(100);
		for (BlockPos rel : BlockPos.betweenClosed(18, water ? 0 : 2, 0, water ? 27 : 20, water ? 1 : 6, 47))
			level.setBlock(h.absolutePos(rel), (water ? Blocks.WATER : transport ? Blocks.BEDROCK : Blocks.STONE).defaultBlockState(), 2);
		Hive base = new Hive(UUID.randomUUID(), level.dimension().location().toString(), BlockPos.containing(TestArena.point(h, 6, 40)));
		base.race = UnitKind.Race.TERRAN; base.owner = UUID.randomUUID(); base.minerals = 2000;
		HiveSavedData.get(level.getServer()).add(base);
		for (int i = 0; i < 3; i++) TestArena.member(h, base, UnitKind.SCV, 8 + i * 3, 34);
		if (transport) TestArena.member(h, base, UnitKind.MEDIVAC, 8, 16);
		var army = new ArrayList<StarUnit>();
		army.add(TestArena.member(h, base, UnitKind.MARINE, 8, 10));
		army.add(TestArena.member(h, base, UnitKind.MARAUDER, 8, 14));
		var goal = TestArena.point(h, 36, 14);
		var timeline = TestArena.Timeline.of(h);
		timeline.at(5, () -> {
			var taken = Engineering.order(level, base, army, StarUnit.Order.MOVE, goal);
			h.assertTrue(taken.size() == army.size(), "no engineering/ferry job for blocked army");
		});
		boolean[] aboard = {false};
		timeline.every(() -> {
			aboard[0] |= army.stream().anyMatch(StarUnit::isPassenger);
			if (army.stream().allMatch(u -> u.isAlive() && !u.isPassenger() && TestArena.rel(h, u).x > 30)) {
				h.assertTrue(!transport || aboard[0], "army bypassed obstacle without using transport");
				h.succeed();
			}
		});
	}
}
