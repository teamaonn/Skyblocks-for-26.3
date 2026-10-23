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
# Large Island Support

## Automatic completion in survival and Hardcore

Islands 5 and 19 are checked automatically when a player approaches their
naturally generated location. No commands, operator permissions, or cheats are
required. The check also works on structures already saved in an existing world.

The mod reads the island's actual saved template origin and rotation, then checks
fully loaded chunk sections within 128 horizontal blocks of a player. Entirely
missing sections are filled from the original template in small batches. It
does not force-load distant chunks, clear the island, replace surviving blocks,
refill existing containers, or respawn entities. Placement waits while a player
is standing inside the missing section. Newly restored containers retain their
random loot tables and spawners retain their template data.

Completed checks and repairs are saved per dimension with the world. Subsequent
visits do not undo mining. Interrupted repairs resume without replacing existing
blocks or containers. Each tick checks at most 2,048 blocks or places at most 128
blocks, so a large repair takes some time; approach and let the area finish.

The automatic check is intentionally conservative: a section containing any
solid surviving structure or player block is left alone. It repairs whole missing
chunk sections, not individual mined holes or partially damaged buildings. On the
first check after installing this update, a whole section that was previously
mined completely empty cannot be distinguished from a generation gap and may be
filled once. World height limits still apply.

### Repair regression tests

Run `gradle build runGameTest` with Java 25 and Gradle 9.7.1. For the headless test
server, place Cristel Lib 3.1.12 or newer in `build/run/gameTest/mods/` first.
The test mod lives in `src/gametest` and is not included in the playable JAR.
Tests cover missing blocks and chest loot, existing builds, saved repair recovery
without a chest refill, player collision protection, rotation, and revisits after
mining. GitHub Actions installs the test dependency and runs these checks.

## Random loot on every island

Every chest, trapped chest, and barrel in all 22 island templates uses the same
combined pool of vanilla Minecraft 26.3 chest loot tables. Each container rolls
independently when its loot is first generated. Overworld, Nether, End, village,
and trial chamber loot are all eligible immediately, with equal table weights
and no progression or island restrictions. There is no added empty-result entry;
the chosen vanilla table controls its own contents and rolls.

This applies to newly generated or newly placed islands. Containers already
saved in a world are not modified. Ender chests retain their normal behavior.

After converting or replacing any template, run `python tools/assign_chest_loot.py`
(requires `nbtlib`) to restore assignments and rebuild the shared loot table.

## Fixed starter placement

Use the starter command on a fresh world when you want the selected island to
be placed at the same known location instead of waiting for normal worldgen:

```
/skyislands start 19
```

The island is placed with its template origin at `(0, 120, 0)`. Island 19
then sends the player to the bed included in that template. The command also
accepts the original capitalized spelling `/SkyIslands start <number>`.
For the other islands, it sends the player to a solid surface selected from
that island's template instead of leaving them at the template origin. Island
19's four spawners are explicitly configured as two cow spawners and two
chicken spawners.

Island 5 and Island 19 use the vanilla jigsaw expansion path because their
templates are larger than the normal structure footprint. The mod also adds
repair commands for worlds where one of those islands was only partially
written:

```
/skyislands restore nearest19
/skyislands restore nearest5
```

Stand near the incomplete island first. The command scans nearby generated
structure starts, selects the nearest matching island, clears its actual saved
bounding box in small slices, and places the complete template there. Move
valuable items and animals away first. Chest and trapped-chest blocks have fresh
random loot tables.
