package com.teamaonn.skyislands;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Per-dimension ledger, saved with the world rather than in a global config. */
final class IslandRepairData extends SavedData {
    static final Codec<IslandRepairData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.listOf().optionalFieldOf("checked", List.of())
                    .forGetter((IslandRepairData data) -> new ArrayList<>(data.checked)),
            Codec.STRING.listOf().optionalFieldOf("pending", List.of())
                    .forGetter((IslandRepairData data) -> new ArrayList<>(data.pending))
    ).apply(instance, IslandRepairData::new));

    static final SavedDataType<IslandRepairData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("sky_islands", "automatic_repair"),
            IslandRepairData::new, CODEC, null);

    final Set<String> checked;
    final Set<String> pending;

    IslandRepairData() {
        this(List.of(), List.of());
    }

    private IslandRepairData(List<String> checked, List<String> pending) {
        this.checked = new HashSet<>(checked);
        this.pending = new HashSet<>(pending);
    }

    void begin(String key) {
        pending.add(key);
        setDirty();
    }

    void finish(String key) {
        pending.remove(key);
        checked.add(key);
        setDirty();
    }
}
