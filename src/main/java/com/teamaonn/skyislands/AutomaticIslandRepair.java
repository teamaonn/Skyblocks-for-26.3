package com.teamaonn.skyislands;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Completes missing chunk-sized sections without replaying a whole structure. */
final class AutomaticIslandRepair {
    private static final Logger LOGGER = LoggerFactory.getLogger("sky_islands/automatic_repair");
    // Large templates affected by vanilla's structure footprint limit.
    private static final Set<String> LARGE_ISLANDS = Set.of("island_5", "island_19");
    private static final int DISCOVERY_RADIUS = 12;
    private static final int APPROACH_DISTANCE = 128;
    private static final int SCAN_BUDGET = 2048;
    private static final int WRITE_BUDGET = 128;
    private static final Map<ServerLevel, LevelWork> LEVELS = new HashMap<>();

    static void register() {
        ServerTickEvents.END_LEVEL_TICK.register(AutomaticIslandRepair::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> LEVELS.clear());
    }

    private static void tick(ServerLevel level) {
        if (level.players().isEmpty()) {
            return;
        }
        LevelWork work = LEVELS.computeIfAbsent(level, ignored -> new LevelWork());
        if (++work.ticks % 40 == 0) {
            discover(level, work);
        }
        // One bounded unit of scan/placement work per world tick. Waiting for an
        // unloaded chunk never prevents another nearby island from progressing.
        Iterator<Map.Entry<String, RepairTask>> tasks = work.tasks.entrySet().iterator();
        while (tasks.hasNext()) {
            var entry = tasks.next();
            RepairTask task = entry.getValue();
            if (task.index == task.blocks.size() && task.sections.isEmpty()) {
                work.completed.add(entry.getKey());
                tasks.remove();
                continue;
            }
            if (task.step(level)) {
                break;
            }
        }
    }

    private static void discover(ServerLevel level, LevelWork work) {
        Set<Long> visited = new HashSet<>();
        for (var player : level.players()) {
            ChunkPos center = player.chunkPosition();
            for (int dx = -DISCOVERY_RADIUS; dx <= DISCOVERY_RADIUS; dx++) {
                for (int dz = -DISCOVERY_RADIUS; dz <= DISCOVERY_RADIUS; dz++) {
                    int x = center.x() + dx;
                    int z = center.z() + dz;
                    if (!visited.add(ChunkPos.pack(x, z))) {
                        continue;
                    }
                    // Never generate chunks or grant force-load tickets merely
                    // to look for an island. Full chunks have finished worldgen.
                    LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
                    if (chunk == null) {
                        continue;
                    }
                    for (StructureStart start : chunk.getAllStarts().values()) {
                        if (!start.isValid()) {
                            continue;
                        }
                        for (var piece : start.getPieces()) {
                            if (!(piece instanceof PoolElementStructurePiece pool)
                                    || !(pool.getElement() instanceof SinglePoolElement single)) {
                                continue;
                            }
                            Identifier id = single.getTemplateLocation();
                            if (id == null || !id.getNamespace().equals("sky_islands")
                                    || !LARGE_ISLANDS.contains(id.getPath())) {
                                continue;
                            }
                            String key = id + "/" + pool.getPosition().asLong() + "/" + pool.getRotation();
                            if (work.tasks.containsKey(key) || work.completed.contains(key)) {
                                continue;
                            }
                            level.getStructureTemplateManager().get(id).ifPresent(template -> {
                                work.tasks.put(key, new RepairTask(level, key, template,
                                        pool.getPosition(), pool.getRotation()));
                            });
                        }
                    }
                }
            }
        }
    }

    private static final class LevelWork {
        int ticks;
        final Map<String, RepairTask> tasks = new LinkedHashMap<>();
        final Set<String> completed = new HashSet<>();
    }

    record ExpectedBlock(BlockPos position, BlockState state, CompoundTag nbt) { }

    static final class RepairTask {
        final String key;
        final BlockPos origin;
        final Rotation rotation;
        final IslandRepairData ledger;
        final ListTag blocks;
        final List<BlockState> palette = new ArrayList<>();
        final Map<Long, SectionWork> sections = new LinkedHashMap<>();
        int index;

        RepairTask(ServerLevel level, String key, StructureTemplate template, BlockPos origin, Rotation rotation) {
            this.key = key;
            this.origin = origin.immutable();
            this.rotation = rotation;
            ledger = level.getDataStorage().computeIfAbsent(IslandRepairData.TYPE);
            CompoundTag data = template.save(new CompoundTag());
            var blockRegistry = level.registryAccess().lookupOrThrow(Registries.BLOCK);
            for (var entry : data.getListOrEmpty("palette")) {
                palette.add(NbtUtils.readBlockState(blockRegistry, (CompoundTag) entry).rotate(rotation));
            }
            blocks = data.getListOrEmpty("blocks");
        }

