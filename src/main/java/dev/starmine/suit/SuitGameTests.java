package dev.starmine.suit;

import dev.starmine.ability.Ability;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.possess.ModuleItem;
import java.util.List;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import static dev.starmine.ability.TestArena.ARENA;
import static dev.starmine.ability.TestArena.clear;
import static dev.starmine.ability.TestArena.point;
import static dev.starmine.ability.TestArena.unit;

/**
 * Race suits (owner 15.10, {@link Suits}): shards from units a player kills, each race's gift of a whole
 * suit, ability modules that work only in their race's suit.
 */
public class SuitGameTests implements FabricGameTest {
	/** A test player at the arena's corner. */
	private static ServerPlayer player(GameTestHelper helper) {
		ServerPlayer player = (ServerPlayer) helper.makeMockServerPlayerInLevel();
		Vec3 at = point(helper, 5, 5);
		player.teleportTo(at.x, at.y, at.z);
		// a mock player may not be in the server's player list: its suit is then ticked here
		helper.onEachTick(() -> {
			if (!player.isRemoved() && !helper.getLevel().getServer().getPlayerList().getPlayers().contains(player)) Suits.tick(player);
		});
		return player;
	}

	/** A check at `delay`; a failing one, or the `last`, takes the player away (left in the world it would make every other test's mobs despawn). */
	private static void step(GameTestHelper helper, ServerPlayer player, int delay, boolean last, Consumer<ServerPlayer> check) {
		helper.runAfterDelay(delay, () -> {
			try {
				check.accept(player);
			} catch (RuntimeException | Error failure) {
				helper.getLevel().removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
				throw failure;
			}
			if (!last) return;
			helper.getLevel().removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
			helper.succeed();
		});
	}

	private static void wear(ServerPlayer player, UnitKind.Race race) {
		EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
		for (int i = 0; i < 4; i++) player.setItemSlot(slots[i], new ItemStack(SuitItems.piece(race, SuitItems.TYPES.get(i))));
	}

	/** An ultralisk a player kills leaves zerg shards for its cost (475 → 9 or 10); one killed by a wild unit leaves none. */
	@GameTest(template = ARENA, batch = "suitShards", timeoutTicks = 200)
	public void unitsLeaveShardsWhenAPlayerKillsThem(GameTestHelper helper) {
		clear(helper);
		ServerPlayer player = player(helper);
		StarUnit ultralisk = unit(helper, UnitKind.ULTRALISK, 20, 24, null);
		StarUnit wild = unit(helper, UnitKind.ZERGLING, 30, 24, null), other = unit(helper, UnitKind.ULTRALISK, 36, 24, null);
		for (StarUnit unit : List.of(ultralisk, wild, other)) unit.setNoAi(true);
		helper.runAfterDelay(2, () -> {
			ultralisk.hurt(helper.getLevel().damageSources().playerAttack(player), 10000);
			other.hurt(helper.getLevel().damageSources().mobAttack(wild), 10000);
		});
		step(helper, player, 40, true, p -> {
			int near = shards(helper, ultralisk.position()), far = shards(helper, other.position());
			helper.assertTrue(near >= 9 && near <= 10, "a player's kill left " + near + " zerg shards");
			helper.assertTrue(far == 0, "a wild unit's kill left " + far + " shards");
		});
	}

