package dev.starmine.ability;

import static dev.starmine.ability.TestArena.ARENA;
import static dev.starmine.ability.TestArena.clear;
import static dev.starmine.ability.TestArena.unit;

import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.gear.Guns;
import dev.starmine.gear.Townsfolk;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Owner 19.10: the bosses' powers by phase — Kerrigan heals in liquid creep and not once it is broken, her blade storm
 * cuts; Raynor's charged round pierces all in its line; the Hyperion's laser strikes its line after its warning;
 * Artanis's barriers stand and go; and the traders of the other races sell their wares.
 */
public class BossPowerGameTests implements FabricGameTest {
	@GameTest(template = ARENA, batch = "bossPools", timeoutTicks = 40)
	public void kerriganHealsInLiquidCreep(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		StarUnit kerrigan = unit(helper, UnitKind.KERRIGAN, 24, 24, null);
		kerrigan.setNoAi(true);
		BlockPos feet = kerrigan.blockPosition();
		for (BlockPos at : BlockPos.betweenClosed(feet.offset(-1, 0, -1), feet.offset(1, 0, 1))) level.setBlock(at, BossPowers.LIQUID_CREEP.defaultBlockState(), 3);
		kerrigan.setHealth(kerrigan.getMaxHealth() / 2);
		float before = kerrigan.getHealth();
		kerrigan.tickCount = 20;
		BossPowers.tick(kerrigan, level);
		float healed = kerrigan.getHealth() - before;
		for (BlockPos at : BlockPos.betweenClosed(feet.offset(-1, 0, -1), feet.offset(1, 0, 1))) level.destroyBlock(at, false); // the player breaks the pool
		float mid = kerrigan.getHealth();
		kerrigan.tickCount = 40;
		BossPowers.tick(kerrigan, level);
		boolean stopped = kerrigan.getHealth() == mid;
		kerrigan.discard();
		helper.assertTrue(healed > 0, "she did not heal in the pool");
		helper.assertTrue(stopped, "she healed with the pool broken");
		helper.assertTrue(BossPowers.LIQUID_CREEP.defaultBlockState().getDestroySpeed(level, feet) < 1, "the pool is hard to break");
		helper.succeed();
	}