        boolean step(ServerLevel level) {
            if (index < blocks.size()) {
                int end = Math.min(index + SCAN_BUDGET, blocks.size());
                while (index < end) {
                    CompoundTag data = blocks.getCompoundOrEmpty(index++);
                    BlockState state = palette.get(data.getIntOr("state", 0));
                    if (state.isAir() || state.is(Blocks.STRUCTURE_VOID)) {
                        continue;
                    }
                    ListTag p = data.getListOrEmpty("pos");
                    BlockPos local = new BlockPos(p.getIntOr(0, 0), p.getIntOr(1, 0), p.getIntOr(2, 0));
                    BlockPos pos = StructureTemplate.transform(local, Mirror.NONE, rotation, BlockPos.ZERO).offset(origin);
                    if (level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos)) {
                        continue;
                    }
                    long chunk = ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4);
                    String sectionKey = key + "/" + chunk;
                    if (ledger.checked.contains(sectionKey)) {
                        continue;
                    }
                    SectionWork section = sections.computeIfAbsent(chunk, ignored -> new SectionWork(sectionKey));
                    section.add(new ExpectedBlock(pos, state, data.getCompound("nbt").orElse(null)));
                }
                if (index == blocks.size()) {
                    sections.values().forEach(SectionWork::prepare);
                }
                return true;
            }
            Iterator<SectionWork> iterator = sections.values().iterator();
            while (iterator.hasNext()) {
                SectionWork section = iterator.next();
                if (section.finished) {
                    iterator.remove();
                    continue;
                }
                if (section.step(level, ledger)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static boolean replaceable(BlockState state) {
        // Only air or plain flowing liquids. Never treat plants, blocks with a
        // replaceable material, or waterlogged player builds as disposable.
        return state.isAir() || state.is(Blocks.WATER) || state.is(Blocks.LAVA);
    }

    static final class SectionWork {
        final String key;
        final List<ExpectedBlock> expected = new ArrayList<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        int scanX, scanY, scanZ, writeIndex;
        boolean preflightDone, finished;

        SectionWork(String key) {
            this.key = key;
        }

        void add(ExpectedBlock block) {
            expected.add(block);
            BlockPos pos = block.position();
            minX = Math.min(minX, pos.getX()); maxX = Math.max(maxX, pos.getX());
            minY = Math.min(minY, pos.getY()); maxY = Math.max(maxY, pos.getY());
            minZ = Math.min(minZ, pos.getZ()); maxZ = Math.max(maxZ, pos.getZ());
        }

        void prepare() {
            scanX = minX; scanY = minY; scanZ = minZ;
            expected.sort(Comparator.comparingInt(block -> block.position().getY()));
        }

        boolean step(ServerLevel level, IslandRepairData ledger) {
            if (level.getChunkSource().getChunkNow(minX >> 4, minZ >> 4) == null) {
                return false;
            }
            AABB bounds = new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
            boolean approaching = level.players().stream().anyMatch(player ->
                    player.getX() >= minX - APPROACH_DISTANCE && player.getX() <= maxX + APPROACH_DISTANCE
                    && player.getZ() >= minZ - APPROACH_DISTANCE && player.getZ() <= maxZ + APPROACH_DISTANCE);
            if (!approaching || level.players().stream().anyMatch(player -> player.getBoundingBox().intersects(bounds))) {
                return false;
            }
            if (ledger.pending.contains(key)) {
                // A saved repair can be resumed after a restart without
                // resetting containers that were already placed or opened.
                preflightDone = true;
            }
            if (!preflightDone) {
                BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
                for (int count = 0; count < SCAN_BUDGET; count++) {
                    cursor.set(scanX, scanY, scanZ);
                    if (!replaceable(level.getBlockState(cursor))) {
                        // Any surviving structure or player block in this
                        // section means it must not be replayed automatically.
                        finish(ledger);
                        return true;
                    }
                    if (++scanX > maxX) {
                        scanX = minX;
                        if (++scanZ > maxZ) {
                            scanZ = minZ;
                            if (++scanY > maxY) {
                                preflightDone = true;
                                ledger.begin(key);
                                break;
                            }
                        }
                    }
                }
                return true;
            }
            int end = Math.min(writeIndex + WRITE_BUDGET, expected.size());
            for (; writeIndex < end; writeIndex++) {
                ExpectedBlock block = expected.get(writeIndex);
                BlockPos pos = block.position();
                if (!replaceable(level.getBlockState(pos)) || level.getBlockEntity(pos) != null) {
                    continue;
                }
                if (!level.setBlock(pos, block.state(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE)) {
                    continue;
                }
                if (block.nbt() != null) {
                    BlockEntity entity = BlockEntity.loadStatic(pos, block.state(), block.nbt().copy(), level.registryAccess());
                    if (entity != null) {
                        level.setBlockEntity(entity);
                        entity.setChanged();
                        level.sendBlockUpdated(pos, block.state(), block.state(), Block.UPDATE_CLIENTS);
                    }
                }
            }
            if (writeIndex == expected.size()) {
                LOGGER.info("Completed missing Sky Island section {} ({} template blocks)", key, expected.size());
                finish(ledger);
            }
            return true;
        }

        private void finish(IslandRepairData ledger) {
            ledger.finish(key);
            finished = true;
        }
    }
}
