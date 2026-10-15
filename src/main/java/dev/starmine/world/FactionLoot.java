package dev.starmine.world;

import dev.starmine.StarMine;
import dev.starmine.entity.UnitKind;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * The races' own chests (owner 16.10: dungeons worth their danger): `data/starmine/loot_table/chests/` —
 * the terran armory (guns, rounds, shards, a stimpack module), the protoss vault (gold, amethyst, blink and
 * storm modules), the zerg cache (flesh, liquid creep, burrow and bile modules); minerals, vespene and
 * emeralds in each.
 */
public final class FactionLoot {
	public static final ResourceKey<LootTable> TERRAN = key("terran_armory"), PROTOSS = key("protoss_vault"), ZERG = key("zerg_cache");

	private FactionLoot() {
	}

	private static ResourceKey<LootTable> key(String name) {
		return ResourceKey.create(Registries.LOOT_TABLE, StarMine.id("chests/" + name));
	}

	public static ResourceKey<LootTable> of(UnitKind.Race race) {
		return switch (race) {
			case ZERG -> ZERG;
			case TERRAN -> TERRAN;
			case PROTOSS -> PROTOSS;
		};
	}
}
