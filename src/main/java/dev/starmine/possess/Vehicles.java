package dev.starmine.possess;

import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveSavedData;
import dev.starmine.world.Spawn;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;

/**
 * Craft one gets into like a car (owner 19.10): on the terrans' and protoss' sky ships stand craft and machines no one
 * mans — vikings, banshees, siege tanks, hellions, a thor; phoenixes, void rays, immortals, a colossus. A player who
 * right-clicks one (empty-handed) takes it: it is theirs, and they drive it ({@link Possession}); leaving it, it stays
 * theirs where they left it; destroyed, they are thrown out where it fell, alive.
 */
public final class Vehicles {
	public static final String PARKED = "starmine_parked";

	private Vehicles() {
	}

	/** A craft standing empty at `at` (its own hall is its place; {@link dev.starmine.hive.HiveStructure} parks it). */
	public static Spawn parked(UnitKind kind, BlockPos at) {
		return new Spawn(kind, at.immutable(), at.immutable(), kind, 0);
	}

	/** Whether a waiting unit is a parked craft (its "brood" is itself). */
	public static boolean parked(UnitKind kind, UnitKind brood) {
		return kind == brood && kind != UnitKind.EGG;
	}

	/** It stands still, empty, fighting no one, till someone gets in. */
	public static void park(StarUnit unit) {
		unit.addTag(PARKED);
		unit.setNoAi(true);
		unit.setPersistenceRequired();
	}

	public static boolean isParked(StarUnit unit) {
		return unit.getTags().contains(PARKED);
	}

	static void register() {
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (!(player instanceof ServerPlayer server) || !(entity instanceof StarUnit unit) || !isParked(unit) || player.isSpectator()
				|| !player.getItemInHand(hand).isEmpty() || Possession.piloting(server)) return InteractionResult.PASS;
			return board(server, unit) ? InteractionResult.SUCCESS : InteractionResult.FAIL;
		});
	}

	/** A player takes a parked craft: it is theirs (in their base if they have one here), and they drive it. */
	public static boolean board(ServerPlayer player, StarUnit unit) {
		unit.removeTag(PARKED);
		unit.setNoAi(false);
		unit.setOwner(player.getUUID());
		Hive base = HiveSavedData.get(player.server).baseOf(player.getUUID(), player.level().dimension().location().toString());
		if (base != null) unit.joinHive(base);
		player.level().playSound(null, unit.blockPosition(), SoundEvents.IRON_DOOR_OPEN, SoundSource.PLAYERS, 1f, 0.8f);
		((net.minecraft.server.level.ServerLevel) player.level()).sendParticles(ParticleTypes.HAPPY_VILLAGER, unit.getX(), unit.getY() + unit.getBbHeight(),
			unit.getZ(), 10, 0.5, 0.5, 0.5, 0);
		if (!Possession.start(player, unit)) return false;
		Possession.asVehicle(player);
		player.displayClientMessage(Component.translatable("message.starmine.vehicle_taken", unit.getDisplayName()), true);
		return true;
	}
}
