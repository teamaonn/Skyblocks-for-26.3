package com.teamaonn.skyislands;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;

/** Per-dimension ledger, saved with the world rather than in a global config. */
final class IslandRepairData extends SavedData {
    static final Codec<IslandRepairData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.listOf().optionalFieldOf("checked", List.of())
                    .forGetter((IslandRepairData data) -> new ArrayList<>(data.checked)),
            Codec.STRING.listOf().optionalFieldOf("pending", List.of())
                    .forGetter((IslandRepairData data) -> new ArrayList<>(data.pending)),
            Codec.unboundedMap(Codec.STRING, Ritual.CODEC).optionalFieldOf("rituals", Map.of())
                    .forGetter((IslandRepairData data) -> data.rituals)
    ).apply(instance, IslandRepairData::new));

    static final SavedDataType<IslandRepairData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("sky_islands", "automatic_repair"),
            IslandRepairData::new, CODEC, null);

    final Set<String> checked;
    final Set<String> pending;
    final Map<String, Ritual> rituals;

    IslandRepairData() {
        this(List.of(), List.of(), Map.of());
    }

    private IslandRepairData(List<String> checked, List<String> pending, Map<String, Ritual> rituals) {
        this.checked = new HashSet<>(checked);
        this.pending = new HashSet<>(pending);
        this.rituals = new HashMap<>(rituals);
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

    record Ritual(BlockPos head, boolean alongX) {
        static final Codec<Ritual> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BlockPos.CODEC.fieldOf("head").forGetter(Ritual::head),
                Codec.BOOL.fieldOf("along_x").forGetter(Ritual::alongX)
        ).apply(instance, Ritual::new));

        Set<BlockPos> positions() {
            BlockPos center = head.below();
            return Set.of(head, center, center.below(),
                    alongX ? center.east() : center.south(),
                    alongX ? center.west() : center.north());
        }

        boolean loaded(ServerLevel level) {
            return positions().stream().allMatch(pos ->
                    level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null);
        }

        boolean intact(ServerLevel level) {
            if (!loaded(level)) return false;
            return positions().stream().allMatch(pos -> pos.equals(head)
                    ? level.getBlockState(pos).is(Blocks.CARVED_PUMPKIN) || level.getBlockState(pos).is(Blocks.JACK_O_LANTERN)
                    : level.getBlockState(pos).is(Blocks.LODESTONE));
        }
    }
}
