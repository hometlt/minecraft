package dev.starmine.world;

import static dev.starmine.ability.TestArena.ARENA;
import static dev.starmine.ability.TestArena.clear;
import static dev.starmine.ability.TestArena.point;

import dev.starmine.StarMine;
import dev.starmine.entity.Heroes;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.suit.HeroHelmets;
import dev.starmine.suit.SuitItems;
import dev.starmine.suit.Suits;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The heroes' helmets and the artifact (owner 17.10): each hero leaves its own helmet (a piece of its race's
 * suit, with a perk) and its part of the artifact's key; the three parts of three races stacked on a pedestal
 * light it and open the portal to the Void high above; taking a part away closes it.
 */
public class ArtifactGameTests implements FabricGameTest {
	@GameTest(template = ARENA, batch = "artifactLoot", timeoutTicks = 20)
	public void heroesLeaveTheirHelmetAndKeyPart(GameTestHelper helper) {
		for (UnitKind kind : List.of(UnitKind.KERRIGAN, UnitKind.RAYNOR, UnitKind.ARTANIS)) {
			StarUnit hero = StarMine.type(kind).create(helper.getLevel());
			List<ItemStack> loot = Heroes.reward(hero);
			helper.assertTrue(loot.stream().anyMatch(stack -> HeroHelmets.heroOf(stack.getItem()) == kind.race), kind + " leaves no helmet of its own");
			helper.assertTrue(loot.stream().noneMatch(stack -> stack.is(SuitItems.piece(kind.race, ArmorItem.Type.HELMET))), kind + " leaves a plain helmet too");
			helper.assertTrue(loot.stream().anyMatch(stack -> stack.is(Artifact.part(kind.race))), kind + " leaves no key part");
			helper.assertTrue(SuitItems.raceOf(HeroHelmets.HELMETS.get(kind.race)) == kind.race, kind + "'s helmet is not a piece of its suit");
			hero.discard();
		}
		helper.succeed();
	}

