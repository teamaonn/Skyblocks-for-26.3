#!/usr/bin/env python3
"""Convert classic MCEdit/WorldEdit .schematic files to vanilla structure NBT.

This intentionally keeps the converter self-contained so the source schematics
can be converted without requiring WorldEdit at runtime.
"""

from __future__ import annotations

import gzip
import io
import json
import re
import struct
import sys
import zipfile
from collections import Counter
from pathlib import Path


class NbtReader:
    def __init__(self, data: bytes):
        try:
            data = gzip.decompress(data)
        except OSError:
            pass
        self.f = io.BytesIO(data)

    def read_string(self) -> str:
        raw = self.f.read(2)
        if len(raw) != 2:
            raise ValueError("truncated NBT string length")
        length = struct.unpack(">H", raw)[0]
        raw = self.f.read(length)
        if len(raw) != length:
            raise ValueError("truncated NBT string")
        return raw.decode("utf-8", "replace")

    def payload(self, tag_type: int):
        if tag_type == 1:
            return struct.unpack(">b", self.f.read(1))[0]
        if tag_type == 2:
            return struct.unpack(">h", self.f.read(2))[0]
        if tag_type == 3:
            return struct.unpack(">i", self.f.read(4))[0]
        if tag_type == 4:
            return struct.unpack(">q", self.f.read(8))[0]
        if tag_type == 5:
            return struct.unpack(">f", self.f.read(4))[0]
        if tag_type == 6:
            return struct.unpack(">d", self.f.read(8))[0]
        if tag_type == 7:
            length = struct.unpack(">i", self.f.read(4))[0]
            return self.f.read(max(0, length))
        if tag_type == 8:
            return self.read_string()
        if tag_type == 9:
            element_type = self.f.read(1)[0]
            length = struct.unpack(">i", self.f.read(4))[0]
            return [self.payload(element_type) for _ in range(max(0, length))]
        if tag_type == 10:
            result = {}
            while True:
                child_type = self.f.read(1)[0]
                if child_type == 0:
                    return result
                child_name = self.read_string()
                result[child_name] = self.payload(child_type)
        if tag_type == 11:
            length = struct.unpack(">i", self.f.read(4))[0]
            return [struct.unpack(">i", self.f.read(4))[0] for _ in range(max(0, length))]
        if tag_type == 12:
            length = struct.unpack(">i", self.f.read(4))[0]
            return [struct.unpack(">q", self.f.read(8))[0] for _ in range(max(0, length))]
        raise ValueError(f"unsupported NBT tag type {tag_type}")

    def root(self) -> dict:
        tag_type = self.f.read(1)[0]
        if tag_type != 10:
            raise ValueError("schematic root is not a compound")
        self.read_string()
        return self.payload(tag_type)


def read_nbt(data: bytes) -> dict:
    return NbtReader(data).root()


class NbtWriter:
    def __init__(self):
        self.out = io.BytesIO()

    def string(self, value: str):
        raw = value.encode("utf-8")
        self.out.write(struct.pack(">H", len(raw)))
        self.out.write(raw)

    def named(self, tag_type: int, name: str, value):
        self.out.write(bytes([tag_type]))
        self.string(name)
        self.payload(tag_type, value)

    def payload(self, tag_type: int, value):
        if tag_type == 1:
            self.out.write(struct.pack(">b", value))
        elif tag_type == 2:
            self.out.write(struct.pack(">h", value))
        elif tag_type == 3:
            self.out.write(struct.pack(">i", value))
        elif tag_type == 4:
            self.out.write(struct.pack(">q", value))
        elif tag_type == 5:
            self.out.write(struct.pack(">f", value))
        elif tag_type == 6:
            self.out.write(struct.pack(">d", value))
        elif tag_type == 7:
            self.out.write(struct.pack(">i", len(value)))
            self.out.write(value)
        elif tag_type == 8:
            self.string(value)
        elif tag_type == 9:
            element_type, values = value
            self.out.write(bytes([element_type]))
            self.out.write(struct.pack(">i", len(values)))
            for item in values:
                self.payload(element_type, item)
        elif tag_type == 10:
            for child_type, name, child in value:
                self.named(child_type, name, child)
            self.out.write(b"\x00")
        elif tag_type == 11:
            self.out.write(struct.pack(">i", len(value)))
            for item in value:
                self.out.write(struct.pack(">i", item))
        elif tag_type == 12:
            self.out.write(struct.pack(">i", len(value)))
            for item in value:
                self.out.write(struct.pack(">q", item))
        else:
            raise ValueError(f"unsupported NBT tag type {tag_type}")

    def compound(self, values: list[tuple[int, str, object]]) -> bytes:
        self.out.write(b"\x0a")
        self.string("")
        self.payload(10, values)
        return gzip.compress(self.out.getvalue(), compresslevel=6)


