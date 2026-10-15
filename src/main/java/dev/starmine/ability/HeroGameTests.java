package dev.starmine.ability;

import static dev.starmine.ability.TestArena.ARENA;
import static dev.starmine.ability.TestArena.clear;
import static dev.starmine.ability.TestArena.unit;
import static dev.starmine.entity.UnitKind.SCALE;

import dev.starmine.entity.Heroes;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.suit.SuitItems;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.phys.AABB;

/**
 * The heroes of the sky ships (CLOUD_TASK_2 stage 8): their abilities do what SC2 says (Kerrigan's leap
 * 150 and shift 50, Raynor's Hyperion 600 after 1.5 s, Artanis's dash 100 and a 2 s stun), the AI casts
 * them, a boss bar shows, and a killed hero leaves its reward.
 */
public class HeroGameTests implements FabricGameTest {
	private static final double CLOSE = 0.6;

	@GameTest(template = ARENA, batch = "heroKerrigan", timeoutTicks = 100)
	public void kerriganLeapsAndShifts(GameTestHelper helper) {
		clear(helper);
		var level = helper.getLevel();
		UUID player = UUID.randomUUID();
		StarUnit kerrigan = unit(helper, UnitKind.KERRIGAN, 8, 24, null);
		StarUnit thor = unit(helper, UnitKind.THOR, 24, 24, player);
		StarUnit shifter = unit(helper, UnitKind.KERRIGAN, 8, 40, null);
		StarUnit marauder = unit(helper, UnitKind.MARAUDER, 14, 40, player), other = unit(helper, UnitKind.MARAUDER, 17, 41, player);
		helper.runAfterDelay(2, () -> {
			float before = thor.getHealth();
			helper.assertTrue(Abilities.cast(kerrigan, level, Ability.LEAPING_STRIKE, thor, null), "no leaping strike: "
				+ Abilities.refusal(kerrigan, Ability.LEAPING_STRIKE));
			helper.assertTrue(Math.abs(before - thor.getHealth() - HeroAbilities.LEAP_DAMAGE * SCALE) < CLOSE + 2,
				"the thor lost " + (before - thor.getHealth()) + ", not " + HeroAbilities.LEAP_DAMAGE * SCALE);
			helper.assertTrue(kerrigan.distanceTo(thor) < (kerrigan.getBbWidth() + thor.getBbWidth()) / 2 + 3, "Kerrigan did not land by the thor");
			float a = marauder.getHealth(), b = other.getHealth();
			// a second Kerrigan dashes through two marauders to a point 13 blocks off (within 6 SC2 units)
			helper.assertTrue(Abilities.cast(shifter, level, Ability.PSIONIC_SHIFT, null, TestArena.point(helper, 21, 40)), "no psionic shift: "
				+ Abilities.refusal(shifter, Ability.PSIONIC_SHIFT));
			helper.assertTrue(marauder.getHealth() < a || other.getHealth() < b, "the shift hurt nobody on its way");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "heroRaynor", timeoutTicks = 120)
	public void raynorCallsTheHyperion(GameTestHelper helper) {
		clear(helper);
		var level = helper.getLevel();
		// beyond Raynor's gun (6 SC2 units, 14.8 blocks), within the Hyperion's call (15 units): only the Yamato hits it
		StarUnit raynor = unit(helper, UnitKind.RAYNOR, 4, 24, null);
		StarUnit cc = unit(helper, UnitKind.COMMAND_CENTER, 34, 24, UUID.randomUUID());
		raynor.setNoAi(true);
		helper.runAfterDelay(5, () -> {
			float before = cc.getHealth();
			helper.assertTrue(Abilities.cast(raynor, level, Ability.HYPERION_STRIKE, cc, null), "no Hyperion: " + Abilities.refusal(raynor, Ability.HYPERION_STRIKE));
			helper.assertTrue(cc.getHealth() == before, "the Yamato struck before its windup");
			helper.runAfterDelay(40, () -> {
				helper.assertTrue(Math.abs(before - cc.getHealth() - HeroAbilities.HYPERION_DAMAGE * SCALE) < CLOSE + 2,
					"the command center lost " + (before - cc.getHealth()) + ", not " + HeroAbilities.HYPERION_DAMAGE * SCALE);
				helper.assertTrue(Ability.of(UnitKind.RAYNOR).contains(Ability.KD8_CHARGE), "Raynor has no grenade");
				helper.succeed();
			});
		});
	}

	@GameTest(template = ARENA, batch = "heroArtanis", timeoutTicks = 100)
	public void artanisDashesAndStuns(GameTestHelper helper) {
		clear(helper);
		var level = helper.getLevel();
		StarUnit artanis = unit(helper, UnitKind.ARTANIS, 8, 24, null);
		StarUnit marauder = unit(helper, UnitKind.MARAUDER, 22, 24, UUID.randomUUID());
		helper.runAfterDelay(2, () -> {
			float before = marauder.getHealth();
			helper.assertTrue(Abilities.cast(artanis, level, Ability.LIGHTNING_DASH, marauder, null), "no dash: " + Abilities.refusal(artanis, Ability.LIGHTNING_DASH));
			helper.assertTrue(Math.abs(before - marauder.getHealth() - HeroAbilities.DASH_DAMAGE * SCALE) < CLOSE + 2,
				"the marauder lost " + (before - marauder.getHealth()));
			helper.assertTrue(Status.has(marauder, Status.STUNNED), "the marauder is not stunned");
			helper.assertTrue(Ability.of(UnitKind.ARTANIS).contains(Ability.PSIONIC_STORM) && artanis.energy() >= 75, "Artanis cannot storm");
			helper.succeed();
		});
	}

	/** Left to its AI against an army, a hero casts; it shows a boss bar; killed, it leaves a suit of its race. */
	@GameTest(template = ARENA, batch = "heroFight", timeoutTicks = 600)
	public void aHeroFightsWithItsAbilitiesAndLeavesItsReward(GameTestHelper helper) {
		clear(helper);
		UUID player = UUID.randomUUID();
		StarUnit kerrigan = unit(helper, UnitKind.KERRIGAN, 8, 24, null);
		for (int i = 0; i < 3; i++) unit(helper, UnitKind.MARAUDER, 26, 18 + i * 6, player);
		helper.succeedWhen(() -> {
			helper.assertTrue(Heroes.hasBar(kerrigan), "no boss bar");
			helper.assertTrue(kerrigan.abilities.cooldownLeft(Ability.LEAPING_STRIKE, helper.getLevel().getGameTime()) > 0
				|| kerrigan.abilities.cooldownLeft(Ability.PSIONIC_SHIFT, helper.getLevel().getGameTime()) > 0, "Kerrigan cast nothing yet");
			if (kerrigan.isAlive()) kerrigan.kill();
			var drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(kerrigan.blockPosition()).inflate(8));
			helper.assertTrue(drops.stream().anyMatch(item -> item.getItem().is(SuitItems.piece(UnitKind.Race.ZERG, ArmorItem.Type.CHESTPLATE))),
				"no zerg chestplate among " + drops.size() + " drops");
		});
	}
}
