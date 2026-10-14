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
 * Terran bases (owner 14.10): a command center placed by a player founds that player's base (50
 * minerals and 4 SCVs, SC2-like), or joins the base the player already has in this dimension as an
 * expansion. SCVs gather on their own and on orders, and build what the player orders where the
 * player placed it ({@link #place}). The player commands the base from the command view (client) via
 * {@link ColonyNetwork}, and can hand resources in at any of its command centers ({@link #handIn}).
 * Each second the player gets the base's status (stores, supply, tallies, tech) for the resource
 * panel. A base with no command center and no SCV left is gone.
 */
public final class TerranLogic {
	/** How far from its player a unit takes orders (the command view sees as far as the chunks around the player). */
	public static final double COMMAND_RANGE = 192;

	private TerranLogic() {
	}

	/** Ticked by every command center: founds or joins the base. */
	public static void tick(StarUnit cc, ServerLevel level) {
		if (cc.hiveId() == null) found(cc, level);
	}

	private static void found(StarUnit cc, ServerLevel level) {
		HiveSavedData data = HiveSavedData.get(level.getServer());
		String dimension = level.dimension().location().toString();
		Player player = level.getNearestPlayer(cc.getX(), cc.getY(), cc.getZ(), 32, entity -> entity instanceof Player p && !p.isSpectator());
		Hive base = player == null ? null : data.baseOf(player.getUUID(), dimension);
		boolean fresh = base == null;
		if (fresh) {
			base = new Hive(cc.getUUID(), dimension, cc.blockPosition());
			base.race = UnitKind.Race.TERRAN;
			base.owner = player == null ? null : player.getUUID();
			base.minerals = 50;
			data.add(base);
		}
		cc.joinHive(base);
		base.join(UnitKind.COMMAND_CENTER);
		if (fresh) {
			for (int i = 0; i < 4; i++) {
				double angle = i * Math.PI / 2 + 0.4, radius = UnitKind.COMMAND_CENTER.width / 2 + 1.5;
				BlockPos ground = BlockPos.containing(cc.getX() + Math.cos(angle) * radius, cc.getY() + 2, cc.getZ() + Math.sin(angle) * radius);
				for (int step = 0; step < 6 && level.getBlockState(ground.below()).isAir(); step++) ground = ground.below();
				HiveLogic.spawn(level, base, UnitKind.SCV, Vec3.atBottomCenterOf(ground), null);
			}
			if (player != null) player.sendSystemMessage(Component.translatable("message.starmine.base_founded"));
		} else if (player != null) {
			player.sendSystemMessage(Component.translatable("message.starmine.expansion"));
		}
		data.setDirty();
	}

	/**
	 * An SCV at its site places the building: checks tech and money, levels the site (refused when
	 * trees, fences or harder blocks are in the way, or another building stands there), pays, and the
	 * building starts growing at 10 % health while the SCV builds it.
	 */
	public static StarUnit place(StarUnit scv, ServerLevel level, UnitKind kind, BlockPos site) {
		Hive base = HiveSavedData.get(level.getServer()).get(scv.hiveId());
		if (base == null || !UnitKind.TERRAN_BUILDINGS.contains(kind)) return null;
		String refusal = refusal(base, kind);
		if (kind.gas()) { // a refinery stands on a vein outlet, wherever it is (owner 14.10: underground too)
			BlockPos outlet = dev.starmine.vespene.Vespene.outletUnder(level, site);
			if (outlet == null && refusal == null) refusal = "message.starmine.need_outlet";
			if (outlet != null) site = outlet.above();
		}
		if (refusal == null && !siteFree(level, kind, site)) refusal = "message.starmine.site_blocked";
		if (refusal == null && !kind.gas() && !HiveTerrain.prepare(level, site, half(kind), (int) Math.ceil(kind.height), true)) refusal = "message.starmine.site_blocked";
		if (refusal != null) {
			tell(level, base, refusal);
			return null;
		}
		base.pay(kind, 1);
		base.expect(kind, 1);
		StarUnit building = HiveLogic.spawn(level, base, kind, Vec3.atBottomCenterOf(site), null);
		if (building == null) return null;
		building.startConstruction(kind.economy().trainTicks());
		scv.setConstructing(building.getUUID());
		HiveSavedData.get(level.getServer()).setDirty();
		return building;
	}

	/** Footprint half-size of a building in blocks. */
	public static int half(UnitKind kind) {
		return (int) Math.ceil(kind.width / 2);
	}

	/** No other building (or hatchery) overlaps the footprint. */
	public static boolean siteFree(Level level, UnitKind kind, BlockPos site) {
		AABB box = new AABB(site).inflate(half(kind), 0, half(kind)).expandTowards(0, kind.height, 0);
		return level.getEntitiesOfClass(StarUnit.class, box, mob -> mob.kind.structure() && mob.isAlive()).isEmpty();
	}

	/** Why the base cannot have this now (a lang key), or null: tech, minerals, vespene, supply. */
	public static String refusal(Hive base, UnitKind kind) {
		if (!base.unlocked(kind)) return "message.starmine.need_tech";
		UnitKind.Economy cost = kind.economy();
		if (base.minerals < cost.minerals()) return "message.starmine.need_minerals";
		if (base.vespene < cost.vespene()) return "message.starmine.need_vespene";
		if (base.supplyUsed() + cost.food() > base.supplyCap()) return "message.starmine.need_supply";
		return null;
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
		if (!(source.getEntity() instanceof StarUnit terran) || terran.kind.race != UnitKind.Race.TERRAN || !(victim.level() instanceof ServerLevel level)) return true;
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
		if (!(entity instanceof StarUnit cc) || cc.kind != UnitKind.COMMAND_CENTER || cc.underConstruction()) return InteractionResult.PASS;
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
				if (hive.race == UnitKind.Race.TERRAN && hive.count(UnitKind.COMMAND_CENTER) == 0 && hive.count(UnitKind.SCV) == 0
					&& hive.pending(UnitKind.COMMAND_CENTER) == 0) dead.add(hive);
			for (Hive hive : dead) data.remove(hive.id);
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Hive base = data.baseOf(player.getUUID(), player.serverLevel().dimension().location().toString());
			ColonyNetwork.sendStatus(player, base);
		}
	}

	/**
	 * A player's command (from the command view) to units and buildings of their base within
	 * {@link #COMMAND_RANGE}; see {@link ColonyNetwork.UnitCommand} for the actions.
	 */
	public static void command(ServerPlayer player, ColonyNetwork.UnitCommand command) {
		ServerLevel level = player.serverLevel();
		Hive base = HiveSavedData.get(level.getServer()).baseOf(player.getUUID(), level.dimension().location().toString());
		if (base == null) return;
		List<StarUnit> units = new ArrayList<>();
		for (int id : command.ids())
			if (level.getEntity(id) instanceof StarUnit unit && base.id.equals(unit.hiveId()) && unit.isAlive()
				&& unit.distanceTo(player) < COMMAND_RANGE) units.add(unit);
		Vec3 at = new Vec3(command.x(), command.y(), command.z());
		List<StarUnit> mobile = units.stream().filter(unit -> !unit.kind.structure()).toList();
		List<StarUnit> buildings = units.stream().filter(unit -> unit.kind.structure()).toList();
		List<StarUnit> workers = mobile.stream().filter(unit -> unit.kind.worker()).toList();
		switch (command.action()) {
			case "move" -> formation(mobile, StarUnit.Order.MOVE, at);
			case "attack_move" -> formation(mobile, StarUnit.Order.ATTACK_MOVE, at);
			case "patrol" -> formation(mobile, StarUnit.Order.PATROL, at);
			case "hold" -> mobile.forEach(unit -> unit.command(StarUnit.Order.HOLD, null));
			case "stop" -> mobile.forEach(unit -> unit.command(StarUnit.Order.NONE, null));
			case "attack" -> {
				if (level.getEntity(command.target()) instanceof LivingEntity target && target.isAlive())
					mobile.stream().filter(unit -> unit.kind.attack != UnitKind.Attack.NONE).forEach(unit -> unit.commandAttack(target));
				else formation(mobile, StarUnit.Order.ATTACK_MOVE, at);
			}
			case "gather" -> workers.forEach(unit -> unit.commandWork(StarUnit.Work.GATHER, BlockPos.containing(at)));
			case "return" -> workers.forEach(unit -> unit.commandWork(StarUnit.Work.RETURN, null));
			case "build" -> {
				UnitKind kind = kind(command.arg());
				if (kind == null || !UnitKind.TERRAN_BUILDINGS.contains(kind) || workers.isEmpty()) return;
				String refusal = refusal(base, kind);
				if (refusal != null && !refusal.equals("message.starmine.need_supply")) {
					tell(level, base, refusal);
					return;
				}
				BlockPos site = BlockPos.containing(at);
				StarUnit scv = workers.stream().min(Comparator.comparingDouble(unit -> unit.distanceToSqr(at))).orElseThrow();
				scv.command(StarUnit.Order.NONE, null);
				scv.orderBuild(kind, site);
			}
			case "train" -> {
				UnitKind kind = kind(command.arg());
				if (kind == null) return;
				String refusal = refusal(base, kind);
				boolean done = buildings.stream().filter(b -> b.kind.trains().contains(kind) && !b.underConstruction())
					.sorted(Comparator.comparingInt(b -> b.queue().size()))
					.anyMatch(b -> b.train(kind));
				if (!done) tell(level, base, refusal != null ? refusal : "message.starmine.queue_full");
			}
			case "cancel" -> buildings.forEach(StarUnit::cancelLast);
			case "rally" -> buildings.forEach(building -> building.setRally(at));
			case "research" -> {
				boolean started = buildings.stream().anyMatch(building -> building.startResearch(command.arg()));
				if (!started) tell(level, base, "message.starmine.cannot_research");
			}
			default -> {
			}
		}
		HiveSavedData.get(level.getServer()).setDirty();
	}

	private static UnitKind kind(String name) {
		try {
			return UnitKind.valueOf(name);
		} catch (IllegalArgumentException | NullPointerException e) {
			return null;
		}
	}

	/** Units sent to one point stand in a square around it instead of crowding onto it. */
	private static void formation(List<StarUnit> units, StarUnit.Order order, Vec3 at) {
		int columns = (int) Math.ceil(Math.sqrt(units.size()));
		double spacing = 0.6 + units.stream().mapToDouble(unit -> unit.kind.width).max().orElse(1);
		for (int i = 0; i < units.size(); i++) {
			double dx = (i % columns - (columns - 1) / 2.0) * spacing, dz = (i / columns - (columns - 1) / 2.0) * spacing;
			units.get(i).command(order, units.size() == 1 ? at : at.add(dx, 0, dz));
		}
	}
}
