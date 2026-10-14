package dev.starmine.hive;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.starmine.entity.UnitKind;
import java.util.Comparator;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * Hive debugging: `/starmine hive list`, `/starmine hive info` (the nearest hive) and
 * `/starmine hive give <minerals> [vespene]` (adds to the nearest hive), `/starmine hive generate`
 * (builds a generated hive with tunnels where the player stands; see {@link HiveStructure}), `/starmine structure
 * <terran|protoss|hyperion|mothership|leviathan>` (a generated base or sky ship, {@link dev.starmine.world.RaceBases}). Operators only.
 */
public final class HiveCommands {
	private HiveCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("starmine").requires(source -> source.hasPermission(2))
			.then(Commands.literal("hive")
				.then(Commands.literal("list").executes(HiveCommands::list))
				.then(Commands.literal("info").executes(HiveCommands::info))
				.then(Commands.literal("generate").executes(HiveCommands::generate))
				.then(Commands.literal("give")
					.then(Commands.argument("minerals", IntegerArgumentType.integer(0))
						.executes(context -> give(context, IntegerArgumentType.getInteger(context, "minerals"), 0))
						.then(Commands.argument("vespene", IntegerArgumentType.integer(0))
							.executes(context -> give(context, IntegerArgumentType.getInteger(context, "minerals"),
								IntegerArgumentType.getInteger(context, "vespene")))))))
			.then(Commands.literal("structure")
				.then(Commands.argument("what", com.mojang.brigadier.arguments.StringArgumentType.word())
					.suggests((context, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(STRUCTURES, builder))
					.executes(HiveCommands::structure))));
	}

	private static final java.util.List<String> STRUCTURES = java.util.List.of("terran", "protoss", "hyperion", "mothership", "leviathan");

	/** `/starmine structure <terran|protoss|hyperion|mothership|leviathan>`: a generated base or sky ship where the player stands. */
	private static int structure(CommandContext<CommandSourceStack> context) {
		var source = context.getSource();
		String what = com.mojang.brigadier.arguments.StringArgumentType.getString(context, "what");
		if (!STRUCTURES.contains(what)) {
			source.sendFailure(Component.literal("One of " + STRUCTURES));
			return 0;
		}
		var at = net.minecraft.core.BlockPos.containing(source.getPosition());
		var random = source.getLevel().random;
		dev.starmine.world.RaceBases.Report report = switch (what) {
			case "terran" -> dev.starmine.world.RaceBases.build(source.getLevel(), UnitKind.Race.TERRAN, at, random);
			case "protoss" -> dev.starmine.world.RaceBases.build(source.getLevel(), UnitKind.Race.PROTOSS, at, random);
			default -> dev.starmine.world.RaceBases.ship(source.getLevel(), what, at.above(dev.starmine.world.RaceBases.SKY), random);
		};
		source.sendSuccess(() -> Component.literal(what + " generated at " + at.toShortString() + ": " + report.blocks() + " blocks, "
			+ report.units() + " units waiting"), true);
		return 1;
	}

	private static Hive nearest(CommandContext<CommandSourceStack> context) {
		var source = context.getSource();
		String dimension = source.getLevel().dimension().location().toString();
		return HiveSavedData.get(source.getServer()).all().stream()
			.filter(hive -> hive.dimension.equals(dimension))
			.min(Comparator.comparingDouble(hive -> hive.hatchery.getCenter().distanceToSqr(source.getPosition())))
			.orElse(null);
	}

	private static int list(CommandContext<CommandSourceStack> context) {
		var hives = HiveSavedData.get(context.getSource().getServer()).all();
		context.getSource().sendSuccess(() -> Component.literal("Hives: " + hives.size() + "/" + HiveSavedData.MAX_HIVES), false);
		for (Hive hive : hives)
			context.getSource().sendSuccess(() -> Component.literal(" " + hive.dimension + " " + hive.hatchery.toShortString()
				+ "  M" + hive.minerals + " V" + hive.vespene + " D" + hive.diamonds), false);
		return hives.size();
	}

	private static int info(CommandContext<CommandSourceStack> context) {
		Hive hive = nearest(context);
		if (hive == null) {
			context.getSource().sendFailure(Component.literal("No hive in this dimension"));
			return 0;
		}
		StringBuilder members = new StringBuilder();
		for (Map.Entry<UnitKind, Integer> entry : hive.members.entrySet()) members.append(' ').append(entry.getKey().id).append('=').append(entry.getValue());
		StringBuilder pending = new StringBuilder();
		for (Map.Entry<UnitKind, Integer> entry : hive.pending.entrySet()) pending.append(' ').append(entry.getKey().id).append('=').append(entry.getValue());
		String text = "Hive " + hive.hatchery.toShortString() + ": minerals " + hive.minerals + ", vespene " + hive.vespene
			+ ", diamonds " + hive.diamonds + ", supply " + hive.supplyUsed() + "/" + hive.supplyCap()
			+ ", larva in " + (HiveLogic.LARVA_TICKS - hive.larvaTimer) / 20 + " s\n members:" + members + "\n in cocoons:" + pending
			+ (hive.queenTicks >= 0 ? "\n queen in " + hive.queenTicks / 20 + " s" : "");
		context.getSource().sendSuccess(() -> Component.literal(text), false);
		return 1;
	}

	private static int generate(CommandContext<CommandSourceStack> context) {
		var source = context.getSource();
		if (HiveSavedData.get(source.getServer()).full()) {
			source.sendFailure(Component.literal("The world already has " + HiveSavedData.MAX_HIVES + " hives"));
			return 0;
		}
		var at = net.minecraft.core.BlockPos.containing(source.getPosition());
		HiveStructure.Report report = HiveStructure.build(source.getLevel(), at, source.getLevel().random);
		source.sendSuccess(() -> Component.literal("Hive generated at " + at.toShortString() + ": " + report.halls() + " halls ("
			+ report.hallBuildings() + " with buildings), " + report.eggs() + " eggs, " + report.tumors() + " tumors, "
			+ report.tunnelCells() + " blocks dug"), true);
		return 1;
	}

	private static int give(CommandContext<CommandSourceStack> context, int minerals, int vespene) {
		Hive hive = nearest(context);
		if (hive == null) {
			context.getSource().sendFailure(Component.literal("No hive in this dimension"));
			return 0;
		}
		hive.minerals += minerals;
		hive.vespene += vespene;
		hive.mineralsCollected += minerals;
		HiveSavedData.get(context.getSource().getServer()).setDirty();
		context.getSource().sendSuccess(() -> Component.literal("Hive now has " + hive.minerals + " minerals, " + hive.vespene + " vespene"), true);
		return 1;
	}
}
