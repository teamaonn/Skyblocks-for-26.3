# Sky Islands for Minecraft 26.3

This Fabric mod adds 22 floating islands as native Minecraft structures. It
uses the converted structure NBT files supplied for this project and follows
the data-driven layout used by Towns and Towers.

## Runtime requirements

- Minecraft 26.3
- Fabric Loader 0.19.5 or newer
- Cristel Lib 3.1.12 or newer

## Generation

The islands are selected from one combined structure set. Generation is sparse:
the default placement is 96 chunks of spacing with 48 chunks of separation,
and structures begin at Y=192. Cristel Lib exposes the placement and enable /
disable configuration in the normal Minecraft config directory under
`config/sky_islands/`.

Each island has three biome entries. The 22 islands cover the requested 65
biomes, with one repeated biome assignment because 22 × 3 is 66.

The source NBT files came from the supplied conversion pass. That pass removed
34 legacy entities; the final structures therefore contain blocks and modern
structure data, but not those discarded pre-1.13 entities.

## Chest loot

Each ordinary or trapped chest in the included islands uses
`sky_islands:chests/random`. On first opening, it independently picks an empty
result (25% chance) or one of 21 vanilla chest loot tables from the Overworld,
Nether, and End. Existing chests already placed in a world are unchanged.
Ender chests retain their normal player-specific inventory. The source NBT
templates can be updated after a conversion with `python tools/assign_chest_loot.py`
(requires the Python `nbtlib` package).
