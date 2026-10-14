package dev.starmine;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * A StarMine spawn egg (owner 14.10): the unit it spawns belongs to the player who used it
 * ({@link dev.starmine.entity.StarUnit#finalizeSpawn} reads {@link #spawner()}); eggs from dispensers
 * and spawners give wild units.
 */
public final class StarSpawnEggItem extends SpawnEggItem {
	private static final ThreadLocal<Player> SPAWNER = new ThreadLocal<>();

	public StarSpawnEggItem(EntityType<? extends Mob> type, int base, int spots, Properties properties) {
		super(type, base, spots, properties);
	}

	/** The player whose egg is spawning a unit on this thread right now, or null. */
	public static @Nullable Player spawner() {
		return SPAWNER.get();
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		SPAWNER.set(context.getPlayer());
		try {
			return super.useOn(context);
		} finally {
			SPAWNER.remove();
		}
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		SPAWNER.set(player);
		try {
			return super.use(level, player, hand);
		} finally {
			SPAWNER.remove();
		}
	}
}
