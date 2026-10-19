package dev.starmine.world;

import dev.starmine.StarMine;
import dev.starmine.entity.Heroes;
import dev.starmine.entity.StarUnit;
import dev.starmine.entity.UnitKind;
import dev.starmine.hive.Hive;
import dev.starmine.hive.HiveSavedData;
import dev.starmine.hive.HiveStructure;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.jetbrains.annotations.Nullable;

/**
 * The heroes under Amun's hold (owner 17.10). Kerrigan, Raynor and Artanis fight for Amun — black and red motes
 * swirl round them — on their ships' arenas, whose floor cannot be broken and which they never leave. Beaten,
 * they do not die: at a tenth of their health Amun's hold breaks, they say so ({@link Cutscene}), and they go over
 * to the player who beat them, with their whole base and army ({@link #convert}); they stay on their ships. Their
 * part of the artifact's key stands by them on the ship and cannot be broken until they are free.
 */
public final class HeroLiberation {
	/** Blocks round the hero's place that are its arena: the floor under them cannot be broken, the hero stays inside. */
	public static final int ARENA_RADIUS = 8, STAY_RADIUS = 7;
	/** How far a guardian of a main base (owner 17.10: Abathur, Swann, Karax) may go from its post. */
	public static final int GUARD_RADIUS = 24;
	/** The share of its health a beaten hero keeps. */
	public static final float LEFT = 0.1f;
	public static final String FREED = "starmine_freed", FREED_AT = "starmine_freed_at:";
	/** Owner 19.10: for a minute after it is freed a hero cannot die (the fight's last blows, its units', its liberator's). */
	public static final int MERCY_TICKS = 60 * 20;

	/** When a hero was freed (game time), or 0. */
	static long freedAt(StarUnit hero) {
		for (String tag : hero.getTags())
			if (tag.startsWith(FREED_AT)) try {
				return Long.parseLong(tag.substring(FREED_AT.length()));
			} catch (NumberFormatException e) {
				return 0;
			}
		return 0;
	}
	public static final Map<UnitKind.Race, Block> FLOORS = new EnumMap<>(UnitKind.Race.class);
	private static final DustParticleOptions BLACK = new DustParticleOptions(new Vector3f(0.05f, 0.02f, 0.05f), 1.4f);
	private static final DustParticleOptions RED = new DustParticleOptions(new Vector3f(0.85f, 0.05f, 0.08f), 1.1f);

	static {
		for (UnitKind.Race race : UnitKind.Race.values()) {
			String name = race.name().toLowerCase(java.util.Locale.ROOT) + "_arena_floor";
			Block block = Registry.register(BuiltInRegistries.BLOCK, StarMine.id(name), new Block(BlockBehaviour.Properties.of()
				.strength(-1.0f, 3600000.0f).noLootTable().pushReaction(PushReaction.BLOCK).lightLevel(state -> 3)
				.sound(race == UnitKind.Race.ZERG ? SoundType.WART_BLOCK : race == UnitKind.Race.TERRAN ? SoundType.METAL : SoundType.STONE)));
			Registry.register(BuiltInRegistries.ITEM, StarMine.id(name), new net.minecraft.world.item.BlockItem(block, new net.minecraft.world.item.Item.Properties()));
			FLOORS.put(race, block);
		}
	}

	private HeroLiberation() {
	}

