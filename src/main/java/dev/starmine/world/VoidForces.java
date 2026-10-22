package dev.starmine.world;

import dev.starmine.StarMine;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;

/**
 * Amun's side in the Void (owner 17.10): {@link Amun} himself, the {@link VoidShard}s that shield him and the
 * {@link VoidRift}s he opens, out of which troops of all three races come ({@link #TAG}: they fight for him,
 * not for their race). {@link dev.starmine.faction.Faction#amun} tells them apart.
 */
public final class VoidForces {
	/** Units that came out of a rift: Amun's, whatever their race. */
	public static final String TAG = "starmine_amun";

	/** Amun and his shards; shared rifts are classified by VoidRift.invasion() in Faction. */
	public interface Member {
	}

	public static final EntityType<Amun> AMUN = Registry.register(BuiltInRegistries.ENTITY_TYPE, StarMine.id("amun"),
		EntityType.Builder.of(Amun::new, MobCategory.MONSTER).sized(9.0f, 9.0f).fireImmune().clientTrackingRange(16).build("amun"));
	public static final EntityType<VoidShard> SHARD = Registry.register(BuiltInRegistries.ENTITY_TYPE, StarMine.id("void_shard"),
		EntityType.Builder.of(VoidShard::new, MobCategory.MONSTER).sized(2.0f, 2.0f).fireImmune().clientTrackingRange(16).build("void_shard"));
	public static final EntityType<VoidRift> RIFT = Registry.register(BuiltInRegistries.ENTITY_TYPE, StarMine.id("void_rift"),
		EntityType.Builder.of(VoidRift::new, MobCategory.MONSTER).sized(2.5f, 4.0f).fireImmune().clientTrackingRange(12).build("void_rift"));

	private VoidForces() {
	}

	public static void load() {
		FabricDefaultAttributeRegistry.register(AMUN, Monster.createMonsterAttributes().add(Attributes.MAX_HEALTH, Amun.HEALTH)
			.add(Attributes.ARMOR, 12).add(Attributes.ARMOR_TOUGHNESS, 8).add(Attributes.KNOCKBACK_RESISTANCE, 1).add(Attributes.FOLLOW_RANGE, 128)
			.add(Attributes.MOVEMENT_SPEED, 0.3).add(Attributes.FLYING_SPEED, 0.6).add(Attributes.ATTACK_DAMAGE, 16));
		FabricDefaultAttributeRegistry.register(SHARD, Mob.createMobAttributes().add(Attributes.MAX_HEALTH, VoidShard.HEALTH)
			.add(Attributes.KNOCKBACK_RESISTANCE, 1));
		FabricDefaultAttributeRegistry.register(RIFT, Mob.createMobAttributes().add(Attributes.MAX_HEALTH, VoidRift.HEALTH)
			.add(Attributes.ARMOR, 6).add(Attributes.KNOCKBACK_RESISTANCE, 1));
	}
}
