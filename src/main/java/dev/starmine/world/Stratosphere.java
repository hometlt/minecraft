package dev.starmine.world;

import dev.starmine.StarMine;
import dev.starmine.entity.StarUnit;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The stratosphere (owner 17.10): a dimension of its own high over the world — black sky above, the Earth huge
 * below to the horizon (drawn by the client), the overworld's day and night. The races' sky ships hang there
 * ({@link RaceBases}). One comes up only carried by a flying unit — riding a transport, or playing as a flyer —
 * climbing over {@link #CLIMB_Y} in the overworld; alone a player cannot. Whatever falls below {@link #FALL_Y}
 * there comes down into the overworld at the same place, high over it (a player slowly, not to break their neck).
 */
public final class Stratosphere {
	public static final ResourceKey<Level> KEY = ResourceKey.create(Registries.DIMENSION, StarMine.id("stratosphere"));
	/** Where the ships float, where one arrives from below, below what one falls out, over what a flyer climbs in. */
	public static final int SHIP_Y = 150, ARRIVE_Y = 40, FALL_Y = -40, CLIMB_Y = 300, LAND_Y = 310;

	private Stratosphere() {
	}

	public static @Nullable ServerLevel level(MinecraftServer server) {
		return server.getLevel(KEY);
	}

	public static boolean is(Level level) {
		return level.dimension() == KEY;
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % 5 != 0) return;
			ServerLevel overworld = server.overworld(), sky = level(server);
			if (sky == null) return;
			for (ServerPlayer player : new ArrayList<>(overworld.players()))
				if (player.getY() > CLIMB_Y && carried(player)) up(player, sky);
			List<Entity> falling = new ArrayList<>();
			for (Entity entity : sky.getAllEntities())
				if (entity.getY() < FALL_Y && !entity.isPassenger() && entity.isAlive()) falling.add(entity);
			for (Entity entity : falling) down(entity, overworld);
		});
	}

	/** Whether a player is carried by a flyer: riding a flying unit, or playing as one. */
	static boolean carried(ServerPlayer player) {
		if (player.getRootVehicle() instanceof StarUnit unit && unit.flying()) return true;
		StarUnit puppet = dev.starmine.possess.Possession.puppet(player);
		return puppet != null && puppet.kind.flying;
	}

	/** Up into the stratosphere, with the flyer (and all it carries) when riding one. */
	static void up(ServerPlayer player, ServerLevel sky) {
		Entity root = player.getRootVehicle();
		Vec3 at = new Vec3(root.getX(), ARRIVE_Y, root.getZ());
		root.changeDimension(new DimensionTransition(sky, at, Vec3.ZERO, root.getYRot(), root.getXRot(), DimensionTransition.DO_NOTHING));
		player.displayClientMessage(Component.translatable("message.starmine.stratosphere_up"), true);
	}

	/** Down into the overworld at the same place, high over it. */
	static void down(Entity entity, ServerLevel overworld) {
		Vec3 at = new Vec3(entity.getX(), LAND_Y, entity.getZ());
		Entity moved = entity.changeDimension(new DimensionTransition(overworld, at, entity.getDeltaMovement(), entity.getYRot(), entity.getXRot(),
			DimensionTransition.DO_NOTHING));
		for (Entity arrived : moved == null ? List.<Entity>of() : moved.getSelfAndPassengers().toList())
			if (arrived instanceof ServerPlayer player) {
				player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 20 * 30, 0));
				player.displayClientMessage(Component.translatable("message.starmine.stratosphere_down"), true);
			}
	}

	/** Whether a box in the stratosphere is clear of blocks (for tests and arrivals). */
	static boolean clear(ServerLevel level, AABB box) {
		return level.noCollision(box);
	}
}
