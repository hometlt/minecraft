package dev.starmine.gear;

import dev.starmine.StarMine;
import dev.starmine.entity.UnitKind;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.InteractionResult;

/**
 * The races' tools and shields (owner 19.10), made of their armour shards; all of iron's level:
 * - zerg ({@link #ZERG_USES} uses): living chitin, it mends itself — a use every {@link #MEND_TICKS}, three times as fast on
 *   creep; the axe bites wood a little faster, the shovel digs creep fast, the hoe makes soil that never dries, the sword
 *   now and then poisons;
 * - terran ({@link #TERRAN_USES}, half again as many): plain steel, tough; its shield takes blasts better
 *   ({@link #TERRAN_BLAST});
 * - protoss ({@link #PROTOSS_USES}): an energy shield takes its first {@link #SHIELD_HITS} wears; it comes back whole
 *   {@link #SHIELD_TICKS} after the last ({@link #absorb}).
 */
public final class RaceTools {
	public static final int ZERG_USES = 250, TERRAN_USES = 375, PROTOSS_USES = 250;
	public static final int MEND_TICKS = 200, CREEP_MEND_TICKS = 60, SHIELD_HITS = 30, SHIELD_TICKS = 600;
	/** Shields: vanilla's 336 uses, terran half again; a terran shield held takes this share of a blast's damage. */
	public static final int SHIELD_USES = 336;
	public static final float TERRAN_BLAST = 0.6f;
	public static final List<String> KINDS = List.of("pickaxe", "axe", "shovel", "hoe", "sword", "shield");
	public static final Map<UnitKind.Race, Map<String, Item>> ITEMS = new EnumMap<>(UnitKind.Race.class);

	/** A race's tool. */
	public interface RaceTool {
		UnitKind.Race race();
	}

	/** Iron's level and speed; the race's uses; mended with its shards. */
	public record RaceTier(UnitKind.Race race) implements Tier {
		@Override
		public int getUses() {
			return race == UnitKind.Race.TERRAN ? TERRAN_USES : race == UnitKind.Race.ZERG ? ZERG_USES : PROTOSS_USES;
		}

		@Override
		public float getSpeed() {
			return 6.0f;
		}

		@Override
		public float getAttackDamageBonus() {
			return 2.0f;
		}

		@Override
		public TagKey<Block> getIncorrectBlocksForDrops() {
			return BlockTags.INCORRECT_FOR_IRON_TOOL;
		}

		@Override
		public int getEnchantmentValue() {
			return 14;
		}

		@Override
		public Ingredient getRepairIngredient() {
			return Ingredient.of(dev.starmine.suit.SuitItems.SHARDS.get(race));
		}
	}

	static {
		for (UnitKind.Race race : UnitKind.Race.values()) {
			RaceTier tier = new RaceTier(race);
			Map<String, Item> items = new java.util.LinkedHashMap<>();
			Item.Properties p = new Item.Properties();
			items.put("pickaxe", new Pickaxe(race, tier, p.attributes(DiggerItem.createAttributes(tier, 1.0f, -2.8f))));
			items.put("axe", new Axe(race, tier, new Item.Properties().attributes(DiggerItem.createAttributes(tier, 6.0f, -3.1f))));
			items.put("shovel", new Shovel(race, tier, new Item.Properties().attributes(DiggerItem.createAttributes(tier, 1.5f, -3.0f))));
			items.put("hoe", new Hoe(race, tier, new Item.Properties().attributes(DiggerItem.createAttributes(tier, -2.0f, -1.0f))));
			items.put("sword", new Sword(race, tier, new Item.Properties().attributes(SwordItem.createAttributes(tier, 3, -2.4f))));
			items.put("shield", new Shield(race, new Item.Properties().durability(race == UnitKind.Race.TERRAN ? SHIELD_USES * 3 / 2 : SHIELD_USES)));
			String name = race.name().toLowerCase(Locale.ROOT);
			items.forEach((kind, item) -> Registry.register(BuiltInRegistries.ITEM, StarMine.id(name + "_" + kind), item));
			ITEMS.put(race, items);
		}
	}

	private RaceTools() {
	}

	public static void load() {
		net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents.modifyEntriesEvent(net.minecraft.world.item.CreativeModeTabs.TOOLS_AND_UTILITIES).register(entries -> {
			for (UnitKind.Race race : UnitKind.Race.values())
				for (String kind : KINDS) if (!kind.equals("sword") && !kind.equals("shield")) entries.accept(ITEMS.get(race).get(kind));
		});
		net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents.modifyEntriesEvent(net.minecraft.world.item.CreativeModeTabs.COMBAT).register(entries -> {
			for (UnitKind.Race race : UnitKind.Race.values()) {
				entries.accept(ITEMS.get(race).get("sword"));
				entries.accept(ITEMS.get(race).get("shield"));
			}
		});
	}

