package dev.starmine.gear;

import dev.starmine.StarMine;
import dev.starmine.block.BlockWear;
import dev.starmine.entity.UnitKind;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The races' repair tools (owner 19.10), made as shears are of the race's armour shards; used on a block, they mend the
 * damage it keeps ({@link BlockWear}):
 * - the terran's mends what is mechanical — terran armour and blocks of metal;
 * - the zerg's mends what lives — zerg armour, flesh and creep, wood, wool, plants, bone;
 * - the protoss' brings a protoss block's shield back whole.
 * A use mends {@link #MEND} of a block's damage and costs the tool a use.
 */
public final class RepairTools {
	public static final float MEND = 0.25f;
	public static final int USES = 238;
	public static final Map<UnitKind.Race, Item> ITEMS = new EnumMap<>(UnitKind.Race.class);
	private static final Set<SoundType> METAL = Set.of(SoundType.METAL, SoundType.NETHERITE_BLOCK, SoundType.COPPER, SoundType.ANVIL,
		SoundType.CHAIN, SoundType.LANTERN, SoundType.COPPER_BULB, SoundType.COPPER_GRATE, SoundType.HEAVY_CORE);
	private static final Set<SoundType> LIVING = Set.of(SoundType.WOOD, SoundType.NETHER_WOOD, SoundType.CHERRY_WOOD, SoundType.BAMBOO_WOOD,
		SoundType.STEM, SoundType.WOOL, SoundType.GRASS, SoundType.MOSS, SoundType.BONE_BLOCK, SoundType.SLIME_BLOCK, SoundType.HONEY_BLOCK,
		SoundType.CORAL_BLOCK, SoundType.WART_BLOCK, SoundType.FUNGUS, SoundType.ROOTS, SoundType.SHROOMLIGHT, SoundType.SCULK);

	static {
		for (UnitKind.Race race : UnitKind.Race.values())
			ITEMS.put(race, Registry.register(BuiltInRegistries.ITEM, StarMine.id(race.name().toLowerCase(Locale.ROOT) + "_repair_tool"),
				new Tool(race, new Item.Properties().durability(USES))));
	}

	private RepairTools() {
	}

	public static void load() {
		net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents.modifyEntriesEvent(net.minecraft.world.item.CreativeModeTabs.TOOLS_AND_UTILITIES)
			.register(entries -> ITEMS.values().forEach(entries::accept));
	}

	/** Whether a race's tool mends this block. */
	public static boolean mends(UnitKind.Race race, BlockState state) {
		UnitKind.Race of = BlockWear.race(state);
		return switch (race) {
			case TERRAN -> of == UnitKind.Race.TERRAN || of == null && METAL.contains(state.getSoundType());
			case ZERG -> of == UnitKind.Race.ZERG || of == null && (LIVING.contains(state.getSoundType()) || dev.starmine.block.StarMineBlocks.isCreep(state)
				|| state.is(dev.starmine.block.StarMineBlocks.ZERG_FLESH) || state.is(dev.starmine.block.StarMineBlocks.GLOWING_ZERG_FLESH));
			case PROTOSS -> of == UnitKind.Race.PROTOSS;
		};
	}

	/** A race's tool used on a block: true when it did its work. */
	public static boolean use(UnitKind.Race race, ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (!mends(race, state)) return false;
		boolean done = race == UnitKind.Race.PROTOSS ? BlockWear.recharge(level, pos) | BlockWear.mend(level, pos, MEND) : BlockWear.mend(level, pos, MEND);
		if (!done) return false;
		double x = pos.getX() + 0.5, y = pos.getY() + 0.5, z = pos.getZ() + 0.5;
		switch (race) {
			case TERRAN -> {
				level.sendParticles(ParticleTypes.ELECTRIC_SPARK, x, y, z, 14, 0.4, 0.4, 0.4, 0.1);
				level.playSound(null, pos, SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 0.4f, 1.6f);
			}
			case ZERG -> {
				level.sendParticles(ParticleTypes.ITEM_SLIME, x, y, z, 14, 0.4, 0.4, 0.4, 0.05);
				level.playSound(null, pos, SoundEvents.SLIME_SQUISH, SoundSource.BLOCKS, 0.8f, 0.8f);
			}
			case PROTOSS -> {
				level.sendParticles(ParticleTypes.END_ROD, x, y, z, 14, 0.4, 0.4, 0.4, 0.03);
				level.playSound(null, pos, SoundEvents.BEACON_POWER_SELECT, SoundSource.BLOCKS, 0.6f, 1.8f);
			}
		}
		return true;
	}

	static final class Tool extends Item {
		private final UnitKind.Race race;

		Tool(UnitKind.Race race, Properties properties) {
			super(properties);
			this.race = race;
		}

		@Override
		public InteractionResult useOn(UseOnContext context) {
			if (!(context.getLevel() instanceof ServerLevel level)) return InteractionResult.SUCCESS;
			if (!RepairTools.use(race, level, context.getClickedPos())) return InteractionResult.PASS;
			if (context.getPlayer() != null)
				context.getItemInHand().hurtAndBreak(1, context.getPlayer(), context.getHand() == net.minecraft.world.InteractionHand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND);
			return InteractionResult.CONSUME;
		}

		@Override
		public boolean isValidRepairItem(ItemStack stack, ItemStack repair) {
			return repair.is(dev.starmine.suit.SuitItems.SHARDS.get(race));
		}

		@Override
		public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
			lines.add(Component.translatable("tooltip.starmine.repair_tool." + race.name().toLowerCase(Locale.ROOT)).withStyle(ChatFormatting.GRAY));
		}
	}
}
