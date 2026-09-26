"""Assign the shared random loot table to chests in converted island templates.

Run after converting legacy schematics. Requires: pip install nbtlib
"""

from pathlib import Path

import nbtlib
from nbtlib import tag


ROOT = Path(__file__).resolve().parents[1]
STRUCTURES = ROOT / "src/main/resources/data/sky_islands/structure"
LOOT_TABLE = "sky_islands:chests/random"


def main() -> None:
    total = 0
    for path in sorted(STRUCTURES.glob("*.nbt")):
        structure = nbtlib.load(path)
        palette = structure["palette"]
        changed = 0
        for block in structure["blocks"]:
            name = str(palette[int(block["state"])]["Name"])
            if name not in {"minecraft:chest", "minecraft:trapped_chest"}:
                continue
            # Discard inventories from the old schematic format. Some use
            # pre-flattening numeric item IDs that 26.3 cannot read.
            block["nbt"] = tag.Compound({
                "id": tag.String("minecraft:chest"),
                "LootTable": tag.String(LOOT_TABLE),
            })
            changed += 1
        if changed:
            structure.save()
            print(f"{path.name}: {changed} chests")
            total += changed
    print(f"Assigned loot tables to {total} chests")


if __name__ == "__main__":
    main()