	public static Item item(UnitKind.Race race, String kind) {
		return ITEMS.get(race).get(kind);
	}

	// ---- the races' ways ----

	/** Each tick of a race's tool in an inventory: a zerg tool mends itself; a protoss one's shield comes back. */
	static void inventoryTick(UnitKind.Race race, ItemStack stack, Level level, Entity holder) {
		if (!(level instanceof ServerLevel server) || server.getGameTime() % 20 != 0) return;
		long now = server.getGameTime();
		if (race == UnitKind.Race.ZERG && stack.isDamaged() && now % (onCreep(server, holder) ? CREEP_MEND_TICKS : MEND_TICKS) == 0)
			stack.setDamageValue(stack.getDamageValue() - 1);
		if (race == UnitKind.Race.PROTOSS && shield(stack, now) < SHIELD_HITS && now - lastHit(stack) >= SHIELD_TICKS) setShield(stack, SHIELD_HITS, now);
	}

	static boolean onCreep(ServerLevel level, Entity holder) {
		BlockPos feet = holder.blockPosition();
		return dev.starmine.block.StarMineBlocks.isCreep(level.getBlockState(feet)) || dev.starmine.block.StarMineBlocks.isCreep(level.getBlockState(feet.below()));
	}

	/** The energy shield's charge left (a new tool: whole). */
	public static int shield(ItemStack stack, long now) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data == null || !data.contains("StarMineShield") ? SHIELD_HITS : data.copyTag().getInt("StarMineShield");
	}

	static long lastHit(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data == null ? Long.MIN_VALUE / 2 : data.copyTag().getLong("StarMineShieldAt");
	}

	static void setShield(ItemStack stack, int charge, long at) {
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
			tag.putInt("StarMineShield", charge);
			tag.putLong("StarMineShieldAt", at);
		});
	}

	/**
	 * A protoss tool or shield about to wear: its energy shield takes the wear while it lasts (true: nothing worn).
	 * ({@code ItemStackWearMixin}, at the head of {@code ItemStack.hurtAndBreak}.)
	 */
	public static boolean absorb(ItemStack stack, ServerLevel level) {
		if (!(stack.getItem() instanceof RaceTool tool) || tool.race() != UnitKind.Race.PROTOSS) return false;
		long now = level.getGameTime();
		int charge = shield(stack, now);
		if (charge <= 0) {
			setShield(stack, 0, now);
			return false;
		}
		setShield(stack, charge - 1, now);
		return true;
	}

	/** Damage to a holder of a terran shield: a blast's is cut ({@link #TERRAN_BLAST}). */
	public static float blast(LivingEntity victim, net.minecraft.world.damagesource.DamageSource source, float amount) {
		if (!source.is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION)) return amount;
		Item terran = item(UnitKind.Race.TERRAN, "shield");
		return victim.getMainHandItem().is(terran) || victim.getOffhandItem().is(terran) ? amount * TERRAN_BLAST : amount;
	}

	static void tooltip(UnitKind.Race race, ItemStack stack, List<Component> lines) {
		lines.add(Component.translatable("tooltip.starmine.race_tool." + race.name().toLowerCase(Locale.ROOT)).withStyle(ChatFormatting.GRAY));
		if (race == UnitKind.Race.PROTOSS)
			lines.add(Component.translatable("tooltip.starmine.energy_shield", shield(stack, 0), SHIELD_HITS).withStyle(ChatFormatting.AQUA));
	}

	// ---- the items ----

	static final class Pickaxe extends PickaxeItem implements RaceTool {
		private final UnitKind.Race race;

		Pickaxe(UnitKind.Race race, Tier tier, Properties properties) {
			super(tier, properties);
			this.race = race;
		}

		@Override
		public UnitKind.Race race() {
			return race;
		}

		@Override
		public void inventoryTick(ItemStack stack, Level level, Entity holder, int slot, boolean selected) {
			RaceTools.inventoryTick(race, stack, level, holder);
		}

		@Override
		public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
			tooltip(race, stack, lines);
		}
	}

	static final class Axe extends AxeItem implements RaceTool {
		private final UnitKind.Race race;

		Axe(UnitKind.Race race, Tier tier, Properties properties) {
			super(tier, properties);
			this.race = race;
		}

		@Override
		public UnitKind.Race race() {
			return race;
		}

		@Override
		public float getDestroySpeed(ItemStack stack, BlockState state) {
			float speed = super.getDestroySpeed(stack, state);
			return race == UnitKind.Race.ZERG && state.is(BlockTags.LOGS) ? speed * 1.25f : speed; // chitin bites wood
		}

		@Override
		public void inventoryTick(ItemStack stack, Level level, Entity holder, int slot, boolean selected) {
			RaceTools.inventoryTick(race, stack, level, holder);
		}

		@Override
		public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
			tooltip(race, stack, lines);
		}
	}

	static final class Shovel extends ShovelItem implements RaceTool {
		private final UnitKind.Race race;

		Shovel(UnitKind.Race race, Tier tier, Properties properties) {
			super(tier, properties);
			this.race = race;
		}

		@Override
		public UnitKind.Race race() {
			return race;
		}

		@Override
		public float getDestroySpeed(ItemStack stack, BlockState state) {
			return race == UnitKind.Race.ZERG && dev.starmine.block.StarMineBlocks.isCreep(state) ? 12.0f : super.getDestroySpeed(stack, state);
		}

		@Override
		public void inventoryTick(ItemStack stack, Level level, Entity holder, int slot, boolean selected) {
			RaceTools.inventoryTick(race, stack, level, holder);
		}

		@Override
		public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
			tooltip(race, stack, lines);
		}
	}

	static final class Hoe extends HoeItem implements RaceTool {
		private final UnitKind.Race race;

		Hoe(UnitKind.Race race, Tier tier, Properties properties) {
			super(tier, properties);
			this.race = race;
		}

		@Override
		public UnitKind.Race race() {
			return race;
		}

		/** The zerg hoe makes organic soil: farmland wet through, whatever the water. */
		@Override
		public InteractionResult useOn(UseOnContext context) {
			InteractionResult result = super.useOn(context);
			if (race == UnitKind.Race.ZERG && result.consumesAction() && context.getLevel() instanceof ServerLevel level) {
				BlockPos pos = context.getClickedPos();
				BlockState tilled = level.getBlockState(pos);
				if (tilled.is(Blocks.FARMLAND)) {
					level.setBlock(pos, tilled.setValue(FarmBlock.MOISTURE, FarmBlock.MAX_MOISTURE), Block.UPDATE_ALL);
					level.sendParticles(ParticleTypes.ITEM_SLIME, pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5, 8, 0.3, 0.1, 0.3, 0.02);
				}
			}
			return result;
		}

		@Override
		public void inventoryTick(ItemStack stack, Level level, Entity holder, int slot, boolean selected) {
			RaceTools.inventoryTick(race, stack, level, holder);
		}

		@Override
		public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
			tooltip(race, stack, lines);
		}
	}

	static final class Sword extends SwordItem implements RaceTool {
		private final UnitKind.Race race;

		Sword(UnitKind.Race race, Tier tier, Properties properties) {
			super(tier, properties);
			this.race = race;
		}

		@Override
		public UnitKind.Race race() {
			return race;
		}

		/** The zerg sword: one blow in five poisons for three seconds. */
		@Override
		public boolean hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
			if (race == UnitKind.Race.ZERG && attacker.getRandom().nextInt(5) == 0) target.addEffect(new MobEffectInstance(MobEffects.POISON, 60, 0), attacker);
			return super.hurtEnemy(stack, target, attacker);
		}

		@Override
		public void inventoryTick(ItemStack stack, Level level, Entity holder, int slot, boolean selected) {
			RaceTools.inventoryTick(race, stack, level, holder);
		}

		@Override
		public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
			tooltip(race, stack, lines);
		}
	}

	/** A race's shield: drawn as vanilla's in the race's colours (client {@code RaceShieldRenderer}). */
	public static final class Shield extends ShieldItem implements RaceTool {
		private final UnitKind.Race race;

		Shield(UnitKind.Race race, Properties properties) {
			super(properties);
			this.race = race;
		}

		@Override
		public UnitKind.Race race() {
			return race;
		}

		@Override
		public boolean isValidRepairItem(ItemStack stack, ItemStack repair) {
			return repair.is(dev.starmine.suit.SuitItems.SHARDS.get(race));
		}

		@Override
		public void inventoryTick(ItemStack stack, Level level, Entity holder, int slot, boolean selected) {
			RaceTools.inventoryTick(race, stack, level, holder);
		}

		@Override
		public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
			tooltip(race, stack, lines);
		}
	}
}
