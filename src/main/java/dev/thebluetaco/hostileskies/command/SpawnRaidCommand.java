package dev.thebluetaco.hostileskies.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.simibubi.create.content.redstone.analogLever.AnalogLeverBlockEntity;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.EmbeddedPlotLevelAccessor;
import dev.eriksonn.aeronautics.content.blocks.hot_air.hot_air_burner.HotAirBurnerBlockEntity;
import dev.simulated_team.simulated.content.blocks.steering_wheel.SteeringWheelBlockEntity;
import dev.simulated_team.simulated.content.blocks.portable_engine.PortableEngineBlockEntity;
import dev.simulated_team.simulated.content.blocks.throttle_lever.ThrottleLeverBlockEntity;
import dev.thebluetaco.hostileskies.HostileSkies;
import dev.thebluetaco.hostileskies.compat.LootrCompat;
import dev.thebluetaco.hostileskies.raid.RaidManager;
import dev.thebluetaco.hostileskies.ship.ShipRegistry;
import dev.thebluetaco.hostileskies.ship.ShipTemplate;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;

import java.util.ArrayList;
import java.util.stream.Collectors;
import java.util.List;

public class SpawnRaidCommand {

    private static final SuggestionProvider<CommandSourceStack> SHIP_SUGGESTIONS =
            (ctx, builder) -> SharedSuggestionProvider.suggestResource(ShipRegistry.getAllIds(), builder);

