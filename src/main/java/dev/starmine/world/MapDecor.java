package dev.starmine.world;

import dev.starmine.StarMine;
import net.fabricmc.fabric.api.blockview.v2.RenderDataBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Walk-through decor of a laid-over map (owner 17.10): the parts of SC2's decorations that stand on
 * ground units walk, which a solid block would wall off. It looks like the block it stands for (its
 * {@link Look} entity keeps that block, drawn in the chunk's mesh by the client's decor model) but
 * has no body: units, players and path finders go through it, the cursor and rays too, and anything
 * built or placed there replaces it. Placed by {@link MapOverlay} with the map's land.
 */
public final class MapDecor extends Block implements EntityBlock {
	public static final MapDecor BLOCK = Registry.register(BuiltInRegistries.BLOCK, StarMine.id("map_decor"),
		new MapDecor(BlockBehaviour.Properties.of().mapColor(MapColor.NONE).noCollission().noOcclusion().replaceable().instabreak()
			.noLootTable().pushReaction(PushReaction.DESTROY).isValidSpawn((state, level, pos, type) -> false)
			.isSuffocating((state, level, pos) -> false).isViewBlocking((state, level, pos) -> false)));
	public static final BlockEntityType<Look> LOOK = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, StarMine.id("map_decor"),
		BlockEntityType.Builder.of(Look::new, BLOCK).build(null));

	private MapDecor(Properties properties) {
		super(properties);
	}

	/** Decor looking like `look` at `pos`. */
	static void put(LevelAccessor level, BlockPos pos, BlockState look) {
		level.setBlock(pos, BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
		if (level.getBlockEntity(pos) instanceof Look entity) entity.look = look;
	}

	@Override
	public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new Look(pos, state);
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return Shapes.empty();
	}

	@Override
	protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
		return true;
	}

	@Override
	protected float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
		return 1f;
	}

	/** The block a decor block looks like; sent to the client for its mesh. */
	public static final class Look extends BlockEntity implements RenderDataBlockEntity {
		private BlockState look = Blocks.STONE.defaultBlockState();

		Look(BlockPos pos, BlockState state) {
			super(LOOK, pos, state);
		}

		@Override
		protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
			look = NbtUtils.readBlockState(registries.lookupOrThrow(Registries.BLOCK), tag.getCompound("Look"));
		}

		@Override
		protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
			tag.put("Look", NbtUtils.writeBlockState(look));
		}

		@Override
		public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
			return saveWithoutMetadata(registries);
		}

		@Override
		public Packet<ClientGamePacketListener> getUpdatePacket() {
			return ClientboundBlockEntityDataPacket.create(this);
		}

		@Override
		public Object getRenderData() {
			return look;
		}
	}

	/** Registers the block (its class is loaded with the map overlay). */
	static void load() {
	}
}
