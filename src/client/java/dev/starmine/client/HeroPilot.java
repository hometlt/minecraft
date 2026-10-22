package dev.starmine.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The player as a hero of the command view (owner 17.10: «управлять игроком как героем SC2»): selected with the
 * hero button (F4) or a click on their body, they take the card's orders like a unit — move, stop, hold, patrol,
 * attack a creature or attack-move — and carry them out themselves: the client walks the body there (jumping up
 * steps), turns it to its foe and strikes with what is in the right hand, or fires it when it is a gun.
 */
public final class HeroPilot {
	enum Order { NONE, MOVE, ATTACK, ATTACK_MOVE, PATROL, HOLD, USE, MINE }

	private static Order order = Order.NONE;
	private static Vec3 goal, patrolFrom;
	private static int target = -1;
	private static int stuck;
	private static Vec3 lastPos;
	private static Input saved;
	private static net.minecraft.world.phys.BlockHitResult blockTarget;
	private static boolean usingItem;
	/** What the hero's legs do this tick (read by {@link Legs}). */
	private static boolean walking, jumping;
	/** A foe within this many blocks is attacked on an attack-move, patrol or hold. */
	private static final double ALERT = 12;

	private HeroPilot() {
	}

	static void move(Vec3 to) {
		start(Order.MOVE, to, -1);
	}

	static void attackMove(Vec3 to) {
		start(Order.ATTACK_MOVE, to, -1);
	}

	static void patrol(Vec3 to) {
		LocalPlayer player = Minecraft.getInstance().player;
		start(Order.PATROL, to, -1);
		patrolFrom = player == null ? to : player.position();
	}

	static void attack(Entity foe) {
		start(Order.ATTACK, foe.position(), foe.getId());
	}

	static void hold() {
		start(Order.HOLD, null, -1);
	}

	static void stop() {
		start(Order.NONE, null, -1);
	}

	private static void start(Order next, @Nullable Vec3 to, int foe) {
		if (saved != null) endInteraction(Minecraft.getInstance().player);
		order = next;
		goal = to;
		target = foe;
		stuck = 0;
		if (next != Order.NONE) RtsAudio.order(next == Order.ATTACK || next == Order.ATTACK_MOVE ? "attack" : "move");
	}

