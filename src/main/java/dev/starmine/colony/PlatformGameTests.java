package dev.starmine.colony;

import static dev.starmine.ability.TestArena.ARENA;

import dev.starmine.ability.TestArena;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveResources;
import dev.starmine.hive.HiveSavedData;
import dev.starmine.mineral.Minerals;
import dev.starmine.suit.SuitItems;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/**
 * The supply platform (owner 17.10): crafted, placed it is a building of its race; a chest of its bank; a worker
 * for exactly 100 minerals, who founds the player's base at it; monsters attack it; workers gather minerals first.
 */
public class PlatformGameTests implements FabricGameTest {
	private static final BlockPos AT = new BlockPos(24, 2, 24);

	@GameTest(template = ARENA, batch = "platformCraft", timeoutTicks = 20)
	public void aMapAmidEightShardsMakesAPlatform(GameTestHelper helper) {
		for (UnitKind.Race race : UnitKind.Race.values()) {
			List<ItemStack> grid = new ArrayList<>();
			for (int i = 0; i < 9; i++) grid.add(i == 4 ? new ItemStack(Items.MAP) : new ItemStack(SuitItems.SHARDS.get(race)));
			CraftingInput input = CraftingInput.of(3, 3, grid);
			var recipe = helper.getLevel().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, helper.getLevel());
			helper.assertTrue(recipe.isPresent(), "no recipe for the " + race + " platform");
			ItemStack made = recipe.get().value().assemble(input, helper.getLevel().registryAccess());
			helper.assertTrue(made.is(PlatformBlock.BLOCKS.get(race).asItem()), race + " shards make " + made);
		}
		helper.succeed();
	}

	private static Player player(GameTestHelper helper) {
		return helper.makeMockPlayer(GameType.SURVIVAL);
	}

	/** A placed platform block: the platform building of its race, the player's, the block gone. */
	private static StarUnit platform(GameTestHelper helper, UnitKind.Race race, Player player) {
		helper.setBlock(AT, PlatformBlock.BLOCKS.get(race));
		StarUnit platform = Storage.fromBlock(helper.getLevel(), helper.absolutePos(AT), race, player.getUUID());
		helper.assertTrue(platform != null && platform.kind == UnitKind.platformOf(race), "no " + race + " platform building");
		helper.assertTrue(platform.kind.structure() && platform.kind.storage(), "the platform is not a storage building");
		helper.assertTrue(helper.getLevel().getBlockState(helper.absolutePos(AT)).isAir(), "the platform block stayed");
		return platform;
	}

	@GameTest(template = ARENA, batch = "platformShort", timeoutTicks = 40)
	public void aPlatformWithTooLittleCallsNobody(GameTestHelper helper) {
		TestArena.clear(helper);
		Player player = player(helper);
		StarUnit platform = platform(helper, UnitKind.Race.TERRAN, player);
		platform.bankMinerals = Storage.WORKER_PRICE - 5;
		helper.assertTrue("message.starmine.platform_short".equals(Storage.call(helper.getLevel(), platform, player)), "a call with 95 minerals");
		helper.runAfterDelay(20, () -> {
			helper.assertTrue(helper.getLevel().getEntitiesOfClass(StarUnit.class, new AABB(platform.blockPosition()).inflate(40),
				unit -> unit.kind == UnitKind.MEDIVAC).isEmpty(), "a transport came anyway");
			helper.assertTrue(platform.bankMinerals == 95, "the short call took minerals");
			helper.succeed();
		});
	}

	/** The bank as a chest: its minerals as mineral shards; shards taken out and iron laid in change the bank. */
	@GameTest(template = ARENA, batch = "platformChest", timeoutTicks = 40)
	public void thePlatformIsAChestOfTheBank(GameTestHelper helper) {
		TestArena.clear(helper);
		Player player = player(helper);
		StarUnit platform = platform(helper, UnitKind.Race.ZERG, player);
		platform.bankMinerals = 52;
		Storage.View view = new Storage.View(Storage.bankOf(helper.getLevel(), platform), player);
		helper.assertTrue(view.getContainerSize() == 54 && view.getItem(0).is(Minerals.SHARD) && view.getItem(0).getCount() == 10,
			"52 minerals do not show as 10 shards: " + view.getItem(0));
		view.removeItem(0, 4); // 4 shards = 20 minerals taken out
		helper.assertTrue(platform.bankMinerals == 32, "taking 4 shards left " + platform.bankMinerals);
		view.setItem(5, new ItemStack(Items.RAW_IRON, 10));
		int iron = HiveResources.yieldOfItem(new ItemStack(Items.RAW_IRON)).minerals() * 10;
		helper.assertTrue(platform.bankMinerals == 32 + iron, "10 raw iron did not count: " + platform.bankMinerals);
		helper.assertFalse(view.canPlaceItem(6, new ItemStack(Items.STICK)), "a stick is taken");
		helper.succeed();
	}

	/** 100 minerals and the command panel: a medivac brings an SCV, the base is founded at the platform, no main building. */
	@GameTest(template = ARENA, batch = "platformCall", timeoutTicks = 1600)
	public void aCalledWorkerFoundsTheBaseAtThePlatform(GameTestHelper helper) {
		TestArena.clear(helper);
		Player player = player(helper);
		StarUnit platform = platform(helper, UnitKind.Race.TERRAN, player);
		platform.bankMinerals = Storage.WORKER_PRICE + 30;
		String said = Storage.call(helper.getLevel(), platform, player);
		helper.assertTrue("message.starmine.platform_calling".equals(said), "the call: " + said);
		helper.assertTrue(platform.bankMinerals == 30, "the call cost " + (Storage.WORKER_PRICE + 30 - platform.bankMinerals));
		helper.assertTrue("message.starmine.platform_called".equals(Storage.call(helper.getLevel(), platform, player)), "a second call went through");
		AABB area = new AABB(platform.blockPosition()).inflate(48, 40, 48);
		StarUnit medivac = helper.getLevel().getEntitiesOfClass(StarUnit.class, area, unit -> unit.kind == UnitKind.MEDIVAC).stream().findFirst().orElse(null);
		helper.assertTrue(medivac != null && medivac.getPassengers().size() == 1, "no medivac with an SCV aboard");
		helper.succeedWhen(() -> {
			Hive base = HiveSavedData.get(helper.getLevel().getServer()).baseOf(player.getUUID(), helper.getLevel().dimension().location().toString());
			helper.assertTrue(base != null, "no base yet");
			helper.assertTrue(base.id.equals(platform.hiveId()), "the platform is not a storage of the base");
			helper.assertTrue(base.minerals == 30 && platform.bankMinerals == 0, "the platform's minerals did not go to the base: " + base.minerals);
			StarUnit scv = helper.getLevel().getEntitiesOfClass(StarUnit.class, area, unit -> unit.kind == UnitKind.SCV && base.id.equals(unit.hiveId()))
				.stream().findFirst().orElse(null);
			helper.assertTrue(scv != null && scv.owner().map(player.getUUID()::equals).orElse(false), "the base has no SCV of the player's");
			helper.assertTrue(helper.getLevel().getEntitiesOfClass(StarUnit.class, area, unit -> unit.kind == UnitKind.COMMAND_CENTER
				&& base.id.equals(unit.hiveId())).isEmpty(), "a command center came for free");
			helper.assertTrue(helper.getLevel().getEntitiesOfClass(StarUnit.class, area, unit -> unit.kind == UnitKind.MEDIVAC).isEmpty(), "the medivac is still there");
		});
	}

	/** The platform is a building: a zombie goes for it and hurts it. */
	@GameTest(template = ARENA, batch = "platformAttacked", timeoutTicks = 400)
	public void monstersAttackThePlatform(GameTestHelper helper) {
		TestArena.clear(helper);
		StarUnit platform = platform(helper, UnitKind.Race.PROTOSS, player(helper));
		Zombie zombie = helper.spawn(EntityType.ZOMBIE, AT.offset(5, 0, 0));
		zombie.setPersistenceRequired();
		helper.succeedWhen(() -> helper.assertTrue(platform.getHealth() < platform.getMaxHealth() || platform.shield() < platform.kind.shields,
			"the zombie did not hurt the platform (target " + zombie.getTarget() + ")"));
	}

	/** Owner 17.10: workers gather minerals first — a crystal farther off before iron ore at hand. */
	@GameTest(template = ARENA, batch = "platformMinerals", timeoutTicks = 600)
	public void workersGatherMineralsFirst(GameTestHelper helper) {
		TestArena.clear(helper);
		BlockPos core = helper.absolutePos(new BlockPos(38, 2, 24));
		helper.assertTrue(Minerals.place(helper.getLevel(), core, (pos, state) -> helper.getLevel().setBlock(pos, state, 2)), "no crystal");
		for (int z = 20; z <= 28; z++) helper.setBlock(new BlockPos(14, 2, z), Blocks.IRON_ORE);
		StarUnit cc = TestArena.unit(helper, UnitKind.COMMAND_CENTER, 24, 8, UUID.randomUUID());
		helper.runAfterDelay(20, () -> {
			Hive base = TestArena.base(helper, cc);
			helper.assertTrue(base != null, "no base");
			StarUnit scv = TestArena.member(helper, base, UnitKind.SCV, 16, 24);
			helper.succeedWhen(() -> {
				helper.assertTrue(scv.cargoBlocks > 0 || base.collected.getOrDefault("minerals", 0) > 0, "nothing gathered yet: " + scv.goalsRunning());
				helper.assertTrue(helper.getLevel().getBlockState(helper.absolutePos(new BlockPos(14, 2, 24))).is(Blocks.IRON_ORE), "the SCV took the iron first");
				helper.assertTrue("minerals".equals(scv.cargoLabel) || base.collected.getOrDefault("minerals", 0) > 0, "the SCV carries " + scv.cargoLabel);
			});
		});
	}

	/** Owner 19.10, SC2: an SCV called away from an unfinished barracks goes on building it when sent to it again. */
	@GameTest(template = ARENA, batch = "resumeConstruction", timeoutTicks = 400)
	public void anScvSentToAnUnfinishedBuildingBuildsItAgain(GameTestHelper helper) {
		dev.starmine.ability.TestArena.clear(helper);
		java.util.UUID player = java.util.UUID.randomUUID();
		dev.starmine.entity.StarUnit cc = dev.starmine.ability.TestArena.unit(helper, dev.starmine.entity.UnitKind.COMMAND_CENTER, 10, 10, player);
		dev.starmine.entity.StarUnit barracks = dev.starmine.ability.TestArena.unit(helper, dev.starmine.entity.UnitKind.BARRACKS, 30, 30, player);
		dev.starmine.entity.StarUnit scv = dev.starmine.ability.TestArena.unit(helper, dev.starmine.entity.UnitKind.SCV, 22, 30, player);
		int[] left = new int[1];
		helper.runAfterDelay(5, () -> {
			dev.starmine.hive.Hive base = dev.starmine.ability.TestArena.base(helper, cc);
			helper.assertTrue(base != null, "no base");
			barracks.joinHive(base);
			scv.joinHive(base);
			barracks.startConstruction(barracks.kind.economy().trainTicks());
			scv.orderBuild(barracks.kind, barracks.blockPosition());
			scv.setConstructing(barracks.getUUID());
			scv.command(dev.starmine.entity.StarUnit.Order.NONE, null); // called away: the order and the building are dropped
			helper.assertTrue(scv.constructing() == null, "the SCV still builds after being called away");
			Commands.resumeConstruction(helper.getLevel(), base, java.util.List.of(scv), java.util.List.of(scv), barracks.getId(), barracks.position());
			helper.assertTrue(barracks.getUUID().equals(scv.constructing()), "the SCV did not take the building up again");
			left[0] = barracks.progress();
		});
		helper.runAfterDelay(300, () -> {
			helper.assertTrue(barracks.underConstruction() && barracks.progress() > left[0], "the barracks did not grow: " + left[0] + " -> " + barracks.progress());
			helper.succeed();
		});
	}

	/** Owner 19.10, SC2 autocast: a zealot's charge is on from the start, repair is not; a right click on the button toggles them. */
	@GameTest(template = ARENA, batch = "autocast", timeoutTicks = 40)
	public void aRightClickTogglesAutocast(GameTestHelper helper) {
		dev.starmine.ability.TestArena.clear(helper);
		java.util.UUID player = java.util.UUID.randomUUID();
		StarUnit zealot = dev.starmine.ability.TestArena.unit(helper, UnitKind.ZEALOT, 10, 10, player);
		StarUnit scv = dev.starmine.ability.TestArena.unit(helper, UnitKind.SCV, 20, 10, player);
		zealot.setNoAi(true);
		scv.setNoAi(true);
		dev.starmine.ability.Ability charge = dev.starmine.ability.Ability.CHARGE, repair = dev.starmine.ability.Ability.REPAIR;
		helper.assertTrue(zealot.abilities.autocasts(charge), "a zealot's charge is not on by itself");
		helper.assertFalse(scv.abilities.autocasts(repair), "an SCV repairs by itself from the start");
		helper.assertTrue(scv.abilities.autoGather, "a worker does not seek resources by itself from the start");
		Commands.autocast(java.util.List.of(zealot, scv), "charge");
		Commands.autocast(java.util.List.of(zealot, scv), "repair");
		Commands.autocast(java.util.List.of(zealot, scv), "gather");
		helper.assertFalse(zealot.abilities.autocasts(charge), "the charge stayed on");
		helper.assertTrue(scv.abilities.autocasts(repair) && scv.autocasts("repair"), "repair did not turn on");
		helper.assertFalse(scv.abilities.autoGather || scv.autocasts("gather"), "gathering by itself did not turn off");
		var tag = new net.minecraft.nbt.CompoundTag();
		scv.abilities.save(tag);
		var loaded = new dev.starmine.ability.AbilityState();
		loaded.load(tag);
		helper.assertTrue(loaded.autocasts(repair) && !loaded.autoGather, "the autocasts were not saved");
		helper.succeed();
	}
}
