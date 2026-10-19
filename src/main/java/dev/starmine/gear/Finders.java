package dev.starmine.gear;

import dev.starmine.StarMine;
import java.util.List;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

/**
 * Finders (owner 19.10), turning like a compass to the nearest of what they look for within {@link #REACH} blocks:
 * - the mineral compass (a compass among four mineral shards): mineral fields;
 * - the vespene detector (a compass, vespene, redstone): vespene veins.
 * They look again every {@link #EVERY} ticks; with nothing near they spin.
 */
public final class Finders {
	public static final int REACH = 128, EVERY = 40;
	public static final Item MINERAL_COMPASS = Registry.register(BuiltInRegistries.ITEM, StarMine.id("mineral_compass"),
		new Finder(false, new Item.Properties().stacksTo(1)));
	public static final Item VESPENE_DETECTOR = Registry.register(BuiltInRegistries.ITEM, StarMine.id("vespene_detector"),
		new Finder(true, new Item.Properties().stacksTo(1)));

	private Finders() {
	}

	public static void load() {
		net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents.modifyEntriesEvent(net.minecraft.world.item.CreativeModeTabs.TOOLS_AND_UTILITIES).register(entries -> {
			entries.accept(MINERAL_COMPASS);
			entries.accept(VESPENE_DETECTOR);
		});
	}

	/** The nearest mineral field (its crystal's core) or vespene vein (its root) within reach, or null. */
	public static @Nullable BlockPos nearest(ServerLevel level, BlockPos from, boolean vespene) {
		if (!vespene) {
			List<BlockPos> cores = dev.starmine.mineral.Minerals.coresNear(level, from, REACH);
			return cores.isEmpty() ? null : cores.get(0);
		}
		BlockPos best = null;
		double bestDistance = (double) REACH * REACH;
		for (int cx = (from.getX() - REACH) >> 4; cx <= (from.getX() + REACH) >> 4; cx++)
			for (int cz = (from.getZ() - REACH) >> 4; cz <= (from.getZ() + REACH) >> 4; cz++) {
				if (!level.hasChunk(cx, cz)) continue;
				for (BlockEntity entity : level.getChunk(cx, cz).getBlockEntities().values()) {
					if (!(entity instanceof dev.starmine.vespene.VespeneRootBlockEntity root) || root.outletCount() == 0) continue;
					BlockPos outlet = root.outlet(0);
					double dx = outlet.getX() - from.getX(), dz = outlet.getZ() - from.getZ(), d = dx * dx + dz * dz;
					if (d < bestDistance) {
						best = outlet;
						bestDistance = d;
					}
				}
			}
		return best;
	}

	/** A finder: its needle's mark kept as a lodestone's (the client's compass needle follows it). */
	static final class Finder extends Item {
		private final boolean vespene;

		Finder(boolean vespene, Properties properties) {
			super(properties);
			this.vespene = vespene;
		}

		@Override
		public void inventoryTick(ItemStack stack, Level level, Entity holder, int slot, boolean selected) {
			if (!(level instanceof ServerLevel server) || server.getGameTime() % EVERY != slot % EVERY) return;
			BlockPos found = nearest(server, holder.blockPosition(), vespene);
			LodestoneTracker mark = found == null ? null : new LodestoneTracker(Optional.of(GlobalPos.of(level.dimension(), found)), false);
			if (!java.util.Objects.equals(mark, stack.get(DataComponents.LODESTONE_TRACKER))) stack.set(DataComponents.LODESTONE_TRACKER, mark);
		}

		@Override
		public boolean isFoil(ItemStack stack) {
			return false;
		}

		@Override
		public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
			lines.add(Component.translatable(vespene ? "tooltip.starmine.vespene_detector" : "tooltip.starmine.mineral_compass").withStyle(ChatFormatting.GRAY));
		}
	}
}
