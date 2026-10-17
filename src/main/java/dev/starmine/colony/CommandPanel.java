package dev.starmine.colony;

import dev.starmine.StarMine;
import java.util.List;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * The command panel (owner 14.10): the device a player commands their base and army with. Crafted from
 * a map with 4 repeaters and 4 comparators; right click (or the command key while it is in the
 * inventory) opens the command view on the client. Without it there is no command view.
 */
public final class CommandPanel extends Item {
	public static final CommandPanel ITEM = Registry.register(BuiltInRegistries.ITEM, StarMine.id("command_panel"),
		new CommandPanel(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON)));

	/** Set by the client: opens the command view (the server side does nothing). */
	public static Runnable openView = () -> { };

	private CommandPanel(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		if (level.isClientSide) openView.run();
		return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
	}

	/**
	 * Owner 17.10: the command panel used on one of the player's own units puts them in control of it, as the
	 * command view's take-control button does ({@link dev.starmine.possess.Possession}).
	 */
	@Override
	public net.minecraft.world.InteractionResult interactLivingEntity(ItemStack stack, Player player, net.minecraft.world.entity.LivingEntity target,
			InteractionHand hand) {
		if (!(target instanceof dev.starmine.entity.StarUnit unit) || !player.getUUID().equals(unit.owner().orElse(null))) return net.minecraft.world.InteractionResult.PASS;
		if (player instanceof net.minecraft.server.level.ServerPlayer server) {
			if (dev.starmine.world.HeroicTransport.is(unit)) dev.starmine.world.HeroicTransport.board(server, unit); // owner 17.10: aboard, to the ship
			else dev.starmine.possess.Possession.start(server, unit);
		}
		return net.minecraft.world.InteractionResult.sidedSuccess(player.level().isClientSide);
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
		lines.add(Component.translatable("item.starmine.command_panel.tooltip"));
	}

	/** Whether the player carries a command panel. */
	public static boolean carried(Player player) {
		Inventory inventory = player.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) if (inventory.getItem(i).is(ITEM)) return true;
		return false;
	}

	public static void load() {
		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(entries -> entries.accept(ITEM));
	}
}