	@GameTest(template = ARENA, batch = "bossStorm", timeoutTicks = 80)
	public void kerrigansBladeStormCuts(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		StarUnit kerrigan = unit(helper, UnitKind.KERRIGAN, 20, 24, null);
		StarUnit foe = unit(helper, UnitKind.MARINE, 26, 24, UUID.randomUUID());
		kerrigan.setNoAi(true);
		foe.setNoAi(true);
		float health = foe.getHealth();
		BossPowers.bladeStorm(kerrigan, level, foe, BossPowers.STORM_DASHES);
		helper.runAfterDelay(50, () -> {
			boolean cut = foe.getHealth() < health || !foe.isAlive();
			kerrigan.discard();
			foe.discard();
			helper.assertTrue(cut, "the blade storm cut nothing");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "bossPierce", timeoutTicks = 20)
	public void raynorsChargedRoundPiercesItsLine(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		StarUnit raynor = unit(helper, UnitKind.RAYNOR, 4, 24, null);
		StarUnit near = unit(helper, UnitKind.MARINE, 12, 24, UUID.randomUUID()), far = unit(helper, UnitKind.MARINE, 26, 24, UUID.randomUUID());
		StarUnit aside = unit(helper, UnitKind.MARINE, 14, 34, UUID.randomUUID());
		for (StarUnit u : new StarUnit[] {raynor, near, far, aside}) u.setNoAi(true);
		float health = near.getHealth();
		Vec3 from = new Vec3(raynor.getX(), near.getY() + near.getBbHeight() / 2, raynor.getZ()); // level with their bodies
		BossPowers.pierce(raynor, level, from, new Vec3(near.getX() - raynor.getX(), 0, near.getZ() - raynor.getZ()).normalize());
		boolean both = near.getHealth() < health && far.getHealth() < health, spared = aside.getHealth() == health;
		for (StarUnit u : new StarUnit[] {raynor, near, far, aside}) u.discard();
		helper.assertTrue(both, "the round did not pierce both in its line");
		helper.assertTrue(spared, "the round hurt one off its line");
		helper.succeed();
	}

	@GameTest(template = ARENA, batch = "bossLaser", timeoutTicks = 60)
	public void theHyperionsLaserStrikesAfterItsWarning(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		StarUnit raynor = unit(helper, UnitKind.RAYNOR, 4, 4, null);
		StarUnit foe = unit(helper, UnitKind.MARINE, 20, 24, UUID.randomUUID());
		raynor.setNoAi(true);
		foe.setNoAi(true);
		float health = foe.getHealth();
		BossPowers.lineStrike(raynor, level, foe.position().add(-10, 0, 0), foe.position().add(10, 0, 0), 30, BossPowers.LASER_DAMAGE,
			new net.minecraft.core.particles.DustParticleOptions(new org.joml.Vector3f(1, 0, 0), 1), 0xff0000, true);
		helper.runAfterDelay(10, () -> {
			boolean warned = foe.getHealth() == health;
			helper.runAfterDelay(30, () -> {
				boolean struck = foe.getHealth() < health || !foe.isAlive();
				raynor.discard();
				foe.discard();
				helper.assertTrue(warned, "the laser struck before its warning");
				helper.assertTrue(struck, "the laser did not strike its line");
				helper.succeed();
			});
		});
	}

	@GameTest(template = ARENA, batch = "bossBarrier", timeoutTicks = 200)
	public void artanissBarriersStandAndGo(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		StarUnit artanis = unit(helper, UnitKind.ARTANIS, 10, 24, null);
		StarUnit foe = unit(helper, UnitKind.MARINE, 30, 24, UUID.randomUUID());
		artanis.setNoAi(true);
		foe.setNoAi(true);
		BossPowers.barriers(artanis, level, foe);
		AABB area = new AABB(artanis.blockPosition()).inflate(30, 4, 30);
		long walls = BlockPos.betweenClosedStream(area).filter(at -> level.getBlockState(at).is(BossPowers.PSI_BARRIER)).count();
		helper.runAfterDelay(BossPowers.BARRIER_TICKS + 5, () -> {
			long left = BlockPos.betweenClosedStream(area).filter(at -> level.getBlockState(at).is(BossPowers.PSI_BARRIER)).count();
			artanis.discard();
			foe.discard();
			helper.assertTrue(walls >= 20, "only " + walls + " barrier blocks");
			helper.assertTrue(left == 0, left + " barrier blocks stayed");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "traders", timeoutTicks = 20)
	public void theAlienTradersSellTheirWares(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		Townsfolk preserver = Guns.PRESERVER.create(level), dehaka = Guns.DEHAKA.create(level);
		boolean relic = preserver.getOffers().stream().anyMatch(offer -> offer.getResult().is(dev.starmine.world.DungeonBosses.RELIC));
		boolean essence = dehaka.getOffers().stream().anyMatch(offer -> offer.getCostA().is(net.minecraft.world.item.Items.ROTTEN_FLESH));
		boolean zerg = dehaka.getOffers().stream().anyMatch(offer -> offer.getResult().is(dev.starmine.suit.SuitItems.SHARDS.get(UnitKind.Race.ZERG)));
		boolean unarmed = preserver.getMainHandItem().isEmpty() && dehaka.getMainHandItem().isEmpty();
		helper.assertTrue(relic, "the preserver does not sell the relic");
		helper.assertTrue(essence && zerg, "Dehaka does not trade essence and zerg gear");
		helper.assertTrue(unarmed, "a trader of another race carries a gun");
		helper.succeed();
	}

	@GameTest(template = ARENA, batch = "dehakaCave", timeoutTicks = 40)
	public void dehakaLivesInHisCave(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos column = helper.absolutePos(BlockPos.ZERO).offset(0, 0, 2600);
		for (int cx = -2; cx <= 2; cx++)
			for (int cz = -2; cz <= 2; cz++) level.getChunk((column.getX() >> 4) + cx, (column.getZ() >> 4) + cz);
		BlockPos heart = dev.starmine.world.SmallDungeons.build(level, net.minecraft.util.RandomSource.create(3), dev.starmine.world.SmallDungeons.Kind.DEHAKA_CAVE,
			column.getX(), column.getZ(), 60);
		helper.assertTrue(heart != null, "no cave");
		var found = level.getEntitiesOfClass(Townsfolk.class, new AABB(heart).inflate(12), folk -> folk.role == Townsfolk.Role.DEHAKA);
		boolean bones = BlockPos.betweenClosedStream(new AABB(heart).inflate(8)).anyMatch(at -> level.getBlockState(at).is(Blocks.BONE_BLOCK));
		found.forEach(Entity::discard);
		helper.assertTrue(found.size() == 1, "Dehaka is not in his cave: " + found.size());
		helper.assertTrue(bones, "no bones in Dehaka's cave");
		helper.succeed();
	}
}
