package dev.starmine.possess;

import dev.starmine.StarMine;
import dev.starmine.ability.Ability;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Playing as a unit (owner 14.10): a player takes their marine, has its body and modules, shoots with
 * its weapon, leaves it (the marine is back, the player's own things too); a second time the marine
 * dies and the player is back, alive, where they were.
 */
public class PossessionGameTests implements FabricGameTest {
	private static final String ARENA = "starmine:arena";

	private static void arena(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		for (BlockPos rel : BlockPos.betweenClosed(0, 0, 0, 47, 11, 47))
			level.setBlock(helper.absolutePos(rel), rel.getY() == 1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
	}

	private static StarUnit unit(GameTestHelper helper, UnitKind kind, int x, int z, java.util.UUID owner) {
		StarUnit unit = StarMine.type(kind).create(helper.getLevel());
		Vec3 at = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(x, 2, z)));
		unit.moveTo(at.x, at.y, at.z, 0, 0);
		if (owner != null) unit.setOwner(owner);
		unit.setNoAi(owner == null);
		helper.getLevel().addFreshEntity(unit);
		return unit;
	}

	@GameTest(template = ARENA, batch = "possession", timeoutTicks = 200)
	public void playerPlaysAsMarine(GameTestHelper helper) {
		arena(helper);
		ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayerInLevel();
		Vec3 start = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(5, 2, 5)));
		player.teleportTo(start.x, start.y, start.z);
		player.getInventory().add(new ItemStack(Items.DIAMOND, 3));
		StarUnit marine = unit(helper, UnitKind.MARINE, 20, 24, player.getUUID());
		StarUnit zergling = unit(helper, UnitKind.ZERGLING, 26, 24, null);
		helper.runAfterDelay(2, () -> {
			try {
				check(helper, player, marine, zergling, start);
			} finally { // a mock player left in the world would make every other test's mobs despawn (far from a player)
				if (Pilots.kindOf(player) != null) Possession.end(player, false);
				helper.getLevel().removePlayerImmediately(player, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
			}
			helper.succeed();
		});
	}

	/**
	 * A player in a viking flies; its landing module puts it down and the player walks (no flight) until
	 * it lifts off again. A transport with a pilot loads nothing: an explicit refusal, not a silent one
	 * (docs/CLOUD_TASK.md, stage F). A mock player is not in the server's player list, so the test ticks
	 * its possession itself.
	 */
	@GameTest(template = ARENA, batch = "possessionModes", timeoutTicks = 300)
	public void vikingLandsUnderItsPilot(GameTestHelper helper) {
		arena(helper);
		ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayerInLevel();
		Vec3 start = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(5, 2, 5)));
		player.teleportTo(start.x, start.y, start.z);
		StarUnit viking = unit(helper, UnitKind.VIKING, 20, 24, player.getUUID());
		StarUnit medivac = unit(helper, UnitKind.MEDIVAC, 30, 24, player.getUUID());
		Runnable leave = () -> {
			if (Pilots.kindOf(player) != null) Possession.end(player, false);
			helper.getLevel().removePlayerImmediately(player, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
		};
		helper.onEachTick(() -> {
			StarUnit puppet = Possession.puppet(player);
			if (puppet != null && !player.isRemoved()) Possession.tick(player, puppet);
		});
		helper.runAfterDelay(2, () -> {
			try {
				helper.assertTrue(Possession.start(player, viking), "no possession of the viking");
				Possession.tick(player, Possession.puppet(player));
				helper.assertTrue(player.getAbilities().mayfly, "the player in a flying viking cannot fly");
				helper.assertTrue(Possession.useModule(player, ModuleItem.of(Ability.VIKING_LAND), InteractionHand.MAIN_HAND), "the landing module failed");
			} catch (RuntimeException failure) {
				leave.run();
				throw failure;
			}
		});
		helper.runAfterDelay(90, () -> {
			try {
				StarUnit puppet = Possession.puppet(player);
				helper.assertTrue(puppet != null && puppet.kind == UnitKind.VIKING_ASSAULT, "the viking has not landed: " + puppet);
				helper.assertTrue(!player.getAbilities().mayfly && !player.getAbilities().flying, "the player still flies in a landed viking");
				Possession.end(player, false);
				helper.assertTrue(Possession.start(player, medivac), "no possession of the medivac");
				helper.assertTrue("message.starmine.pilot_transport".equals(dev.starmine.ability.Abilities.refusal(Possession.puppet(player), Ability.LOAD)),
					"a medivac with a pilot is not refused loading");
				helper.assertFalse(Possession.useModule(player, ModuleItem.of(Ability.LOAD), InteractionHand.MAIN_HAND), "a medivac with a pilot loaded");
			} finally {
				leave.run();
			}
			helper.succeed();
		});
	}

	private static void check(GameTestHelper helper, ServerPlayer player, StarUnit marine, StarUnit zergling, Vec3 start) {
		{
			helper.assertTrue(Possession.start(player, marine), "no possession");
			helper.assertTrue(marine.isRemoved() && Pilots.kindOf(player) == UnitKind.MARINE, "the marine is still in the world");
			helper.assertTrue(player.distanceToSqr(marine.position()) < 1, "the player is not in the marine's place");
			helper.assertTrue(player.getMaxHealth() == (float) UnitKind.MARINE.health, "not the marine's health: " + player.getMaxHealth());
			helper.assertTrue(player.getBbWidth() == UnitKind.MARINE.width, "not the marine's size");
			helper.assertTrue(player.getInventory().hasAnyMatching(s -> s.is(ModuleItem.WEAPON))
				&& player.getInventory().hasAnyMatching(s -> s.is(ModuleItem.of(Ability.STIMPACK))), "no modules");
			helper.assertTrue(!player.getInventory().contains(new ItemStack(Items.DIAMOND)), "the player's own things are with the marine");
			// aim at the zergling and fire
			Vec3 eye = player.getEyePosition(), at = zergling.position().add(0, zergling.getBbHeight() / 2, 0);
			player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, at);
			helper.assertTrue(Possession.useModule(player, ModuleItem.WEAPON, InteractionHand.MAIN_HAND), "the weapon did not fire from " + eye);
			helper.assertTrue(zergling.getHealth() < zergling.getMaxHealth(), "the zergling was not hit");
			Possession.end(player, false);
			helper.assertTrue(Pilots.kindOf(player) == null && player.getMaxHealth() == 20, "the player is not themselves again");
			helper.assertTrue(player.getInventory().contains(new ItemStack(Items.DIAMOND, 3)), "the player's things did not come back");
			helper.assertTrue(!player.getInventory().hasAnyMatching(s -> s.is(ModuleItem.WEAPON)), "modules left with the player");
			helper.assertTrue(player.distanceToSqr(start) < 1, "the player is not back where they were");
			StarUnit back = helper.getLevel().getEntitiesOfClass(StarUnit.class, player.getBoundingBox().inflate(40),
				u -> u.kind == UnitKind.MARINE && u.getUUID().equals(marine.getUUID())).stream().findFirst().orElse(null);
			helper.assertTrue(back != null && back.getUUID().equals(marine.getUUID()), "the marine did not come back");
			// again: this time the marine dies
			helper.assertTrue(Possession.start(player, back), "no second possession");
			player.hurt(helper.getLevel().damageSources().fellOutOfWorld(), 1000); // past the new player's spawn protection
			helper.assertTrue(player.isAlive() && Pilots.kindOf(player) == null, "the player died with the marine: alive " + player.isAlive() + " hp " + player.getHealth() + " kind " + Pilots.kindOf(player) + " mode " + player.gameMode.getGameModeForPlayer());
			helper.assertTrue(player.getAttributeValue(Attributes.MAX_HEALTH) == 20 && player.distanceToSqr(start) < 1, "the player is not themselves after the death");
		}
	}
}
