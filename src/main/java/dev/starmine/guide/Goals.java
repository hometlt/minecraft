package dev.starmine.guide;

import com.mojang.brigadier.CommandDispatcher;
import dev.starmine.StarMine;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.HiveSavedData;
import java.util.Map;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleFactory;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * The journal of goals (owner 16.10, item 7): the StarMine tab of the advancements screen (L) — get a
 * command panel, found a base, mine minerals and vespene, take control of a unit, find the three races'
 * bases, a suit, a module, a gauss rifle, reach a sky ship, beat the three heroes; owner 19.10: an army of 20, an upgrade at
 * level 3, a race tool, a turret, a race boat, terrazine, the guardians freed, the heroes of the Nether and the End, a void
 * shard. Each goal is an
 * advancement with one `minecraft:impossible` criterion, granted here: most are checked every two
 * seconds from what the player has and where they are, the heroes when they die near the player.
 * The first goal not done is the next step: `/stargoals` names it, and with the game rule
 * `starmineHints` on (the default) a hint about it comes every five minutes and when a goal is done.
 */
public final class Goals {
	/** The goals in the order the journal suggests them. */
	public enum Goal {
		COMMAND_PANEL, OWN_BASE, MINERALS, VESPENE, POSSESS, ARMY, UPGRADE, FIND_ZERG, FIND_TERRAN, FIND_PROTOSS, SUIT, MODULE, GAUSS_RIFLE,
		RACE_TOOL, TURRET, RACE_BOAT, TERRAZINE, SKY_SHIP, GUARDIANS, KERRIGAN, RAYNOR, ARTANIS, ALL_HEROES, NETHER_HEROES, END_HEROES, VOID_SHARD,
		ARTIFACT, VOID, AMUN;

		public final String id = name().toLowerCase();

		public net.minecraft.resources.ResourceLocation advancement() {
			return StarMine.id("goals/" + id);
		}
	}

	public static final net.minecraft.resources.ResourceLocation ROOT = StarMine.id("goals/root");
	/** Hints about the next goal now and then (on by default). */
	public static final GameRules.Key<GameRules.BooleanValue> HINTS =
		GameRuleRegistry.register("starmineHints", GameRules.Category.CHAT, GameRuleFactory.createBooleanRule(true));
	/** How near (blocks, flat) a race's main base must be to count as found. */
	public static final double FOUND = 96;
	/** How high a player must be over a base to be on its sky ship. */
	public static final int SHIP_Y = 180;
	public static final int HINT_EVERY = 20 * 60 * 5;
	/** Fighters (not workers, not buildings) a base must have for the army goal. */
	public static final int ARMY = 20;
	/** Owner 19.10: the heroes freed for the group goals (VoidWorld.State.freed keeps them by name). */
	static final java.util.List<String> GUARDIAN_NAMES = java.util.List.of("ABATHUR", "SWANN", "KARAX"),
		NETHER_NAMES = java.util.List.of("TOSH", "ZERATUL", "ZAGARA"), END_NAMES = java.util.List.of("HORNER", "STUKOV", "SELENDIS");
	private static final Map<UnitKind, Goal> HEROES = Map.of(UnitKind.KERRIGAN, Goal.KERRIGAN, UnitKind.RAYNOR, Goal.RAYNOR, UnitKind.ARTANIS, Goal.ARTANIS);

	private Goals() {
	}