def safe_id(name: str) -> str:
    name = Path(name).stem.lower()
    name = re.sub(r"[^a-z0-9_.-]+", "_", name)
    return name.strip("._-") or "island"


def state(name: str, properties: dict[str, str] | None = None):
    fields = [(8, "Name", name)]
    if properties:
        fields.append((10, "Properties", [(8, k, v) for k, v in properties.items()]))
    return fields


COLORS = ["white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"]
WOOD = ["oak", "spruce", "birch", "jungle", "acacia", "dark_oak"]


def legacy_block(block_id: int, data: int, mapping: dict[str, int]) -> tuple[str, dict[str, str]]:
    # Prefer an exact embedded Schematica mapping when it exists.
    for modern, mapped_id in mapping.items():
        if mapped_id == block_id:
            name = modern.removeprefix("minecraft:")
            aliases = {
                "grass": "grass_block",
                "wooden_slab": "oak_slab",
                "wall_sign": "oak_wall_sign",
                "standing_sign": "oak_sign",
                "tallgrass": "short_grass",
            }
            name = aliases.get(name, name)
            if name == "log":
                name = WOOD[(data & 3) % len(WOOD)] + "_log"
            elif name == "leaves":
                name = WOOD[(data & 3) % len(WOOD)] + "_leaves"
            elif name == "red_flower":
                name = {0: "poppy", 1: "blue_orchid", 2: "allium", 3: "azure_bluet", 4: "red_tulip", 5: "orange_tulip", 6: "white_tulip", 7: "pink_tulip", 8: "oxeye_daisy"}.get(data, "poppy")
            elif name == "tallgrass":
                name = {0: "dead_bush", 1: "short_grass", 2: "fern"}.get(data, "short_grass")
            elif name in ("wool", "stained_hardened_clay", "stained_glass", "stained_glass_pane", "carpet"):
                name = f"{COLORS[data & 15]}_{name.replace('stained_hardened_clay', 'terracotta')}"
            return "minecraft:" + name, {}

    # Classic numeric ID fallback.
    simple = {
        0: "air", 1: "stone", 2: "grass_block", 3: "dirt", 4: "cobblestone",
        7: "bedrock", 8: "water", 9: "water", 10: "lava", 11: "lava",
        12: "sand", 13: "gravel", 14: "gold_ore", 15: "iron_ore", 16: "coal_ore",
        20: "glass", 24: "sandstone", 41: "gold_block", 42: "iron_block",
        45: "bricks", 46: "tnt", 47: "bookshelf", 48: "mossy_cobblestone",
        49: "obsidian", 50: "torch", 56: "diamond_ore", 57: "diamond_block",
        58: "crafting_table", 59: "wheat", 60: "farmland", 61: "furnace",
        62: "furnace", 78: "snow", 79: "ice", 80: "snow_block", 81: "cactus",
        82: "clay", 83: "sugar_cane", 85: "oak_fence", 86: "pumpkin",
        87: "netherrack", 88: "soul_sand", 89: "glowstone", 91: "jack_o_lantern",
        96: "oak_trapdoor", 98: "stone_bricks", 102: "glass_pane", 103: "melon",
        106: "vine", 107: "oak_fence_gate", 112: "nether_bricks", 113: "nether_brick_fence",
        115: "nether_wart", 116: "enchanting_table", 121: "end_stone", 125: "oak_log",
        129: "emerald_ore", 130: "ender_chest", 133: "emerald_block", 139: "cobblestone_wall",
        141: "carrots", 142: "potatoes", 145: "anvil", 146: "trapped_chest",
        23: "dispenser", 26: "bed", 29: "sticky_piston", 33: "piston", 34: "piston_head",
        37: "dandelion", 39: "brown_mushroom", 40: "red_mushroom", 52: "spawner",
        54: "chest", 55: "redstone_wire", 63: "oak_sign", 64: "oak_door", 65: "ladder",
        68: "oak_wall_sign", 69: "lever", 71: "iron_door", 72: "oak_pressure_plate",
        73: "redstone_ore", 75: "redstone_torch", 76: "redstone_torch", 84: "jukebox",
        90: "nether_portal", 93: "repeater", 94: "comparator", 95: "white_stained_glass",
        97: "infested_stone", 101: "iron_bars", 104: "pumpkin_stem", 105: "melon_stem",
        110: "mycelium", 117: "brewing_stand", 118: "cauldron", 122: "dragon_egg",
        123: "redstone_lamp", 124: "redstone_lamp", 127: "cocoa", 137: "command_block",
        140: "flower_pot", 143: "oak_button", 144: "skeleton_skull", 149: "comparator",
        152: "redstone_block", 165: "slime_block", 166: "barrier", 169: "sea_lantern",
        177: "oak_wall_banner", 183: "spruce_fence_gate", 184: "birch_fence_gate",
        185: "jungle_fence_gate", 186: "dark_oak_fence_gate", 187: "acacia_fence_gate",
        193: "spruce_door", 194: "birch_door", 195: "jungle_door", 196: "acacia_door",
        154: "hopper", 155: "quartz_block", 158: "dropper", 159: "terracotta",
        160: "white_stained_glass_pane", 161: "acacia_leaves", 162: "acacia_log",
        171: "white_carpet", 172: "terracotta", 173: "coal_block", 174: "packed_ice",
    }
    if block_id == 5:
        return "minecraft:" + WOOD[data & 7] + "_planks", {}
    if block_id in (17, 162):
        return "minecraft:" + WOOD[data & 3] + "_log", {}
    if block_id in (18, 161):
        return "minecraft:" + WOOD[data & 3] + "_leaves", {}
    if block_id in (35, 159, 171):
        suffix = {35: "wool", 159: "terracotta", 171: "carpet"}[block_id]
        return f"minecraft:{COLORS[data & 15]}_{suffix}", {}
    if block_id == 38:
        return "minecraft:" + {0: "poppy", 1: "blue_orchid", 2: "allium", 3: "azure_bluet", 4: "red_tulip", 5: "orange_tulip", 6: "white_tulip", 7: "pink_tulip", 8: "oxeye_daisy"}.get(data, "poppy"), {}
    if block_id == 31:
        return "minecraft:" + {0: "dead_bush", 1: "short_grass", 2: "fern"}.get(data, "short_grass"), {}
    if block_id == 44:
        return "minecraft:" + ("stone_slab" if data & 7 == 0 else "sandstone_slab"), {"type": "top" if data & 8 else "bottom"}
    if block_id == 126:
        return "minecraft:" + WOOD[data & 7] + "_slab", {"type": "top" if data & 8 else "bottom"}
    if block_id in (43,):
        return "minecraft:stone", {}
    if block_id in (53, 67, 108, 109, 114, 128, 134, 135, 136, 156, 163, 164, 180):
        stairs = {53: "oak", 67: "cobblestone", 108: "brick", 109: "stone_brick", 114: "nether_brick", 128: "sandstone", 134: "spruce", 135: "birch", 136: "jungle", 156: "quartz", 163: "acacia", 164: "dark_oak", 180: "red_sandstone"}
        return "minecraft:" + stairs[block_id] + "_stairs", {}
    if block_id in (188, 189, 190, 191, 192, 85):
        fences = {85: "oak", 188: "spruce", 189: "birch", 190: "jungle", 191: "dark_oak", 192: "acacia"}
        return "minecraft:" + fences[block_id] + "_fence", {}
    return "minecraft:" + simple.get(block_id, "air"), {}


