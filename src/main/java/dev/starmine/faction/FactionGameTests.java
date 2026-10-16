package dev.starmine.faction;

import dev.starmine.StarMine;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;

/** The side rule (docs/ARCHITECTURE.md): a player's units, wild units of each race, other players. */
public class FactionGameTests implements FabricGameTest {
	private static StarUnit unit(GameTestHelper helper, UnitKind kind, UUID owner, int x) {
		StarUnit unit = helper.spawn(StarMine.type(kind), new BlockPos(x, 2, 2));
		unit.setOwner(owner);
		unit.setNoAi(true);
		return unit;
	}

	@GameTest(template = "starmine:arena", batch = "faction")
	public void sides(GameTestHelper helper) {
		UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
		StarUnit aliceMarine = unit(helper, UnitKind.MARINE, alice, 2), aliceLing = unit(helper, UnitKind.ZERGLING, alice, 4);
		StarUnit bobMarine = unit(helper, UnitKind.MARINE, bob, 6);
		StarUnit wildLing = unit(helper, UnitKind.ZERGLING, null, 8), wildLing2 = unit(helper, UnitKind.ZERGLING, null, 10);
		StarUnit wildMarine = unit(helper, UnitKind.MARINE, null, 12);
		Cow cow = helper.spawn(EntityType.COW, new BlockPos(14, 2, 2));
		helper.assertTrue(Faction.allied(aliceMarine, aliceLing), "a player's units of two races are not allies");
		helper.assertFalse(Faction.hostile(aliceMarine, bobMarine), "two players' units fight unprovoked");
		helper.assertTrue(Faction.hostile(aliceMarine, wildLing) && Faction.hostile(aliceLing, wildLing), "a player's units leave the wild alone");
		helper.assertTrue(Faction.hostile(wildLing, aliceMarine) && Faction.hostile(wildMarine, aliceLing), "the wild leaves a player's units alone");
		helper.assertTrue(Faction.allied(wildLing, wildLing2), "wild units of one race are not allies");
		helper.assertTrue(Faction.hostile(wildLing, wildMarine) && Faction.hostile(wildMarine, wildLing), "wild races do not fight each other");
		helper.assertTrue(Faction.hostile(wildLing, cow) && !Faction.hostile(wildMarine, cow) && !Faction.hostile(aliceLing, cow),
			"only wild zerg attack every creature");
		helper.succeed();
	}

	/** Owner 16.10: the races are always foes — wild zerglings and wild marines put near each other fight at once. */
	@GameTest(template = "starmine:arena", batch = "factionFight", timeoutTicks = 400)
	public void wildRacesFightEachOther(GameTestHelper helper) {
		dev.starmine.ability.TestArena.clear(helper);
		java.util.List<StarUnit> units = new java.util.ArrayList<>();
		for (int i = 0; i < 3; i++) {
			units.add(dev.starmine.ability.TestArena.unit(helper, UnitKind.ZERGLING, 12, 18 + i * 3, null));
			units.add(dev.starmine.ability.TestArena.unit(helper, UnitKind.MARINE, 30, 18 + i * 3, null));
			units.add(dev.starmine.ability.TestArena.unit(helper, UnitKind.ZEALOT, 21, 34 + i * 3, null));
		}
		java.util.Set<String> fights = new java.util.HashSet<>();
		helper.succeedWhen(() -> {
			for (StarUnit unit : units)
				if (unit.getLastHurtByMob() instanceof StarUnit other && other.kind.race != unit.kind.race) fights.add(other.kind.race + ">" + unit.kind.race);
			helper.assertTrue(fights.stream().map(f -> f.substring(0, f.indexOf('>'))).distinct().count() == 3,
				"not every race fought another: " + fights);
			units.forEach(StarUnit::discard);
		});
	}
}
