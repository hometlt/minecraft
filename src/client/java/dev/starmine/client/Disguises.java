package dev.starmine.client;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.starmine.ability.Changeling;
import dev.starmine.entity.StarUnit;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

/**
 * A changeling in disguise drawn as what it copied (dev.starmine.ability.Changeling): a villager, or
 * the player with their name and skin. Each changeling has a stand-in entity of that kind, never added
 * to the world, that follows its place, turn and steps and is drawn by the game's own renderer.
 */
final class Disguises {
	private record StandIn(String look, LivingEntity body, int[] lastTick) {
	}

	private static final Map<StarUnit, StandIn> STAND_INS = new WeakHashMap<>();

	private Disguises() {
	}

	/** Draws it as what it copied; false when there is no such look to draw (its own model then). */
	static boolean render(StarUnit changeling, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int packedLight) {
		StandIn standIn = standIn(changeling);
		if (standIn == null) return false;
		LivingEntity body = standIn.body();
		follow(changeling, body, standIn.lastTick());
		@SuppressWarnings("unchecked")
		EntityRenderer<LivingEntity> renderer = (EntityRenderer<LivingEntity>) Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(body);
		renderer.render(body, body.getYRot(), partialTick, poseStack, buffers, packedLight);
		return true;
	}

	private static @Nullable StandIn standIn(StarUnit changeling) {
		String look = changeling.disguise();
		StandIn known = STAND_INS.get(changeling);
		if (known != null && known.look().equals(look)) return known;
		if (!(changeling.level() instanceof ClientLevel level)) return null;
		LivingEntity body;
		if (look.equals(Changeling.VILLAGER)) body = EntityType.VILLAGER.create(level);
		else {
			UUID id;
			try {
				id = UUID.fromString(look);
			} catch (IllegalArgumentException e) {
				return null;
			}
			var connection = Minecraft.getInstance().getConnection();
			PlayerInfo info = connection == null ? null : connection.getPlayerInfo(id); // the skin comes from it
			body = new RemotePlayer(level, info != null ? info.getProfile() : new GameProfile(id, ""));
		}
		if (body == null) return null;
		StandIn standIn = new StandIn(look, body, new int[] {-1});
		STAND_INS.put(changeling, standIn);
		return standIn;
	}

	/** The stand-in takes the changeling's place, turn and steps (as the game animates walking: by distance a tick). */
	private static void follow(StarUnit changeling, LivingEntity body, int[] lastTick) {
		body.setPos(changeling.getX(), changeling.getY(), changeling.getZ());
		body.xo = changeling.xo;
		body.yo = changeling.yo;
		body.zo = changeling.zo;
		body.xOld = changeling.xOld;
		body.yOld = changeling.yOld;
		body.zOld = changeling.zOld;
		body.setYRot(changeling.getYRot());
		body.yRotO = changeling.yRotO;
		body.setXRot(changeling.getXRot());
		body.xRotO = changeling.xRotO;
		body.yBodyRot = changeling.yBodyRot;
		body.yBodyRotO = changeling.yBodyRotO;
		body.yHeadRot = changeling.yHeadRot;
		body.yHeadRotO = changeling.yHeadRotO;
		body.hurtTime = changeling.hurtTime;
		body.tickCount = changeling.tickCount;
		if (lastTick[0] != changeling.tickCount) {
			lastTick[0] = changeling.tickCount;
			float moved = (float) Math.sqrt(Math.pow(changeling.getX() - changeling.xo, 2) + Math.pow(changeling.getZ() - changeling.zo, 2));
			body.walkAnimation.update(Math.min(moved * 4f, 1f), 0.4f);
		}
	}
}
