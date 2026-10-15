import sys
from pathlib import Path

import nbtlib


def patch(path: Path) -> int:
    structure = nbtlib.load(path)
    palette = structure["palette"]
    container_states = {
        i for i, state in enumerate(palette)
        if str(state.get("Name")) in {"minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel"}
    }

    patched = 0
    for block in structure["blocks"]:
        state = int(block["state"])
        if state not in container_states:
            continue

        name = str(palette[state]["Name"])
        data = block.setdefault("nbt", nbtlib.Compound())
        data.pop("Items", None)
        data.pop("LootTableSeed", None)
        data["id"] = nbtlib.String("minecraft:barrel" if name == "minecraft:barrel" else "minecraft:chest")
        data["LootTable"] = nbtlib.String("sky_islands:chests/random")
        patched += 1

    structure.save(path)
    return patched


if __name__ == "__main__":
    for raw in sys.argv[1:]:
        target = Path(raw)
        print(f"{target}: patched {patch(target)} container block entities")
