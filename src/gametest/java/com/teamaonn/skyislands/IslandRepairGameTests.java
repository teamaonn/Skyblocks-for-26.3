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

/** Run in a real headless Minecraft server using gradle runGameTest. */
public final class IslandRepairGameTests {
    private static final BlockPos LOCAL = new BlockPos(2, 3, 2);

    private static void observer(GameTestHelper helper) {
        var player = helper.makeMockServerPlayerInLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(-4, 5, -4));
        player.setPos(pos.getX(), pos.getY(), pos.getZ());
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
    public void preservesPlayerBlocksAndExistingSections(GameTestHelper helper) {
        observer(helper);
        var section = emptySection(helper, "existing");
        helper.setBlock(LOCAL, Blocks.DIAMOND_BLOCK);
        var ledger = new IslandRepairData();
        runSection(helper, section, ledger);
        helper.assertBlockPresent(Blocks.DIAMOND_BLOCK, LOCAL);
        helper.assertBlockPresent(Blocks.AIR, LOCAL.above());
        helper.succeed();
    }

    @GameTest
    public void resumesSavedRepairWithoutRefillingChest(GameTestHelper helper) {
        observer(helper);
        var section = emptySection(helper, "interrupted");
        helper.setBlock(LOCAL.above(), Blocks.CHEST);
        var chest = helper.getBlockEntity(LOCAL.above(), ChestBlockEntity.class);
        chest.setItem(0, new ItemStack(Items.DIAMOND, 7));
        var ledger = new IslandRepairData();
        ledger.begin("interrupted");
        // Round-trip the same codec used by SavedData, simulating reopening.
        var serialized = IslandRepairData.CODEC.encodeStart(NbtOps.INSTANCE, ledger).getOrThrow();
        var reopened = IslandRepairData.CODEC.parse(NbtOps.INSTANCE, serialized).getOrThrow();
        runSection(helper, section, reopened);
        helper.assertBlockPresent(Blocks.STONE, LOCAL);
        helper.assertTrue(chest.getItem(0).is(Items.DIAMOND) && chest.getItem(0).getCount() == 7,
                "existing chest contents must survive a resumed repair");
        helper.assertTrue(!chest.saveWithFullMetadata(helper.getLevel().registryAccess()).contains("LootTable"),
                "existing chest must not receive fresh loot");
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

    @GameTest(maxTicks = 200)
    public void approachTriggersRepairWithoutCommand(GameTestHelper helper) {
        observer(helper);
        var level = helper.getLevel();
        var manager = level.getStructureTemplateManager();
        Identifier id = Identifier.fromNamespaceAndPath("sky_islands", "island_19");
        // Replace only the in-memory test-server template with a small fixture.
        // This exercises saved structure discovery and the normal tick callback.
        manager.remove(id);
        var template = manager.getOrCreate(id);
        helper.setBlock(new BlockPos(1, 2, 1), Blocks.STONE);
        helper.setBlock(new BlockPos(2, 2, 1), Blocks.GOLD_BLOCK);
        template.fillFromWorld(level, helper.absolutePos(new BlockPos(1, 2, 1)),
                new Vec3i(2, 1, 1), false, List.of());
        BlockPos origin = helper.absolutePos(new BlockPos(4, 6, 4));
        level.setBlock(origin, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        level.setBlock(origin.south(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        var element = StructurePoolElement.single(id.toString()).apply(StructureTemplatePool.Projection.RIGID);
        var piece = new PoolElementStructurePiece(manager, element, origin, 0, Rotation.CLOCKWISE_90,
                element.getBoundingBox(manager, origin, Rotation.CLOCKWISE_90), LiquidSettings.IGNORE_WATERLOGGING);
        var structure = level.registryAccess().lookupOrThrow(Registries.STRUCTURE)
                .getOrThrow(ResourceKey.create(Registries.STRUCTURE, id)).value();
        ChunkPos source = ChunkPos.containing(origin);
        var chunk = level.getChunkSource().getChunkNow(source.x(), source.z());
        helper.assertTrue(chunk != null, "test fixture chunk is fully loaded");
        var previous = chunk.getStartForStructure(structure);
        chunk.setStartForStructure(structure, new StructureStart(structure, source, 0, new PiecesContainer(List.of(piece))));
        helper.runAfterDelay(100, () -> {
            try {
                helper.assertTrue(level.getBlockState(origin).is(Blocks.STONE), "approach automatically fills missing section");
                helper.assertTrue(level.getBlockState(origin.south()).is(Blocks.GOLD_BLOCK), "automatic repair respects rotation");
            } finally {
                chunk.setStartForStructure(structure, previous == null ? StructureStart.INVALID_START : previous);
                manager.remove(id);
            }
            helper.succeed();
        });
    }
}
