package dev.starmine.world;

import static dev.starmine.ability.TestArena.ARENA;
import static dev.starmine.ability.TestArena.clear;
import static dev.starmine.ability.TestArena.point;

import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveLogic;
import dev.starmine.hive.HiveSavedData;
import dev.starmine.vespene.Vespene;
import dev.starmine.vespene.VespeneBlock;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Owner 17.10: the guardians of the main bases (Abathur, Swann, Karax) are Amun's until beaten; freed, they go over
 * with their base and call their heroic transport, which takes only its player aboard. Space platforms carry a deck,
 * a vespene store under grated outlets, lit passages, a hangar and wild troops.
 */
public class GuardianGameTests implements FabricGameTest {
	@GameTest(template = ARENA, batch = "guardianKarax", timeoutTicks = 400)
	public void aFreedGuardianCallsItsTransport(GameTestHelper helper) {
		clear(helper);
		ServerLevel level = helper.getLevel();
		HiveSavedData data = HiveSavedData.get(level.getServer());
		VoidWorld.State state = VoidWorld.State.get(level.getServer());
		boolean shipKnown = data.structuresDone.contains("SHIP:PROTOSS");
		data.structuresDone.add("SHIP:PROTOSS"); // no real mothership from a test
		BlockPos centre = BlockPos.containing(point(helper, 24, 24));
		Hive wild = new Hive(UUID.randomUUID(), level.dimension().location().toString(), centre);
		wild.race = UnitKind.Race.PROTOSS;
		data.add(wild);
		StarUnit karax = HiveLogic.spawnNest(level, wild, UnitKind.KARAX, point(helper, 24, 24), centre);
		karax.setNoAi(true);
		ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayerInLevel();
		Vec3 at = point(helper, 20, 20);
		player.teleportTo(at.x, at.y, at.z);
		helper.runAfterDelay(2, () -> karax.hurt(level.damageSources().playerAttack(player), 100000));
		helper.runAfterDelay(300, () -> {
			List<StarUnit> transports = level.getEntitiesOfClass(StarUnit.class, karax.getBoundingBox().inflate(16), HeroicTransport::is);
			boolean freed = karax.isAlive() && HeroLiberation.freed(karax) && state.freed.contains("KARAX");
			boolean called = transports.size() == 1 && transports.get(0).kind == UnitKind.WARP_PRISM
				&& transports.get(0).owner().filter(player.getUUID()::equals).isPresent();
			boolean theirs = called && HeroicTransport.carries(transports.get(0), player);
			boolean based = player.getUUID().equals(wild.owner);
			state.freed.remove("KARAX");
			if (!shipKnown) data.structuresDone.remove("SHIP:PROTOSS");
			transports.forEach(Entity::discard);
			karax.discard();
			data.remove(wild.id);
			level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
			helper.assertTrue(freed, "Karax is still Amun's");
			helper.assertTrue(based, "his base did not go over");
			helper.assertTrue(called, "no warp prism of the player's by Karax: " + transports.size() + "; anywhere: "
				+ level.getEntitiesOfClass(StarUnit.class, karax.getBoundingBox().inflate(300), HeroicTransport::is).stream()
					.map(t -> t.position().subtract(karax.position()).toString()).toList());
			helper.assertTrue(theirs, "the player may not ride their transport");
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "guardianKinds", timeoutTicks = 20)
	public void everyMainBaseHasItsGuardian(GameTestHelper helper) {
		for (UnitKind.Race race : UnitKind.Race.values()) {
			UnitKind guardian = UnitKind.guardianOf(race);
			helper.assertTrue(guardian.guardian() && guardian.boss() && guardian.race == race, race + "'s guardian is " + guardian);
			helper.assertTrue(HeroicTransport.kindOf(race).flying, race + "'s transport does not fly");
		}
		helper.assertFalse(RacePortal.ENABLED, "the race portals are on");
		helper.succeed();
	}

	@GameTest(template = ARENA, batch = "platform", timeoutTicks = 200)
	public void aPlatformHasItsParts(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		HiveSavedData data = HiveSavedData.get(level.getServer());
		BlockPos base = helper.absolutePos(BlockPos.ZERO);
		BlockPos centre = new BlockPos(base.getX() + 1200, 200, base.getZ() + 1200);
		int half = 20;
		SpacePlatforms.build(level, new SpacePlatforms.Platform("TEST", centre, half, UnitKind.Race.TERRAN));
		Painter.finishAll();
		AABB area = new AABB(centre).inflate(half + 2, 12, half + 2);
		try {
			helper.assertFalse(level.getBlockState(centre.below()).isAir(), "no deck");
			BlockPos tank = centre.offset(-half / 2, 0, half / 2);
			helper.assertTrue(VespeneBlock.isSource(level.getBlockState(tank.offset(1, -1, 0)))
				&& level.getBlockState(tank.offset(1, -1, 0)).getValue(VespeneBlock.OUTLET), "no vespene outlet in the deck");
			helper.assertTrue(level.getBlockState(tank.below(7)).is(Vespene.ROOT), "the store has no vein");
			helper.assertTrue(level.getBlockState(centre.offset(-half + 2, -4, 0)).isAir(), "no hangar");
			helper.assertTrue(level.getBlockState(centre.offset(0, -5, 0)).isAir() && level.getBlockState(centre.offset(5, -3, 0)).is(Blocks.SEA_LANTERN)
				|| level.getBlockState(centre.offset(0, -3, 0)).is(Blocks.SEA_LANTERN) || level.getBlockState(centre.offset(0, -5, 0)).isAir(), "no passages");
			long troops = data.pending.stream().filter(p -> area.contains(Vec3.atCenterOf(p.pos()))).count();
			helper.assertTrue(troops >= 5, "only " + troops + " wild troops wait on the platform");
		} finally {
			data.pending.removeIf(p -> area.contains(Vec3.atCenterOf(p.pos())));
		}
		helper.succeed();
	}
}
