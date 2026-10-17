package dev.starmine.world;

import dev.starmine.StarMine;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * A sight blocker of a laid-over map (owner 17.10: SC2's line-of-sight blockers, tall grass and
 * smoke): a box of ground from its corner (the entity's position) across `SizeX` × `SizeZ` blocks
 * and `Height` up. It has no body — units and players walk through it, nothing hits, pushes or
 * targets it, it is drawn as a thicket as high as it is ({@code dev.starmine.client.VisionBlockerRenderer}) — and in the command view what is in or behind it is hidden from an
 * eye outside it, the units as well as the ground's fog and the minimap
 * ({@code dev.starmine.client.VisionBlockers}); an eye inside it sees out, as in SC2. Placed by
 * {@link MapOverlay} with the map's land and saved with its chunk.
 */
public final class VisionBlocker extends Entity {
	private static final EntityDataAccessor<Integer> SIZE_X = SynchedEntityData.defineId(VisionBlocker.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> SIZE_Z = SynchedEntityData.defineId(VisionBlocker.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> HEIGHT = SynchedEntityData.defineId(VisionBlocker.class, EntityDataSerializers.INT);

	public static final EntityType<VisionBlocker> TYPE = Registry.register(BuiltInRegistries.ENTITY_TYPE, StarMine.id("vision_blocker"),
		EntityType.Builder.<VisionBlocker>of(VisionBlocker::new, MobCategory.MISC).sized(0.1f, 0.1f).clientTrackingRange(12)
			.updateInterval(400).fireImmune().noSummon().build());

	public VisionBlocker(EntityType<? extends VisionBlocker> type, Level level) {
		super(type, level);
		noPhysics = true;
		setNoGravity(true);
		setInvulnerable(true);
	}

	/** Its extent: blocks across (x, z) and up. */
	public void span(int sizeX, int sizeZ, int height) {
		entityData.set(SIZE_X, Math.max(1, sizeX));
		entityData.set(SIZE_Z, Math.max(1, sizeZ));
		entityData.set(HEIGHT, Math.max(1, height));
	}

	/** The box it hides. */
	public AABB hiding() {
		return new AABB(getX(), getY(), getZ(), getX() + entityData.get(SIZE_X), getY() + entityData.get(HEIGHT), getZ() + entityData.get(SIZE_Z));
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(SIZE_X, 1).define(SIZE_Z, 1).define(HEIGHT, 4);
	}

	@Override
	protected void readAdditionalSaveData(CompoundTag tag) {
		span(tag.getInt("SizeX"), tag.getInt("SizeZ"), tag.getInt("Height"));
	}

	@Override
	protected void addAdditionalSaveData(CompoundTag tag) {
		tag.putInt("SizeX", entityData.get(SIZE_X));
		tag.putInt("SizeZ", entityData.get(SIZE_Z));
		tag.putInt("Height", entityData.get(HEIGHT));
	}

	@Override
	public void tick() {
		// nothing moves it and nothing ages it
	}

	@Override
	public boolean isPickable() {
		return false;
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	public boolean isAttackable() {
		return false;
	}

	@Override
	public boolean hurt(DamageSource source, float amount) {
		return false;
	}

	@Override
	public boolean shouldRenderAtSqrDistance(double distance) {
		return distance < 160 * 160;
	}

	@Override
	public AABB getBoundingBoxForCulling() {
		return hiding();
	}

	/** Registers the type (its class is loaded with the map overlay). */
	static void load() {
	}
}
