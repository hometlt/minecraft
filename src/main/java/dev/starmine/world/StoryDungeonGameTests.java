package dev.starmine.world;

import static dev.starmine.ability.TestArena.ARENA;
import static dev.starmine.ability.TestArena.clear;
import static dev.starmine.ability.TestArena.unit;

import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;

/**
 * Owner 19.10: the story dungeons — each of the five builds with its heart and its dwellers; the vault opens when the
 * beam reaches its receiver; the brood mother lays eggs; the hybrid blinks to its foe.
 */
public class StoryDungeonGameTests implements FabricGameTest {
	private static BlockPos far(GameTestHelper helper, int dx) {
		return helper.absolutePos(BlockPos.ZERO).offset(dx, 0, 2000);
	}

	@GameTest(template = ARENA, batch = "storyDungeons", timeoutTicks = 200)
	public void everyStoryDungeonBuilds(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		int i = 0;
		for (StoryDungeons.Kind kind : StoryDungeons.Kind.values()) {
			BlockPos column = far(helper, 200 * i++);
			for (int cx = -2; cx <= 2; cx++)
				for (int cz = -2; cz <= 2; cz++) level.getChunk((column.getX() >> 4) + cx, (column.getZ() >> 4) + cz);
			BlockPos heart = StoryDungeons.build(level, RandomSource.create(7), kind, column.getX(), column.getZ(), 60);
			helper.assertTrue(heart != null, kind + " was not built");
			helper.assertTrue(level.getBlockEntity(heart) instanceof DungeonHeart.Heart h && !h.guards().isEmpty(), kind + " has no dwellers");
			DungeonHeart.Heart dwellers = (DungeonHeart.Heart) level.getBlockEntity(heart);
			List<UnitKind> kinds = dwellers.guards().stream().map(DungeonHeart.Guard::kind).toList();
			switch (kind) {
				case INFESTED_MINE -> helper.assertTrue(kinds.contains(UnitKind.INFESTED_TERRAN) && kinds.contains(UnitKind.INFESTOR), "no infested in the mine: " + kinds);
				case BROOD_NEST -> helper.assertTrue(kinds.contains(UnitKind.BROOD_MOTHER) && kinds.contains(UnitKind.EGG), "no brood mother: " + kinds);
				case HYBRID_LAB -> helper.assertTrue(kinds.contains(UnitKind.HYBRID), "no hybrid in the lab: " + kinds);
				case XELNAGA_VAULT -> helper.assertTrue(kinds.contains(UnitKind.XELNAGA_SENTINEL), "no keeper in the vault: " + kinds);
				case SUNKEN_SCOUT -> helper.assertTrue(kinds.contains(UnitKind.STALKER), "no stalkers by the scout: " + kinds);
			}
			int notes = 0, relics = 0;
			for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(column.getX() - 24, heart.getY() - 12, column.getZ() - 24), new BlockPos(column.getX() + 23, heart.getY() + 14, column.getZ() + 23))) {
				var entity = level.getBlockEntity(pos);
				if (entity instanceof LecternBlockEntity lectern && lectern.getBook().has(DataComponents.WRITTEN_BOOK_CONTENT)) notes++;
				if (entity instanceof ChestBlockEntity chest && chest.getItem(13).is(DungeonBosses.RELIC)) relics++;
			}
			if (kind == StoryDungeons.Kind.HYBRID_LAB) helper.assertTrue(notes == 4, "the lab holds " + notes + " notes");
			if (kind == StoryDungeons.Kind.XELNAGA_VAULT || kind == StoryDungeons.Kind.SUNKEN_SCOUT) helper.assertTrue(relics == 1, kind + " holds " + relics + " relics");
			dwellers.guards().clear(); // nobody wakes there
		}
		helper.succeed();
	}

	/** The vault's seal stays shut till the prisms are turned so the beam reaches the receiver, then opens for good. */
	@GameTest(template = ARENA, batch = "storyVault", timeoutTicks = 60)
	public void theVaultOpensWhenTheBeamArrives(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos column = far(helper, 1200);
		for (int cx = -2; cx <= 2; cx++)
			for (int cz = -2; cz <= 2; cz++) level.getChunk((column.getX() >> 4) + cx, (column.getZ() >> 4) + cz);
		BlockPos heart = StoryDungeons.build(level, RandomSource.create(11), StoryDungeons.Kind.XELNAGA_VAULT, column.getX(), column.getZ(), 60);
		if (level.getBlockEntity(heart) instanceof DungeonHeart.Heart h) h.guards().clear();
		BlockPos floor = new BlockPos(column.getX(), heart.getY() + 1, column.getZ());
		StoryDungeons.Lock lock = (StoryDungeons.Lock) level.getBlockEntity(floor.below());
		helper.assertTrue(lock != null && lock.solution().size() == 3, "no lock or no way for the beam");
		boolean shut = !lock.trace(level) && level.getBlockState(floor.offset(11, 1, 0)).is(StoryDungeons.SEAL);
		for (long[] prism : lock.solution()) {
			BlockPos at = BlockPos.of(prism[0]);
			level.setBlock(at, level.getBlockState(at).setValue(StoryDungeons.FACING, Direction.from2DDataValue((int) prism[1])), 3);
		}
		boolean opened = lock.trace(level) && level.getBlockState(floor.offset(11, 1, 0)).isAir() && lock.done();
		boolean lit = level.getBlockState(floor.offset(-5, 0, -9)).getValue(StoryDungeons.LIT);
		helper.assertTrue(shut, "the seal opened with the prisms turned wrong");
		helper.assertTrue(opened && lit, "the beam did not open the seal");
		helper.assertTrue(level.getBlockState(floor.offset(11, 0, 4)).getDestroySpeed(level, floor) < 0, "the vault's walls break");
		helper.succeed();
	}

	/** The brood mother lays eggs round her while a foe is near; they hatch into her brood. */
	@GameTest(template = ARENA, batch = "storyMother", timeoutTicks = 200)
	public void theBroodMotherLaysEggs(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		StarUnit mother = unit(helper, UnitKind.BROOD_MOTHER, 24, 24, null);
		StarUnit foe = unit(helper, UnitKind.ZEALOT, 32, 24, UUID.randomUUID());
		mother.setNoAi(true);
		foe.setNoAi(true);
		mother.setTarget(foe);
		mother.tickCount = DungeonBosses.LAY_EVERY;
		DungeonBosses.broodMother(mother, level);
		List<StarUnit> eggs = level.getEntitiesOfClass(StarUnit.class, mother.getBoundingBox().inflate(8), unit -> unit.kind == UnitKind.EGG);
		helper.runAfterDelay(130, () -> {
			long brood = level.getEntitiesOfClass(StarUnit.class, mother.getBoundingBox().inflate(10), unit -> unit.isAlive() && unit.owner().isEmpty()
				&& (unit.kind == UnitKind.ZERGLING || unit.kind == UnitKind.ROACH || unit.kind == UnitKind.HYDRALISK)).size();
			level.getEntitiesOfClass(StarUnit.class, mother.getBoundingBox().inflate(16)).forEach(Entity::discard);
			helper.assertTrue(eggs.size() >= 2, "she laid " + eggs.size() + " eggs");
			helper.assertTrue(brood >= 2, "her eggs hatched " + brood);
			helper.succeed();
		});
	}

	/** The hybrid blinks behind its foe and strikes round it. */
	@GameTest(template = ARENA, batch = "storyHybrid", timeoutTicks = 40)
	public void theHybridBlinksToItsFoe(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		StarUnit hybrid = unit(helper, UnitKind.HYBRID, 10, 10, null);
		StarUnit foe = unit(helper, UnitKind.MARINE, 34, 30, UUID.randomUUID()); // no shields to soak the blow
		hybrid.setNoAi(true);
		foe.setNoAi(true);
		hybrid.setTarget(foe);
		float health = foe.getHealth() + foe.getAbsorptionAmount();
		hybrid.tickCount = DungeonBosses.BLINK_EVERY;
		DungeonBosses.hybrid(hybrid, level);
		double near = hybrid.distanceTo(foe);
		helper.runAfterDelay(2, () -> {
			boolean hurt = foe.getHealth() + foe.getAbsorptionAmount() < health || foe.hurtTime > 0;
			hybrid.discard();
			foe.discard();
			helper.assertTrue(near < 5, "the hybrid stayed " + near + " away");
			helper.assertTrue(hurt, "its shockwave did not hurt the foe");
			helper.succeed();
		});
	}
}
