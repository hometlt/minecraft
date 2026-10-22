package dev.starmine.ability;

import static dev.starmine.ability.TestArena.ARENA;
import static dev.starmine.ability.TestArena.clear;
import static dev.starmine.ability.TestArena.point;
import static dev.starmine.ability.TestArena.unit;

import dev.starmine.block.StarMineBlocks;
import dev.starmine.creep.CreepGrowth;
import dev.starmine.creep.CreepTumors;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/**
 * Owner 17.10, SC2 creep tumors: burrowed after their first seconds (seen by their side and detectors only), shot
 * down by units that see them, wounded by arrows.
 */
public class CreepTumorGameTests implements FabricGameTest {
	@GameTest(template = ARENA, batch = "creepCellScale", timeoutTicks = 20)
	public void creepUsesTheThreeBlockCellAndTerranBasesDoNotFeedIt(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		BlockPos at = helper.absolutePos(new BlockPos(24, 2, 24));
		var base = new dev.starmine.hive.Hive(UUID.randomUUID(), level.dimension().location().toString(), at);
		dev.starmine.hive.HiveSavedData.get(level.getServer()).add(base);
		base.race = UnitKind.Race.TERRAN;
		helper.assertFalse(dev.starmine.creep.CreepSources.covered(level, at), "terran base feeds creep");
		base.race = UnitKind.Race.ZERG;
		helper.assertTrue(dev.starmine.creep.CreepSources.covered(level, at.east(35)), "hatchery stops before twelve three-block cells");
		helper.assertFalse(dev.starmine.creep.CreepSources.covered(level, at.east(38)), "hatchery exceeds its radius");
		helper.assertTrue(FieldAbilities.OVERLORD_CREEP_RADIUS == 12, "overlord radius ignores cell scale");
		helper.succeed();
	}

	/** A creep floor and a wild tumor on it at (24, 24). */
	private static BlockPos tumor(GameTestHelper helper, UUID owner) {
		ServerLevel level = helper.getLevel();
		for (int x = 18; x <= 30; x++)
			for (int z = 18; z <= 30; z++) level.setBlock(helper.absolutePos(new BlockPos(x, 2, z)), StarMineBlocks.CREEP.defaultBlockState()
				.setValue(net.minecraft.world.level.block.MultifaceBlock.getFaceProperty(net.minecraft.core.Direction.DOWN), true), Block.UPDATE_ALL);
		BlockPos at = helper.absolutePos(new BlockPos(24, 2, 24));
		helper.assertTrue(CreepGrowth.placeTumor(level, at, 1, owner), "no tumor planted");
		return at;
	}

	@GameTest(template = ARENA, batch = "tumorSeen", timeoutTicks = 400)
	public void aBurrowedTumorIsSeenOnlyWithADetector(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		UUID player = UUID.randomUUID();
		BlockPos at = tumor(helper, null);
		StarUnit marine = unit(helper, UnitKind.MARINE, 19, 24, player); // within its reach
		helper.runAfterDelay(5, () -> helper.assertTrue(CreepTumors.seen(level, at, marine), "a new tumor is not seen while it rises"));
		helper.runAfterDelay(CreepTumors.SHOWN_TICKS + 20, () -> {
			helper.assertFalse(CreepTumors.seen(level, at, marine), "a burrowed tumor seen without a detector");
			StarUnit raven = unit(helper, UnitKind.RAVEN, 14, 24, player);
			raven.setNoAi(true);
			helper.assertTrue(CreepTumors.seen(level, at, marine), "a raven's side does not see the tumor");
		});
		helper.runAfterDelay(CreepTumors.SHOWN_TICKS + 300, () -> {
			helper.assertFalse(level.getBlockState(at).is(StarMineBlocks.CREEP_TUMOR), "the marine did not shoot down the tumor it sees");
			helper.succeed();
		});
	}