	/** Selects the item; the command card then asks for its world target. */
	static void hand(int slot) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.gameMode == null) return;
		mc.player.getInventory().selected = slot;
		mc.player.connection.send(new ServerboundSetCarriedItemPacket(slot));
	}

	static void use(@Nullable LivingEntity entity, @Nullable net.minecraft.world.phys.BlockHitResult hit) {
		var player = Minecraft.getInstance().player;
		if (player == null) return;
		ItemStack stack = player.getMainHandItem();
		boolean tool = stack.is(net.minecraft.tags.ItemTags.MINING_ENCHANTABLE) || stack.is(net.minecraft.tags.ItemTags.SWORDS);
		if (entity != null && (tool || ranged(stack))) { attack(entity); return; }
		if (hit == null && entity == null) return;
		start(tool && hit != null ? Order.MINE : Order.USE, entity != null ? entity.position() : hit.getLocation(), entity == null ? -1 : entity.getId());
		blockTarget = hit;
		usingItem = false;
	}

	/** Client tick: carries out the order. */
	static void tick(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || !player.isAlive() || !CommandView.active()) {
			release(player);
			return;
		}
		walking = jumping = false;
		if (order == Order.NONE) {
			release(player);
			return;
		}
		if (!(player.input instanceof Legs)) {
			saved = player.input;
			player.input = new Legs();
		}
		if (order == Order.USE || order == Order.MINE) {
			useAt(mc, player);
			return;
		}
		LivingEntity foe = target >= 0 && mc.level.getEntity(target) instanceof LivingEntity living && living.isAlive() ? living : null;
		if (order == Order.ATTACK && foe == null) {
			order = Order.NONE;
			return;
		}
		if (foe == null && order != Order.MOVE) foe = nearestFoe(mc, player);
		if (foe != null && (order != Order.HOLD || player.distanceTo(foe) <= reach(player))) {
			fight(mc, player, foe);
			return;
		}
		if (order == Order.HOLD || goal == null) return;
		double flat = Math.hypot(goal.x - player.getX(), goal.z - player.getZ());
		if (flat < 1.2) {
			if (order == Order.PATROL && patrolFrom != null) { // back and forth
				Vec3 back = patrolFrom;
				patrolFrom = goal;
				goal = back;
			} else order = Order.NONE;
			return;
		}
		walkTo(player, goal);
	}

	private static void useAt(Minecraft mc, LocalPlayer player) {
		Entity entity = target < 0 ? null : mc.level.getEntity(target);
		Vec3 at = entity != null ? entity.getEyePosition() : blockTarget != null ? blockTarget.getLocation() : goal;
		if (at == null || target >= 0 && (entity == null || !entity.isAlive())) { stop(); return; }
		double reach = entity == null ? player.blockInteractionRange() : player.entityInteractionRange();
		if (player.getEyePosition().distanceToSqr(at) > (reach - 0.4) * (reach - 0.4)) { walkTo(player, at); return; }
		face(player, at, true);
		if (order == Order.MINE) {
			if (blockTarget == null || mc.level.getBlockState(blockTarget.getBlockPos()).isAir()) { stop(); return; }
			if (!mc.gameMode.isDestroying()) mc.gameMode.startDestroyBlock(blockTarget.getBlockPos(), blockTarget.getDirection());
			else mc.gameMode.continueDestroyBlock(blockTarget.getBlockPos(), blockTarget.getDirection());
			player.swing(InteractionHand.MAIN_HAND);
			return;
		}
		if (usingItem) {
			if (!player.isUsingItem()) stop();
			return;
		}
		net.minecraft.world.InteractionResult result = net.minecraft.world.InteractionResult.PASS;
		if (entity != null) {
			result = mc.gameMode.interactAt(player, entity, new net.minecraft.world.phys.EntityHitResult(entity, at), InteractionHand.MAIN_HAND);
			if (!result.consumesAction()) result = mc.gameMode.interact(player, entity, InteractionHand.MAIN_HAND);
		} else if (blockTarget != null) result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, blockTarget);
		if (!result.consumesAction()) result = mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
		if (result.shouldSwing()) player.swing(InteractionHand.MAIN_HAND);
		usingItem = player.isUsingItem();
		if (!usingItem) stop();
	}

	/** Walks toward a point: faces it, steps forward, jumps up what blocks it; gives up after being stuck for 6 s. */
	private static void walkTo(LocalPlayer player, Vec3 to) {
		face(player, to, false);
		walking = true;
		jumping = player.horizontalCollision && player.onGround() || player.isInWater();
		if (lastPos != null && lastPos.distanceToSqr(player.position()) < 0.0004) {
			if (++stuck > 120) order = Order.NONE;
		} else stuck = 0;
		lastPos = player.position();
	}

	/** Closes in to the weapon's reach, then strikes (melee, when the swing is charged) or fires (a gun). */
	private static void fight(Minecraft mc, LocalPlayer player, LivingEntity foe) {
		double distance = player.distanceTo(foe);
		if (distance > reach(player)) {
			walkTo(player, foe.position());
			return;
		}
		face(player, foe.getEyePosition(), true);
		if (ranged(player.getMainHandItem())) {
			if (player.isUsingItem()) {
				if (player.getTicksUsingItem() >= 20) mc.gameMode.releaseUsingItem(player);
			} else if (!player.getCooldowns().isOnCooldown(player.getMainHandItem().getItem())) mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
		} else if (player.getAttackStrengthScale(0.5f) >= 0.95f) {
			mc.gameMode.attack(player, foe);
			player.swing(InteractionHand.MAIN_HAND);
		}
	}

	private static boolean ranged(ItemStack stack) {
		return stack.getItem() instanceof dev.starmine.gear.GunItem || stack.getItem() instanceof dev.starmine.gear.LauncherItem
			|| stack.getItem() instanceof net.minecraft.world.item.ProjectileWeaponItem || stack.getItem() instanceof net.minecraft.world.item.TridentItem;
	}

	/** How close the hero must be to strike: a gun's reach, or the arm's. */
	private static double reach(LocalPlayer player) {
		return ranged(player.getMainHandItem()) ? 14 : player.entityInteractionRange() - 0.4;
	}

	/** The nearest foe in sight: monsters and the units of other sides that are hostile to the player. */
	private static @Nullable LivingEntity nearestFoe(Minecraft mc, LocalPlayer player) {
		LivingEntity best = null;
		double bestDistance = ALERT * ALERT;
		for (Entity entity : mc.level.getEntities(player, new AABB(player.position(), player.position()).inflate(ALERT))) {
			if (!(entity instanceof LivingEntity living) || !living.isAlive() || !player.hasLineOfSight(living)) continue;
			boolean foe = entity instanceof dev.starmine.entity.StarUnit unit ? !CommandView.own(unit) && dev.starmine.faction.Faction.hostile(unit, player)
				: entity instanceof Enemy;
			if (!foe) continue;
			double d = player.distanceToSqr(living);
			if (d < bestDistance) {
				bestDistance = d;
				best = living;
			}
		}
		return best;
	}

	private static void face(LocalPlayer player, Vec3 at, boolean pitch) {
		double dx = at.x - player.getX(), dz = at.z - player.getZ();
		float yaw = (float) (Mth.atan2(dz, dx) * (180 / Math.PI)) - 90f;
		player.setYRot(yaw);
		player.setYHeadRot(yaw);
		player.yBodyRot = yaw;
		if (pitch) {
			double dy = at.y - player.getEyeY();
			player.setXRot((float) -(Mth.atan2(dy, Math.hypot(dx, dz)) * (180 / Math.PI)));
		}
	}

	private static void release(@Nullable LocalPlayer player) {
		if (saved != null) endInteraction(player);
		order = Order.NONE;
		if (player != null && player.input instanceof Legs) player.input = saved != null ? saved : new KeyboardInput(Minecraft.getInstance().options);
		saved = null;
		walking = jumping = false;
	}

	private static void endInteraction(@Nullable LocalPlayer player) {
		if (Minecraft.getInstance().gameMode != null) {
			Minecraft.getInstance().gameMode.stopDestroyBlock();
			if (player != null && player.isUsingItem()) Minecraft.getInstance().gameMode.releaseUsingItem(player);
		}
		usingItem = false;
	}

	static void command(String action, Vec3 at, @Nullable Entity entity) {
		switch (action) {
			case "move", "portal" -> move(at);
			case "attack_move" -> attackMove(at);
			case "attack" -> { if (entity != null) attack(entity); else attackMove(at); }
			case "patrol" -> patrol(at);
			case "stop" -> stop();
			case "hold" -> hold();
		}
	}

	/** Whether the hero has an order under way (the card and the minimap show it). */
	static boolean busy() {
		return order != Order.NONE;
	}

	/** The body's legs while it carries out an order: forward when walking, a jump at a step. */
	private static final class Legs extends Input {
		@Override
		public void tick(boolean sneaking, float sneakSpeed) {
			this.up = walking;
			this.down = this.left = this.right = false;
			this.forwardImpulse = walking ? 1f : 0f;
			this.leftImpulse = 0f;
			this.jumping = HeroPilot.jumping;
			this.shiftKeyDown = false;
		}
	}
}