	private static int shards(GameTestHelper helper, Vec3 at) {
		int count = 0;
		for (ItemEntity item : helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(at, at).inflate(4)))
			if (item.getItem().is(SuitItems.SHARDS.get(UnitKind.Race.ZERG))) count += item.getItem().getCount();
		return count;
	}

	/** A whole protoss suit gives shields that come back by themselves; without the whole suit there are none. */
	@GameTest(template = ARENA, batch = "suitProtoss", timeoutTicks = 300)
	public void protossSuitShieldsComeBack(GameTestHelper helper) {
		clear(helper);
		ServerPlayer player = player(helper);
		helper.runAfterDelay(2, () -> wear(player, UnitKind.Race.PROTOSS));
		step(helper, player, 200, false, p -> {
			helper.assertTrue(p.getAttributeValue(Attributes.MAX_ABSORPTION) >= Suits.SHIELDS, "no shield capacity");
			helper.assertTrue(p.getAbsorptionAmount() >= Suits.SHIELDS - 0.01, "shields came back to " + p.getAbsorptionAmount());
			p.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
		});
		step(helper, player, 210, true, p -> {
			helper.assertTrue(p.getAttributeValue(Attributes.MAX_ABSORPTION) < 0.01, "shields without the whole suit");
			helper.assertTrue(p.getAbsorptionAmount() < 0.01, "shields left: " + p.getAbsorptionAmount());
		});
	}

	/** A whole zerg suit heals its wearer (with no natural healing: little food); a terran suit does not. */
	@GameTest(template = ARENA, batch = "suitZerg", timeoutTicks = 300)
	public void zergSuitRegenerates(GameTestHelper helper) {
		clear(helper);
		ServerPlayer player = player(helper);
		helper.runAfterDelay(2, () -> {
			player.getFoodData().setFoodLevel(17);
			player.getFoodData().setSaturation(0);
			player.setHealth(10);
			wear(player, UnitKind.Race.TERRAN);
		});
		step(helper, player, 100, false, p -> {
			helper.assertTrue(p.getHealth() <= 10, "a terran suit healed to " + p.getHealth());
			wear(p, UnitKind.Race.ZERG);
		});
		step(helper, player, 200, true, p -> helper.assertTrue(p.getHealth() >= 12, "a zerg suit healed only to " + p.getHealth()));
	}

	/**
	 * Modules work only in their race's whole suit: the stimpack without a suit and in a zerg suit is
	 * refused; in a terran suit it costs health and hastens the player; the ghost's cloak hides them.
	 */
	@GameTest(template = ARENA, batch = "suitModules", timeoutTicks = 200)
	public void modulesNeedTheirRaceSuit(GameTestHelper helper) {
		clear(helper);
		ServerPlayer player = player(helper);
		ModuleItem stim = ModuleItem.of(Ability.STIMPACK), cloak = ModuleItem.of(Ability.CLOAK);
		step(helper, player, 2, false, p -> {
			helper.assertTrue(!Suits.useModule(p, stim, InteractionHand.MAIN_HAND), "a stimpack without a suit");
			wear(p, UnitKind.Race.ZERG);
			helper.assertTrue(!Suits.useModule(p, stim, InteractionHand.MAIN_HAND), "a stimpack in a zerg suit");
			wear(p, UnitKind.Race.TERRAN);
			float health = p.getHealth();
			helper.assertTrue(Suits.useModule(p, stim, InteractionHand.MAIN_HAND), "no stimpack in a terran suit");
			helper.assertTrue(p.getHealth() < health, "the stimpack cost no health");
			helper.assertTrue(p.hasEffect(MobEffects.MOVEMENT_SPEED), "the stimpack did not hasten the player");
			helper.assertTrue(Suits.useModule(p, cloak, InteractionHand.MAIN_HAND), "no cloak in a terran suit");
			helper.assertTrue(Suits.casterKind(Ability.SIEGE_MODE) == null && Suits.casterKind(Ability.ORBITAL_COMMAND) == null,
				"a form or a building's ability in a suit");
		});
		step(helper, player, 40, true, p -> helper.assertTrue(p.hasEffect(MobEffects.INVISIBILITY), "the cloak did not hide the player"));
	}

	/** Every suit piece is known as its race's (the whole suit is told by its pieces' material). */
	@GameTest(template = ARENA, batch = "suitItems", timeoutTicks = 20)
	public void everyPieceIsItsRace(GameTestHelper helper) {
		for (UnitKind.Race race : UnitKind.Race.values())
			for (ArmorItem.Type type : SuitItems.TYPES)
				helper.assertTrue(SuitItems.raceOf(SuitItems.piece(race, type)) == race, race + " " + type + " is not of its race");
		helper.succeed();
	}
}