    public static void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("spawnraid")
                .requires(src -> src.hasPermission(2))
                .executes(SpawnRaidCommand::executeRandom)
                .then(Commands.argument("ship", ResourceLocationArgument.id())
                        .suggests(SHIP_SUGGESTIONS)
                        .executes(SpawnRaidCommand::executeNamed)
                        .then(Commands.argument("omen", IntegerArgumentType.integer(0, 10))
                                .executes(SpawnRaidCommand::executeNamedWithOmen))));

        root.then(Commands.literal("stopraid")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> {
                    int count = RaidManager.clearAll(ctx.getSource().getLevel());
                    ctx.getSource().sendSuccess(() ->
                            Component.literal("Stopped " + count + " active raid(s)"), true);
                    return count;
                }));

        root.then(Commands.literal("ships")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> {
                    var ids = ShipRegistry.getAllIds();
                    if (ids.isEmpty()) {
                        ctx.getSource().sendFailure(Component.literal("No ships loaded"));
                        return 0;
                    }
                    String list = ids.stream().map(ResourceLocation::toString)
                            .collect(Collectors.joining(", "));
                    var suppressed = ShipRegistry.getSuppressed();
                    String suffix = suppressed.isEmpty() ? "" : "\nSuppressed (" + suppressed.size() + "): "
                                                                + suppressed.entrySet().stream()
                                                                  .map(e -> e.getKey() + " (replaced by " + e.getValue() + ")")
                                                                  .collect(Collectors.joining(", "));
                    ctx.getSource().sendSuccess(() ->
                            Component.literal("Loaded ships (" + ids.size() + "): " + list + suffix), false);
                    return ids.size();
                }));
    }

    // Command execution

    private static int executeRandom(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ShipTemplate ship = ShipRegistry.getRandomForTier(1, ctx.getSource().getLevel().random);
        if (ship == null) {
            ctx.getSource().sendFailure(Component.literal("No tier 1 ships registered"));
            return 0;
        }
        return spawnShip(ctx.getSource(), ship, 0);
    }

    private static int executeNamed(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ResourceLocation shipId = ResourceLocationArgument.getId(ctx, "ship");
        ShipTemplate ship = ShipRegistry.get(shipId);
        if (ship == null) {
            ctx.getSource().sendFailure(Component.literal("Unknown ship: " + shipId));
            return 0;
        }
        return spawnShip(ctx.getSource(), ship, 0);
    }

    private static int executeNamedWithOmen(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ResourceLocation shipId = ResourceLocationArgument.getId(ctx, "ship");
        ShipTemplate ship = ShipRegistry.get(shipId);
        if (ship == null) {
            ctx.getSource().sendFailure(Component.literal("Unknown ship: " + shipId));
            return 0;
        }
        int omen = IntegerArgumentType.getInteger(ctx, "omen");
        return spawnShip(ctx.getSource(), ship, omen);
    }

    /** Resolves player position/look into spawnShipAt() parameters. */
    private static int spawnShip(CommandSourceStack source, ShipTemplate ship,
                                  int badOmenLevel) throws CommandSyntaxException {
        ServerLevel level = source.getLevel();
        ServerPlayer player = source.getPlayerOrException();

        Vec3 look = player.getLookAngle();
        double lookLen = Math.sqrt(look.x * look.x + look.z * look.z);
        if (lookLen < 0.001) lookLen = 1.0;
        Vec3 horizontalLook = new Vec3(look.x / lookLen, 0, look.z / lookLen);

        boolean success = spawnShipAt(level, player.position(), horizontalLook, ship, badOmenLevel);
        if (success) {
            String msg = badOmenLevel > 0
                    ? ship.name + " spawned! (Bad Omen " + badOmenLevel + ")"
                    : ship.name + " spawned!";
            source.sendSuccess(() -> Component.literal(msg), true);
            return 1;
        } else {
            source.sendFailure(Component.literal("Failed to spawn " + ship.name));
            return 0;
        }
    }

    // Core spawn logic
    /** Spawns a raid ship targeting the given position.
     * Used by both /hostileskies spawnraid and the automatic spawn system. */
    public static boolean spawnShipAt(ServerLevel level, Vec3 patrolCenter,
                                       Vec3 lookDir, ShipTemplate ship, int badOmenLevel) {
        StructureTemplate template = level.getStructureManager()
                .get(ship.getStructureId()).orElse(null);
        if (template == null) {
            HostileSkies.LOGGER.error("Structure not found: {}", ship.getStructureId());
            return false;
        }

        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            HostileSkies.LOGGER.error("Sable sub-level system not available");
            return false;
        }

        // Cap spawn distance to 70% of simulation distance
        int simDist = level.getServer().getPlayerList().getSimulationDistance() * 16;
        double maxSpawnDist = simDist * 0.7;
        double spawnDist = Math.min(ship.spawning.spawnDistance, maxSpawnDist);
        double patrolRadius = ship.spawning.circleRadius;
        int terrainClear = ship.spawning.terrainClearance;
        int minAltSea = ship.spawning.minAltitudeAboveSea;

        double lx = lookDir.x;
        double lz = lookDir.z;

        // Tangential spawn geometry — orbit direction is randomized per encounter,
        // and the tangent entry shares the same handedness to avoid hooking at entry.
        double orbitSign = level.random.nextBoolean() ? 1.0 : -1.0;

        double perpX = -lz * orbitSign;
        double perpZ = lx * orbitSign;

        double tangentEntryX = patrolCenter.x + patrolRadius * perpX;
        double tangentEntryZ = patrolCenter.z + patrolRadius * perpZ;

        double travelX = -lx;
        double travelZ = -lz;

        double spawnX = tangentEntryX - travelX * spawnDist;
        double spawnZ = tangentEntryZ - travelZ * spawnDist;

        // Terrain aware altitude
        int terrainHeight = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) spawnX, (int) spawnZ);
        int seaLevel = level.getSeaLevel();
        double spawnY = Math.max(terrainHeight + terrainClear, seaLevel + minAltSea);

        Vec3 spawnPos = new Vec3(spawnX, spawnY, spawnZ);

        double spawnAngle = Math.atan2(travelZ, -travelX)
                + Math.toRadians(ship.navigation.spawnYawOffset);
        Quaterniond spawnOrientation = new Quaterniond().rotateY(spawnAngle);

        Pose3d pose = new Pose3d();
        pose.position().set(spawnPos.x, spawnPos.y, spawnPos.z);
        pose.orientation().set(spawnOrientation);

        ServerSubLevel subLevel = (ServerSubLevel) container.allocateNewSubLevel(pose);
        subLevel.setName(ship.name);

        PlacementResult result = placeStructureInPlot(subLevel, template, ship);

        subLevel.updateLastPose();
        subLevel.logicalPose().position().set(spawnPos.x, spawnPos.y, spawnPos.z);
        subLevel.logicalPose().orientation().set(spawnOrientation);

        RaidManager.track(level, subLevel, ship, spawnPos, patrolCenter, spawnOrientation,
                result, badOmenLevel, orbitSign);

        HostileSkies.LOGGER.info("{} spawned at ({},{},{}), patrol center ({},{})",
                ship.name, (int) spawnX, (int) spawnY, (int) spawnZ,
                (int) patrolCenter.x, (int) patrolCenter.z);
        return true;
    }

    // Structure placement

    public record PlacementResult(
            List<BlockPos> burnerPositions,
            List<BlockPos> steeringWheelPositions,
            List<BlockPos> enginePositions,
            List<BlockPos> captainSeats,
            List<BlockPos> crewSeats,
            List<BlockPos> lootContainerPositions,
            List<BlockPos> blazeBurnerPositions,
            net.minecraft.core.Vec3i structureSize,
            net.minecraft.core.Vec3i plotOffset,
            int lootContainerCount
    ) {}

    private static PlacementResult placeStructureInPlot(ServerSubLevel subLevel,
                                                         StructureTemplate template,
                                                         ShipTemplate ship) {
        LevelPlot plot = subLevel.getPlot();
        ChunkPos center = plot.getCenterChunk();

        BoundingBox bounds = template.getBoundingBox(
                BlockPos.ZERO, Rotation.NONE, BlockPos.ZERO, Mirror.NONE);

        int minChunkX = bounds.minX() >> 4;
        int minChunkZ = bounds.minZ() >> 4;
        int maxChunkX = bounds.maxX() >> 4;
        int maxChunkZ = bounds.maxZ() >> 4;

        HostileSkies.LOGGER.info("Plot center chunk: ({}, {}), structure bounds: ({},{},{}) to ({},{},{}), " +
                        "chunks X: {} to {}, Z: {} to {}",
                center.x, center.z,
                bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ(),
                minChunkX, maxChunkX, minChunkZ, maxChunkZ);

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                plot.newEmptyChunk(new ChunkPos(center.x + cx, center.z + cz));
            }
        }

        EmbeddedPlotLevelAccessor accessor = plot.getEmbeddedLevelAccessor();

        // Pass 1: skip fragile blocks (redstone, carpets) that need supports
        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setRotation(Rotation.NONE)
                .setMirror(Mirror.NONE)
                .setIgnoreEntities(false)
                .addProcessor(FRAGILE_BLOCK_FILTER);

        template.placeInWorld(accessor, BlockPos.ZERO, BlockPos.ZERO,
                settings, RandomSource.create(), 2);

        // Pass 2: place everything (supports now exist),    skip entities to avoid dupes
        StructurePlaceSettings pass2 = new StructurePlaceSettings()
                .setRotation(Rotation.NONE)
                .setMirror(Mirror.NONE)
                .setIgnoreEntities(true);
        template.placeInWorld(accessor, BlockPos.ZERO, BlockPos.ZERO,
                pass2, RandomSource.create(), 2);

        List<BlockPos> burnerPositions = new ArrayList<>();
        List<BlockPos> steeringWheelPositions = new ArrayList<>();
        List<BlockPos> enginePositions = new ArrayList<>();
        List<BlockPos> captainSeats = new ArrayList<>();
        List<BlockPos> crewSeats = new ArrayList<>();
        List<BlockPos> lootContainerPositions = new ArrayList<>();
        List<BlockPos> blazeBurnerPositions = new ArrayList<>();
        ResourceKey<LootTable> lootKey = ship.getLootTableKey();
        ResourceKey<LootTable> captainKey = ship.getCaptainTableKey();
        int lootCount = 0;

        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockEntity be = accessor.getBlockEntity(pos);

                    if (be instanceof RandomizableContainerBlockEntity lootContainer) {
                        boolean isCaptain = captainKey != null && ship.isCaptainChest(pos);
                        ResourceKey<LootTable> tableKey = isCaptain ? captainKey : lootKey;

                        if (HostileSkies.isLootrLoaded()) {
                            LootrCompat.convertContainer(accessor, pos, tableKey);
                        } else {
                            lootContainer.setLootTable(tableKey);
                        }

                        lootContainerPositions.add(pos.immutable());
                        lootCount++;
                    }
                    if (be instanceof HotAirBurnerBlockEntity) {
                        burnerPositions.add(pos.immutable());
                    }
                    if (be instanceof SteeringWheelBlockEntity) {
                        steeringWheelPositions.add(pos.immutable());
                    }
                    if (be instanceof PortableEngineBlockEntity) {
                        enginePositions.add(pos.immutable());
                    }
                    if (be instanceof com.simibubi.create.content.processing.burner.BlazeBurnerBlockEntity) {
                        blazeBurnerPositions.add(pos.immutable());
                        HostileSkies.LOGGER.info("Found blaze burner at [{}, {}, {}]",
                                pos.getX(), pos.getY(), pos.getZ());
                    }
                    if (be instanceof ThrottleLeverBlockEntity || be instanceof AnalogLeverBlockEntity) {
                        HostileSkies.LOGGER.info("Found throttle lever at [{}, {}, {}]",
                                pos.getX(), pos.getY(), pos.getZ());
                    }

                    ResourceLocation blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                            .getKey(accessor.getBlockState(pos).getBlock());
                    String blockPath = blockId.getPath();
                    if (blockPath.contains("steam_vent")) {
                        burnerPositions.add(pos.immutable());
                        HostileSkies.LOGGER.info("Found steam vent at [{}, {}, {}]",
                                pos.getX(), pos.getY(), pos.getZ());
                    }
                    if (blockPath.contains("seat")) {
                        if (blockPath.contains("red")) {
                            captainSeats.add(pos.immutable());
                        } else if (blockPath.contains("black")) {
                            crewSeats.add(pos.immutable());
                        }
                    }
                }
            }
        }

        // Fall back to explicit spawn positions from the ship JSON when no more seats exist
        for (int[] pos : ship.crew.captainSpawns) {
            BlockPos bp = new BlockPos(pos[0], pos[1], pos[2]);
            if (!captainSeats.contains(bp)) captainSeats.add(bp);
        }
        for (int[] pos : ship.crew.crewSpawns) {
            BlockPos bp = new BlockPos(pos[0], pos[1], pos[2]);
            if (!crewSeats.contains(bp)) crewSeats.add(bp);
        }

        // Compute offset between structure-relative and plot-absolute coordinates.
        // Any block entity works — its getBlockPos() returns plot-absolute.
        net.minecraft.core.Vec3i plotOffset = net.minecraft.core.Vec3i.ZERO;
        for (int x = bounds.minX(); x <= bounds.maxX() && plotOffset == net.minecraft.core.Vec3i.ZERO; x++) {
            for (int y = bounds.minY(); y <= bounds.maxY() && plotOffset == net.minecraft.core.Vec3i.ZERO; y++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ() && plotOffset == net.minecraft.core.Vec3i.ZERO; z++) {
                    BlockEntity be = accessor.getBlockEntity(new BlockPos(x, y, z));
                    if (be != null) {
                        BlockPos abs = be.getBlockPos();
                        plotOffset = new net.minecraft.core.Vec3i(abs.getX() - x, abs.getY() - y, abs.getZ() - z);
                    }
                }
            }
        }

        HostileSkies.LOGGER.info("Placed {}: {} burner(s), {} wheel(s), {} engine(s), " +
                        "{} captain seat(s), {} crew seat(s), {} loot container(s), {} blaze burner(s), plotOffset=({},{},{})",
                ship.name, burnerPositions.size(), steeringWheelPositions.size(),
                enginePositions.size(), captainSeats.size(), crewSeats.size(), lootCount,
                blazeBurnerPositions.size(),
                plotOffset.getX(), plotOffset.getY(), plotOffset.getZ());

        return new PlacementResult(burnerPositions, steeringWheelPositions, enginePositions,
                captainSeats, crewSeats, lootContainerPositions, blazeBurnerPositions,
                template.getSize(), plotOffset, lootCount);
    }

    // Fragile block filter
    /** Skips blocks that fail canSurvive() during placement (redstone wire, carpets).
     * Used in pass 1; pass 2 places them after supports exist. */
    private static final FragileBlockFilter FRAGILE_BLOCK_FILTER = new FragileBlockFilter();

    private static class FragileBlockFilter extends net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor {

        @Override
        public net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo processBlock(
                net.minecraft.world.level.LevelReader level,
                BlockPos offset,
                BlockPos pos,
                net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo originalBlock,
                net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo modifiedBlock,
                StructurePlaceSettings settings) {
            Block block = modifiedBlock.state().getBlock();
            if (block instanceof net.minecraft.world.level.block.RedStoneWireBlock
                    || block instanceof net.minecraft.world.level.block.WoolCarpetBlock) {
                return null;
            }
            return modifiedBlock;
        }

        @Override
        protected net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType<?> getType() {
            return net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType.NOP;
        }
    }
}
