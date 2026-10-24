package com.teamaonn.skyislands;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;

import java.util.List;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.BlockHitResult;

/** Run in a real headless Minecraft server using gradle runGameTest. */
public final class IslandRepairGameTests {
    private static final BlockPos LOCAL = new BlockPos(2, 3, 2);

    private static net.minecraft.server.level.ServerPlayer observer(GameTestHelper helper) {
        var player = helper.makeMockServerPlayerInLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(-4, 5, -4));
        player.setPos(pos.getX(), pos.getY(), pos.getZ());
        return player;
    }

    private static AutomaticIslandRepair.SectionWork emptySection(GameTestHelper helper, String key) {
        BlockPos pos = helper.absolutePos(LOCAL);
        helper.setBlock(LOCAL, Blocks.AIR);
        helper.setBlock(LOCAL.above(), Blocks.AIR);
        CompoundTag chest = new CompoundTag();
        chest.putString("id", "minecraft:chest");
        chest.putString("LootTable", "sky_islands:chests/random");
        var section = new AutomaticIslandRepair.SectionWork(key);
        section.add(new AutomaticIslandRepair.ExpectedBlock(pos, Blocks.STONE.defaultBlockState(), null));
        section.add(new AutomaticIslandRepair.ExpectedBlock(pos.above(), Blocks.CHEST.defaultBlockState(), chest));
        section.prepare();
        return section;
    }

    private static void runSection(GameTestHelper helper, AutomaticIslandRepair.SectionWork section, IslandRepairData ledger) {
        for (int tick = 0; tick < 100 && !section.finished; tick++) {
            section.step(helper.getLevel(), ledger);
        }
        helper.assertTrue(section.finished, "repair must finish");
    }

    @GameTest
    public void restoresMissingSectionAndLoot(GameTestHelper helper) {
        observer(helper);
        var section = emptySection(helper, "missing");
        var ledger = new IslandRepairData();
        runSection(helper, section, ledger);
        helper.assertBlockPresent(Blocks.STONE, LOCAL);
        helper.assertBlockPresent(Blocks.CHEST, LOCAL.above());
        var tag = helper.getBlockEntity(LOCAL.above(), ChestBlockEntity.class)
                .saveWithFullMetadata(helper.getLevel().registryAccess());
        helper.assertTrue(tag.getStringOr("LootTable", "").equals("sky_islands:chests/random"),
                "newly restored chest keeps its random loot table");
        helper.assertTrue(ledger.checked.contains("missing") && ledger.pending.isEmpty(), "completion is recorded");
        helper.succeed();
    }

    @GameTest
    public void overwritesSurvivingBlocksAndExistingSections(GameTestHelper helper) {
        observer(helper);
        var section = emptySection(helper, "existing");
        helper.setBlock(LOCAL, Blocks.DIAMOND_BLOCK);
        var ledger = new IslandRepairData();
        runSection(helper, section, ledger);
        helper.assertBlockPresent(Blocks.STONE, LOCAL);
        helper.assertBlockPresent(Blocks.CHEST, LOCAL.above());
        helper.succeed();
    }

    @GameTest
    public void resetsExistingChestIncludingAfterSavedRecovery(GameTestHelper helper) {
        observer(helper);
        var section = emptySection(helper, "interrupted");
        helper.setBlock(LOCAL.above(), Blocks.CHEST);
        var chest = helper.getBlockEntity(LOCAL.above(), ChestBlockEntity.class);
        chest.setItem(0, new ItemStack(Items.DIAMOND, 7));
        var ledger = new IslandRepairData();
        ledger.begin("interrupted");
        var marker = new IslandRepairData.Ritual(helper.absolutePos(new BlockPos(4, 6, 4)), true);
        ledger.rituals.put("request", marker);
        // Round-trip the same codec used by SavedData, simulating reopening.
        var serialized = IslandRepairData.CODEC.encodeStart(NbtOps.INSTANCE, ledger).getOrThrow();
        var reopened = IslandRepairData.CODEC.parse(NbtOps.INSTANCE, serialized).getOrThrow();
        helper.assertTrue(marker.equals(reopened.rituals.get("request")), "active markers survive a saved-data round trip");
        runSection(helper, section, reopened);
        helper.assertBlockPresent(Blocks.STONE, LOCAL);
        var restored = helper.getBlockEntity(LOCAL.above(), ChestBlockEntity.class);
        helper.assertTrue(restored != chest, "old chest entity is replaced even for the same block type");
        helper.assertTrue(restored.saveWithFullMetadata(helper.getLevel().registryAccess())
                .getStringOr("LootTable", "").equals("sky_islands:chests/random"),
                "existing chest gets a fresh template loot table");
        helper.succeed();
    }

    @GameTest
    public void waitsWhilePlayerIsInsideMissingSection(GameTestHelper helper) {
        var section = emptySection(helper, "player");
        var player = helper.makeMockServerPlayerInLevel();
        BlockPos pos = helper.absolutePos(LOCAL);
        player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        var ledger = new IslandRepairData();
        for (int i = 0; i < 10; i++) section.step(helper.getLevel(), ledger);
        helper.assertTrue(!section.finished && ledger.pending.isEmpty(), "do not build into the player");
        helper.assertBlockPresent(Blocks.AIR, LOCAL);
        player.setPos(pos.getX() - 4, pos.getY() + 4, pos.getZ() - 4);
        runSection(helper, section, ledger);
        helper.assertBlockPresent(Blocks.STONE, LOCAL);
        helper.succeed();
    }

    @GameTest
    public void rotationAndCompletionSurviveRevisit(GameTestHelper helper) {
        observer(helper);
        helper.setBlock(new BlockPos(1, 2, 1), Blocks.STONE);
        helper.setBlock(new BlockPos(2, 2, 1), Blocks.GOLD_BLOCK);
        var template = new StructureTemplate();
        template.fillFromWorld(helper.getLevel(), helper.absolutePos(new BlockPos(1, 2, 1)),
                new Vec3i(2, 1, 1), false, List.of());
        BlockPos origin = helper.absolutePos(new BlockPos(4, 5, 4));
        helper.getLevel().setBlock(origin, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        helper.getLevel().setBlock(origin.south(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        String key = "rotation/" + origin.asLong();
        var task = new AutomaticIslandRepair.RepairTask(helper.getLevel(), key, template, origin, Rotation.CLOCKWISE_90);
        for (int i = 0; i < 100; i++) task.step(helper.getLevel());
        helper.assertTrue(helper.getLevel().getBlockState(origin).is(Blocks.STONE), "saved origin is respected");
        helper.assertTrue(helper.getLevel().getBlockState(origin.south()).is(Blocks.GOLD_BLOCK), "saved rotation is respected");
        helper.getLevel().setBlock(origin, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        var revisit = new AutomaticIslandRepair.RepairTask(helper.getLevel(), key, template, origin, Rotation.CLOCKWISE_90);
        for (int i = 0; i < 100; i++) revisit.step(helper.getLevel());
        helper.assertTrue(helper.getLevel().getBlockState(origin).isAir(), "subsequent visits do not undo mining");
        helper.succeed();
    }

    @GameTest
    public void restoresTemplateAirAndResetsBarrel(GameTestHelper helper) {
        observer(helper);
        var section = new AutomaticIslandRepair.SectionWork("air-and-barrel");
        BlockPos pos = helper.absolutePos(LOCAL);
        helper.setBlock(LOCAL, Blocks.DIAMOND_BLOCK);
        helper.setBlock(LOCAL.above(), Blocks.BARREL);
        var old = helper.getLevel().getBlockEntity(pos.above());
        ((net.minecraft.world.level.block.entity.BarrelBlockEntity) old).setItem(0, new ItemStack(Items.DIAMOND, 64));
        CompoundTag nbt = new CompoundTag();
        nbt.putString("id", "minecraft:barrel");
        nbt.putString("LootTable", "sky_islands:chests/random");
        section.add(new AutomaticIslandRepair.ExpectedBlock(pos, Blocks.AIR.defaultBlockState(), null));
        section.add(new AutomaticIslandRepair.ExpectedBlock(pos.above(), Blocks.BARREL.defaultBlockState(), nbt));
        section.prepare();
        runSection(helper, section, new IslandRepairData());
        helper.assertBlockPresent(Blocks.AIR, LOCAL);
        var restored = helper.getLevel().getBlockEntity(pos.above());
        helper.assertTrue(restored != old, "barrel inventory must not survive reset");
        helper.assertTrue(restored.saveWithFullMetadata(helper.getLevel().registryAccess())
                .getStringOr("LootTable", "").equals("sky_islands:chests/random"), "barrel gets fresh loot");
        helper.succeed();
    }

    @GameTest
    public void sparseTemplateRestoresAcrossThreeChunks(GameTestHelper helper) {
        observer(helper);
        var level = helper.getLevel();
        var template = new StructureTemplate();
        CompoundTag data = new CompoundTag();
        var size = new net.minecraft.nbt.ListTag();
        size.add(net.minecraft.nbt.IntTag.valueOf(35));
        size.add(net.minecraft.nbt.IntTag.valueOf(1));
        size.add(net.minecraft.nbt.IntTag.valueOf(1));
        data.put("size", size);
        var palette = new net.minecraft.nbt.ListTag();
        palette.add(net.minecraft.nbt.NbtUtils.writeBlockState(Blocks.STONE.defaultBlockState()));
        data.put("palette", palette);
        var blocks = new net.minecraft.nbt.ListTag();
        for (int x : new int[]{0, 34}) {
            CompoundTag block = new CompoundTag();
            var pos = new net.minecraft.nbt.ListTag();
            pos.add(net.minecraft.nbt.IntTag.valueOf(x));
            pos.add(net.minecraft.nbt.IntTag.valueOf(0));
            pos.add(net.minecraft.nbt.IntTag.valueOf(0));
            block.put("pos", pos); block.putInt("state", 0); blocks.add(block);
        }
        data.put("blocks", blocks);
        template.load(level.registryAccess().lookupOrThrow(Registries.BLOCK), data);
        BlockPos base = helper.absolutePos(new BlockPos(2, 8, 2));
        BlockPos origin = new BlockPos((base.getX() >> 4) << 4, base.getY(), base.getZ());
        for (int x = 0; x <= 35; x++) {
            level.getChunk(origin.offset(x, 0, 0));
            level.setBlock(origin.offset(x, 0, 0), Blocks.DIAMOND_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        var task = new AutomaticIslandRepair.RepairTask(level, "sparse-three-chunks/" + origin.asLong(), template, origin, Rotation.NONE);
        for (int tick = 0; tick < 100; tick++) task.step(level);
        helper.assertTrue(task.sections.isEmpty(), "all three chunks complete despite surviving blocks");
        for (int x = 0; x < 35; x++) {
            helper.assertTrue(level.getBlockState(origin.offset(x, 0, 0)).is(x == 0 || x == 34 ? Blocks.STONE : Blocks.AIR),
                    "omitted template air is cleared, including the entirely empty middle chunk");
        }
        helper.assertTrue(level.getBlockState(origin.offset(35, 0, 0)).is(Blocks.DIAMOND_BLOCK), "outside the template remains untouched");
        helper.succeed();
    }

    @GameTest
    public void savedCompletionRestoresBlockUnderMarker(GameTestHelper helper) {
        observer(helper);
        var level = helper.getLevel();
        helper.setBlock(LOCAL, Blocks.GOLD_BLOCK);
        var template = new StructureTemplate();
        template.fillFromWorld(level, helper.absolutePos(LOCAL), new Vec3i(1, 1, 1), false, List.of());
        BlockPos origin = helper.absolutePos(new BlockPos(4, 7, 4));
        var ritual = new IslandRepairData.Ritual(origin, true);
        buildBody(helper, ritual);
        level.setBlock(origin, Blocks.CARVED_PUMPKIN.defaultBlockState(), Block.UPDATE_CLIENTS);
        String key = "saved-marker/" + origin.asLong();
        var task = new AutomaticIslandRepair.RepairTask(level, key, template, origin, Rotation.NONE);
        task.ritual = ritual;
        task.ledger.finish(key + "/" + ChunkPos.pack(origin.getX() >> 4, origin.getZ() >> 4));
        for (int tick = 0; tick < 20; tick++) task.step(level);
        helper.assertTrue(task.completeRitual(level), "saved completed section can finish its marker");
        helper.assertTrue(level.getBlockState(origin).is(Blocks.GOLD_BLOCK), "template block under marker is restored after restart");
        helper.succeed();
    }

    private record Fixture(BlockPos origin, BlockPos second, Runnable cleanup) { }

    private static Fixture fixture(GameTestHelper helper, String island, Rotation rotation) {
        var level = helper.getLevel();
        var manager = level.getStructureTemplateManager();
        Identifier id = Identifier.fromNamespaceAndPath("sky_islands", island);
        manager.remove(id);
        var template = manager.getOrCreate(id);
        helper.setBlock(new BlockPos(1, 2, 1), Blocks.STONE);
        helper.setBlock(new BlockPos(2, 2, 1), Blocks.GOLD_BLOCK);
        template.fillFromWorld(level, helper.absolutePos(new BlockPos(1, 2, 1)),
                new Vec3i(2, 1, 1), false, List.of());
        BlockPos origin = helper.absolutePos(new BlockPos(4, 6, 4));
        BlockPos second = rotation == Rotation.CLOCKWISE_90 ? origin.south() : origin.east();
        level.setBlock(origin, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        level.setBlock(second, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        var element = StructurePoolElement.single(id.toString()).apply(StructureTemplatePool.Projection.RIGID);
        var piece = new PoolElementStructurePiece(manager, element, origin, 0, rotation,
                element.getBoundingBox(manager, origin, rotation), LiquidSettings.IGNORE_WATERLOGGING);
        var structure = level.registryAccess().lookupOrThrow(Registries.STRUCTURE)
                .getOrThrow(ResourceKey.create(Registries.STRUCTURE, id)).value();
        ChunkPos source = ChunkPos.containing(origin);
        var chunk = level.getChunkSource().getChunkNow(source.x(), source.z());
        helper.assertTrue(chunk != null, "fixture chunk must be fully loaded");
        var previous = chunk.getStartForStructure(structure);
        chunk.setStartForStructure(structure, new StructureStart(structure, source, 0, new PiecesContainer(List.of(piece))));
        return new Fixture(origin, second, () -> {
            chunk.setStartForStructure(structure, previous == null ? StructureStart.INVALID_START : previous);
            manager.remove(id);
        });
    }

    private static void placePumpkinLast(GameTestHelper helper, IslandRepairData.Ritual ritual,
                                          net.minecraft.server.level.ServerPlayer player) {
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.CARVED_PUMPKIN));
        UseBlockCallback.EVENT.invoker().interact(player, helper.getLevel(), InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(ritual.head().below()), Direction.UP, ritual.head().below(), false));
        helper.getLevel().setBlock(ritual.head(), Blocks.CARVED_PUMPKIN.defaultBlockState(), Block.UPDATE_CLIENTS);
    }

    private static void buildBody(GameTestHelper helper, IslandRepairData.Ritual ritual) {
        for (BlockPos pos : ritual.positions()) {
            helper.getLevel().setBlock(pos, pos.equals(ritual.head()) ? Blocks.AIR.defaultBlockState()
                    : Blocks.LODESTONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    private static void triggerTest(GameTestHelper helper, String island, Rotation rotation, boolean alongX) {
        var player = observer(helper);
        var fixture = fixture(helper, island, rotation);
        var level = helper.getLevel();
        var ritual = new IslandRepairData.Ritual(fixture.origin().above(3), alongX);
        // More than two seconds near a registered broken island does nothing.
        helper.runAfterDelay(60, () -> {
            helper.assertTrue(level.getBlockState(fixture.origin()).isAir(), "proximity must never activate repair");
            var ledger = level.getDataStorage().computeIfAbsent(IslandRepairData.TYPE);
            String key = "sky_islands:" + island + "/" + fixture.origin().asLong() + "/" + rotation;
            ledger.finish(key + "/" + ChunkPos.pack(fixture.origin().getX() >> 4, fixture.origin().getZ() >> 4));
            buildBody(helper, ritual);
            BlockPos arm = alongX ? ritual.head().below().east() : ritual.head().below().south();
            level.setBlock(arm, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            placePumpkinLast(helper, ritual, player);
            helper.runAfterDelay(20, () -> {
                helper.assertTrue(level.getBlockState(fixture.origin()).isAir(), "incomplete marker must not activate");
                helper.assertTrue(level.getBlockState(ritual.head()).is(Blocks.CARVED_PUMPKIN), "incomplete marker must not be consumed");
                buildBody(helper, ritual);
                placePumpkinLast(helper, ritual, player);
                helper.runAfterDelay(20, () -> {
                    try {
                        helper.assertTrue(level.getBlockState(fixture.origin()).is(Blocks.STONE), "marker triggers repair without commands");
                        helper.assertTrue(level.getBlockState(fixture.second()).is(Blocks.GOLD_BLOCK), "saved rotation is respected");
                        for (BlockPos pos : ritual.positions()) {
                            helper.assertTrue(level.getBlockState(pos).isAir(), "successful repair consumes exactly its marker");
                        }
                    } finally { fixture.cleanup().run(); }
                    helper.succeed();
                });
            });
        });
    }

    @GameTest(maxTicks = 200)
    public void lodestoneMarkerAlongXTriggersRepair(GameTestHelper helper) {
        triggerTest(helper, "island_19", Rotation.CLOCKWISE_90, true);
    }

    @GameTest(maxTicks = 200)
    public void lodestoneMarkerAlongZTriggersRepair(GameTestHelper helper) {
        triggerTest(helper, "island_5", Rotation.NONE, false);
    }

    @GameTest(maxTicks = 80)
    public void invalidLocationKeepsAllMarkerBlocks(GameTestHelper helper) {
        var player = observer(helper);
        var ritual = new IslandRepairData.Ritual(helper.absolutePos(new BlockPos(3, 6, 3)), true);
        buildBody(helper, ritual);
        placePumpkinLast(helper, ritual, player);
        helper.runAfterDelay(20, () -> {
            helper.assertTrue(ritual.intact(helper.getLevel()), "no island found: keep all five marker blocks");
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 80)
    public void breakingMarkerCancelsWithoutConsumingBody(GameTestHelper helper) {
        var player = observer(helper);
        var fixture = fixture(helper, "island_3", Rotation.NONE);
        var ritual = new IslandRepairData.Ritual(fixture.origin().above(3), true);
        buildBody(helper, ritual);
        placePumpkinLast(helper, ritual, player);
        helper.runAfterDelay(2, () -> {
            helper.getLevel().setBlock(ritual.head(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            helper.runAfterDelay(10, () -> {
                try {
                    for (BlockPos pos : ritual.positions()) {
                        if (!pos.equals(ritual.head())) helper.assertTrue(helper.getLevel().getBlockState(pos).is(Blocks.LODESTONE),
                                "cancelling a repair must not consume the remaining lodestones");
                    }
                } finally { fixture.cleanup().run(); }
                helper.succeed();
            });
        });
    }
}
