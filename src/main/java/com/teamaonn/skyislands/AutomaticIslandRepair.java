package com.teamaonn.skyislands;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import java.util.UUID;
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

/** Repairs missing sections only after a player builds a lodestone marker. */
final class AutomaticIslandRepair {
    private static final Logger LOGGER = LoggerFactory.getLogger("sky_islands/automatic_repair");
    private static final int DISCOVERY_RADIUS = 12;
    private static final int APPROACH_DISTANCE = 128;
    private static final int SCAN_BUDGET = 2048;
    private static final int WRITE_BUDGET = 128;
    private static final Map<ServerLevel, LevelWork> LEVELS = new HashMap<>();

    static void register() {
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world instanceof ServerLevel level &&
                    (player.getItemInHand(hand).is(Items.CARVED_PUMPKIN)
                    || player.getItemInHand(hand).is(Items.JACK_O_LANTERN))) {
                LevelWork work = LEVELS.computeIfAbsent(level, ignored -> new LevelWork());
                // This callback is before vanilla placement. Check the resulting
                // world once at the end of this tick, after the pumpkin is placed.
                work.placedHeads.put(hit.getBlockPos().immutable(), player.getUUID());
                work.placedHeads.put(hit.getBlockPos().relative(hit.getDirection()), player.getUUID());
            }
            return InteractionResult.PASS;
        });
        ServerTickEvents.END_LEVEL_TICK.register(AutomaticIslandRepair::tick);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            for (ServerLevel level : server.getAllLevels()) {
                IslandRepairData data = level.getDataStorage().computeIfAbsent(IslandRepairData.TYPE);
                // Resume only repairs that somebody explicitly activated. There
                // is no player proximity scan or timer, even after restarting.
                for (var entry : new HashMap<>(data.rituals).entrySet()) {
                    resume(level, entry.getKey(), entry.getValue());
                }
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> LEVELS.clear());
    }

    private static void resume(ServerLevel level, String key, IslandRepairData.Ritual ritual) {
        try {
            String[] parts = key.split("/");
            Identifier id = Identifier.parse(parts[0]);
            BlockPos origin = BlockPos.of(Long.parseLong(parts[1]));
            Rotation rotation = Rotation.valueOf(parts[2]);
            level.getStructureTemplateManager().get(id).ifPresent(template -> {
                RepairTask task = new RepairTask(level, key, template, origin, rotation);
                task.ritual = ritual;
                LEVELS.computeIfAbsent(level, ignored -> new LevelWork()).tasks.put(key, task);
            });
        } catch (RuntimeException error) {
            LOGGER.error("Unable to resume requested island repair {}", key, error);
        }
    }

    private static void tick(ServerLevel level) {
        LevelWork work = LEVELS.get(level);
        if (work == null) return;
        for (var entry : new HashMap<>(work.placedHeads).entrySet()) {
            activate(level, entry.getKey(), entry.getValue(), work);
        }
        work.placedHeads.clear();
        // Idle worlds do no discovery or repair work. Only an explicitly
        // activated job receives small batches of work on subsequent ticks.
        Iterator<Map.Entry<String, RepairTask>> tasks = work.tasks.entrySet().iterator();
        while (tasks.hasNext()) {
            RepairTask task = tasks.next().getValue();
            if (task.ritual != null) {
                if (task.ritual.loaded(level) && !task.ritual.intact(level)) {
                    task.ledger.rituals.remove(task.key);
                    task.ledger.setDirty();
                    tasks.remove();
                    LOGGER.info("Cancelled island repair because its lodestone marker was removed: {}", task.key);
                    continue;
                }
            }
            if (task.index == task.blocks.size() && task.sections.isEmpty()) {
                if (task.completeRitual(level)) tasks.remove();
                continue;
            }
            if (task.step(level)) break;
        }
        if (work.tasks.isEmpty() && work.placedHeads.isEmpty()) LEVELS.remove(level);
    }

    private static void activate(ServerLevel level, BlockPos head, UUID playerId, LevelWork work) {
        IslandRepairData.Ritual ritual = new IslandRepairData.Ritual(head, true);
        if (!ritual.intact(level)) ritual = new IslandRepairData.Ritual(head, false);
        if (!ritual.intact(level)) return;
        for (RepairTask task : work.tasks.values()) {
            if (ritual.equals(task.ritual)) return;
        }
        // This search happens once, in response to building a complete marker.
        // It does not generate chunks while looking for saved structure starts.
        int centerX = head.getX() >> 4;
        int centerZ = head.getZ() >> 4;
        for (int dx = -DISCOVERY_RADIUS; dx <= DISCOVERY_RADIUS; dx++) {
            for (int dz = -DISCOVERY_RADIUS; dz <= DISCOVERY_RADIUS; dz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(centerX + dx, centerZ + dz);
                if (chunk == null) continue;
                for (StructureStart start : chunk.getAllStarts().values()) {
                    if (!start.isValid()) continue;
                    for (var piece : start.getPieces()) {
                        if (!(piece instanceof PoolElementStructurePiece pool)
                                || !(pool.getElement() instanceof SinglePoolElement single)) continue;
                        Identifier id = single.getTemplateLocation();
                        if (id == null || !id.getNamespace().equals("sky_islands")
                                || !id.getPath().matches("island_([1-9]|1[0-9]|2[0-2])")) continue;
                        var template = level.getStructureTemplateManager().get(id).orElse(null);
                        if (template == null) continue;
                        var box = template.getBoundingBox(new StructurePlaceSettings().setRotation(pool.getRotation()), pool.getPosition());
                        if (head.getX() < box.minX() || head.getX() > box.maxX()
                                || head.getZ() < box.minZ() || head.getZ() > box.maxZ()
                                || head.getY() < box.minY() || head.getY() > box.maxY() + 4) continue;
                        String key = id + "/" + pool.getPosition().asLong() + "/" + pool.getRotation();
                        if (work.tasks.containsKey(key)) {
                            tell(level, playerId, "This island already has an active repair marker.");
                            return;
                        }
                        RepairTask task = new RepairTask(level, key, template, pool.getPosition(), pool.getRotation());
                        task.ritual = ritual;
                        task.ledger.rituals.put(key, ritual);
                        task.ledger.setDirty();
                        work.tasks.put(key, task);
                        tell(level, playerId, "Sky Island repair started. Keep the marker intact and the island loaded until it finishes.");
                        return;
                    }
                }
            }
        }
        tell(level, playerId, "No saved Sky Island found here. Your repair blocks have been kept.");
    }

    private static void tell(ServerLevel level, UUID playerId, String text) {
        var player = level.getServer().getPlayerList().getPlayer(playerId);
        if (player != null) player.sendSystemMessage(Component.literal(text));
    }

    private static final class LevelWork {
        final Map<BlockPos, UUID> placedHeads = new LinkedHashMap<>();
        final Map<String, RepairTask> tasks = new LinkedHashMap<>();
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
        final List<ExpectedBlock> coveredByMarker = new ArrayList<>();
        IslandRepairData.Ritual ritual;
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
                    if (ritual != null && ritual.positions().contains(pos)) {
                        coveredByMarker.add(new ExpectedBlock(pos, state, data.getCompound("nbt").orElse(null)));
                    }
                    SectionWork section = sections.computeIfAbsent(chunk, ignored -> new SectionWork(sectionKey));
                    section.add(new ExpectedBlock(pos, state, data.getCompound("nbt").orElse(null)));
                }
                if (index == blocks.size()) {
                    sections.values().forEach(section -> {
                        section.reserved = ritual == null ? Set.of() : ritual.positions();
                        section.prepare();
                    });
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

        boolean completeRitual(ServerLevel level) {
            if (ritual == null) return true;
            if (!ritual.loaded(level) || !ritual.intact(level)) return false;
            if (coveredByMarker.stream().anyMatch(block -> level.players().stream().anyMatch(player ->
                    player.getBoundingBox().intersects(new AABB(block.position()))))) return false;
            // Consume exactly the five activating blocks, without item drops.
            // Never clear surrounding blocks or replay surviving containers.
            for (BlockPos pos : ritual.positions()) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
            for (ExpectedBlock block : coveredByMarker) placeExpected(level, block);
            ledger.rituals.remove(key);
            ledger.setDirty();
            LOGGER.info("Lodestone repair complete; consumed marker at {}", ritual.head());
            for (var player : level.players()) {
                if (player.blockPosition().distSqr(ritual.head()) <= 256 * 256) {
                    player.sendSystemMessage(Component.literal("Sky Island repair finished. The lodestone marker has been consumed."));
                }
            }
            return true;
        }
    }

    private static boolean replaceable(BlockState state) {
        // Only air or plain flowing liquids. Never treat plants, blocks with a
        // replaceable material, or waterlogged player builds as disposable.
        return state.isAir() || state.is(Blocks.WATER) || state.is(Blocks.LAVA);
    }

    private static void placeExpected(ServerLevel level, ExpectedBlock block) {
        BlockPos pos = block.position();
        if (!replaceable(level.getBlockState(pos)) || level.getBlockEntity(pos) != null) return;
        if (!level.setBlock(pos, block.state(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE)) return;
        if (block.nbt() != null) {
            BlockEntity entity = BlockEntity.loadStatic(pos, block.state(), block.nbt().copy(), level.registryAccess());
            if (entity != null) {
                level.setBlockEntity(entity);
                entity.setChanged();
                level.sendBlockUpdated(pos, block.state(), block.state(), Block.UPDATE_CLIENTS);
            }
        }
    }

    static final class SectionWork {
        final String key;
        final List<ExpectedBlock> expected = new ArrayList<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        int scanX, scanY, scanZ, writeIndex;
        boolean preflightDone, finished;
        Set<BlockPos> reserved = Set.of();

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
                    if (!reserved.contains(cursor) && !replaceable(level.getBlockState(cursor))) {
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
                if (!reserved.contains(pos)) placeExpected(level, block);
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
