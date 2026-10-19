package dev.starmine.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.starmine.StarMine;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.possess.Pilots;
import dev.starmine.possess.PossessNetwork;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

/**
 * Client side of playing as a unit (owner 14.10, dev.starmine.possess): who plays as what (their body
 * is drawn as the unit: a stand-in unit copies the player's place, turn, walk and swing), the player's
 * own unit screen (energy, the leave key) and going back to the command view when it is over.
 */
public final class PilotClient {
	public static final net.minecraft.client.KeyMapping LEAVE = net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.registerKeyBinding(
		new net.minecraft.client.KeyMapping("key.starmine.leave_unit", com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM,
			org.lwjgl.glfw.GLFW.GLFW_KEY_V, "category.starmine"));
	/** A stand-in unit per piloting player, drawn in their place. */
	private static final Map<UUID, StarUnit> STAND_INS = new ConcurrentHashMap<>();
	private static PossessNetwork.PilotHud hud = new PossessNetwork.PilotHud(0, 0, 0, false);

	private PilotClient() {
	}

	static void register() {
		ClientPlayNetworking.registerGlobalReceiver(PossessNetwork.PilotState.TYPE, (state, context) -> {
			Minecraft mc = context.client();
			UnitKind kind = state.kind().isEmpty() ? null : UnitKind.valueOf(state.kind());
			Pilots.set(true, state.player(), kind);
			Pilots.CLIENT_FLAGS.put(state.player(), state.flags());
			if (kind == null) STAND_INS.remove(state.player());
			Entity entity = mc.level == null ? null : mc.level.getEntity(state.entity());
			if (entity != null) entity.refreshDimensions();
			if (kind != null && mc.player != null && state.player().equals(mc.player.getUUID())) { // we became the unit: out of the command view
				if (CommandView.active()) CommandView.exit(mc);
				mc.setScreen(null);
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(PossessNetwork.PilotHud.TYPE, (state, context) -> hud = state);
		ClientPlayNetworking.registerGlobalReceiver(PossessNetwork.PilotEnded.TYPE, (ended, context) -> {
			Minecraft mc = context.client();
			if (mc.player != null) mc.player.refreshDimensions();
			if (!ended.vehicle() && mc.player != null && dev.starmine.colony.CommandPanel.carried(mc.player) && !CommandView.active()) CommandView.enter(mc);
		});
	}

	/** Each client tick: the leave key. */
	static void tick(Minecraft mc) {
		while (LEAVE.consumeClick()) if (mc.player != null && Pilots.kindOf(mc.player) != null) ClientPlayNetworking.send(new PossessNetwork.LeaveUnit());
	}

	static void clear() {
		Pilots.clear(true);
		STAND_INS.clear();
	}

	/** Draws a piloting player as their unit; false when the player is not piloting (the usual player model is drawn). */
	public static boolean render(AbstractClientPlayer player, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
		UnitKind kind = Pilots.kindOf(player);
		if (kind == null) return false;
		StarUnit standIn = STAND_INS.get(player.getUUID());
		if (standIn == null || standIn.kind != kind) {
			standIn = StarMine.type(kind).create(player.level());
			if (standIn == null) return false;
			STAND_INS.put(player.getUUID(), standIn);
		}
		standIn.setPos(player.getX(), player.getY(), player.getZ());
		standIn.xo = player.xo;
		standIn.yo = player.yo;
		standIn.zo = player.zo;
		standIn.yBodyRot = player.yBodyRot;
		standIn.yBodyRotO = player.yBodyRotO;
		standIn.setYRot(player.getYRot());
		standIn.tickCount = player.tickCount;
		standIn.hurtTime = player.hurtTime;
		standIn.walkAnimation.setSpeed(player.walkAnimation.speed(partialTick));
		standIn.setDeltaMovement(player.getDeltaMovement());
		if (player.swinging && player.swingTime == 0) {
			standIn.attackStartTick = player.tickCount;
			standIn.attackVariant = player.getRandom().nextInt(3);
		}
		int flags = Pilots.CLIENT_FLAGS.getOrDefault(player.getUUID(), 0);
		standIn.setCloaked((flags & PossessNetwork.CLOAKED) != 0);
		standIn.setRevealed((flags & PossessNetwork.REVEALED) != 0);
		standIn.setOwner(player.getUUID());
		Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(standIn).render(standIn, yaw, partialTick, pose, buffers, light);
		return true;
	}

	/** The player's unit screen: its name, energy and the leave key, above the hotbar. */
	static void drawHud(GuiGraphics graphics) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.options.hideGui) return;
		UnitKind kind = Pilots.kindOf(mc.player);
		if (kind == null) return;
		int x = graphics.guiWidth() / 2 - 91, y = graphics.guiHeight() - 62;
		graphics.drawString(mc.font, Component.translatable("entity.starmine." + kind.id), x, y - 10, 0xFFFFD040);
		if (hud.maxEnergy() > 0) {
			graphics.fill(x, y, x + 182, y + 4, 0xC0101418);
			graphics.fill(x, y, x + (int) (182 * hud.energy() / hud.maxEnergy()), y + 4, 0xFFA040FF);
			graphics.drawString(mc.font, Component.translatable("command.starmine.energy", (int) hud.energy(), hud.maxEnergy()), x + 100, y - 10, 0xFFC080FF);
		}
		if (hud.busy()) graphics.drawString(mc.font, Component.translatable("message.starmine.busy"), x + 50, y - 20, 0xFFFF8040);
		Component leave = Component.translatable("message.starmine.leave_hint", LEAVE.getTranslatedKeyMessage());
		graphics.drawString(mc.font, leave, graphics.guiWidth() - mc.font.width(leave) - 6, 6, 0xFFFFFFFF);
	}
}
