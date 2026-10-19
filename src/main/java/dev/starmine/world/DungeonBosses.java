package dev.starmine.world;

import dev.starmine.StarMine;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.HiveLogic;
import java.util.List;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * The story dungeons' bosses and their prize (owner 19.10):
 * - the brood mother in her womb ({@link StoryDungeons}): while a foe is near she lays eggs round her every
 *   {@link #LAY_EVERY} ticks, which hatch in a few seconds — zerglings, roaches, hydralisks — till {@link #BROOD_CAP}
 *   of her brood are round her; the eggs stop when she dies;
 * - the hybrid of Moebius's laboratory: it blinks behind its foe every {@link #BLINK_EVERY} ticks with a psionic
 *   shockwave round it, and below half its health calls a psionic storm onto its foe;
 * - the Xel'Naga relic ({@link #RELIC}): the prize of the vault and the sunken scout, and the hybrid's: used, it shows
 *   every creature within {@link #RELIC_REACH} blocks through walls for 30 seconds and heals its holder; it rests two
 *   minutes after.
 */
public final class DungeonBosses {
	public static final int LAY_EVERY = 240, BROOD_CAP = 14, BLINK_EVERY = 160, STORM_EVERY = 220, RELIC_REACH = 48, RELIC_REST = 2400;
	public static final Item RELIC = Registry.register(BuiltInRegistries.ITEM, StarMine.id("xelnaga_relic"),
		new Relic(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)));
	private static final DustParticleOptions PSI = new DustParticleOptions(new Vector3f(0.55f, 0.25f, 1.0f), 1.4f);

	private DungeonBosses() {
	}

	public static void load() {
		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(entries -> entries.accept(RELIC));
	}

	/** Each tick of a dungeon boss. */
	public static void tick(StarUnit boss, ServerLevel level) {
		if (boss.kind == UnitKind.BROOD_MOTHER) broodMother(boss, level);
		else if (boss.kind == UnitKind.HYBRID) hybrid(boss, level);
	}

	private static LivingEntity foe(StarUnit boss, ServerLevel level) {
		if (boss.getTarget() != null && boss.getTarget().isAlive()) return boss.getTarget();
		Player near = level.getNearestPlayer(boss, 24);
		return near != null && !near.isSpectator() && !near.isCreative() ? near : null;
	}

	// ---- the brood mother ----

	static void broodMother(StarUnit mother, ServerLevel level) {
		if (mother.tickCount % LAY_EVERY != 0 || foe(mother, level) == null) return;
		int brood = level.getEntitiesOfClass(StarUnit.class, mother.getBoundingBox().inflate(24, 8, 24),
			unit -> unit != mother && unit.isAlive() && unit.owner().isEmpty() && unit.kind.race == UnitKind.Race.ZERG).size();
		int eggs = Math.min(3, BROOD_CAP - brood);
		for (int i = 0; i < eggs; i++) {
			double a = level.random.nextDouble() * Math.PI * 2;
			Vec3 at = mother.position().add(Math.cos(a) * 4, 0, Math.sin(a) * 4);
			StarUnit egg = HiveLogic.spawnNest(level, null, UnitKind.EGG, at, mother.blockPosition());
			if (egg == null) continue;
			int roll = level.random.nextInt(6);
			UnitKind kind = roll < 3 ? UnitKind.ZERGLING : roll < 5 ? UnitKind.ROACH : UnitKind.HYDRALISK;
			egg.becomeCocoon(kind, kind == UnitKind.ZERGLING ? 2 : 1, 60 + level.random.nextInt(40));
			level.sendParticles(ParticleTypes.ITEM_SLIME, at.x, at.y + 0.5, at.z, 12, 0.4, 0.3, 0.4, 0.05);
		}
		if (eggs > 0) level.playSound(null, mother.blockPosition(), SoundEvents.SLIME_SQUISH, SoundSource.HOSTILE, 2.0f, 0.5f);
	}

	// ---- the hybrid ----

	static void hybrid(StarUnit hybrid, ServerLevel level) {
		LivingEntity foe = foe(hybrid, level);
		if (foe == null) return;
		if (hybrid.getTarget() == null) hybrid.setTarget(foe);
		if (hybrid.tickCount % BLINK_EVERY == 0) { // behind its foe, a shockwave round it
			Vec3 behind = foe.position().add(foe.getLookAngle().multiply(-2.5, 0, -2.5));
			level.sendParticles(PSI, hybrid.getX(), hybrid.getY() + 1.5, hybrid.getZ(), 30, 0.6, 1.2, 0.6, 0);
			hybrid.teleportTo(behind.x, foe.getY(), behind.z);
			level.playSound(null, hybrid.blockPosition(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.5f, 0.6f);
			for (LivingEntity hit : level.getEntitiesOfClass(LivingEntity.class, hybrid.getBoundingBox().inflate(4), e -> e != hybrid && e.isAlive()
				&& !(e instanceof StarUnit unit && unit.kind == UnitKind.HYBRID))) {
				hit.hurt(level.damageSources().mobAttack(hybrid), 6);
				Vec3 away = hit.position().subtract(hybrid.position()).normalize().scale(1.2);
				hit.push(away.x, 0.4, away.z);
			}
			level.sendParticles(PSI, hybrid.getX(), hybrid.getY() + 0.5, hybrid.getZ(), 60, 3, 0.3, 3, 0);
		}
		if (hybrid.getHealth() < hybrid.getMaxHealth() / 2 && hybrid.tickCount % STORM_EVERY == 0) // a psionic storm on its foe
			storm(level, hybrid, foe.position());
	}

	/** A psionic storm at `at`: three seconds of hurt round it, its motes first as a warning. */
	static void storm(ServerLevel level, StarUnit caster, Vec3 at) {
		for (int pulse = 0; pulse < 6; pulse++) {
			int delay = 20 + pulse * 10;
			dev.starmine.ability.Delayed.after(level, delay, () -> {
				level.sendParticles(PSI, at.x, at.y + 1, at.z, 50, 2.5, 1, 2.5, 0);
				level.sendParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y + 1, at.z, 20, 2.5, 1, 2.5, 0.1);
				for (LivingEntity hit : level.getEntitiesOfClass(LivingEntity.class, new AABB(BlockPos.containing(at)).inflate(3, 2, 3),
					e -> e.isAlive() && !(e instanceof StarUnit unit && unit.kind == UnitKind.HYBRID)))
					hit.hurt(level.damageSources().indirectMagic(caster, caster), 3);
			});
		}
		level.sendParticles(PSI, at.x, at.y + 0.2, at.z, 40, 2.5, 0.1, 2.5, 0);
		level.playSound(null, BlockPos.containing(at), SoundEvents.BEACON_POWER_SELECT, SoundSource.HOSTILE, 2.0f, 0.5f);
	}

	/** What a dungeon boss leaves: the mother chitin and diamonds; the hybrid the relic, diamonds and an echo shard. */
	public static List<ItemStack> loot(StarUnit boss) {
		return switch (boss.kind) {
			case BROOD_MOTHER -> List.of(new ItemStack(Items.DIAMOND, 6), new ItemStack(Items.EXPERIENCE_BOTTLE, 8));
			case HYBRID -> List.of(new ItemStack(RELIC), new ItemStack(Items.DIAMOND, 8), new ItemStack(Items.ECHO_SHARD, 2));
			default -> List.of();
		};
	}

	/** The Xel'Naga relic: everything round is seen through walls for a while, and its holder healed. */
	static final class Relic extends Item {
		Relic(Properties properties) {
			super(properties);
		}

		@Override
		public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
			ItemStack stack = player.getItemInHand(hand);
			if (level instanceof ServerLevel server) {
				for (LivingEntity seen : server.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(RELIC_REACH), e -> e != player && e.isAlive()))
					seen.addEffect(new MobEffectInstance(MobEffects.GLOWING, 600, 0, false, false));
				player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 200, 1));
				server.sendParticles(ParticleTypes.END_ROD, player.getX(), player.getY() + 1, player.getZ(), 60, 1.5, 1, 1.5, 0.2);
				server.playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 2.0f, 0.8f);
				player.displayClientMessage(Component.translatable("message.starmine.relic_used"), true);
			}
			player.getCooldowns().addCooldown(this, RELIC_REST);
			return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
		}
	}
}
