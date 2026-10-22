package dev.starmine.world;

import static dev.starmine.ability.TestArena.*;
import dev.starmine.StarMine;
import dev.starmine.entity.*;
import dev.starmine.faction.Faction;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.*;

/** Server-only gate checks; real cross-dimension checks run in the client Harness scenario portals. */
public class VoidPortalGameTests implements FabricGameTest {
	static void arms(ServerLevel level, BlockPos centre, int radius, Block stone) {
		level.setBlock(centre, stone.defaultBlockState(), 2);
		for (Direction dir : Direction.values()) for (int i = 1; i <= radius; i++) level.setBlock(centre.relative(dir, i), VoidCraft.BLOCK.defaultBlockState(), 2);
	}

	@GameTest(template = ARENA, batch = "portalPattern", timeoutTicks = 30)
	public void sixEqualArmsSelectFourSizes(GameTestHelper h) {
		clear(h);
		ServerLevel level = h.getLevel();
		BlockPos c = BlockPos.containing(point(h, 24, 24)).above(6);
		for (int r = 1; r <= 4; r++) {
			arms(level, c, r, Blocks.NETHERRACK);
			h.assertTrue(VoidGate.radius(level, c) == r, "not size " + (2*r+1));
		}
		level.setBlock(c.above(4), Blocks.AIR.defaultBlockState(), 2);
		h.assertTrue(VoidGate.radius(level, c) == 0, "unequal arms accepted");
		h.assertTrue(VoidGate.world(Blocks.NETHERRACK) == Level.NETHER && VoidGate.world(Blocks.STONE) == Level.OVERWORLD
			&& VoidGate.world(Blocks.END_STONE) == Level.END && VoidGate.world(VoidIslands.STONE) == VoidWorld.KEY, "world markers missing");
		h.succeed();
	}

