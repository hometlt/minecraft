package dev.starmine.colony;

import static dev.starmine.ability.TestArena.ARENA;

import dev.starmine.ability.TestArena;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.possess.Possession;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/**
 * The command view's eye (owner 17.10): the world is loaded round where the view looks however far from the
 * player, and the player's own chunks stay loaded; the command panel used on an own unit takes control of it.
 */
public class CommanderEyeGameTests implements FabricGameTest {
	@GameTest(template = ARENA, batch = "commanderEye", timeoutTicks = 400)
	public void theWorldIsLoadedWhereTheViewLooks(GameTestHelper helper) {
		ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayerInLevel();
		Vec3 body = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(5, 2, 5)));
		player.teleportTo(body.x, body.y, body.z);
		Vec3 far = body.add(600, 0, 0); // well past any view distance from the body
		ChunkPos eye = new ChunkPos(BlockPos.containing(far));
		helper.assertFalse(helper.getLevel().getChunkSource().hasChunk(eye.x, eye.z), "the far chunk was loaded already");
		CommanderEye.open(player, far);
		helper.assertTrue(CommanderEye.centre(player).equals(far) && CommanderEye.near(player, far.add(10, 0, 0), 32), "no eye");
		helper.succeedWhen(() -> {
			helper.assertTrue(helper.getLevel().getChunkSource().hasChunk(eye.x, eye.z), "the chunk where the view looks is not loaded");
			helper.assertTrue(helper.getLevel().getChunkSource().hasChunk(player.chunkPosition().x, player.chunkPosition().z), "the body's chunk went");
			CommanderEye.close(player);
			helper.assertTrue(CommanderEye.eye(player) == null, "the eye stayed open");
			helper.getLevel().removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
		});
	}

	@GameTest(template = ARENA, batch = "commanderPanel", timeoutTicks = 100)
	public void thePanelOnAnOwnUnitTakesControl(GameTestHelper helper) {
		TestArena.clear(helper);
		ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayerInLevel();
		Vec3 start = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(5, 2, 5)));
		player.teleportTo(start.x, start.y, start.z);
		StarUnit marine = TestArena.unit(helper, UnitKind.MARINE, 20, 24, player.getUUID());
		StarUnit wild = TestArena.unit(helper, UnitKind.MARINE, 26, 24, null);
		helper.runAfterDelay(2, () -> {
			try {
				ItemStack panel = new ItemStack(CommandPanel.ITEM);
				CommandPanel.ITEM.interactLivingEntity(panel, player, wild, InteractionHand.MAIN_HAND);
				helper.assertTrue(Possession.puppet(player) == null, "the panel took a wild unit");
				CommandPanel.ITEM.interactLivingEntity(panel, player, marine, InteractionHand.MAIN_HAND);
				helper.assertTrue(Possession.puppet(player) != null && Possession.puppet(player).kind == UnitKind.MARINE, "the panel did not take the marine");
			} finally {
				if (Possession.puppet(player) != null) Possession.end(player, false);
				helper.getLevel().removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
			}
			helper.succeed();
		});
	}
}
