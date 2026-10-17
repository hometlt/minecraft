package dev.starmine.world;

import static dev.starmine.ability.TestArena.ARENA;
import static dev.starmine.ability.TestArena.clear;
import static dev.starmine.ability.TestArena.point;

import dev.starmine.ability.Transport;
import dev.starmine.entity.StarUnit;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveSavedData;
import dev.starmine.mineral.Minerals;
import dev.starmine.vespene.Vespene;
import dev.starmine.vespene.VespeneBlock;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * The Void's economy (owner 17.10): gold crystals and terrazine are worth twice, water on vespene makes
 * cobblestone, and the player's army for the Void — transports full, flyers — is theirs, of their Void base.
 */
public class VoidEconomyGameTests implements FabricGameTest {
	@GameTest(template = ARENA, batch = "voidGold", timeoutTicks = 20)
	public void goldAndTerrazineAreWorthTwice(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		BlockPos plain = BlockPos.containing(point(helper, 10, 10)), gold = BlockPos.containing(point(helper, 30, 10));
		helper.assertTrue(Minerals.place(level, plain, (pos, state) -> level.setBlock(pos, state, 2)), "no plain crystal");
		helper.assertTrue(Minerals.place(level, gold, (pos, state) -> level.setBlock(pos, state, 2)), "no gold crystal");
		((Minerals.Core) level.getBlockEntity(gold)).setRich(true);
		int one = Minerals.harvest(level, plain, Minerals.PER_TRIP), two = Minerals.harvest(level, gold, Minerals.PER_TRIP);
		int left = ((Minerals.Core) level.getBlockEntity(gold)).amount();
		helper.assertTrue(one == Minerals.PER_TRIP && two == Minerals.PER_TRIP * 2, "a trip gave " + one + " and, of gold, " + two);
		helper.assertTrue(left == Minerals.AMOUNT - Minerals.PER_TRIP, "the gold crystal lost " + (Minerals.AMOUNT - left));
		helper.assertTrue(Vespene.perTrip(Vespene.TERRAZINE.defaultBlockState()) == Vespene.PER_TRIP * 2
			&& Vespene.perTrip(Vespene.VESPENE.defaultBlockState()) == Vespene.PER_TRIP, "terrazine is not worth twice vespene");
		helper.assertTrue(VespeneBlock.isSource(Vespene.TERRAZINE.defaultBlockState()), "terrazine is no source for workers");
		helper.succeed();
	}

	@GameTest(template = ARENA, batch = "voidWater", timeoutTicks = 20)
	public void waterOnVespeneMakesCobblestone(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		BlockPos at = BlockPos.containing(point(helper, 20, 20));
		level.setBlock(at, Vespene.VESPENE.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(at.east(), Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
		BlockPos outlet = at.south(6);
		level.setBlock(outlet, Vespene.VESPENE.defaultBlockState().setValue(VespeneBlock.OUTLET, true), Block.UPDATE_ALL);
		level.setBlock(outlet.east(), Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
		helper.runAfterDelay(2, () -> {
			helper.assertTrue(level.getBlockState(at).is(Blocks.COBBLESTONE), "vespene under water is " + level.getBlockState(at));
			helper.assertTrue(VespeneBlock.isSource(level.getBlockState(outlet)), "an outlet was sealed by water");
			helper.assertTrue(level.getBlockState(outlet.east()).is(Blocks.COBBLESTONE), "the water on an outlet is " + level.getBlockState(outlet.east()));
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "voidArmy", timeoutTicks = 40)
	public void theVoidArmyIsThePlayers(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		HiveSavedData data = HiveSavedData.get(level.getServer());
		UUID player = UUID.randomUUID();
		List<StarUnit> roots = VoidArmy.spawn(level, point(helper, 24, 24), 3, player);
		Hive base = data.baseOf(player, VoidArmy.VOID_DIMENSION);
		helper.runAfterDelay(2, () -> {
			long transports = roots.stream().filter(unit -> Transport.capacity(unit.kind) > 0).count();
			boolean full = roots.stream().filter(unit -> Transport.capacity(unit.kind) > 0).allMatch(unit -> !unit.getPassengers().isEmpty());
			boolean owned = roots.stream().allMatch(unit -> unit.owner().filter(player::equals).isPresent() && base != null && base.id.equals(unit.hiveId()));
			long races = roots.stream().map(unit -> unit.kind.race).distinct().count();
			for (StarUnit unit : roots) {
				for (Entity rider : List.copyOf(unit.getPassengers())) rider.discard();
				unit.discard();
			}
			if (base != null) data.remove(base.id);
			helper.assertTrue(base != null && base.minerals == VoidArmy.START_MINERALS, "no Void base with minerals to start");
			helper.assertTrue(transports >= 3 && full, transports + " transports, full: " + full);
			helper.assertTrue(owned, "the army is not the player's, of the Void base");
			helper.assertTrue(races == 3, "the army is of " + races + " races");
			helper.succeed();
		});
	}
}
