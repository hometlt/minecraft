package dev.starmine.ability;

import dev.starmine.StarMine;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The common ability mechanics (owner 14.10, docs/ABILITIES.md): energy and cloak with detection, a mode
 * change (siege tank), a channelled spell (Yamato) and a transport (medivac).
 */
public class AbilityGameTests implements FabricGameTest {
	private static final String ARENA = "starmine:arena";

	private static void arena(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		// up to 16: no barrier ceiling over the arena, flyers keep their height over the ground (the heightmap counts barriers)
		for (BlockPos rel : BlockPos.betweenClosed(0, 0, 0, 47, 16, 47))
			level.setBlock(helper.absolutePos(rel), rel.getY() == 1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
	}

	private static StarUnit unit(GameTestHelper helper, UnitKind kind, int x, int z, @Nullable UUID owner) {
		StarUnit unit = StarMine.type(kind).create(helper.getLevel());
		Vec3 at = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(x, 2, z)));
		unit.moveTo(at.x, at.y + (kind.flying ? 3 : 0), at.z, 0, 0);
		if (owner != null) unit.setOwner(owner);
		helper.getLevel().addFreshEntity(unit);
		return unit;
	}

	@GameTest(template = ARENA, batch = "abilityCloak", timeoutTicks = 200)
	public void ghostCloaksUntilDetected(GameTestHelper helper) {
		arena(helper);
		UUID player = UUID.randomUUID();
		StarUnit ghost = unit(helper, UnitKind.GHOST, 10, 24, player);
		StarUnit marine = unit(helper, UnitKind.MARINE, 20, 24, null);
		ServerLevel level = helper.getLevel();
		helper.runAfterDelay(2, () -> {
			ghost.setEnergy(100);
			helper.assertTrue(Abilities.cast(ghost, level, Ability.CLOAK, null, null), "the ghost did not cloak: " + Abilities.refusal(ghost, Ability.CLOAK) + " " + ghost.kind + " alive " + ghost.isAlive() + " users " + Ability.CLOAK.users() + " hp " + ghost.getHealth());
			helper.assertTrue(ghost.cloaked() && ghost.energy() <= 75.1f, "cloak not paid");
		});
		helper.runAfterDelay(30, () -> {
			helper.assertTrue(!Cloak.seen(ghost, marine), "a foe without a detector sees the ghost");
			helper.assertTrue(marine.getTarget() != ghost, "the marine still targets the cloaked ghost");
			helper.assertTrue(ghost.energy() < 75, "the cloak costs no upkeep");
			unit(helper, UnitKind.MISSILE_TURRET, 16, 24, null); // a wild detector on the marine's side
		});
		helper.runAfterDelay(60, () -> {
			helper.assertTrue(Cloak.seen(ghost, marine) && ghost.revealed(), "the turret does not reveal the ghost");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "abilitySiege", timeoutTicks = 600)
	public void tankSiegesAndShootsFar(GameTestHelper helper) {
		arena(helper);
		StarUnit tank = unit(helper, UnitKind.SIEGE_TANK, 8, 24, UUID.randomUUID());
		ServerLevel level = helper.getLevel();
		helper.runAfterDelay(1, () -> helper.assertTrue(Abilities.cast(tank, level, Ability.SIEGE_MODE, null, null), "no siege"));
		helper.runAfterDelay(70, () -> {
			helper.assertTrue(tank.mode() == Modes.SIEGED, "not sieged after the change");
			helper.assertTrue(tank.getAttributeValue(Attributes.MOVEMENT_SPEED) <= 0.0001, "a sieged tank can move");
			StarUnit crawler = unit(helper, UnitKind.SPINE_CRAWLER, 21, 24, null); // a wild foe beyond tank range, within siege range
			helper.succeedWhen(() -> helper.assertTrue(crawler.getHealth() < crawler.getMaxHealth(), "the sieged tank does not hit at 13 blocks"));
		});
	}

	@GameTest(template = ARENA, batch = "abilityYamato", timeoutTicks = 300)
	public void battlecruiserFiresYamato(GameTestHelper helper) {
		arena(helper);
		StarUnit cruiser = unit(helper, UnitKind.BATTLECRUISER, 10, 24, UUID.randomUUID());
		StarUnit crawler = unit(helper, UnitKind.SPINE_CRAWLER, 18, 24, null);
		ServerLevel level = helper.getLevel();
		helper.runAfterDelay(2, () -> {
			helper.assertTrue(Abilities.cast(cruiser, level, Ability.YAMATO, crawler, null), "no Yamato");
			helper.assertTrue(cruiser.abilities.channelling(), "Yamato is not channelled");
			helper.assertTrue(Abilities.refusal(cruiser, Ability.YAMATO) != null, "Yamato has no cooldown");
		});
		helper.runAfterDelay(50, () -> {
			float lost = crawler.getMaxHealth() - crawler.getHealth();
			helper.assertTrue(lost >= TerranAbilities.YAMATO_DAMAGE * UnitKind.SCALE * 0.8 || !crawler.isAlive(), "Yamato took only " + lost);
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "abilityTransport", timeoutTicks = 600)
	public void medivacCarriesMarines(GameTestHelper helper) {
		arena(helper);
		UUID player = UUID.randomUUID();
		StarUnit medivac = unit(helper, UnitKind.MEDIVAC, 8, 24, player);
		StarUnit a = unit(helper, UnitKind.MARINE, 9, 25, player), b = unit(helper, UnitKind.MARINE, 9, 23, player);
		ServerLevel level = helper.getLevel();
		Vec3 drop = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(30, 2, 24)));
		helper.runAfterDelay(3, () -> {
			helper.assertTrue(Abilities.cast(medivac, level, Ability.LOAD, null, null), "no load");
			helper.assertTrue(medivac.getPassengers().size() == 2, "the medivac took " + medivac.getPassengers().size());
			helper.assertTrue(Abilities.cast(medivac, level, Ability.UNLOAD, null, drop), "no unload");
			helper.succeedWhen(() -> {
				helper.assertTrue(medivac.getPassengers().isEmpty(), "still carrying, at " + helper.relativePos(medivac.blockPosition()).toShortString());
				helper.assertTrue(Math.abs(a.getX() - drop.x) < 5 && Math.abs(b.getZ() - drop.z) < 5 && a.getY() < drop.y + 1.5, "not put down there");
			});
		});
	}
}
