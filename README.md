# StarMine

StarCraft II zerg as Minecraft mobs (Fabric, Minecraft 1.21.1). First mob: the zergling — walk, attack
(three variants) and death animations, and its sounds.

StarMine ships **no Blizzard assets**. Models, textures and sounds are imported by
[Modkit](../modkit-core) from the player's own StarCraft II installation and injected into the
player's copy of the mod (`assets/starmine/**/sc2/**`).

## Layout

- `src/main` — entities, attributes, spawn eggs, sound events (common side).
- `src/client` — StarMine model loader (`.smm`, see `SkinnedModel`) and skinned renderers.
- `sc2-assets/` — git-ignored; assets produced by Modkit for dev runs (`runClient`).

## Build

```
gradlew build                      # distributable jar, without SC2 assets
gradlew build -PincludeSc2Assets   # private jar with assets from sc2-assets/ (never publish)
gradlew runClient                  # dev client with sc2-assets/
```

Health is scaled to Minecraft: 1 SC2 hit point = 10/35 health (zergling 10, hydralisk ≈26).
