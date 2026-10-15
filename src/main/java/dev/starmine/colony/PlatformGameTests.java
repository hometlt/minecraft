package dev.starmine.colony;

import static dev.starmine.ability.TestArena.ARENA;

import dev.starmine.ability.TestArena;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveSavedData;
import dev.starmine.suit.SuitItems;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;

/**
 * The supply platform (CLOUD_TASK_2 stage 5): its recipe, resources put in, a call refused when it holds
 * too little, and a call that brings a worker by transport and founds the player's base there.
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

	@GameTest(template = ARENA, batch = "platformShort", timeoutTicks = 40)
	public void aPlatformWithTooLittleCallsNobody(GameTestHelper helper) {
		TestArena.clear(helper);
		helper.setBlock(AT, PlatformBlock.BLOCKS.get(UnitKind.Race.TERRAN));
		BlockPos pos = helper.absolutePos(AT);
		Player player = player(helper);
		ItemStack iron = new ItemStack(Items.RAW_IRON, 40);
		helper.assertTrue("message.starmine.platform_holds".equals(PlatformBlock.put(helper.getLevel(), pos, player, iron)), "raw iron is not taken");
		helper.assertTrue(iron.isEmpty(), "the stack is left in the hand");
		PlatformBlockEntity platform = (PlatformBlockEntity) helper.getLevel().getBlockEntity(pos);
		helper.assertTrue(platform.minerals() == 320 && platform.stock().getOrDefault("iron", 0) == 40,
			"40 raw iron: " + platform.minerals() + " minerals, " + platform.stock());
		helper.assertTrue("message.starmine.platform_short".equals(PlatformBlock.call(helper.getLevel(), pos, player)), "a call with 320 minerals");
		helper.runAfterDelay(20, () -> {
			helper.assertTrue(helper.getLevel().getEntitiesOfClass(StarUnit.class, new AABB(pos).inflate(40)).isEmpty(), "a transport came anyway");
			helper.assertBlockPresent(PlatformBlock.BLOCKS.get(UnitKind.Race.TERRAN), AT);
			helper.succeed();
		});
	}

	/** 63 raw iron (504 minerals) and the command panel: a medivac brings an SCV, the platform goes, a command center rises there. */
	@GameTest(template = ARENA, batch = "platformCall", timeoutTicks = 1600)
	public void aCalledTransportFoundsTheBase(GameTestHelper helper) {
		TestArena.clear(helper);
		helper.setBlock(AT, PlatformBlock.BLOCKS.get(UnitKind.Race.TERRAN));
		BlockPos pos = helper.absolutePos(AT);
		Player player = player(helper);
		PlatformBlock.put(helper.getLevel(), pos, player, new ItemStack(Items.RAW_IRON, 63));
		PlatformBlock.put(helper.getLevel(), pos, player, new ItemStack(Items.COAL, 5));
		String said = PlatformBlock.call(helper.getLevel(), pos, player);
		helper.assertTrue("message.starmine.platform_calling".equals(said), "the call: " + said);
		helper.assertTrue("message.starmine.platform_called".equals(PlatformBlock.call(helper.getLevel(), pos, player)), "a second call went through");
		AABB area = new AABB(pos).inflate(48, 40, 48);
		StarUnit medivac = helper.getLevel().getEntitiesOfClass(StarUnit.class, area, unit -> unit.kind == UnitKind.MEDIVAC).stream().findFirst().orElse(null);
		helper.assertTrue(medivac != null && medivac.getPassengers().size() == 1, "no medivac with an SCV aboard");
		helper.assertTrue(medivac.distanceToSqr(pos.getCenter()) > 15 * 15, "the medivac did not come from afar");
		helper.succeedWhen(() -> {
			Hive base = HiveSavedData.get(helper.getLevel().getServer()).baseOf(player.getUUID(), helper.getLevel().dimension().location().toString());
			helper.assertTrue(base != null, "no base yet");
			StarUnit cc = helper.getLevel().getEntitiesOfClass(StarUnit.class, area, unit -> unit.kind == UnitKind.COMMAND_CENTER).stream().findFirst().orElse(null);
			helper.assertTrue(cc != null && base.id.equals(cc.hiveId()), "no command center of the base");
			helper.assertTrue(cc.blockPosition().distManhattan(pos) <= 2, "the command center is not on the platform's place: " + TestArena.where(helper, cc));
			helper.assertTrue(!(helper.getLevel().getBlockState(pos).getBlock() instanceof PlatformBlock), "the platform is still there");
			helper.assertTrue(helper.getLevel().getEntitiesOfClass(StarUnit.class, area, unit -> unit.kind == UnitKind.MEDIVAC).isEmpty(), "the medivac is still there");
			StarUnit scv = helper.getLevel().getEntitiesOfClass(StarUnit.class, area, unit -> unit.kind == UnitKind.SCV).stream().findFirst().orElse(null);
			helper.assertTrue(scv != null && base.id.equals(scv.hiveId()) && scv.owner().map(player.getUUID()::equals).orElse(false), "the SCV is not the player's");
			helper.assertTrue(base.collected.getOrDefault("coal", 0) == 5, "the platform's coal did not go to the base: " + base.collected);
		});
	}
}