def normalize_block_entity(tag: dict) -> dict:
    result = dict(tag)
    old_id = result.get("id")
    ids = {
        "Chest": "minecraft:chest", "Trap": "minecraft:chest", "Sign": "minecraft:sign",
        "Furnace": "minecraft:furnace", "Hopper": "minecraft:hopper", "Dropper": "minecraft:dropper",
        "Dispenser": "minecraft:dispenser", "MobSpawner": "minecraft:mob_spawner", "Beacon": "minecraft:beacon",
        "Control": "minecraft:command_block", "RecordPlayer": "minecraft:jukebox", "Skull": "minecraft:skull",
    }
    if old_id in ids:
        result["id"] = ids[old_id]
    return result


def convert(data: dict) -> tuple[bytes, dict]:
    width, height, length = int(data["Width"]), int(data["Height"]), int(data["Length"])
    blocks = bytes(data.get("Blocks", b""))
    metadata = bytes(data.get("Data", b""))
    add_blocks = bytes(data.get("AddBlocks", b""))
    mapping = data.get("SchematicaMapping", {}) or {}
    if not isinstance(mapping, dict):
        mapping = {}
    palette = []
    palette_index = {}
    out_blocks = []
    unknown = Counter()

    def palette_for(name, props):
        key = (name, tuple(sorted(props.items())))
        if key not in palette_index:
            palette_index[key] = len(palette)
            palette.append(state(name, props))
        return palette_index[key]

    tile_by_pos = {}
    for tile in data.get("TileEntities", []) or []:
        if not isinstance(tile, dict):
            continue
        try:
            pos = (int(tile.get("x", 0)), int(tile.get("y", 0)), int(tile.get("z", 0)))
            tile_by_pos[pos] = normalize_block_entity(tile)
        except (TypeError, ValueError):
            continue

    for y in range(height):
        for z in range(length):
            for x in range(width):
                index = x + z * width + y * width * length
                block_id = blocks[index] if index < len(blocks) else 0
                if add_blocks:
                    half = index >> 1
                    if half < len(add_blocks):
                        high = (add_blocks[half] >> (4 if index & 1 else 0)) & 0xF
                        block_id |= high << 8
                block_data = metadata[index] & 0xF if index < len(metadata) else 0
                name, props = legacy_block(block_id, block_data, mapping)
                if name == "minecraft:air":
                    # Unknown IDs are tracked separately from intentional air.
                    if block_id != 0:
                        unknown[block_id] += 1
                if name == "minecraft:air":
                    continue
                entry = [(11, "pos", [x, y, z]), (3, "state", palette_for(name, props))]
                tile = tile_by_pos.get((x, y, z))
                if tile:
                    entry.append((10, "nbt", [(8, k, v) for k, v in tile.items() if isinstance(v, str)]))
                out_blocks.append(entry)

    entities = []
    for entity in data.get("Entities", []) or []:
        if isinstance(entity, dict):
            entities.append([(10, key, [(8, k, v) for k, v in value.items() if isinstance(v, str)]) if isinstance(value, dict) else (8, key, value) for key, value in entity.items() if isinstance(value, (str, dict))])

    root = [
        (3, "DataVersion", 0),
        (9, "size", (3, [width, height, length])),
        (9, "palette", (10, palette)),
        (9, "blocks", (10, out_blocks)),
        (9, "entities", (10, entities)),
    ]
    return NbtWriter().compound(root), {"size": [width, height, length], "palette": len(palette), "blocks": len(out_blocks), "unknown": dict(unknown)}


def main():
    if len(sys.argv) != 3:
        raise SystemExit("usage: convert_schematics.py Skyblocks.zip output-directory")
    archive = Path(sys.argv[1])
    output = Path(sys.argv[2])
    output.mkdir(parents=True, exist_ok=True)
    report = {}
    with zipfile.ZipFile(archive) as source:
        for member in source.namelist():
            if not member.lower().endswith(".schematic"):
                continue
            if Path(member).stem == "JoshuaIsland1":
                continue
            source_data = read_nbt(source.read(member))
            converted, details = convert(source_data)
            name = safe_id(member)
            (output / f"{name}.nbt").write_bytes(converted)
            report[name] = details
            print(f"{name}: {details['size'][0]}x{details['size'][1]}x{details['size'][2]} palette={details['palette']} unknown={details['unknown']}")
    (output / "conversion-report.json").write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
