"""Add explicit mob-spawner block entities to converted structure NBT files."""

from pathlib import Path
import nbtlib
from nbtlib import Compound, Int, List, String


ROOT = Path(__file__).resolve().parents[1]
ISLAND_19 = ROOT / "src/main/resources/data/sky_islands/structure/island_19.nbt"


def spawner(mob: str) -> Compound:
    # Minecraft 1.21+ uses the nested entity/data form. The older
    # SpawnData:{id:"Cow"} form can make the block look like a spawner while
    # silently falling back to the default behavior when the structure loads.
    entity = Compound({"id": String(f"minecraft:{mob.lower()}")})
    return Compound({
        "Delay": Int(20),
        "MaxNearbyEntities": Int(6),
        "MaxSpawnDelay": Int(800),
        "MinSpawnDelay": Int(200),
        "RequiredPlayerRange": Int(16),
        "SpawnCount": Int(4),
        "SpawnData": Compound({"entity": entity}),
        "SpawnPotentials": List[Compound]([
            Compound({
                "weight": Int(1),
                "data": Compound({"entity": Compound({"id": String(f"minecraft:{mob.lower()}")})}),
            })
        ]),
        "SpawnRange": Int(4),
        "id": String("minecraft:mob_spawner"),
    })


def main() -> None:
    root = nbtlib.load(ISLAND_19)
    spawner_state = next(
        i for i, palette in enumerate(root["palette"])
        if str(palette.get("Name", "")).lower() == "minecraft:spawner"
    )
    blocks = [b for b in root["blocks"] if int(b["state"]) == spawner_state]
    if len(blocks) != 4:
        raise RuntimeError(f"Expected exactly four Island 19 spawners, found {len(blocks)}")

    for index, block in enumerate(blocks):
        block["nbt"] = spawner("Cow" if index < 2 else "Chicken")

    root.save(ISLAND_19)
    print("Configured Island 19 spawners: 2 Cow, 2 Chicken")


if __name__ == "__main__":
    main()
