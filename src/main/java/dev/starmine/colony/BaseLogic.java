package dev.starmine.colony;

import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveLogic;
import dev.starmine.hive.HiveResources;
import dev.starmine.hive.HiveSavedData;
import dev.starmine.hive.HiveTerrain;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A player's base, whatever its races (owner 14.10): messages to its player, resources handed in at
 * any of its main buildings ({@link #handIn}), its status for the resource panel each second, the end
 * of a base with no main building and no worker left, and villages' grudge against a base whose units
 * hurt a villager or a golem ({@link #villageHostile}). Founding: {@link Founding}; commands:
 * {@link Commands}; buildings put down: {@link dev.starmine.race.Placement}.
 */
public final class BaseLogic {

	private BaseLogic() {
	}

	/** How long a village keeps a base for dangerous after its terrans hurt a villager or a golem (10 min). */
	public static final int VILLAGE_GRUDGE = 12_000;

	/** Whether villages count this terran's base as dangerous (golems attack it, villagers flee). */
	public static boolean villageHostile(StarUnit terran) {
		if (!(terran.level() instanceof ServerLevel level) || terran.hiveId() == null) return false;
		Hive base = HiveSavedData.get(level.getServer()).get(terran.hiveId());
		return base != null && level.getGameTime() < base.villageHostileUntil;
	}

	/**
	 * Villagers and iron golems are neutral to terrans (owner 14.10); a terran that hurts one makes its base
	 * dangerous to villages for {@link #VILLAGE_GRUDGE}, and golems nearby go for it at once.
	 */
	public static boolean onDamage(LivingEntity victim, net.minecraft.world.damagesource.DamageSource source) {
		if (!(victim instanceof net.minecraft.world.entity.npc.AbstractVillager || victim instanceof net.minecraft.world.entity.animal.IronGolem)) return true;
		if (!(source.getEntity() instanceof StarUnit terran) || terran.owner().isEmpty() || !(victim.level() instanceof ServerLevel level)) return true;
		Hive base = terran.hiveId() == null ? null : HiveSavedData.get(level.getServer()).get(terran.hiveId());
		if (base == null) return true;
		boolean fresh = level.getGameTime() >= base.villageHostileUntil;
		base.villageHostileUntil = level.getGameTime() + VILLAGE_GRUDGE;
		HiveSavedData.get(level.getServer()).setDirty();
		if (fresh) tell(level, base, "message.starmine.village_hostile");
		for (net.minecraft.world.entity.animal.IronGolem golem : level.getEntitiesOfClass(net.minecraft.world.entity.animal.IronGolem.class,
				victim.getBoundingBox().inflate(32), golem -> golem.getTarget() == null))
			golem.setTarget(terran);
		return true;
	}

	/** A short message on the base player's action bar. */
	public static void tell(ServerLevel level, Hive base, String key, Object... args) {
		if (base.owner == null) return;
		ServerPlayer player = level.getServer().getPlayerList().getPlayer(base.owner);
		if (player != null) player.displayClientMessage(Component.translatable(key, args), true);
	}

	/**
	 * A player hands in what is in their hand at a command center of their base: raw ores, ingots,
	 * gems, logs (resources.json `items` and block items of resource blocks), the whole stack.
	 */
	public static InteractionResult handIn(Player player, Level level, InteractionHand hand, Entity entity) {
		if (!(entity instanceof StarUnit cc) || !cc.kind.main() || cc.underConstruction()) return InteractionResult.PASS;
		ItemStack stack = player.getItemInHand(hand);
		boolean canister = stack.is(dev.starmine.vespene.Vespene.CANISTER_ITEM);
		if (canister) return handInCanister(player, level, stack, cc);
		HiveResources.Yield yield = HiveResources.yieldOfItem(stack);
		if (!yield.any()) return InteractionResult.PASS;
		if (level.isClientSide) return InteractionResult.SUCCESS;
		Hive base = HiveSavedData.get(level.getServer()).get(cc.hiveId());
		if (base == null || base.owner != null && !base.owner.equals(player.getUUID())) return InteractionResult.PASS;
		int count = stack.getCount();
		base.deliver(yield.minerals() * count, yield.vespene() * count, yield.diamonds() * count, yield.label(), count);
		HiveSavedData.get(level.getServer()).setDirty();
		player.displayClientMessage(Component.translatable("message.starmine.handed_in", count, stack.getHoverName(),
			yield.minerals() * count, yield.vespene() * count), true);
		if (!player.getAbilities().instabuild) {
			boolean bucket = stack.is(dev.starmine.vespene.Vespene.BUCKET);
			stack.shrink(count);
			if (bucket) player.setItemInHand(hand, new ItemStack(net.minecraft.world.item.Items.BUCKET)); // the bucket is kept
		}
		cc.playSound(net.minecraft.sounds.SoundEvents.ITEM_PICKUP, 1.0f, 0.8f);
		return InteractionResult.SUCCESS;
	}

	/** A vespene canister handed in: its vespene (4 per bucket) goes to the base, the canister stays, empty. */
	private static InteractionResult handInCanister(Player player, Level level, ItemStack stack, StarUnit cc) {
		int buckets = dev.starmine.vespene.VespeneCanisterBlock.fill(stack);
		if (buckets == 0) return InteractionResult.PASS;
		if (level.isClientSide) return InteractionResult.SUCCESS;
		Hive base = HiveSavedData.get(level.getServer()).get(cc.hiveId());
		if (base == null || base.owner != null && !base.owner.equals(player.getUUID())) return InteractionResult.PASS;
		int vespene = buckets * dev.starmine.vespene.Vespene.PER_TRIP;
		base.deliver(0, vespene, 0, "vespene", buckets);
		HiveSavedData.get(level.getServer()).setDirty();
		dev.starmine.vespene.VespeneCanisterBlock.setFill(stack, 0);
		player.displayClientMessage(Component.translatable("message.starmine.handed_in", buckets, stack.getHoverName(), 0, vespene), true);
		cc.playSound(net.minecraft.sounds.SoundEvents.BUCKET_EMPTY, 1.0f, 0.8f);
		return InteractionResult.SUCCESS;
	}

	/** Each second: every player's base status; bases with no command center and no SCV are removed. */
	public static void serverTick(MinecraftServer server) {
		if (server.getTickCount() % 20 != 0) return;
		HiveSavedData data = HiveSavedData.get(server);
		if (server.getTickCount() % 200 == 0) {
			List<Hive> dead = new ArrayList<>();
			for (Hive hive : data.all())
				if (hive.race != UnitKind.Race.ZERG || hive.owner != null) // wild hives end with their hatchery
					if (java.util.Arrays.stream(UnitKind.values()).noneMatch(kind -> (kind.main() || kind.worker()) && (hive.count(kind) > 0 || hive.pending(kind) > 0))
						&& data.pending.stream().noneMatch(p -> p.hive().equals(hive.id))) // a generated base still waiting for its units
						dead.add(hive);
			for (Hive hive : dead) data.remove(hive.id);
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Hive base = data.baseOf(player.getUUID(), player.serverLevel().dimension().location().toString());
			ColonyNetwork.sendStatus(player, base);
			ColonyNetwork.sendMarks(player, base);
		}
	}
}
