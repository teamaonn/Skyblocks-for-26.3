#!/usr/bin/env python3
"""Generate the data-driven structure definitions for the 22 converted islands."""

import json
from pathlib import Path

ROOT = Path(__file__).parents[1] / "src/main/resources/data"
MOD = ROOT / "sky_islands"

# The 26.3 registry contains the normal overworld, Nether, End, and cave
# biomes. The 22 islands receive three entries each; the final slot repeats the
# first biome so the 65 requested biome entries are all covered.
BIOMES = """badlands bamboo_jungle basalt_deltas beach birch_forest cherry_grove cold_ocean crimson_forest dark_forest deep_cold_ocean deep_dark deep_frozen_ocean deep_lukewarm_ocean deep_ocean desert dripstone_caves end_barrens end_highlands end_midlands eroded_badlands flower_forest forest frozen_ocean frozen_peaks frozen_river grove ice_spikes jagged_peaks jungle lukewarm_ocean lush_caves mangrove_swamp meadow mushroom_fields nether_wastes ocean old_growth_birch_forest old_growth_pine_taiga old_growth_spruce_taiga pale_garden plains river savanna savanna_plateau small_end_islands snowy_beach snowy_plains snowy_slopes snowy_taiga soul_sand_valley sparse_jungle stony_peaks stony_shore sunflower_plains swamp taiga the_end the_void warm_ocean warped_forest windswept_forest windswept_gravelly_hills windswept_hills windswept_savanna wooded_badlands""".split()

assert len(BIOMES) == 65, len(BIOMES)


def write(path: Path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def main():
    structure_ids = [f"island_{i}" for i in range(1, 23)]
    assignments = {}
    for index, island_id in enumerate(structure_ids):
        assignments[island_id] = [BIOMES[(index * 3 + offset) % len(BIOMES)] for offset in range(3)]

        write(MOD / "worldgen/structure" / f"{island_id}.json", {
            "type": "minecraft:jigsaw",
            "biomes": f"#sky_islands:has_structure/{island_id}",
            "max_distance_from_center": 128,
            "size": 1,
            "spawn_overrides": {},
            "start_height": {"absolute": 192},
            "start_pool": f"sky_islands:{island_id}",
            "step": "surface_structures",
            "terrain_adaptation": "none",
            "use_expansion_hack": False,
        })

        write(MOD / "worldgen/template_pool" / f"{island_id}.json", {
            "name": f"sky_islands:{island_id}",
            "fallback": "minecraft:empty",
            "elements": [{
                "weight": 1,
                "element": {
                    "element_type": "minecraft:single_pool_element",
                    "projection": "rigid",
                    "location": f"sky_islands:{island_id}",
                    "processors": "minecraft:empty",
                },
            }],
        })

        write(MOD / "tags/worldgen/biome/has_structure" / f"{island_id}.json", {
            "replace": False,
            "values": [f"minecraft:{biome}" for biome in assignments[island_id]],
        })

    write(MOD / "worldgen/structure_set/islands.json", {
        "structures": [{"structure": f"sky_islands:{island_id}", "weight": 1} for island_id in structure_ids],
        "placement": {
            "type": "minecraft:random_spread",
            "salt": 19860922,
            "spacing": 96,
            "separation": 48,
        },
    })

    config_root = ROOT / "cristellib/structure_config"
    write(config_root / "sky_islands_placement.json", {
        "name": "sky_islands_structure_placement",
        "path": "<CONFIG_DIR>/sky_islands",
        "header": "Controls the spacing and separation of the Sky Islands structure set. Larger values make islands less frequent.",
        "config_type": "PLACEMENT",
        "comments": {
            "islands.spacing": "DEFAULT 96",
            "islands.separation": "DEFAULT 48",
        },
        "structure_sets": [{
            "modid": "sky_islands",
            "structure_set": ["sky_islands:islands"],
        }],
    })
    write(config_root / "sky_islands_enable_disable.json", {
        "name": "sky_islands_structure_enable_or_disable",
        "path": "<CONFIG_DIR>/sky_islands",
        "header": "Controls whether the Sky Islands structure set is enabled.",
        "config_type": "ENABLE_DISABLE",
        "comments": {"islands": "The Sky Islands structure set."},
        "structure_sets": [{
            "modid": "sky_islands",
            "structure_set": ["sky_islands:islands"],
        }],
    })

    write(MOD / "island_biomes.json", assignments)


if __name__ == "__main__":
    main()
