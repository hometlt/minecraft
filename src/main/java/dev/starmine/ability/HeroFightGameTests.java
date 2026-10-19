package dev.starmine.ability;

import static dev.starmine.ability.TestArena.ARENA;
import static dev.starmine.ability.TestArena.clear;
import static dev.starmine.ability.TestArena.point;
import static dev.starmine.ability.TestArena.unit;

import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveLogic;
import dev.starmine.hive.HiveSavedData;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;

/**
 * Owner 19.10: Abathur's fight and his brutalisk, Artanis's surge, the ship heroes' aircraft, the liberator's zone.
 */
public class HeroFightGameTests implements FabricGameTest {
	private static StarUnit hero(GameTestHelper helper, UnitKind kind) {
		ServerLevel level = helper.getLevel();
		BlockPos centre = BlockPos.containing(point(helper, 24, 24));
		Hive wild = new Hive(UUID.randomUUID(), level.dimension().location().toString(), centre);
		wild.race = kind.race;
		HiveSavedData.get(level.getServer()).add(wild);
		StarUnit hero = HiveLogic.spawnNest(level, wild, kind, point(helper, 24, 24), centre);
		hero.setNoAi(true);
		return hero;
	}

	private static StarUnit foe(GameTestHelper helper, StarUnit hero, int x, int z) {
		StarUnit zealot = unit(helper, UnitKind.ZEALOT, x, z, UUID.randomUUID());
		zealot.setNoAi(true);
		var health = zealot.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
		if (health != null) health.setBaseValue(1000);
		zealot.setHealth(zealot.getMaxHealth());
		hero.setTarget(zealot);
		return zealot;
	}

	@GameTest(template = ARENA, batch = "abathur", timeoutTicks = 200)
	public void abathurNestsEvolvesAndBecomesTheBrutalisk(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		StarUnit abathur = hero(helper, UnitKind.ABATHUR);
		StarUnit zealot = foe(helper, abathur, 24, 8);
		Hive base = HiveSavedData.get(level.getServer()).get(abathur.hiveId());
		StarUnit roach = HiveLogic.spawn(level, base, UnitKind.ROACH, point(helper, 30, 30), null);
		roach.setNoAi(true);
		helper.runAfterDelay(100, () -> {
			helper.assertTrue(abathur.getTags().stream().anyMatch(t -> t.startsWith(GuardianFights.NEST)), "no toxic nest laid");
			StarUnit ravager = GuardianFights.evolve(abathur, level);
			helper.assertTrue(ravager != null && ravager.kind == UnitKind.RAVAGER && !roach.isAlive(), "the roach did not evolve");
			abathur.addTag("starmine_phase2");
			abathur.addTag("starmine_phase3");
		});
		helper.runAfterDelay(110, () -> {
			StarUnit form = GuardianFights.formOf(abathur, level);
			helper.assertTrue(form != null && form.kind == UnitKind.ULTRALISK && abathur.getVehicle() == form && abathur.isInvisible(), "no brutalisk");
			helper.assertTrue(!GuardianFights.summons(abathur, level, GuardianFights.SYMBIOTE).isEmpty(), "no symbiote");
			helper.assertTrue(!GuardianFights.summons(abathur, level, GuardianFights.CLONE).isEmpty(), "no copy of Abathur");
			GuardianFights.end(abathur, level);
			helper.assertTrue(!abathur.isPassenger() && !abathur.isInvisible() && GuardianFights.formOf(abathur, level) == null, "the form stayed");
			abathur.discard();
			zealot.discard();
			level.getEntitiesOfClass(StarUnit.class, abathur.getBoundingBox().inflate(40), u -> u.owner().isEmpty()).forEach(StarUnit::discard);
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "artanis", timeoutTicks = 100)
	public void artanisKneelsAndBurstsWithNoShields(GameTestHelper helper) {
		clear(helper);
		StarUnit artanis = hero(helper, UnitKind.ARTANIS);
		StarUnit zealot = foe(helper, artanis, 26, 24);
		artanis.setShield(0);
		helper.runAfterDelay(3, () -> helper.assertTrue(HeroTactics.kneeling(artanis), "Artanis does not kneel"));
		helper.runAfterDelay(HeroTactics.KNEEL + 10, () -> {
			helper.assertTrue(artanis.shield() >= artanis.maxShield() - 0.01, "his shields did not come back: " + artanis.shield());
			helper.assertTrue(zealot.getHealth() + zealot.shield() < zealot.getMaxHealth() + zealot.maxShield(), "the burst hurt no one near");
			artanis.discard();
			zealot.discard();
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "aircraft", timeoutTicks = 60)
	public void raynorCallsAircraftAtHisSecondPhase(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		StarUnit raynor = hero(helper, UnitKind.RAYNOR);
		raynor.setHealth(raynor.getMaxHealth() * 0.6f);
		helper.runAfterDelay(25, () -> {
			var air = level.getEntitiesOfClass(StarUnit.class, raynor.getBoundingBox().inflate(32), u -> u.kind == UnitKind.VIKING || u.kind == UnitKind.BANSHEE);
			helper.assertTrue(air.size() >= 4, "only " + air.size() + " aircraft");
			air.forEach(StarUnit::discard);
			raynor.discard();
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "liberatorZone", timeoutTicks = 20)
	public void aLiberatorHitsOnlyInItsZone(GameTestHelper helper) {
		clear(helper);
		StarUnit liberator = unit(helper, UnitKind.LIBERATOR, 24, 24, null);
		StarUnit inside = unit(helper, UnitKind.MARINE, 24, 32, UUID.randomUUID()), outside = unit(helper, UnitKind.MARINE, 40, 40, UUID.randomUUID());
		liberator.setTarget(inside);
		liberator.setMode(Modes.DEFENDER);
		helper.assertTrue(liberator.zone() != null && liberator.canTarget(inside) && !liberator.canTarget(outside), "the zone does not bound its fire");
		liberator.setMode(0);
		helper.assertTrue(liberator.zone() == null, "the zone stays after it lifts");
		liberator.discard();
		inside.discard();
		outside.discard();
		helper.succeed();
	}
}
