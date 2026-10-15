"""Assign the shared random loot table to chests in converted island templates.

Run after converting legacy schematics. Requires: pip install nbtlib
"""

from pathlib import Path
import json

import nbtlib
from nbtlib import tag


ROOT = Path(__file__).resolve().parents[1]
STRUCTURES = ROOT / "src/main/resources/data/sky_islands/structure"
LOOT_TABLE = "sky_islands:chests/random"
CONTAINERS = {"minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel"}


def main() -> None:
    total = 0
    for path in sorted(STRUCTURES.glob("*.nbt")):
        structure = nbtlib.load(path)
        palette = structure["palette"]
        changed = 0
        for block in structure["blocks"]:
            name = str(palette[int(block["state"])]["Name"])
            if name not in CONTAINERS:
                continue
            # Discard inventories from the old schematic format. Some use
            # pre-flattening numeric item IDs that 26.3 cannot read.
            # Keep unrelated container metadata while removing legacy contents
            # and fixed seeds. Every newly placed chest rolls independently.
            data = block.setdefault("nbt", tag.Compound())
            data.pop("Items", None)
            data.pop("LootTableSeed", None)
            data["id"] = tag.String("minecraft:barrel" if name == "minecraft:barrel" else "minecraft:chest")
            data["LootTable"] = tag.String(LOOT_TABLE)
            changed += 1
        if changed:
            structure.save()
            print(f"{path.name}: {changed} chests")
            total += changed
    print(f"Assigned loot tables to {total} chests")
    # Vanilla 26.3 chest tables, including village and trial chamber tables.
    # All entries have the same default weight; there is no dimension,
    # progression, island, or intentional empty-result filter.
    names = json.loads((ROOT / "tools/vanilla_chest_tables.json").read_text())
    table = {"type": "minecraft:chest", "pools": [{"rolls": 1, "entries": [
        {"type": "minecraft:loot_table", "value": "minecraft:" + name}
        for name in names
    ]}]}
    target = ROOT / "src/main/resources/data/sky_islands/loot_table/chests/random.json"
    target.write_text(json.dumps(table, indent=2) + "\n")
    print(f"Shared pool: {len(names)} equally weighted vanilla chest tables")


if __name__ == "__main__":
    main()
