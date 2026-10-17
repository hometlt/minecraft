package dev.starmine.gear;

import dev.starmine.entity.UnitKind;
import dev.starmine.entity.ZergSpit;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * Owner 17.10: what fires a projectile — the marauder's Punisher grenade launcher (concussive grenades, a bonus
 * against armored targets, they slow what they hit) and the reaper's KD8 charge, thrown by hand (a blast that
 * hurts and throws back what is round it, blocks stay). A launcher uses a round of its ammunition a shot and wears
 * out; a thrown charge is itself the ammunition.
 */
public final class LauncherItem extends Item {
	private final Supplier<Item> ammo;
	private final float damage, armoredBonus, speed;
	private final int cooldown;
	private final boolean slows, blast;

	public LauncherItem(Properties properties, Supplier<Item> ammo, float damage, float armoredBonus, float speed, int cooldown, boolean slows, boolean blast) {
		super(properties);
		this.ammo = ammo;
		this.damage = damage;
		this.armoredBonus = armoredBonus;
		this.speed = speed;
		this.cooldown = cooldown;
		this.slows = slows;
		this.blast = blast;
	}

	/** A thrown thing: the stack in the hand is the ammunition. */
	private boolean thrown() {
		return ammo.get() == this;
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		ItemStack held = player.getItemInHand(hand);
		if (level.isClientSide) return InteractionResultHolder.success(held);
		boolean free = player.getAbilities().instabuild;
		if (!free && !thrown() && !takeRound(player)) {
			player.displayClientMessage(Component.translatable("message.starmine.no_ammo", Component.translatable(ammo.get().getDescriptionId())), true);
			level.playSound(null, player.blockPosition(), SoundEvents.DISPENSER_FAIL, SoundSource.PLAYERS, 0.6f, 1.4f);
			return InteractionResultHolder.fail(held);
		}
		ZergSpit shot = ZergSpit.fromPlayer(level, player, thrown() ? UnitKind.REAPER : UnitKind.MARAUDER, new ItemStack(ammo.get()), damage, armoredBonus,
			slows, blast);
		shot.shootFromRotation(player, player.getXRot(), player.getYRot(), 0, speed, 0.5f);
		level.addFreshEntity(shot);
		level.playSound(null, player.getX(), player.getY(), player.getZ(), thrown() ? SoundEvents.SNOWBALL_THROW : SoundEvents.FIREWORK_ROCKET_LAUNCH,
			SoundSource.PLAYERS, 0.8f, thrown() ? 0.6f : 0.8f);
		player.getCooldowns().addCooldown(this, cooldown);
		if (thrown()) {
			if (!free) held.shrink(1);
		} else {
			held.hurtAndBreak(1, player, hand == InteractionHand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND);
		}
		return InteractionResultHolder.consume(held);
	}

	private boolean takeRound(Player player) {
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			ItemStack stack = player.getInventory().getItem(i);
			if (stack.is(ammo.get())) {
				stack.shrink(1);
				return true;
			}
		}
		return false;
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
		lines.add(Component.translatable(thrown() ? "item.starmine.charge.tip" : "item.starmine.launcher.tip", Math.round(damage), Math.round(armoredBonus),
			Component.translatable(ammo.get().getDescriptionId())).withColor(0xFFB0B0B0));
	}
}
