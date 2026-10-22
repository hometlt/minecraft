package dev.starmine.colony;

import dev.starmine.block.ArmorBlocks;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

/**
 * The base's exchange (owner 22.10): a base's minerals for its race's armour — the armour shards (suits, the race's
 * crate) and the armour blocks (walls, door, slab, stairs, rampart, porthole, bars). Opened from a base's storage
 * building: its button in the command view, or a right click with an empty hand while sneaking. It looks like a chest
 * of the goods, each with its price; a click buys one (a shift click eight), paid from the base's minerals.
 */
public final class Exchange {
	private Exchange() {
	}

	/** A good of the exchange: what it gives and its price in minerals. */
	record Good(Item item, int count, int price) {
	}

	/** The goods of a race, in the chest's order. */
	static List<Good> goods(UnitKind.Race race) {
		List<Good> goods = new ArrayList<>();
		goods.add(new Good(dev.starmine.suit.SuitItems.SHARDS.get(race), 1, 25));
		goods.add(new Good(dev.starmine.suit.SuitItems.SHARDS.get(race), 8, 180));
		for (var wall : ArmorBlocks.WALLS.get(race)) goods.add(new Good(wall.asItem(), 4, 60));
		goods.add(new Good(ArmorBlocks.DOORS.get(race).asItem(), 1, 75));
		var building = ArmorBlocks.BUILDING_BLOCKS.get(race);
		int[] prices = {25, 40, 40, 30, 30, 40, 30};
		int i = 0;
		for (String name : ArmorBlocks.BUILDING) {
			var block = building.get(name);
			if (block != null) goods.add(new Good(block.asItem(), 4, prices[Math.min(i, prices.length - 1)]));
			i++;
		}
		return goods;
	}

	/** Opens the exchange of `storage`'s base for the player; false when the building belongs to no base of theirs. */
	public static boolean open(ServerLevel level, StarUnit storage, Player player) {
		if (!Storage.ownedBy(level, storage, player)) return false;
		Storage.Bank bank = Storage.bankOf(level, storage);
		List<Good> goods = goods(storage.kind.race);
		SimpleContainer shown = new SimpleContainer(27);
		for (int i = 0; i < goods.size() && i < 27; i++) {
			Good good = goods.get(i);
			ItemStack stack = new ItemStack(good.item(), good.count());
			stack.set(DataComponents.LORE, new ItemLore(List.of(Component.translatable("container.starmine.exchange.price", good.price()).withColor(0xFF80D0FF),
				Component.translatable("container.starmine.exchange.tip").withColor(0xFFA0A0A0))));
			shown.setItem(i, stack);
		}
		Component title = Component.translatable("container.starmine.exchange", bank.minerals());
		player.openMenu(new SimpleMenuProvider((id, inventory, who) -> new ChestMenu(MenuType.GENERIC_9x3, id, inventory, shown, 3) {
			@Override
			public void clicked(int slot, int button, ClickType type, Player clicker) {
				if (slot < 0 || slot >= 27) { // the player's own inventory works as ever
					super.clicked(slot, button, type, clicker);
					return;
				}
				if (slot >= goods.size() || type == ClickType.CLONE || type == ClickType.THROW || type == ClickType.PICKUP_ALL) return;
				Good good = goods.get(slot);
				int times = type == ClickType.QUICK_MOVE ? 8 : 1;
				int bought = 0;
				for (int k = 0; k < times && bank.minerals() >= good.price(); k++) {
					bank.add(-good.price(), 0);
					ItemStack item = new ItemStack(good.item(), good.count());
					if (!clicker.getInventory().add(item)) clicker.drop(item, false);
					bought++;
				}
				if (bought > 0) level.playSound(null, storage.blockPosition(), SoundEvents.VILLAGER_YES, SoundSource.PLAYERS, 0.6f, 1.2f);
				else clicker.displayClientMessage(Component.translatable("container.starmine.exchange.short", good.price(), bank.minerals()), true);
				broadcastChanges();
			}

			@Override
			public ItemStack quickMoveStack(Player clicker, int slot) {
				return ItemStack.EMPTY; // nothing goes into the goods, nothing comes out but by a purchase
			}

			@Override
			public void removed(Player clicker) {
				super.removed(clicker);
				shown.clearContent(); // the goods are shown, never given out of it
			}
		}, title));
		return true;
	}
}
