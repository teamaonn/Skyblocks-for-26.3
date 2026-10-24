# 0.1.5 — Full island restoration

- Lodestone markers restore the entire saved island template at its original origin and rotation.
- Surviving blocks and player builds no longer cause entire chunks to be skipped.
- Clear the complete template bounds, including omitted air and completely empty template chunks, before placing the original blocks in each chunk.
- Replace all block entities from template data, including fresh random loot tables on existing chests, trapped chests and barrels. Old container contents are discarded without item drops.
- New markers explicitly reset prior completion records, including sections skipped by older releases. No periodic proximity scan.
- Retain bounded tick work, loaded-chunk checks, player collision pauses, marker cancellation and saved active-request recovery.
- Restore template blocks covered by the marker even when recovering a saved request after its sections finished.

Validation: Java 25 compilation and real Minecraft 26.3 Fabric server GameTests, including three-chunk sparse restoration, existing chest/barrel reset, rotation, both marker orientations, legacy completion records, marker recovery, cancellation and player collision.