	@GameTest(template = ARENA, batch = "portalCraft", timeoutTicks = 20)
	public void crystalCraftingAndMiningGiveTheActualResources(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		var input = CraftingInput.of(2, 1, List.of(new ItemStack(Items.REDSTONE), new ItemStack(VoidGate.SHARD)));
		var found = level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, level);
		h.assertTrue(found.isPresent(), "redstone recipe missing");
		ItemStack output = found.orElseThrow().value().assemble(input, level.registryAccess());
		h.assertTrue(output.is(Items.REDSTONE) && output.getCount() == 4, "not four redstone");
		var packed = CraftingInput.of(3, 3, Collections.nCopies(9, new ItemStack(VoidGate.SHARD)));
		var recipe = level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, packed, level).orElseThrow();
		h.assertTrue(recipe.value().assemble(packed, level.registryAccess()).is(VoidCraft.BLOCK.asItem()), "nine crystals do not make a block");
		for (int i = 0; i < 12; i++) {
			List<ItemStack> drops = Block.getDrops(VoidIslands.CRYSTAL.defaultBlockState(), level, BlockPos.containing(point(h, 24, 24)), null);
			int count = drops.stream().filter(s -> s.is(VoidGate.SHARD)).mapToInt(ItemStack::getCount).sum();
			h.assertTrue(count >= 2 && count <= 4 && drops.stream().noneMatch(s -> s.is(Items.AMETHYST_SHARD)), "crystal did not drop 2-4 void crystals");
		}
		h.assertTrue(VoidCraft.LANTERN.defaultBlockState().getLightEmission() == 15 && VoidCraft.SLAB instanceof SlabBlock, "decorations missing");
		h.succeed();
	}

	@GameTest(template = ARENA, batch = "portalSize", timeoutTicks = 20)
	public void capitalsAndAllGroundTroopsHaveTheirGateSizes(GameTestHelper h) {
		VoidRift gate = VoidForces.RIFT.create(h.getLevel());
		for (int size : new int[] {3, 5, 7, 9}) {
			gate.size(size);
			h.assertTrue(gate.getBbHeight() == size && gate.getBbWidth() == size, "body does not match sphere");
			for (UnitKind kind : new UnitKind[] {UnitKind.BATTLECRUISER, UnitKind.CARRIER, UnitKind.TEMPEST, UnitKind.BROOD_LORD})
				h.assertTrue(gate.admits(kind, true) == (size >= 7), "capital fits wrong gate: " + kind);
			h.assertTrue(gate.admits(UnitKind.MOTHERSHIP, true) == (size >= 9), "mothership fits wrong gate");
			if (size >= 7) for (UnitKind kind : UnitKind.values()) if (!kind.flying && !kind.structure())
				h.assertTrue(gate.admits(kind, false), "ground unit excluded: " + kind);
		}
		h.assertTrue(UnitKind.BATTLECRUISER.modelScale() > UnitKind.UNIT_SIZE && UnitKind.BROOD_LORD.width > 6, "capital models were not enlarged");
		Amun amun = VoidForces.AMUN.create(h.getLevel());
		gate.size(7); h.assertFalse(gate.admits(amun), "Amun fits seven");
		StarUnit crawler = StarMine.type(UnitKind.SPINE_CRAWLER).create(h.getLevel());
		crawler.setMode(dev.starmine.ability.Modes.UPROOTED);
		h.assertTrue(gate.admits(crawler), "mobile rooted-building form excluded from ground troops");
		crawler.setMode(0);
		h.assertFalse(gate.admits(crawler), "rooted building admitted");
		gate.size(9); h.assertTrue(gate.admits(amun) && amun.getBbHeight() == 9, "Amun does not fit nine");
		h.assertFalse(amun.canChangeDimensions(h.getLevel(), h.getLevel()), "Amun may leave the Void before personal invasions are enabled");
		h.succeed();
	}



	@GameTest(template = ARENA, batch = "portalSides", timeoutTicks = 30)
	public void hostileArmiesAndAmunAttackPlayerGates(GameTestHelper h) {
		clear(h);
		ServerLevel level = h.getLevel();
		UUID owner = UUID.randomUUID();
		VoidRift gate = VoidForces.RIFT.create(level);
		gate.configure(owner, UUID.randomUUID(), Level.NETHER, Vec3.ZERO, 7);
		gate.moveTo(point(h, 24, 24));
		StarUnit mine = unit(h, UnitKind.MARINE, 10, 10, owner), wild = unit(h, UnitKind.MARINE, 12, 12, null);
		h.assertTrue(Faction.allied(mine, gate) && !Faction.hostile(mine, gate), "owner attacks gate");
		h.assertTrue(Faction.hostile(wild, gate) && Amun.foeOf(gate), "hostiles ignore player gate");
		VoidRift rift = VoidForces.RIFT.create(level);
		h.assertTrue(rift.invasion() && Faction.amun(rift) && !Faction.amun(gate), "sides mixed");
		h.assertTrue(rift.travel(mine) == null, "one-way invasion gate admitted a traveller");
		for (int i = 0; i < 1200; i++) rift.tick();
		h.assertTrue(rift.size() == 5, "invasion gate did not grow");
		h.succeed();
	}

	@GameTest(template = ARENA, batch = "portalOwnedArmy", timeoutTicks = 30)
	public void arrivalsStillTakeOrdersWhenTheDestinationHasItsOwnBase(GameTestHelper h) {
		clear(h);
		ServerLevel level=h.getLevel();
		ServerPlayer player=(ServerPlayer)h.makeMockServerPlayerInLevel();
		try {
			player.getInventory().add(new ItemStack(dev.starmine.colony.CommandPanel.ITEM));
			var data=dev.starmine.hive.HiveSavedData.get(level.getServer());
			var local=new dev.starmine.hive.Hive(UUID.randomUUID(),level.dimension().location().toString(),BlockPos.containing(point(h,24,24)));
			local.owner=player.getUUID(); data.add(local);
			var home=new dev.starmine.hive.Hive(UUID.randomUUID(),"minecraft:the_end",BlockPos.ZERO);
			home.owner=player.getUUID(); data.add(home);
			StarUnit marine=unit(h,UnitKind.MARINE,24,24,player.getUUID()); marine.joinHive(home);
			player.setPos(marine.position());
			dev.starmine.colony.Commands.command(player,new dev.starmine.colony.ColonyNetwork.UnitCommand(new int[]{marine.getId()},"hold",0,0,0,-1,""));
			h.assertTrue(marine.order()==StarUnit.Order.HOLD,"arrival tied to another base ignored its owner");
			marine.discard(); data.remove(local.id); data.remove(home.id);
		} finally { player.discard(); }
		h.succeed();
	}

}
