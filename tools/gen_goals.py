#!/usr/bin/env python3
"""Writes the journal of goals (dev.starmine.guide.Goals): data/starmine/advancement/goals/*.json.

Every goal is an advancement with one `minecraft:impossible` criterion "done", granted by Goals.java.
Titles and descriptions are the lang keys advancements.starmine.<id>.title / .description.
"""
import json
import pathlib

OUT = pathlib.Path(__file__).resolve().parent.parent / "src/main/resources/data/starmine/advancement/goals"

# id, icon, parent (None: the root), frame
GOALS = [
    ("command_panel", "starmine:command_panel", None, "task"),
    ("own_base", "starmine:commandcenter_spawn_egg", "command_panel", "goal"),
    ("minerals", "starmine:mineral_shard", "own_base", "task"),
    ("vespene", "starmine:vespene_bucket", "minerals", "task"),
    ("possess", "starmine:module_weapon", "own_base", "goal"),
    ("find_zerg", "starmine:zerg_flesh", None, "task"),
    ("find_terran", "minecraft:gray_concrete", None, "task"),
    ("find_protoss", "minecraft:sea_lantern", None, "task"),
    ("suit", "starmine:terran_chestplate", None, "task"),
    ("module", "starmine:module_weapons_upgrade", "suit", "task"),
    ("gauss_rifle", "starmine:gauss_rifle", None, "task"),
    # owner 19.10: more goals
    ("army", "starmine:marine_spawn_egg", "own_base", "goal"),
    ("upgrade", "starmine:engineeringbay_spawn_egg", "own_base", "goal"),
    ("race_tool", "starmine:protoss_pickaxe", None, "task"),
    ("turret", "starmine:gauss_turret", "race_tool", "task"),
    ("race_boat", "starmine:terran_boat", "race_tool", "task"),
    ("terrazine", "starmine:terrazine_bucket", "vespene", "task"),
    ("guardians", "starmine:karax_spawn_egg", "sky_ship", "goal"),
    ("nether_heroes", "starmine:zagara_spawn_egg", "all_heroes", "challenge"),
    ("end_heroes", "starmine:horner_spawn_egg", "all_heroes", "challenge"),
    ("void_shard", "starmine:void_shard", None, "task"),
    ("sky_ship", "minecraft:elytra", "find_terran", "goal"),
    ("kerrigan", "starmine:zerg_chestplate", "sky_ship", "challenge"),
    ("raynor", "starmine:terran_helmet", "sky_ship", "challenge"),
    ("artanis", "starmine:protoss_chestplate", "sky_ship", "challenge"),
    ("all_heroes", "minecraft:nether_star", "artanis", "challenge"),
    ("artifact", "starmine:artifact_pedestal", "all_heroes", "goal"),
    ("void", "starmine:void_portal_frame", "artifact", "goal"),
    ("amun", "minecraft:dragon_egg", "void", "challenge"),
]


def advancement(goal_id, icon, parent, frame):
    return {
        "parent": "starmine:goals/" + (parent or "root"),
        "display": {
            "icon": {"id": icon},
            "title": {"translate": f"advancements.starmine.{goal_id}.title"},
            "description": {"translate": f"advancements.starmine.{goal_id}.description"},
            "frame": frame,
            "show_toast": True,
            "announce_to_chat": False,
        },
        "criteria": {"done": {"trigger": "minecraft:impossible"}},
    }


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    root = {
        "display": {
            "icon": {"id": "minecraft:written_book"},
            "title": {"translate": "advancements.starmine.root.title"},
            "description": {"translate": "advancements.starmine.root.description"},
            "background": "minecraft:textures/block/purple_concrete.png",
            "show_toast": False,
            "announce_to_chat": False,
        },
        "criteria": {"done": {"trigger": "minecraft:impossible"}},
    }
    (OUT / "root.json").write_text(json.dumps(root, indent=2) + "\n")
    for goal in GOALS:
        (OUT / f"{goal[0]}.json").write_text(json.dumps(advancement(*goal), indent=2) + "\n")


if __name__ == "__main__":
    main()