	/** Raynor's helmet is heavier than the plain one and still makes the terran suit whole. */
	@GameTest(template = ARENA, batch = "artifactRaynor", timeoutTicks = 20)
	public void raynorsHelmetIsHeavier(GameTestHelper helper) {
		ArmorItem plain = SuitItems.piece(UnitKind.Race.TERRAN, ArmorItem.Type.HELMET);
		ArmorItem raynor = (ArmorItem) HeroHelmets.HELMETS.get(UnitKind.Race.TERRAN);
		double[] armor = {0, 0};
		plain.getDefaultAttributeModifiers().forEach(EquipmentSlot.HEAD, (attribute, modifier) -> {
			if (attribute.is(Attributes.ARMOR)) armor[0] += modifier.amount();
		});
		raynor.getDefaultAttributeModifiers().forEach(EquipmentSlot.HEAD, (attribute, modifier) -> {
			if (attribute.is(Attributes.ARMOR)) armor[1] += modifier.amount();
		});
		helper.assertTrue(armor[1] == HeroHelmets.RAYNOR_ARMOR && armor[1] > armor[0], "Raynor's helmet gives " + armor[1] + " armor, the plain one " + armor[0]);
		ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayerInLevel();
		player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(raynor));
		player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(SuitItems.piece(UnitKind.Race.TERRAN, ArmorItem.Type.CHESTPLATE)));
		player.setItemSlot(EquipmentSlot.LEGS, new ItemStack(SuitItems.piece(UnitKind.Race.TERRAN, ArmorItem.Type.LEGGINGS)));
		player.setItemSlot(EquipmentSlot.FEET, new ItemStack(SuitItems.piece(UnitKind.Race.TERRAN, ArmorItem.Type.BOOTS)));
		boolean whole = Suits.worn(player) == UnitKind.Race.TERRAN;
		helper.getLevel().removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
		helper.assertTrue(whole, "the suit with Raynor's helmet is not whole");
		helper.succeed();
	}

	/** Kerrigan's crown alone heals its wearer. */
	@GameTest(template = ARENA, batch = "artifactKerrigan", timeoutTicks = 200)
	public void kerrigansCrownRegenerates(GameTestHelper helper) {
		clear(helper);
		ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayerInLevel();
		Vec3 at = point(helper, 5, 5);
		player.teleportTo(at.x, at.y, at.z);
		helper.onEachTick(() -> {
			if (!player.isRemoved() && !helper.getLevel().getServer().getPlayerList().getPlayers().contains(player)) {
				player.tickCount++;
				Suits.tick(player);
			}
		});
		helper.runAfterDelay(2, () -> {
			player.getFoodData().setFoodLevel(17);
			player.getFoodData().setSaturation(0);
			player.setHealth(10);
			player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(HeroHelmets.HELMETS.get(UnitKind.Race.ZERG)));
		});
		helper.runAfterDelay(150, () -> {
			float health = player.getHealth();
			helper.getLevel().removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
			helper.assertTrue(health >= 12, "Kerrigan's crown healed only to " + health);
			helper.succeed();
		});
	}

	@GameTest(template = ARENA, batch = "artifactPortal", timeoutTicks = 40)
	public void theWholeArtifactOpensThePortal(GameTestHelper helper) {
		clear(helper);
		var level = helper.getLevel();
		BlockPos pedestal = BlockPos.containing(point(helper, 24, 24));
		BlockPos centre = Artifact.portalCentre(level, pedestal);
		level.setBlock(pedestal, Artifact.PEDESTAL.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(pedestal.above(1), Artifact.PARTS.get(UnitKind.Race.ZERG).defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(pedestal.above(2), Artifact.PARTS.get(UnitKind.Race.ZERG).defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(pedestal.above(3), Artifact.PARTS.get(UnitKind.Race.PROTOSS).defaultBlockState(), Block.UPDATE_ALL);
		helper.assertFalse(level.getBlockState(pedestal).getValue(Artifact.ACTIVE), "two zerg parts made the artifact");
		helper.assertFalse(level.getBlockState(centre).is(Artifact.PORTAL), "a portal without the whole artifact");
		level.setBlock(pedestal.above(2), Artifact.PARTS.get(UnitKind.Race.TERRAN).defaultBlockState(), Block.UPDATE_ALL);
		helper.assertTrue(level.getBlockState(pedestal).getValue(Artifact.ACTIVE), "the whole artifact does not light");
		helper.assertTrue(centre.getY() >= pedestal.getY() + Artifact.PORTAL_RISE, "the portal is low: y " + centre.getY());
		helper.assertTrue(level.getBlockState(centre).is(Artifact.PORTAL), "no portal over the artifact");
		helper.assertTrue(level.getBlockState(centre.offset(Artifact.PORTAL_RADIUS + 1, 0, 0)).is(Artifact.FRAME), "no frame round the portal");
		helper.assertTrue(level.getBlockState(centre).getDestroySpeed(level, centre) < 0, "the portal can be broken");
		helper.assertTrue(VoidWorld.State.get(level.getServer()).open().stream().anyMatch(o -> o.pos().equals(pedestal)), "the Void does not know the artifact");
		level.setBlock(pedestal.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL); // the artifact taken apart
		helper.assertFalse(level.getBlockState(pedestal).getValue(Artifact.ACTIVE), "the broken artifact still lights");
		helper.assertTrue(level.getBlockState(centre).isAir() && level.getBlockState(centre.offset(Artifact.PORTAL_RADIUS + 1, 0, 0)).isAir(),
			"the portal outlived the artifact");
		helper.assertTrue(VoidWorld.State.get(level.getServer()).open().stream().noneMatch(o -> o.pos().equals(pedestal)), "the Void still knows the artifact");
		level.setBlock(pedestal.above(1), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(pedestal.above(3), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(pedestal, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		helper.succeed();
	}
}