	public static void load() {
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(Goals::serverTick);
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof StarUnit unit && HEROES.containsKey(unit.kind) && unit.level() instanceof ServerLevel level)
				for (ServerPlayer player : level.players())
					if (player.distanceTo(unit) <= dev.starmine.entity.Heroes.BAR_RANGE) award(player, HEROES.get(unit.kind));
		});
		net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> register(dispatcher));
	}

	/** Owner 17.10: a hero beaten — freed from Amun, not killed — counts as beaten for everyone near. */
	public static void heroFreed(ServerPlayer player, UnitKind hero) {
		Goal goal = HEROES.get(hero);
		if (goal != null) award(player, goal);
	}

	private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("stargoals").executes(context -> {
			ServerPlayer player = context.getSource().getPlayerOrException();
			context.getSource().sendSuccess(() -> journal(player), false);
			return 1;
		}));
	}

	/** Every two seconds: each player's goals that can be seen from what they hold and where they are; a hint now and then. */
	static void serverTick(MinecraftServer server) {
		if (server.getTickCount() % 40 != 0) return;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			check(player);
			if (player.tickCount > 0 && player.tickCount % HINT_EVERY < 40 && server.getGameRules().getBoolean(HINTS)) hint(player);
		}
	}

	/** Grants whatever of the player's goals is true now. */
	public static void check(ServerPlayer player) {
		award(player, null); // the root, so the tab shows
		Inventory inventory = player.getInventory();
		boolean piloting = dev.starmine.possess.Possession.piloting(player);
		if (has(inventory, stack -> stack.is(dev.starmine.colony.CommandPanel.ITEM))) award(player, Goal.COMMAND_PANEL);
		HiveSavedData data = HiveSavedData.get(player.getServer());
		dev.starmine.hive.Hive base = data.baseOf(player.getUUID(), player.serverLevel().dimension().location().toString());
		if (base != null) {
			award(player, Goal.OWN_BASE);
			int army = 0;
			for (Map.Entry<UnitKind, Integer> member : base.members.entrySet())
				if (!member.getKey().structure() && !member.getKey().worker() && member.getKey() != UnitKind.LARVA) army += member.getValue();
			if (army >= ARMY) award(player, Goal.ARMY);
			for (dev.starmine.race.Research research : dev.starmine.race.Research.values())
				if (base.levels.getOrDefault(research.id(), 0) >= 3) award(player, Goal.UPGRADE);
		}
		if (has(inventory, stack -> stack.is(dev.starmine.mineral.Minerals.SHARD))) award(player, Goal.MINERALS);
		if (has(inventory, stack -> stack.is(dev.starmine.vespene.Vespene.BUCKET))) award(player, Goal.VESPENE);
		if (piloting) award(player, Goal.POSSESS);
		if (has(inventory, stack -> stack.getItem() instanceof net.minecraft.world.item.ArmorItem armor
			&& dev.starmine.suit.SuitItems.SHARDS.keySet().stream().anyMatch(race -> dev.starmine.suit.SuitItems.piece(race, armor.getType()) == armor)))
			award(player, Goal.SUIT);
		if (!piloting && has(inventory, stack -> stack.getItem() instanceof dev.starmine.possess.ModuleItem)) award(player, Goal.MODULE);
		if (has(inventory, stack -> stack.is(dev.starmine.gear.Guns.GAUSS_RIFLE))) award(player, Goal.GAUSS_RIFLE);
		if (has(inventory, stack -> dev.starmine.gear.RaceTools.ITEMS.values().stream().anyMatch(tools -> tools.containsValue(stack.getItem()))))
			award(player, Goal.RACE_TOOL);
		if (player.getVehicle() instanceof dev.starmine.gear.RaceBoats.RaceBoat) award(player, Goal.RACE_BOAT);
		if (has(inventory, stack -> stack.is(dev.starmine.vespene.Vespene.TERRAZINE_BUCKET))) award(player, Goal.TERRAZINE);
		if (has(inventory, stack -> stack.is(dev.starmine.world.VoidGate.SHARD))) award(player, Goal.VOID_SHARD);
		if (!player.serverLevel().getEntitiesOfClass(dev.starmine.gear.Turrets.Turret.class, player.getBoundingBox().inflate(32),
			turret -> player.getUUID().equals(turret.owner())).isEmpty()) award(player, Goal.TURRET);
		java.util.Set<String> freed = dev.starmine.world.VoidWorld.State.get(player.getServer()).freed;
		if (freed.containsAll(GUARDIAN_NAMES)) award(player, Goal.GUARDIANS);
		if (freed.containsAll(NETHER_NAMES)) award(player, Goal.NETHER_HEROES);
		if (freed.containsAll(END_NAMES)) award(player, Goal.END_HEROES);
		if (player.level().dimension() == Level.OVERWORLD) {
			for (Map.Entry<UnitKind.Race, BlockPos> site : sites(data).entrySet()) {
				double dx = player.getX() - site.getValue().getX(), dz = player.getZ() - site.getValue().getZ();
				double flat = Math.sqrt(dx * dx + dz * dz);
				if (flat > FOUND * 2) continue;
				if (flat <= FOUND) award(player, switch (site.getKey()) {
					case ZERG -> Goal.FIND_ZERG;
					case TERRAN -> Goal.FIND_TERRAN;
					case PROTOSS -> Goal.FIND_PROTOSS;
				});
				if (player.getY() >= SHIP_Y) award(player, Goal.SKY_SHIP);
			}
		}
		if (done(player, Goal.KERRIGAN) && done(player, Goal.RAYNOR) && done(player, Goal.ARTANIS)) award(player, Goal.ALL_HEROES);
		if (dev.starmine.world.Stratosphere.is(player.level())) award(player, Goal.SKY_SHIP); // owner 17.10: the ships hang in the stratosphere
		if (dev.starmine.world.VoidWorld.is(player.level())) award(player, Goal.VOID);
		if (player.level() instanceof ServerLevel level) // a whole artifact near the player
			for (dev.starmine.world.VoidWorld.State.Open open : dev.starmine.world.VoidWorld.State.get(player.getServer()).open())
				if (open.dimension() == level.dimension() && open.pos().closerToCenterThan(player.position(), FOUND)) award(player, Goal.ARTIFACT);
	}

	/** The three races' main bases of the world (sites chosen so far). */
	static Map<UnitKind.Race, BlockPos> sites(HiveSavedData data) {
		Map<UnitKind.Race, BlockPos> sites = new java.util.EnumMap<>(UnitKind.Race.class);
		if (data.spawnHiveSite != null) sites.put(UnitKind.Race.ZERG, data.spawnHiveSite);
		data.structureSites.forEach((race, site) -> {
			try {
				sites.put(UnitKind.Race.valueOf(race), site);
			} catch (IllegalArgumentException ignored) {
				// not a race's base
			}
		});
		return sites;
	}

	private static boolean has(Inventory inventory, java.util.function.Predicate<ItemStack> what) {
		for (int i = 0; i < inventory.getContainerSize(); i++)
			if (what.test(inventory.getItem(i))) return true;
		return false;
	}

	private static @Nullable AdvancementHolder holder(ServerPlayer player, @Nullable Goal goal) {
		return player.getServer().getAdvancements().get(goal == null ? ROOT : goal.advancement());
	}

	/** Whether the player has done a goal. */
	public static boolean done(ServerPlayer player, Goal goal) {
		AdvancementHolder holder = holder(player, goal);
		return holder != null && player.getAdvancements().getOrStartProgress(holder).isDone();
	}

	/** Grants a goal (null: the journal's root); the first time, a hint about the next one follows. */
	public static void award(ServerPlayer player, @Nullable Goal goal) {
		AdvancementHolder holder = holder(player, goal);
		if (holder == null || player.getAdvancements().getOrStartProgress(holder).isDone()) return;
		player.getAdvancements().award(holder, "done");
		if (goal != null && player.getServer().getGameRules().getBoolean(HINTS)) hint(player);
	}

	/** The first goal not done, or null when all are. */
	public static @Nullable Goal next(ServerPlayer player) {
		for (Goal goal : Goal.values())
			if (!done(player, goal)) return goal;
		return null;
	}

	/** A hint about the next goal, in the chat. */
	static void hint(ServerPlayer player) {
		Goal next = next(player);
		if (next == null) return;
		player.sendSystemMessage(Component.translatable("message.starmine.goal_next", Component.translatable("hint.starmine." + next.id))
			.withStyle(ChatFormatting.GRAY));
	}

	/** `/stargoals`: every goal, done or not, and the next step. */
	static Component journal(ServerPlayer player) {
		MutableComponent text = Component.translatable("message.starmine.goals").withStyle(ChatFormatting.GOLD);
		for (Goal goal : Goal.values()) {
			boolean done = done(player, goal);
			text.append(Component.literal("\n" + (done ? "✔ " : "✘ ")).withStyle(done ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY))
				.append(Component.translatable("advancements.starmine." + goal.id + ".title").withStyle(done ? ChatFormatting.WHITE : ChatFormatting.GRAY));
		}
		Goal next = next(player);
		text.append(Component.literal("\n")).append(next == null ? Component.translatable("message.starmine.goals_all").withStyle(ChatFormatting.GOLD)
			: Component.translatable("message.starmine.goal_next", Component.translatable("hint.starmine." + next.id)).withStyle(ChatFormatting.YELLOW));
		return text;
	}
}
