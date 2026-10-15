package com.teamaonn.skyislands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.StructureManager;

/** Runtime support and nearest-structure replacement for oversized templates. */
public final class SkyIslands implements ModInitializer {
    private static final String MOD_ID = "sky_islands";

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                registerCommands(dispatcher));
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        registerCommandTree(dispatcher, "skyislands");
        // Minecraft commands are normally lowercase, but keep the spelling
        // from the original request usable as well.
        registerCommandTree(dispatcher, "SkyIslands");
    }

    private static void registerCommandTree(CommandDispatcher<CommandSourceStack> dispatcher, String literal) {
        dispatcher.register(Commands.literal(literal)
                .then(Commands.literal("start")
                        .then(Commands.argument("island", IntegerArgumentType.integer(1, 22))
                                .executes(context -> startIsland(
                                        context.getSource(),
                                        IntegerArgumentType.getInteger(context, "island")))))
                .then(Commands.literal("restore")
                        .then(Commands.literal("nearest19")
                                .executes(context -> restoreNearest(context.getSource(), "island_19")))
                        .then(Commands.literal("nearest5")
                                .executes(context -> restoreNearest(context.getSource(), "island_5")))));
    }

    /** Places a fresh island at the fixed starter location, then moves the player to its bed. */
    private static int startIsland(CommandSourceStack source, int islandNumber) {
        String template = "island_" + islandNumber;
        String place = "place template " + MOD_ID + ":" + template + " 0 120 0";
        source.getServer().getCommands().performPrefixedCommand(source, place);

        // Island 19's bed is at template-relative (57, 23, 116). Teleport one
        // block above it after the template has been placed at Y=120.
        BlockPos bed = starterTeleport(islandNumber);
        String teleport = "tp @s " + bed.getX() + " " + bed.getY() + " " + bed.getZ();
        source.getServer().getCommands().performPrefixedCommand(source, teleport);
        return 1;
    }

    private static BlockPos starterTeleport(int islandNumber) {
        // These are safe solid surface blocks measured from the converted
        // templates. The starter origin is (0, 120, 0), so these are already
        // world coordinates after placement. The player is placed one block
        // above each selected surface block.
        return switch (islandNumber) {
            case 1 -> new BlockPos(37, 157, 14);
            case 2 -> new BlockPos(13, 144, 9);
            case 3 -> new BlockPos(30, 188, 27);
            case 4 -> new BlockPos(3, 129, 4);
            case 5 -> new BlockPos(81, 282, 79);
            case 6 -> new BlockPos(15, 137, 23);
            case 7 -> new BlockPos(4, 127, 4);
            case 8 -> new BlockPos(18, 143, 17);
            case 9 -> new BlockPos(5, 129, 5);
            case 10 -> new BlockPos(2, 139, 4);
            case 11 -> new BlockPos(27, 138, 24);
            case 12 -> new BlockPos(16, 138, 48);
            case 13 -> new BlockPos(3, 129, 2);
            case 14 -> new BlockPos(27, 143, 28);
            case 15 -> new BlockPos(35, 149, 8);
            case 16 -> new BlockPos(22, 170, 25);
            case 17 -> new BlockPos(17, 151, 16);
            case 18 -> new BlockPos(4, 127, 4);
            case 19 -> new BlockPos(57, 144, 116); // template bed
            case 20 -> new BlockPos(32, 165, 20);
            case 21 -> new BlockPos(18, 153, 18);
            case 22 -> new BlockPos(5, 122, 4);
            default -> new BlockPos(0, 121, 0);
        };
    }

    private static int restoreNearest(CommandSourceStack source, String template) {
        ServerLevel level = source.getLevel();
        BlockPos playerPos = BlockPos.containing(source.getPosition());
        StructureManager structures = level.structureManager();
        ResourceKey<Structure> key = ResourceKey.create(
                Registries.STRUCTURE,
                Identifier.fromNamespaceAndPath(MOD_ID, template));
        Structure target = level.registryAccess().lookupOrThrow(Registries.STRUCTURE)
                .getOrThrow(key).value();

        int centerChunkX = playerPos.getX() >> 4;
        int centerChunkZ = playerPos.getZ() >> 4;
        StructureStart nearest = null;
        double nearestDistance = Double.MAX_VALUE;

        // Scan the nearby generated chunks for the actual saved structure start.
        // This deliberately does not use /locate, whose result is only a
        // placement hint and is not necessarily the template origin.
        for (int dx = -64; dx <= 64; dx++) {
            for (int dz = -64; dz <= 64; dz++) {
                ChunkPos chunk = new ChunkPos(centerChunkX + dx, centerChunkZ + dz);
                for (StructureStart candidate : structures.startsForStructure(chunk.x(), chunk.z(), target)) {
                    if (!candidate.isValid()) {
                        continue;
                    }
                    BoundingBox box = candidate.getBoundingBox();
                    double dxToCenter = box.getCenter().getX() - source.getPosition().x;
                    double dyToCenter = box.getCenter().getY() - source.getPosition().y;
                    double dzToCenter = box.getCenter().getZ() - source.getPosition().z;
                    double distance = dxToCenter * dxToCenter
                            + dyToCenter * dyToCenter
                            + dzToCenter * dzToCenter;
                    if (distance < nearestDistance) {
                        nearest = candidate;
                        nearestDistance = distance;
                    }
                }
            }
        }

        if (nearest == null) {
            return 0;
        }

        BoundingBox box = nearest.getBoundingBox();
        clearBox(source, box);
        String command = "place template " + MOD_ID + ":" + template + " "
                + box.minX() + " " + box.minY() + " " + box.minZ();
        source.getServer().getCommands().performPrefixedCommand(source, command);
        return 1;
    }

    private static void clearBox(CommandSourceStack source, BoundingBox box) {
        for (int x = box.minX(); x <= box.maxX(); x += 16) {
            for (int y = box.minY(); y <= box.maxY(); y += 16) {
                for (int z = box.minZ(); z <= box.maxZ(); z += 16) {
                    int x2 = Math.min(box.maxX(), x + 15);
                    int y2 = Math.min(box.maxY(), y + 15);
                    int z2 = Math.min(box.maxZ(), z + 15);
                    String clear = "fill " + x + " " + y + " " + z + " "
                            + x2 + " " + y2 + " " + z2 + " air replace";
                    source.getServer().getCommands().performPrefixedCommand(source, clear);
                }
            }
        }
    }
}
