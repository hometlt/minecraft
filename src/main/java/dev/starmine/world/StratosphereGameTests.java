package dev.starmine.world;

import static dev.starmine.ability.TestArena.ARENA;

import dev.starmine.ability.TestArena;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/**
 * Owner 17.10, the stratosphere: up only carried by a flyer (alone a player stays below); whatever falls out of it
 * comes down into the overworld; the races' ships hang there. (The game test server makes only the vanilla
 * dimensions: where the stratosphere is missing the ships hang over their bases, and the trips are checked by
 * their rules alone.)
 */
public class StratosphereGameTests implements FabricGameTest {
	@GameTest(template = ARENA, batch = "stratosphereUp", timeoutTicks = 200)
	public void onlyAFlyerCarriesAPlayerUp(GameTestHelper helper) {
		TestArena.clear(helper);
		ServerLevel level = helper.getLevel();
		ServerLevel sky = Stratosphere.level(level.getServer());
		helper.assertTrue(RaceBases.skyLevel(level) == (sky != null ? sky : level), "the ships are built in " + RaceBases.skyLevel(level).dimension());
		ServerPlayer rider = (ServerPlayer) helper.makeMockServerPlayerInLevel(), alone = (ServerPlayer) helper.makeMockServerPlayerInLevel();
		StarUnit medivac = TestArena.unit(helper, UnitKind.MEDIVAC, 24, 24, rider.getUUID());
		StarUnit marine = TestArena.unit(helper, UnitKind.MARINE, 30, 24, null);
		BlockPos at = helper.absolutePos(new BlockPos(24, 2, 24));
		rider.teleportTo(at.getX() + 0.5, at.getY() + 3, at.getZ() + 0.5);
		rider.startRiding(medivac, true);
		marine.startRiding(medivac, true);
		try {
			helper.assertTrue(Stratosphere.carried(rider), "a player riding a medivac is not carried up");
			helper.assertFalse(Stratosphere.carried(alone), "a player alone is carried up");
			if (sky != null) { // the trip itself where the dimension is there
				Stratosphere.up(rider, sky);
				helper.assertTrue(rider.level() == sky && rider.getVehicle() instanceof StarUnit carried && carried.kind == UnitKind.MEDIVAC,
					"the rider did not go up with the medivac");
			}
		} finally {
			((ServerLevel) rider.level()).removePlayerImmediately(rider, Entity.RemovalReason.DISCARDED);
			level.removePlayerImmediately(alone, Entity.RemovalReason.DISCARDED);
		}
		helper.succeed();
	}

	@GameTest(template = ARENA, batch = "stratosphereDown", timeoutTicks = 100)
	public void whatFallsOutComesDownIntoTheOverworld(GameTestHelper helper) {
		ServerLevel sky = Stratosphere.level(helper.getLevel().getServer());
		ServerLevel from = sky != null ? sky : helper.getLevel();
		BlockPos at = helper.absolutePos(new BlockPos(24, 2, 24));
		var cow = EntityType.COW.create(from);
		cow.moveTo(at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5);
		from.addFreshEntity(cow);
		Stratosphere.down(cow, helper.getLevel());
		helper.succeedWhen(() -> {
			Entity down = helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.animal.Cow.class,
				new net.minecraft.world.phys.AABB(at).inflate(4, 400, 4)).stream().findFirst().orElse(null);
			helper.assertTrue(down != null && down.getY() > Stratosphere.LAND_Y - 20, "the cow did not come down over the overworld");
			down.discard();
		});
	}
}
