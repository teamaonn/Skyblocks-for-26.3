# Sky Islands for Minecraft 26.3

This Fabric mod adds 22 floating islands as native Minecraft structures. It
uses the converted structure NBT files supplied for this project and follows
the data-driven layout used by Towns and Towers.

[Download 0.1.5 full restore](downloads/skyblocks-0.1.5-full-restore.jar)

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

## Player-triggered repairs in survival and Hardcore

There is no recurring proximity scan. Build an iron-golem-shaped T using four
lodestones (three across, one under the middle) on a naturally generated Sky
Island, then place a carved pumpkin on top of the middle block **last**. Either
horizontal orientation works; a jack o'lantern also works. No commands, operator
permissions, or cheats are needed. All 22 mod island templates are supported.

Completing the marker starts a one-time search for the island's saved origin and
rotation, then restores the original template in small batches. **This is a full
reset: player builds, mined blocks, existing containers and their contents are
overwritten. Template air clears existing blocks. Chests, trapped chests and barrels
receive fresh random loot tables; spawners and other block entities reset from the
template. Move valuables away before activating it.** Entities are not respawned.

Keep the marker intact and the island loaded. Walk around to load distant chunks.
Once restoration finishes, exactly four lodestones and the pumpkin are consumed
without drops. Placement pauses while a player is inside the section being restored.
Invalid locations keep all marker blocks. Breaking the marker cancels further work;
changes already made remain. Active requests resume after a normal restart.

Each newly built marker starts a fresh full restoration, even if an older version
marked sections checked without repairing them. Mere visits never activate it.
At most 2,048 template entries are scanned or 128 blocks placed per active tick.
No recurring proximity scan runs. World height and border limits still apply.

### Repair regression tests

Run `gradle build runGameTest` with Java 25 and Gradle 9.7.1. Put Cristel Lib 3.1.12
or newer in `build/run/gameTest/mods/` for the headless test server first. The test
mod in `src/gametest` is not included in the playable JAR. Tests cover marker
activation and consumption, no proximity activation, invalid locations, incomplete
patterns, both orientations, saved recovery, resetting existing chests/builds and template air, player
collision protection, rotation, and revisits after mining.

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
