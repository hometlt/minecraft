package dev.starmine.world;

import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A race's portal (owner 17.10, «создаём портал на месте главной базы после её уничтожения, ключи не вводим»):
 * when a race base's main building falls, a portal like the End's opens where it stood — 3 x 3 portal blocks in a
 * frame of the race's portal frame, level with the ground — to the race's sky ship; the ship's pad leads back to
 * it. Like every portal it wakes once no guard is left round it ({@link Teleporter#guards}). The portal hall
 * under the base wakes at the same time ({@link Teleporter#locked}).
 */
public final class RacePortal {
	/**
	 * Owner 17.10: off for now — no portal opens where a main building fell, and the portal halls under the bases stay
	 * asleep; the way to a ship is the heroic transport of its race's freed guardian ({@link HeroicTransport}).
	 */
	public static final boolean ENABLED = false;

	private RacePortal() {
	}

	/** Opens the portal of `base` at `where` (the fallen main building's feet); nothing for a base without a ship. */
	public static void open(ServerLevel level, Hive base, BlockPos where) {
		if (!ENABLED || base.shipPad == null || base.race == null) return;
		UnitKind.Race race = base.race;
		BlockPos centre = where.immutable(); // the building stood on the ground: its feet
		BlockState frame = Teleporter.FRAMES.get(race).defaultBlockState();
		List<BlockPos> portal = new ArrayList<>();
		for (int dx = -2; dx <= 2; dx++)
			for (int dz = -2; dz <= 2; dz++) {
				BlockPos at = centre.offset(dx, 0, dz);
				for (int up = 1; up <= 3; up++) level.setBlock(at.above(up), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
				level.setBlock(at.below(), frame, Block.UPDATE_ALL);
				if (Math.abs(dx) == 2 || Math.abs(dz) == 2) {
					level.setBlock(at, (Math.abs(dx) == 2 && Math.abs(dz) == 2) ? frame : Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
					if (Math.abs(dx) == 2 && Math.abs(dz) == 2) level.setBlock(at.above(), frame, Block.UPDATE_ALL); // corner posts
					continue;
				}
				level.setBlock(at, Teleporter.BLOCKS.get(race).defaultBlockState(), Block.UPDATE_ALL);
				portal.add(at.immutable());
			}
		ServerLevel padLevel = base.shipDim == null ? level : level.getServer().getLevel(net.minecraft.resources.ResourceKey.create(
			net.minecraft.core.registries.Registries.DIMENSION, net.minecraft.resources.ResourceLocation.parse(base.shipDim)));
		if (padLevel != null) Teleporter.linkPortal(level, portal, centre, padLevel, base.shipPad);
		level.playSound(null, centre, SoundEvents.END_PORTAL_SPAWN, SoundSource.BLOCKS, 1.5f, 1.0f);
		level.sendParticles(ParticleTypes.REVERSE_PORTAL, centre.getX() + 0.5, centre.getY() + 1, centre.getZ() + 0.5, 120, 1.5, 1.5, 1.5, 0.1);
		for (ServerPlayer player : level.players())
			if (player.distanceToSqr(centre.getCenter()) < 160 * 160)
				player.displayClientMessage(Component.translatable("message.starmine.portal_opened", Component.translatable("race.starmine." + race.name().toLowerCase())), false);
	}
}