	public static void load() {
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(HeroLiberation::respawn);
		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) ->
			!(entity instanceof StarUnit hero && hero.kind.boss() && hero.level() instanceof ServerLevel level && beaten(hero, level, source)));
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
			if (!(level instanceof ServerLevel server) || !(state.getBlock() instanceof Artifact.Part part)) return true;
			VoidWorld.State data = VoidWorld.State.get(server.getServer());
			String race = part.race.name();
			if (!pos.equals(data.parts.get(race)) || data.freed.contains(race)) return true;
			player.displayClientMessage(Component.translatable("message.starmine.part_held", heroName(part.race)), true);
			return false; // the hero's part stays while Amun holds the hero
		});
	}

	public static boolean freed(StarUnit hero) {
		return hero.getTags().contains(FREED);
	}

	private static Component heroName(UnitKind.Race race) {
		return Component.translatable("entity.starmine." + hero(race).id);
	}

	static UnitKind hero(UnitKind.Race race) {
		return switch (race) {
			case ZERG -> UnitKind.KERRIGAN;
			case TERRAN -> UnitKind.RAYNOR;
			case PROTOSS -> UnitKind.ARTANIS;
		};
	}

	// ---- the arena ----

	/**
	 * A ship's boss room is made an arena (when the ship is built): the floor round the hero's place cannot be broken,
	 * and the hero's key part stands on it near the hero.
	 */
	public static void prepare(ServerLevel level, BlockPos boss, UnitKind.Race race) {
		Block floor = FLOORS.get(race);
		BlockPos under = boss.below();
		for (int dx = -ARENA_RADIUS; dx <= ARENA_RADIUS; dx++)
			for (int dz = -ARENA_RADIUS; dz <= ARENA_RADIUS; dz++) {
				if (dx * dx + dz * dz > ARENA_RADIUS * ARENA_RADIUS) continue;
				BlockPos at = under.offset(dx, 0, dz);
				BlockState there = level.getBlockState(at);
				if (!there.isAir() && there.getFluidState().isEmpty() && there.isCollisionShapeFullBlock(level, at) && level.getBlockEntity(at) == null)
					level.setBlock(at, floor.defaultBlockState(), Block.UPDATE_CLIENTS);
			}
		VoidWorld.State data = VoidWorld.State.get(level.getServer());
		if (data.parts.containsKey(race.name())) return;
		for (int r = 3; r <= 5; r++) // the part: on the floor beside the hero, where there is room
			for (int i = 0; i < 8; i++) {
				double a = Math.PI * 2 * i / 8;
				BlockPos at = boss.offset((int) Math.round(Math.cos(a) * r), 0, (int) Math.round(Math.sin(a) * r));
				if (level.getBlockState(at).isAir() && level.getBlockState(at.above()).isAir() && level.getBlockState(at.below()).is(floor)) {
					level.setBlock(at, Artifact.PARTS.get(race).defaultBlockState(), Block.UPDATE_ALL);
					data.parts.put(race.name(), at.immutable());
					data.setDirty();
					return;
				}
			}
	}

	/** Every tick of a hero: Amun's motes round it while he holds it; it never leaves its arena. */
	public static void tick(StarUnit hero, ServerLevel level) {
		if (hero.getTags().contains(VoidArmy.CLONE)) { // a hero of the artifact's scene left behind (the world saved mid-scene)
			if (hero.tickCount > 1600) hero.discard();
			return;
		}
		if (!freed(hero) && hero.tickCount % 3 == 0 && hero.deathTime == 0) {
			double w = hero.getBbWidth() * 0.7, h = hero.getBbHeight();
			level.sendParticles(BLACK, hero.getX(), hero.getY() + h * 0.5, hero.getZ(), 4, w, h * 0.4, w, 0.02);
			level.sendParticles(RED, hero.getX(), hero.getY() + h * 0.5, hero.getZ(), 3, w, h * 0.4, w, 0.02);
			if (hero.tickCount % 12 == 0) level.sendParticles(ParticleTypes.SMOKE, hero.getX(), hero.getY() + h, hero.getZ(), 3, w / 2, 0.2, w / 2, 0.01);
		}
		BlockPos home = hero.nest();
		if (home == null || hero.isPassenger() || hero.pilot() != null) return;
		double dx = hero.getX() - (home.getX() + 0.5), dz = hero.getZ() - (home.getZ() + 0.5);
		boolean guard = hero.kind.guardian() || hero.kind.dimensionHero(); // a base's hero keeps near it; a ship's stays on its arena
		if (freed(hero) && hero.owner().isPresent()) return; // a freed hero goes where its player sends it
		int stay = guard ? GUARD_RADIUS : STAY_RADIUS;
		boolean out = dx * dx + dz * dz > stay * stay, fell = !guard && hero.getY() < home.getY() - 1.5;
		if (out || fell) { // back onto the arena: a hero does not leave it, nor fall off
			Vec3 back = fell ? Vec3.atBottomCenterOf(home) : new Vec3(home.getX() + 0.5 + dx * 0.7, hero.getY(), home.getZ() + 0.5 + dz * 0.7);
			hero.teleportTo(back.x, Math.max(back.y, home.getY()), back.z);
			hero.setDeltaMovement(Vec3.ZERO);
			hero.getNavigation().stop();
			hero.fallDistance = 0;
		}
	}

	// ---- beaten ----

	/**
	 * A hero would die: while Amun holds it, it is freed instead. A freed hero dies (owner 17.10) and comes back at its base
	 * {@link #RESPAWN_TICKS} later ({@link #respawn}); false then.
	 */
	static boolean beaten(StarUnit hero, ServerLevel level, DamageSource source) {
		if (freed(hero) && level.getGameTime() - freedAt(hero) < MERCY_TICKS) { // owner 19.10: blows still flying as the fight ends kill no one
			hero.setHealth(Math.max(1, hero.getMaxHealth() * LEFT));
			return true;
		}
		if (freed(hero)) {
			fell(hero, level);
			return false;
		}
		ServerPlayer player = victor(hero, level, source);
		hero.setHealth(Math.max(1, hero.getMaxHealth() * LEFT));
		free(hero, level, player);
		return true;
	}

	/** Who beat the hero: the player who struck, the owner of the unit that struck, else the nearest player. */
	static @Nullable ServerPlayer victor(StarUnit hero, ServerLevel level, @Nullable DamageSource source) {
		if (source != null && source.getEntity() instanceof ServerPlayer player) return player;
		if (source != null && source.getEntity() instanceof StarUnit unit && unit.owner().isPresent()
			&& level.getServer().getPlayerList().getPlayer(unit.owner().get()) instanceof ServerPlayer owner) return owner;
		Player near = level.getNearestPlayer(hero, 96);
		return near instanceof ServerPlayer player ? player : null;
	}

	/** Amun's hold breaks: the hero is the player's, with its base and army; it says so; its part may be taken. */
	public static void free(StarUnit hero, ServerLevel level, @Nullable ServerPlayer player) {
		hero.addTag(FREED);
		hero.getTags().removeIf(tag -> tag.startsWith(FREED_AT));
		hero.addTag(FREED_AT + level.getGameTime());
		if (hero.kind.guardian()) dev.starmine.ability.GuardianFights.end(hero, level); // owner 19.10: its monoliths, mines, turrets and Odin go
		for (String phase : List.of("starmine_phase2", "starmine_phase3")) hero.removeTag(phase);
		hero.setTarget(null);
		hero.command(StarUnit.Order.HOLD, null);
		Heroes.gone(hero);
		VoidWorld.State data = VoidWorld.State.get(level.getServer());
		// a ship hero by its race (its key part), a guardian and the other worlds' heroes by name
		data.freed.add(hero.kind.guardian() || hero.kind.dimensionHero() ? hero.kind.name() : hero.kind.race.name());
		data.setDirty();
		level.sendParticles(ParticleTypes.FLASH, hero.getX(), hero.getY() + hero.getBbHeight() / 2, hero.getZ(), 1, 0, 0, 0, 0);
		level.sendParticles(ParticleTypes.END_ROD, hero.getX(), hero.getY() + hero.getBbHeight() / 2, hero.getZ(), 60, 1, 1, 1, 0.15);
		level.playSound(null, hero.blockPosition(), SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 2.0f, 0.8f);
		for (ItemStack loot : Heroes.reward(hero)) level.addFreshEntity(new ItemEntity(level, hero.getX(), hero.getY() + 0.5, hero.getZ(), loot));
		if (hero.kind.dimensionHero() // owner 17.10: the Nether's and the End's heroes carry their world's part of the key
			|| !hero.kind.guardian() && !data.parts.containsKey(hero.kind.race.name())) // a ship built before the parts stood on it: the part from the hero's hand
			level.addFreshEntity(new ItemEntity(level, hero.getX(), hero.getY() + 0.5, hero.getZ(), new ItemStack(Artifact.part(hero.kind.race))));
		List<ServerPlayer> audience = level.players().stream().filter(p -> p.distanceTo(hero) <= Heroes.BAR_RANGE * 2).toList();
		for (ServerPlayer near : audience) dev.starmine.guide.Goals.heroFreed(near, hero.kind);
		if (hero.kind.guardian()) RaceBases.shipAfterGuardian(level, hero.kind.race); // owner 17.10: its race's ship comes to the stratosphere now
		if (player == null) return;
		hero.setOwner(player.getUUID());
		HiveSavedData hives = HiveSavedData.get(level.getServer());
		Hive base = hives.get(hero.hiveId());
		convert(hives, base, player.getUUID());
		String who = hero.kind.id;
		List<Cutscene.Beat> beats = new java.util.ArrayList<>(List.of(
			Cutscene.Beat.say(who, "cutscene.starmine." + who + ".freed1", 70),
			Cutscene.Beat.say(who, "cutscene.starmine." + who + ".freed2", 80),
			Cutscene.Beat.say(who, "cutscene.starmine." + who + ".freed3", 90)));
		if (hero.kind.guardian()) // the heroic transport comes, to carry the player to the race's ship
			beats.add(Cutscene.Beat.act(0, () -> HeroicTransport.summon(level, hero, player)));
		Cutscene.play(level, audience.isEmpty() ? List.of(player) : audience, beats, () -> {
		});
	}

	/** Owner 17.10: a fallen freed hero comes back at its base after 30 minutes of game time. */
	public static final int RESPAWN_TICKS = 30 * 60 * 20;

	/** A freed hero fell: it is remembered, to come back at its base ({@link #respawn}). */
	static void fell(StarUnit hero, ServerLevel level) {
		UUID owner = hero.owner().orElse(null);
		if (owner == null) return;
		Hive base = HiveSavedData.get(level.getServer()).get(hero.hiveId());
		BlockPos home = base != null ? base.hatchery : hero.nest() != null ? hero.nest() : hero.blockPosition();
		String dimension = base != null ? base.dimension : level.dimension().location().toString();
		VoidWorld.State data = VoidWorld.State.get(level.getServer());
		data.fallen.add(new VoidWorld.State.Fallen(hero.kind.name(), owner, dimension, home.immutable(), level.getGameTime() + RESPAWN_TICKS));
		data.setDirty();
		ServerPlayer player = level.getServer().getPlayerList().getPlayer(owner);
		if (player != null) player.displayClientMessage(Component.translatable("message.starmine.hero_fell", hero.getDisplayName(), RESPAWN_TICKS / 1200), false);
	}

	/** Each second: fallen heroes whose time has come stand again at their base, freed and their player's. */
	static void respawn(MinecraftServer server) {
		if (server.getTickCount() % 20 != 3) return;
		VoidWorld.State data = VoidWorld.State.get(server);
		if (data.fallen.isEmpty()) return;
		long now = server.overworld().getGameTime();
		for (java.util.Iterator<VoidWorld.State.Fallen> it = data.fallen.iterator(); it.hasNext(); ) {
			VoidWorld.State.Fallen f = it.next();
			if (f.due() > now) continue;
			ServerLevel level = null;
			for (ServerLevel each : server.getAllLevels()) if (each.dimension().location().toString().equals(f.dimension())) level = each;
			if (level == null) level = server.overworld();
			if (!level.isLoaded(f.home())) continue; // waits till its base is loaded
			UnitKind kind;
			try {
				kind = UnitKind.valueOf(f.kind());
			} catch (IllegalArgumentException e) {
				it.remove();
				continue;
			}
			it.remove();
			data.setDirty();
			StarUnit hero = StarMine.type(kind).create(level);
			if (hero == null) continue;
			Vec3 at = Vec3.atBottomCenterOf(f.home()).add(kind.width + 3, kind.flying ? kind.hoverHeight() : 0, 0);
			hero.moveTo(at.x, at.y, at.z, 0, 0);
			hero.addTag(FREED);
			hero.setOwner(f.owner());
			Hive base = HiveSavedData.get(server).baseOf(f.owner(), level.dimension().location().toString());
			if (base != null) hero.joinHive(base);
			level.addFreshEntity(hero);
			level.sendParticles(ParticleTypes.END_ROD, at.x, at.y + 1, at.z, 40, 0.6, 1, 0.6, 0.1);
			ServerPlayer player = server.getPlayerList().getPlayer(f.owner());
			if (player != null) player.displayClientMessage(Component.translatable("message.starmine.hero_back", hero.getDisplayName()), false);
		}
	}

	/**
	 * A wild base goes over to a player: it becomes theirs when they have none in its world; else it joins theirs —
	 * its counts, resources and research added, its units following ({@link StarUnit} adopts the new base).
	 */
	public static void convert(HiveSavedData data, @Nullable Hive hive, UUID player) {
		if (hive == null || hive.owner != null) return;
		Hive mine = data.baseOf(player, hive.dimension);
		if (mine == null) {
			hive.owner = player;
			data.setDirty();
			return;
		}
		mine.minerals += hive.minerals;
		mine.vespene += hive.vespene;
		hive.members.forEach((kind, count) -> mine.members.merge(kind, count, Integer::sum));
		hive.pending.forEach((kind, count) -> mine.pending.merge(kind, count, Integer::sum));
		mine.researched.addAll(hive.researched);
		hive.levels.forEach((key, level) -> mine.levels.merge(key, level, Math::max));
		for (int i = 0; i < data.pending.size(); i++) {
			HiveStructure.Pending p = data.pending.get(i);
			if (p.hive().equals(hive.id))
				data.pending.set(i, new HiveStructure.Pending(mine.id, p.kind(), p.pos(), p.hall(), p.brood(), p.broodCount(), p.dimension()));
		}
		data.merged.put(hive.id, mine.id);
		data.remove(hive.id);
		data.setDirty();
	}
}