	/** SC2: the player sends their tumor's one spread to a creep cell in its reach; never twice, never past its reach. */
	@GameTest(template = ARENA, batch = "tumorDirected", timeoutTicks = 40)
	public void aPlayerSpreadsTheirTumorWhereTheyClick(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		net.minecraft.server.level.ServerPlayer player = (net.minecraft.server.level.ServerPlayer) helper.makeMockServerPlayerInLevel();
		BlockPos at = tumor(helper, player.getUUID());
		BlockPos far = helper.absolutePos(new BlockPos(18, 2, 18)), near = helper.absolutePos(new BlockPos(29, 2, 24));
		helper.assertFalse(CreepTumors.direct(level, player, at, at.offset(30, 0, 0)), "spread off the creep, out of reach");
		helper.assertTrue(level.getBlockEntity(at) instanceof dev.starmine.block.CreepTumorBlockEntity t && !t.spent(), "a refused order spent the tumor");
		helper.assertTrue(CreepTumors.direct(level, player, at, near), "no spread where the player clicked");
		helper.assertTrue(level.getBlockState(near).is(StarMineBlocks.CREEP_TUMOR), "the new tumor is not where the player clicked");
		helper.assertFalse(CreepTumors.direct(level, player, at, far), "the tumor spread twice");
		level.removePlayerImmediately(player, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
		helper.succeed();
	}

	@GameTest(template = ARENA, batch = "tumorArrow", timeoutTicks = 200)
	public void arrowsWoundATumor(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		BlockPos at = tumor(helper, UUID.randomUUID());
		helper.runAfterDelay(5, () -> {
			for (int i = 0; i < 6; i++) {
				Arrow arrow = EntityType.ARROW.create(level);
				Vec3 from = Vec3.atCenterOf(at).add(0, 3, 0);
				arrow.moveTo(from.x, from.y, from.z);
				arrow.setDeltaMovement(0, -2, 0);
				level.addFreshEntity(arrow);
			}
		});
		helper.runAfterDelay(100, () -> {
			helper.assertFalse(level.getBlockState(at).is(StarMineBlocks.CREEP_TUMOR), "six arrows did not burst the tumor");
			helper.succeed();
		});
	}

	/** Owner 17.10: a wild tumor does not spread; a base's spreads round it, to what it mines and toward its foes over its land only. */
	@GameTest(template = ARENA, batch = "tumorAims", timeoutTicks = 120)
	public void onlyABasesTumorsSpreadAndWithAPurpose(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		UUID player = UUID.randomUUID();
		BlockPos wild = tumor(helper, player); // a player's tumor with no base of theirs: wild
		UUID other = UUID.randomUUID();
		StarUnit hatchery = unit(helper, UnitKind.HATCHERY, 36, 36, other); // another's base: not the tumor's
		helper.runAfterDelay(40, () -> {
			helper.assertTrue(dev.starmine.creep.TumorSpread.base(level, wild, player) == null, "a lone tumor has a base");
			helper.assertFalse(CreepGrowth.spawnTumor(level, wild, dev.starmine.creep.CreepSources.TUMOR_SPACING, dev.starmine.creep.CreepSources.TUMOR_RADIUS, player),
				"a wild tumor spread");
			{
				dev.starmine.hive.Hive base = TestArena.base(helper, hatchery);
				helper.assertTrue(base != null, "no base: " + hatchery.isAlive() + " " + hatchery.hiveId() + " " + hatchery.owner());
				helper.assertTrue(dev.starmine.creep.TumorSpread.base(level, wild, other) == base, "a tumor of its side is not its base's");
				BlockPos home = base.hatchery, from = home.offset(20, 0, 0);
				base.expedition = home.offset(90, -20, 0); // a place it wants, deep under the ground
				java.util.List<dev.starmine.creep.TumorSpread.Goal> goals = dev.starmine.creep.TumorSpread.goals(level, base, from);
				base.expedition = null;
				dev.starmine.creep.TumorSpread.Goal want = goals.stream().filter(g -> !g.territory()).findFirst().orElse(null);
				helper.assertTrue(want != null, "the base wants nothing: " + goals);
				dev.starmine.creep.TumorSpread.Goal foe = new dev.starmine.creep.TumorSpread.Goal(home.offset(0, 0, 300), true);
				helper.assertTrue(!Double.isNaN(dev.starmine.creep.TumorSpread.score(base, from, home.offset(10, 0, 4), null)), "no creep round the base");
				helper.assertTrue(Double.isNaN(dev.starmine.creep.TumorSpread.score(base, from, home.offset(30, 0, 0), null)), "creep far off with no purpose");
				helper.assertTrue(!Double.isNaN(dev.starmine.creep.TumorSpread.score(base, from, home.offset(28, -3, 0), want)), "no creep toward what it wants, down too");
				helper.assertTrue(Double.isNaN(dev.starmine.creep.TumorSpread.score(base, home.offset(40, 0, 0), home.offset(33, 0, 0), want)), "creep away from what it wants");
				helper.assertTrue(!Double.isNaN(dev.starmine.creep.TumorSpread.score(base, home.offset(0, 0, 50), home.offset(0, 0, 58), foe)), "no creep toward its foe");
				helper.assertTrue(Double.isNaN(dev.starmine.creep.TumorSpread.score(base, home.offset(0, 0, 62), home.offset(0, 0, 70), foe)), "creep toward its foe beyond its land");
				helper.succeed();
			}
		});
	}
}
