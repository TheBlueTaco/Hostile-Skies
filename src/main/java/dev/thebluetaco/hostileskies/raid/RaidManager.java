package dev.thebluetaco.hostileskies.raid;

import dev.eriksonn.aeronautics.content.blocks.hot_air.balloon.Balloon;
import dev.eriksonn.aeronautics.content.blocks.hot_air.balloon.ServerBalloon;
import dev.eriksonn.aeronautics.content.blocks.hot_air.hot_air_burner.HotAirBurnerBlockEntity;
import dev.eriksonn.aeronautics.content.blocks.hot_air.lifting_gas.LiftingGasData;
import dev.eriksonn.aeronautics.content.blocks.hot_air.lifting_gas.LiftingGasHolder;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.EmbeddedPlotLevelAccessor;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;
import dev.simulated_team.simulated.content.blocks.portable_engine.PortableEngineBlockEntity;
import dev.simulated_team.simulated.content.blocks.throttle_lever.ThrottleLeverBlockEntity;
import dev.thebluetaco.hostileskies.HostileSkies;
import dev.thebluetaco.hostileskies.command.SpawnRaidCommand;
import dev.thebluetaco.hostileskies.entity.HelmChainsEntity;
import com.simibubi.create.content.contraptions.actors.seat.SeatBlock;
import com.simibubi.create.content.redstone.analogLever.AnalogLeverBlockEntity;
import dev.thebluetaco.hostileskies.registry.ModEntityTypes;
import dev.thebluetaco.hostileskies.ship.ShipNavigator;
import dev.thebluetaco.hostileskies.ship.ShipRegistry;
import dev.thebluetaco.hostileskies.ship.ShipTemplate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.event.EventHooks;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The raid encounter lifecycle lives here. Phase transitions, timers, crew, fuel, loot,
 * capture, and persistence. Navigation lives in ShipNavigator. */
public class RaidManager {

    private static final List<TrackedRaid> activeRaids = new ArrayList<>();
    private static final java.util.Set<BlockPos> lockedWheelPositions = new java.util.HashSet<>();

    // Timing
    private static final int MAX_INIT_TICKS = 200;
    private static final int INIT_SETTLE_TICKS = 3;
    private static final int CREW_SETTLE_TICKS = 10;
    private static final int PATROL_TICKS = 14 * 60 * 20;
    private static final int BORED_SECOND_BUMP_TICKS = 10 * 20;
    private static final int EMERGENCY_BELL_INTERVAL = 20;
    private static final int VENT_MIN_INTERVAL = 3 * 20;
    private static final int VENT_MAX_INTERVAL = 8 * 20;
    private static final int RATTLE_MIN_INTERVAL = 3 * 20;
    private static final int RATTLE_MAX_INTERVAL = 8 * 20;

    // Might change these sounds out for custom ones in the future
    private static final String BORED_HORN_SOUND = "minecraft:item.goat_horn.sound.7";
    private static final String EMERGENCY_START_SOUND = "simulated:block.physics_assembler.assemble";
    private static final String EMERGENCY_BELL_SOUND = "create:haunted_bell_use";
    private static final String EMERGENCY_VENT_SOUND = "aeronautics:block.steam_vent.open";

    private static boolean isDeparting(RaidPhase phase) {
        return phase == RaidPhase.DEPARTING_BORED || phase == RaidPhase.DEPARTING_EMERGENCY;
    }

    // Fuel
    private static final int FUEL_TOPOFF_INTERVAL = 1200;
    private static final int FUEL_TOPOFF_AMOUNT = 4000;
    /** Ticks of forced steam-vent efficiency while the boiler multiblock forms. */
    private static final int STEAM_BRIDGE_TICKS = 100;

    // Crew
    public static final String CAPTAIN_TAG = "hostile_skies_captain";
    public static final String ACTIVATED_CAPTAIN_TAG = "hostile_skies_activated_captain";
    public static final String CAPTAIN_RAID_PREFIX = "hostile_skies_raid_";

    // Persistence
    private static final int SYNC_INTERVAL = 200;
    private static final int RESTORE_TIMEOUT_TICKS = 1200;
    /** Max ticks to hold a restored ship while its balloon re-attaches.
     *  Without this, ships fall ~10 blocks per reload before lift kicks in. */
    private static final int RESTORE_HOLD_MAX_TICKS = 100;
    private static boolean restored = false;
    private static boolean restoreParsed = false;
    private static int restoreTicksWaited = 0;
    /** Deserialized raids waiting for Sable to make their sub-level findable. */
    private static final List<TrackedRaid> pendingRestore = new ArrayList<>();

    // Diagnostics
    private static ShipNavigator.DiagMode pendingDiagMode = null;
    private static double pendingDiagAngle = 0.0;

    // Public API

    public static String setNavDiagnostics(ShipNavigator.DiagMode mode, double wheelAngle) {
        if (activeRaids.isEmpty()) {
            if (mode == ShipNavigator.DiagMode.OFF) {
                pendingDiagMode = null;
                return "(pending diag cleared)";
            }
            pendingDiagMode = mode;
            pendingDiagAngle = wheelAngle;
            return "(armed for next spawn)";
        }
        TrackedRaid raid = activeRaids.getLast();
        raid.navigator.setDiagnostics(mode, wheelAngle);
        return raid.ship.name;
    }

    /** Active non-evicted raids counting toward the global cap. */
    public static int getActiveRaidCount() {
        int count = 0;
        for (TrackedRaid raid : activeRaids) {
            if (!raid.evicted) count++;
        }
        return count;
    }

    /**
     * Evicts the oldest unloaded, never-engaged raid to free a cap slot.
     * The evicted raid departs when next loaded. Loaded/engaged raids are never evicted. */
    public static boolean tryEvictForCapacity() {
        TrackedRaid oldest = null;
        for (TrackedRaid raid : activeRaids) {
            if (raid.evicted || raid.engaged) continue;
            if (raid.phase == RaidPhase.CAPTURED) continue;
            ServerSubLevelContainer container = SubLevelContainer.getContainer(raid.level);
            if (container == null) continue;
            if (container.getSubLevel(raid.subLevelId) != null) continue;
            if (oldest == null || raid.spawnTick < oldest.spawnTick) {
                oldest = raid;
            }
        }
        if (oldest == null) return false;

        oldest.evicted = true;
        HostileSkies.LOGGER.info(
                "Evicted raid {} ({}) for capacity. It will depart when next loaded",
                oldest.ship.name, oldest.subLevelId);
        return true;
    }

    /** True if the wheel position is currently locked by a raid. */
    public static boolean isWheelLocked(BlockPos plotAbsPos) {
        return lockedWheelPositions.contains(plotAbsPos);
    }

    /** Finds the raid where this captain spawned from and triggers emergency departure. */
    public static void onCaptainKilledForRaid(net.minecraft.world.entity.Mob captain) {
        for (String tag : captain.getTags()) {
            if (tag.startsWith(CAPTAIN_RAID_PREFIX)) {
                try {
                    UUID raidId = UUID.fromString(tag.substring(CAPTAIN_RAID_PREFIX.length()));
                    for (TrackedRaid raid : activeRaids) {
                        if (raid.subLevelId.equals(raidId)) {
                            if (raid.phase != RaidPhase.CAPTURED
                                    && raid.phase != RaidPhase.DEPARTING_EMERGENCY) {
                                beginEmergencyDeparture(raid);
                            }
                            return;
                        }
                    }
                } catch (IllegalArgumentException e) {
                    HostileSkies.LOGGER.warn("Invalid raid UUID in captain tag: {}", tag);
                }
            }
        }
    }

    /** Transitions the raid at this wheel to CAPTURED, then all mod control ceases. */
    public static void captureShipAtWheel(BlockPos wheelPlotAbs) {
        for (TrackedRaid raid : activeRaids) {
            if (wheelPlotAbs.equals(raid.lockedWheelPos)) {
                lockedWheelPositions.remove(raid.lockedWheelPos);
                transition(raid, RaidPhase.CAPTURED, "ship captured by player");
                HostileSkies.LOGGER.info("Ship captured! Sub-level {} released to player",
                        raid.subLevelId);
                return;
            }
        }
        HostileSkies.LOGGER.warn("captureShipAtWheel: no raid found for wheel at {}", wheelPlotAbs);
    }

    /**
     * True if the player is locked by an active raid: targeted by a non-evicted
     * raid that is either engaged or currently loaded. Unloaded raids that were never
     * engaged don't lock. The next raid supersedes them via registerTargetedPlayers. */
    public static boolean isPlayerInActiveRaid(UUID playerId) {
        for (TrackedRaid raid : activeRaids) {
            if (!raid.targetedPlayerIds.contains(playerId)) continue;
            if (raid.evicted) continue;
            if (raid.engaged) return true;
            ServerSubLevelContainer container = SubLevelContainer.getContainer(raid.level);
            if (container != null && container.getSubLevel(raid.subLevelId) != null) {
                return true;
            }
        }
        return false;
    }

    /** Associates player UUIDs with the most recent raid and evicts any older
     * unloaded never-engaged raids targeting the same players. */
    public static void registerTargetedPlayers(List<UUID> playerIds) {
        if (activeRaids.isEmpty()) return;
        TrackedRaid latest = activeRaids.getLast();

        for (TrackedRaid raid : activeRaids) {
            if (raid == latest || raid.evicted || raid.engaged) continue;
            boolean shared = false;
            for (UUID id : playerIds) {
                if (raid.targetedPlayerIds.contains(id)) {
                    shared = true;
                    break;
                }
            }
            if (!shared) continue;
            ServerSubLevelContainer container = SubLevelContainer.getContainer(raid.level);
            if (container == null || container.getSubLevel(raid.subLevelId) != null) {
                continue;
            }
            raid.evicted = true;
            HostileSkies.LOGGER.info(
                    "Superseded raid {} ({}). Its target has a new raid. Departs when next loaded",
                    raid.ship.name, raid.subLevelId);
        }

        latest.targetedPlayerIds.addAll(playerIds);
        HostileSkies.debug("Registered {} targeted player(s) for raid {}",
                playerIds.size(), latest.subLevelId);
    }

    public static void onServerStopping(MinecraftServer server) {
        syncToSavedData(server);
        HostileSkies.LOGGER.info("Server stopping... Saved {} active raid(s)", activeRaids.size());
        DestructionSequence.clearAllDebris();
        activeRaids.clear();
        lockedWheelPositions.clear();
        pendingRestore.clear();
        restored = false;
        restoreParsed = false;
        restoreTicksWaited = 0;
    }

    private static void unlockAndDiscardChain(TrackedRaid raid) {
        if (raid.chainEntity != null) {
            raid.chainEntity.discard();
            raid.chainEntity = null;
        }
        if (raid.lockedWheelPos != null) {
            lockedWheelPositions.remove(raid.lockedWheelPos);
            raid.lockedWheelPos = null;
        }
    }

    public static void track(ServerLevel level, SubLevel subLevel, ShipTemplate ship,
                             Vec3 spawnOrigin, Vec3 patrolCenter, Quaterniond spawnOrientation,
                             SpawnRaidCommand.PlacementResult placement, int badOmenLevel,
                             double orbitSign) {
        TrackedRaid raid = new TrackedRaid(
                level, subLevel.getUniqueId(), ship, spawnOrigin, patrolCenter,
                new Quaterniond(spawnOrientation),
                level.getServer().getTickCount(),
                placement.burnerPositions(),
                placement.steeringWheelPositions(),
                placement.enginePositions(),
                placement.captainSeats(),
                placement.crewSeats(),
                placement.lootContainerPositions(),
                placement.blazeBurnerPositions(),
                placement.structureSize(),
                placement.plotOffset(),
                badOmenLevel
        );
        raid.navigator.setOrbitSign(orbitSign);
        if (pendingDiagMode != null) {
            raid.navigator.setDiagnostics(pendingDiagMode, pendingDiagAngle);
            pendingDiagMode = null;
        }
        activeRaids.add(raid);
    }

    // Main tick

    public static void tick(MinecraftServer server) {
        if (!restored) {
            tickRestore(server);
        }

        // Clean up expired debris
        for (ServerLevel level : server.getAllLevels()) {
            DestructionSequence.tickDebrisCleanup(level);
        }

        if (activeRaids.isEmpty()) return;
        int currentTick = server.getTickCount();

        activeRaids.removeIf(raid -> {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(raid.level);
            if (container == null) return true;

            SubLevel subLevel = container.getSubLevel(raid.subLevelId);
            if (subLevel == null) {
                raid.loadedLastTick = false;
                return false;
            }
            if (subLevel.isRemoved()) {
                unlockAndDiscardChain(raid);
                applyEndCooldowns(raid, server);
                return true;
            }
            if (!(subLevel instanceof ServerSubLevel ssl)) return true;

            // stopraid marked this while unloaded, despawn when it loads
            // stopraid marked this while unloaded — give Sable ~2s to settle
            // the freshly loaded plot (heat map pass), then despawn
            if (raid.pendingRemoval) {
                raid.removalDelayTicks++;
                if (raid.removalDelayTicks >= 1) {
                    despawn(raid, subLevel);
                    return true;
                }
                return false;
            }

            // rearms the steam bridge on every unload load transition so steam ships don't sink while the tank/boiler multiblock reforms.
            if (!raid.loadedLastTick) {
                raid.loadedLastTick = true;
                raid.steamBridgeTicks = STEAM_BRIDGE_TICKS;
                raid.bridgeFillSucceeded = false;
                raid.bridgeExtensions = 0;
                applyFuelTopOff(raid, ssl, "reload");
                HostileSkies.debug("Raid {} reloaded. Steam bridge rearmed", raid.subLevelId);
            }

            // freeze ship at saved pose until balloon reattaches
            if (raid.restoreHoldTicks > 0) {
                if (raid.holdPos == null) {
                    Pose3d pose = ssl.logicalPose();
                    raid.holdPos = new Vector3d(pose.position());
                    raid.holdRot = new Quaterniond(pose.orientation());
                    HostileSkies.debug("Restore hold engaged for {} at y={}",
                            raid.ship.name, String.format("%.0f", raid.holdPos.y));
                }
                RigidBodyHandle holdHandle = RigidBodyHandle.of(ssl);
                if (holdHandle != null) {
                    holdHandle.teleport(raid.holdPos, raid.holdRot);
                }
                raid.restoreHoldTicks--;
                tickFuel(raid, ssl, currentTick - raid.spawnTick);
                if (raid.bridgeFillSucceeded || raid.restoreHoldTicks == 0) {
                    HostileSkies.LOGGER.info("Restore hold released for {} ({})",
                            raid.ship.name,
                            raid.bridgeFillSucceeded ? "balloon attached" : "timeout");
                    raid.restoreHoldTicks = 0;
                    raid.holdPos = null;
                    raid.holdRot = null;
                }
                return false;
            }

            // Respawn stale/missing chain entity after chunk unload cycles
            if (raid.crewSpawned && !raid.steeringWheelPositions.isEmpty()
                    && raid.phase != RaidPhase.CAPTURED && !isDeparting(raid.phase)
                    && (raid.chainEntity == null || raid.chainEntity.isRemoved())) {
                HostileSkies.LOGGER.info("Chain entity stale/missing. Respawning..");
                spawnChainEntity(raid, ssl);
            }

            // Evicted raids depart as soon as they load
            if (raid.evicted && !isDeparting(raid.phase) && raid.phase != RaidPhase.CAPTURED) {
                if (raid.phase == RaidPhase.MERCY) restoreNormalControls(raid, ssl);
                beginBoredDeparture(raid, ssl, "evicted for capacity");
            }

            int ticksAlive = currentTick - raid.spawnTick;

            switch (raid.phase) {
                case INITIALIZING -> tickInit(raid, ssl, ticksAlive);
                case APPROACHING  -> tickApproach(raid, ssl);
                case PATROLLING   -> tickPatrol(raid, ssl);
                case MERCY        -> tickMercy(raid, ssl);
                case DEPARTING_BORED -> {
                    if (tickDepartBored(raid, ssl)) {
                        despawn(raid, subLevel);
                        applyEndCooldowns(raid, server);
                        return true;
                    }
                }
                case DEPARTING_EMERGENCY -> {
                    if (tickDepartEmergency(raid, ssl)) {
                        despawn(raid, subLevel);
                        applyEndCooldowns(raid, server);
                        return true;
                    }
                }
                case CAPTURED -> {
                    unlockAndDiscardChain(raid);
                    applyEndCooldowns(raid, server);
                    return true;
                }
            }

            if (!isDeparting(raid.phase) && raid.phase != RaidPhase.CAPTURED) {
                tickFuel(raid, ssl, ticksAlive);
            }

            return false;
        });

        if (currentTick % SYNC_INTERVAL == 0) {
            syncToSavedData(server);
        }
    }

    public static int clearAll(ServerLevel level) {
        int removed = 0;
        int deferred = 0;
        MinecraftServer server = level.getServer();
        java.util.Iterator<TrackedRaid> it = activeRaids.iterator();
        while (it.hasNext()) {
            TrackedRaid raid = it.next();
            applyEndCooldowns(raid, server);
            unlockAndDiscardChain(raid);

            ServerSubLevelContainer container = SubLevelContainer.getContainer(raid.level);
            SubLevel sl = container != null ? container.getSubLevel(raid.subLevelId) : null;
            if (sl != null && !sl.isRemoved()) {
                despawn(raid, sl);
                it.remove();
                removed++;
            } else {
                // Currently unloaded, despawn the next time it loads
                raid.pendingRemoval = true;
                deferred++;
            }
        }
        syncToSavedData(server);
        HostileSkies.LOGGER.info("Cleared {} raid(s), {} unloaded marked for removal on load",
                removed, deferred);
        return removed + deferred;
    }

    // Cooldowns

    private static void applyEndCooldowns(TrackedRaid raid, MinecraftServer server) {
        if (raid.targetedPlayerIds.isEmpty()) return;
        RaidSavedData data = RaidSavedData.get(server);
        long tick = server.getTickCount();
        for (UUID playerId : raid.targetedPlayerIds) {
            data.applyCooldown(playerId, tick);
        }
        HostileSkies.LOGGER.info("Applied {} personal cooldown(s) for ended raid",
                raid.targetedPlayerIds.size());
    }

    // Persistence

    /** Serializes all active raids and pending restores into RaidSavedData. */
    public static void syncToSavedData(MinecraftServer server) {
        RaidSavedData data = RaidSavedData.get(server);
        ListTag list = new ListTag();
        int currentTick = server.getTickCount();
        for (TrackedRaid raid : activeRaids) {
            refreshLastKnownPose(raid);
            list.add(serializeRaid(raid, currentTick));
        }
        for (TrackedRaid raid : pendingRestore) {
            list.add(serializeRaid(raid, currentTick));
        }
        data.setActiveRaidsNbt(list);
    }

    /** captures the ship's current pose for restoring accuracy. */
    private static void refreshLastKnownPose(TrackedRaid raid) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(raid.level);
        if (container == null) return;
        SubLevel sl = container.getSubLevel(raid.subLevelId);
        if (sl instanceof ServerSubLevel ssl && !sl.isRemoved()) {
            Pose3d pose = ssl.logicalPose();
            raid.lastKnownPos = new Vector3d(pose.position());
            raid.lastKnownRot = new Quaterniond(pose.orientation());
        }
    }

    /** parses saved raids once, then attaches each to its sub-level the same
     * tick Sable makes it findable. Drops any that never appear after RESTORE_TIMEOUT_TICKS. */
    private static void tickRestore(MinecraftServer server) {
        RaidSavedData data = RaidSavedData.get(server);
        int currentTick = server.getTickCount();

        if (!restoreParsed) {
            ListTag list = data.getStoredRaidsNbt();
            for (int i = 0; i < list.size(); i++) {
                TrackedRaid raid = deserializeRaid(list.getCompound(i), server, currentTick);
                if (raid != null) pendingRestore.add(raid);
            }
            restoreParsed = true;
            if (pendingRestore.isEmpty()) {
                restored = true;
                data.clearStoredRaidsNbt();
                return;
            }
            HostileSkies.LOGGER.info("Restore: {} raid(s) pending sub-level attach", pendingRestore.size());
        }

        restoreTicksWaited++;
        pendingRestore.removeIf(raid -> {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(raid.level);
            if (container == null) return false;
            SubLevel sl = container.getSubLevel(raid.subLevelId);
            if (sl == null || sl.isRemoved()) return false;
            activeRaids.add(raid);
            HostileSkies.LOGGER.info("Restored raid: {} ({}), phase={} (attached at tick {})",
                    raid.ship.name, raid.subLevelId, raid.phase, currentTick);
            return true;
        });

        if (pendingRestore.isEmpty()) {
            restored = true;
            data.clearStoredRaidsNbt();
            HostileSkies.LOGGER.info("Raid restore complete (at tick {})", currentTick);
        } else if (restoreTicksWaited > RESTORE_TIMEOUT_TICKS) {
            for (TrackedRaid raid : pendingRestore) {
                HostileSkies.LOGGER.warn("Raid {} ({}) — sub-level never appeared, dropping.",
                        raid.ship.name, raid.subLevelId);
            }
            pendingRestore.clear();
            restored = true;
            data.clearStoredRaidsNbt();
        }
    }

    private static CompoundTag serializeRaid(TrackedRaid raid, int currentTick) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("subLevelId", raid.subLevelId);
        tag.putString("shipId", raid.ship.id);
        tag.putString("dimension", raid.level.dimension().location().toString());

        tag.putDouble("spawnX", raid.spawnOrigin.x);
        tag.putDouble("spawnY", raid.spawnOrigin.y);
        tag.putDouble("spawnZ", raid.spawnOrigin.z);
        tag.putDouble("patrolX", raid.patrolCenter.x);
        tag.putDouble("patrolY", raid.patrolCenter.y);
        tag.putDouble("patrolZ", raid.patrolCenter.z);

        tag.putDouble("orientW", raid.spawnOrientation.w());
        tag.putDouble("orientX", raid.spawnOrientation.x());
        tag.putDouble("orientY", raid.spawnOrientation.y());
        tag.putDouble("orientZ", raid.spawnOrientation.z());

        tag.putLongArray("burners", posListToLongs(raid.burnerPositions));
        tag.putLongArray("wheels", posListToLongs(raid.steeringWheelPositions));
        tag.putLongArray("engines", posListToLongs(raid.enginePositions));
        tag.putLongArray("captainSeats", posListToLongs(raid.captainSeats));
        tag.putLongArray("crewSeats", posListToLongs(raid.crewSeats));
        tag.putLongArray("lootContainers", posListToLongs(raid.lootContainerPositions));
        tag.putLongArray("blazeBurners", posListToLongs(raid.blazeBurnerPositions));

        tag.putInt("sizeX", raid.structureSize.getX());
        tag.putInt("sizeY", raid.structureSize.getY());
        tag.putInt("sizeZ", raid.structureSize.getZ());
        tag.putInt("plotOffX", raid.plotOffset.getX());
        tag.putInt("plotOffY", raid.plotOffset.getY());
        tag.putInt("plotOffZ", raid.plotOffset.getZ());
        tag.putInt("badOmenLevel", raid.badOmenLevel);

        tag.putString("phase", raid.phase.name());
        tag.putInt("patrolTicksRemaining", raid.patrolTicksRemaining);
        tag.putBoolean("controlsApplied", raid.controlsApplied);
        tag.putBoolean("balloonFilled", raid.balloonFilled);
        tag.putBoolean("crewSpawned", raid.crewSpawned);
        tag.putBoolean("engaged", raid.engaged);
        tag.putBoolean("evicted", raid.evicted);
        tag.putBoolean("pendingRemoval", raid.pendingRemoval);
        tag.putInt("departTicks", raid.departTicks);
        tag.putInt("ticksAlive", currentTick - raid.spawnTick);

        if (raid.lastKnownPos != null) {
            tag.putDouble("poseX", raid.lastKnownPos.x);
            tag.putDouble("poseY", raid.lastKnownPos.y);
            tag.putDouble("poseZ", raid.lastKnownPos.z);
            tag.putDouble("poseQX", raid.lastKnownRot.x);
            tag.putDouble("poseQY", raid.lastKnownRot.y);
            tag.putDouble("poseQZ", raid.lastKnownRot.z);
            tag.putDouble("poseQW", raid.lastKnownRot.w);
        }

        raid.navigator.save(tag);

        ListTag players = new ListTag();
        for (UUID id : raid.targetedPlayerIds) {
            CompoundTag p = new CompoundTag();
            p.putUUID("id", id);
            players.add(p);
        }
        tag.put("targetedPlayers", players);

        return tag;
    }

    private static TrackedRaid deserializeRaid(CompoundTag tag, MinecraftServer server, int currentTick) {
        String shipId = tag.getString("shipId");
        ShipTemplate ship = ShipRegistry.get(shipId);
        if (ship == null) {
            HostileSkies.LOGGER.warn("Ship '{}' not in registry. Skipping raid restore...", shipId);
            return null;
        }

        // Saves before 0.2.0 have no dimension tag
        ServerLevel level = server.overworld();
        if (tag.contains("dimension")) {
            ResourceLocation dimId = ResourceLocation.tryParse(tag.getString("dimension"));
            ServerLevel found = dimId == null ? null
                    : server.getLevel(ResourceKey.create(Registries.DIMENSION, dimId));
            if (found == null) {
                HostileSkies.LOGGER.warn("Dimension '{}' for raid {} no longer exists. Skipping raid restore...",
                        tag.getString("dimension"), shipId);
                return null;
            }
            level = found;
        }

        UUID subLevelId = tag.getUUID("subLevelId");
        Vec3 spawnOrigin = new Vec3(
                tag.getDouble("spawnX"), tag.getDouble("spawnY"), tag.getDouble("spawnZ"));
        Vec3 patrolCenter = new Vec3(
                tag.getDouble("patrolX"), tag.getDouble("patrolY"), tag.getDouble("patrolZ"));
        Quaterniond orientation = new Quaterniond(
                tag.getDouble("orientX"), tag.getDouble("orientY"),
                tag.getDouble("orientZ"), tag.getDouble("orientW"));

        int ticksAlive = tag.getInt("ticksAlive");
        int spawnTick = currentTick - ticksAlive;

        TrackedRaid raid = new TrackedRaid(
                level, subLevelId, ship, spawnOrigin, patrolCenter,
                orientation, spawnTick,
                longsToPosList(tag.getLongArray("burners")),
                longsToPosList(tag.getLongArray("wheels")),
                longsToPosList(tag.getLongArray("engines")),
                longsToPosList(tag.getLongArray("captainSeats")),
                longsToPosList(tag.getLongArray("crewSeats")),
                longsToPosList(tag.getLongArray("lootContainers")),
                tag.contains("blazeBurners")
                        ? longsToPosList(tag.getLongArray("blazeBurners"))
                        : new ArrayList<>(),
                new net.minecraft.core.Vec3i(
                        tag.getInt("sizeX"), tag.getInt("sizeY"), tag.getInt("sizeZ")),
                tag.contains("plotOffX")
                        ? new net.minecraft.core.Vec3i(tag.getInt("plotOffX"), tag.getInt("plotOffY"), tag.getInt("plotOffZ"))
                        : net.minecraft.core.Vec3i.ZERO,
                tag.getInt("badOmenLevel")
        );

        String phaseName = tag.getString("phase");
        if ("DEPARTING".equals(phaseName)) phaseName = "DEPARTING_BORED"; // Saves before 0.2.0 still work
        raid.phase = RaidPhase.valueOf(phaseName);
        raid.patrolTicksRemaining = tag.getInt("patrolTicksRemaining");
        raid.controlsApplied = tag.getBoolean("controlsApplied");
        raid.balloonFilled = tag.getBoolean("balloonFilled");
        raid.crewSpawned = tag.getBoolean("crewSpawned");
        raid.loadedLastTick = false;
        raid.restoreHoldTicks = RESTORE_HOLD_MAX_TICKS;
        if (tag.contains("poseX")) {
            raid.holdPos = new Vector3d(
                    tag.getDouble("poseX"), tag.getDouble("poseY"), tag.getDouble("poseZ"));
            raid.holdRot = new Quaterniond(
                    tag.getDouble("poseQX"), tag.getDouble("poseQY"),
                    tag.getDouble("poseQZ"), tag.getDouble("poseQW"));
        }
        raid.engaged = tag.getBoolean("engaged");
        raid.evicted = tag.getBoolean("evicted");
        raid.pendingRemoval = tag.getBoolean("pendingRemoval");
        raid.departTicks = tag.getInt("departTicks");

        raid.navigator.load(tag);

        if (raid.phase == RaidPhase.MERCY) {
            ShipTemplate.ControlGroup throttleGroup = raid.ship.controls.get("throttle");
            if (throttleGroup != null && throttleGroup.mercySignal >= 0) {
                raid.navigator.setThrottleBaseOverride(throttleGroup.mercySignal);
            }
        }

        if (tag.contains("targetedPlayers")) {
            ListTag players = tag.getList("targetedPlayers", Tag.TAG_COMPOUND);
            for (int i = 0; i < players.size(); i++) {
                raid.targetedPlayerIds.add(players.getCompound(i).getUUID("id"));
            }
        }

        return raid;
    }

    private static long[] posListToLongs(List<BlockPos> positions) {
        long[] longs = new long[positions.size()];
        for (int i = 0; i < positions.size(); i++) {
            longs[i] = positions.get(i).asLong();
        }
        return longs;
    }

    private static List<BlockPos> longsToPosList(long[] longs) {
        List<BlockPos> result = new ArrayList<>(longs.length);
        for (long l : longs) {
            result.add(BlockPos.of(l));
        }
        return result;
    }

    // Initialization

    private static void tickInit(TrackedRaid raid, ServerSubLevel sl, int ticksAlive) {
        RigidBodyHandle handle = RigidBodyHandle.of(sl);
        if (handle != null) {
            handle.teleport(toVec(raid.spawnOrigin), raid.spawnOrientation);
        }

        if (!raid.controlsApplied) {
            applyShipControls(raid, sl);
            raid.controlsApplied = true;
        }

        if (!raid.balloonFilled) {
            if (tryPreFillBalloon(raid, sl) == BalloonProbe.FILLED) {
                raid.balloonFilled = true;
                raid.balloonFilledTick = ticksAlive;
            } else if (ticksAlive > MAX_INIT_TICKS) {
                raid.balloonFilled = true;
                raid.balloonFilledTick = ticksAlive;
            }
            return;
        }

        if (ticksAlive - raid.balloonFilledTick >= INIT_SETTLE_TICKS) {
            if (!raid.crewSpawned) {
                spawnCrew(raid, sl);
                playSpawnHorn(raid.level, raid.spawnOrigin);
                applyFuelTopOff(raid, sl, "init");
                applyLootMultiplier(raid, sl);
                raid.crewSpawned = true;
                raid.crewSpawnedTick = ticksAlive;
                return;
            }

            if (ticksAlive - raid.crewSpawnedTick >= CREW_SETTLE_TICKS) {
                tryPreFillBalloon(raid, sl);
                if (handle != null) {
                    handle.teleport(toVec(raid.spawnOrigin), raid.spawnOrientation);
                }
                transition(raid, RaidPhase.APPROACHING, "init complete");
            }
        }
    }

    /** Sets a throttle or analog lever to the given signal. Returns false if the block entity is neither.
     *  Analog levers only expose changeState() + or -1, and its redstone output updates 15 ticks after the last step. */
    public static boolean setLeverSignal(BlockEntity be, int signal) {
        if (be instanceof ThrottleLeverBlockEntity lever) {
            lever.setSignal(signal);
            return true;
        }
        if (be instanceof AnalogLeverBlockEntity lever) {
            int target = Mth.clamp(signal, 0, 15);
            while (lever.getState() != target) lever.changeState(lever.getState() > target);
            return true;
        }
        return false;
    }

    private static void applyShipControls(TrackedRaid raid, ServerSubLevel sl) {
        EmbeddedPlotLevelAccessor acc = sl.getPlot().getEmbeddedLevelAccessor();

        for (Map.Entry<String, ShipTemplate.ControlGroup> entry : raid.ship.controls.entrySet()) {
            String groupName = entry.getKey();
            ShipTemplate.ControlGroup group = entry.getValue();

            for (int[] pos : group.levers) {
                BlockPos leverPos = new BlockPos(pos[0], pos[1], pos[2]);
                BlockEntity be = acc.getBlockEntity(leverPos);
                if (setLeverSignal(be, group.signal)) {
                    HostileSkies.debug("[{}] lever at {}: setSignal({})", groupName, leverPos, group.signal);
                } else {
                    HostileSkies.LOGGER.warn("[{}] expected a lever at {}, got {}",
                            groupName, leverPos, be != null ? be.getClass().getSimpleName() : "null");
                }
            }
        }
    }

    private static void applyFuelTopOff(TrackedRaid raid, ServerSubLevel sl, String context) {
        EmbeddedPlotLevelAccessor acc = sl.getPlot().getEmbeddedLevelAccessor();

        for (BlockPos pos : raid.enginePositions) {
            BlockEntity be = acc.getBlockEntity(pos);
            if (be instanceof PortableEngineBlockEntity engine) {
                int before = engine.getCurrentBurnTime();
                engine.setCurrentBurnTime(FUEL_TOPOFF_AMOUNT);
                engine.notifyUpdate();
                int after = engine.getCurrentBurnTime();
                HostileSkies.debug("Fuel top-off [{}]: engine at {} was {} -> set {} -> now {}",
                        context, pos, before, FUEL_TOPOFF_AMOUNT, after);
            } else {
                HostileSkies.LOGGER.warn("Engine at {}: got {} instead of PortableEngineBlockEntity",
                        pos, be != null ? be.getClass().getSimpleName() : "null");
            }
        }

        for (BlockPos pos : raid.blazeBurnerPositions) {
            BlockEntity be = acc.getBlockEntity(pos);
            if (be instanceof com.simibubi.create.content.processing.burner.BlazeBurnerBlockEntity blazeBurner) {
                try {
                    Class<?> bbClass = blazeBurner.getClass();
                    java.lang.reflect.Field burnTimeField = bbClass.getDeclaredField("remainingBurnTime");
                    burnTimeField.setAccessible(true);
                    burnTimeField.setInt(blazeBurner, FUEL_TOPOFF_AMOUNT);

                    java.lang.reflect.Field fuelField = bbClass.getDeclaredField("activeFuel");
                    fuelField.setAccessible(true);
                    fuelField.set(blazeBurner,
                            com.simibubi.create.content.processing.burner.BlazeBurnerBlockEntity.FuelType.NORMAL);

                    blazeBurner.notifyUpdate();
                    HostileSkies.debug("Fuel top-off [{}]: blaze burner at {} -> KINDLED ({}t)",
                            context, pos, FUEL_TOPOFF_AMOUNT);
                } catch (Exception e) {
                    HostileSkies.LOGGER.warn("Blaze burner refuel failed at {}: {}", pos, e.getMessage());
                }
            }
        }
    }

    // Crew spawning

    private static final String[] HORN_SOUNDS = {
            "item.goat_horn.sound.2",
            "item.goat_horn.sound.4",
            "item.goat_horn.sound.5",
            "item.goat_horn.sound.6",
            "item.goat_horn.sound.7"
    };

    private static void spawnCrew(TrackedRaid raid, ServerSubLevel sl) {
        Pose3d currentPose = sl.logicalPose();
        Quaterniond currentOrientation = new Quaterniond(currentPose.orientation());
        Vec3 currentOrigin = new Vec3(
                currentPose.position().x(),
                currentPose.position().y(),
                currentPose.position().z());
        ShipTemplate.Crew crewCfg = raid.ship.crew;

        if (!raid.captainSeats.isEmpty()) {
            BlockPos seatPlot = raid.captainSeats.get(0);
            Vec3 seatWorld = plotToWorld(currentOrientation, seatPlot, raid.structureSize, currentOrigin);
            Mob spawned = createMob(raid, crewCfg.captainMob, seatWorld, crewCfg.captainWeapon);

            if (spawned instanceof PathfinderMob captain) {
                captain.setPos(seatWorld.x, seatWorld.y, seatWorld.z);

                captain.goalSelector.removeAllGoals(g -> true);
                captain.targetSelector.removeAllGoals(g -> true);
                captain.goalSelector.addGoal(0,
                        new net.minecraft.world.entity.ai.goal.LookAtPlayerGoal(
                                captain, Player.class, 15.0F));

                captain.setCustomName(Component.literal("Captain"));
                captain.setCustomNameVisible(true);
                if (crewCfg.captainBanner) {
                    captain.setItemSlot(EquipmentSlot.HEAD,
                            net.minecraft.world.entity.raid.Raid.getLeaderBannerInstance(
                                    captain.registryAccess().lookupOrThrow(Registries.BANNER_PATTERN)));
                }

                captain.addTag(CAPTAIN_TAG);
                captain.addTag(CAPTAIN_RAID_PREFIX + raid.subLevelId.toString());

                raid.level.addFreshEntity(captain);

                // Seat the captain through Create's own path if a real seat block
                // exists here. Else he just stands
                net.minecraft.world.level.block.state.BlockState seatState =
                        sl.getPlot().getEmbeddedLevelAccessor().getBlockState(seatPlot);
                if (seatState.getBlock() instanceof SeatBlock) {
                    BlockPos seatPlotAbs = new BlockPos(
                            seatPlot.getX() + raid.plotOffset.getX(),
                            seatPlot.getY() + raid.plotOffset.getY(),
                            seatPlot.getZ() + raid.plotOffset.getZ());
                    SeatBlock.sitDown(raid.level, seatPlotAbs, captain);
                }

                // Face the helm
                if (!raid.steeringWheelPositions.isEmpty()) {
                    BlockPos wheelPos = raid.steeringWheelPositions.get(0);
                    double dx = wheelPos.getX() - seatPlot.getX();
                    double dz = wheelPos.getZ() - seatPlot.getZ();
                    float localYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
                    float shipYaw = (float) Math.toDegrees(ShipNavigator.extractYaw(currentOrientation));
                    float finalYaw = localYaw + shipYaw;
                    captain.setYRot(finalYaw);
                    captain.setYHeadRot(finalYaw);
                    captain.setYBodyRot(finalYaw);
                }

                HostileSkies.debug("Spawned captain ({}): plot={}, world=({}, {}, {})",
                        crewCfg.captainMob, seatPlot, seatWorld.x, seatWorld.y, seatWorld.z);
            } else if (spawned != null) {
                spawned.discard();
                HostileSkies.LOGGER.error("Captain '{}' is not a PathfinderMob, raid has no captain", crewCfg.captainMob);
            }
        }

        double crewMultiplier = raid.badOmenLevel > 0
                ? RaidConfig.badOmenCrewMultiplier(raid.badOmenLevel) : 1.0;

        int seatIndex = 0;
        for (ShipTemplate.CrewEntry entry : crewCfg.mobs) {
            int toSpawn = (int) Math.ceil(entry.count * crewMultiplier);
            HostileSkies.debug("Crew: {}x {} (multiplier {})", toSpawn, entry.mob, crewMultiplier);

            for (int i = 0; i < toSpawn; i++) {
                BlockPos seatPlot = raid.crewSeats.isEmpty()
                        ? (raid.captainSeats.isEmpty() ? null : raid.captainSeats.get(0))
                        : raid.crewSeats.get(seatIndex % raid.crewSeats.size());
                if (seatPlot == null) return;
                seatIndex++;

                Vec3 seatWorld = plotToWorld(currentOrientation, seatPlot, raid.structureSize, currentOrigin);
                Mob crew = createMob(raid, entry.mob, seatWorld, entry.weapon);
                if (crew == null) continue;
                crew.setPos(seatWorld.x, seatWorld.y + 1.0, seatWorld.z);
                raid.level.addFreshEntity(crew);
                HostileSkies.debug("Spawned {} {}/{}: plot={}", entry.mob, i + 1, toSpawn, seatPlot);
            }
        }
    }

    /** Creates a mob with its species' default gear, optionally overriding the main hand. */
    private static Mob createMob(TrackedRaid raid, String typeId, Vec3 pos, String weapon) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(typeId));
        Entity entity = type.create(raid.level);
        if (!(entity instanceof Mob mob)) {
            if (entity != null) entity.discard();
            HostileSkies.LOGGER.error("Crew type '{}' is not a Mob, skipping", typeId);
            return null;
        }
        mob.setPos(pos.x, pos.y, pos.z);
        EventHooks.finalizeMobSpawn(mob, raid.level,
                raid.level.getCurrentDifficultyAt(BlockPos.containing(pos)),
                MobSpawnType.EVENT, null);
        if (weapon != null && !weapon.isEmpty()) {
            mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(
                    BuiltInRegistries.ITEM.get(ResourceLocation.parse(weapon))));
        }
        mob.setPersistenceRequired();
        return mob;
    }

    // Chain visual

    /** Spawns a HelmChainsEntity at the wheel and registers it with Sable's tracking. */
    private static void spawnChainEntity(TrackedRaid raid, ServerSubLevel sl) {
        if (raid.steeringWheelPositions.isEmpty()) return;

        EmbeddedPlotLevelAccessor acc = sl.getPlot().getEmbeddedLevelAccessor();
        BlockPos wheelStructPos = raid.steeringWheelPositions.get(0);

        Pose3d pose = sl.logicalPose();
        Quaterniond q = new Quaterniond(pose.orientation());
        Vec3 origin = new Vec3(pose.position().x(), pose.position().y(), pose.position().z());
        Vec3 wheelWorld = plotToWorld(q, wheelStructPos, raid.structureSize, origin);

        HelmChainsEntity chains = new HelmChainsEntity(ModEntityTypes.HELM_CHAINS.get(), raid.level);
        chains.setPos(wheelWorld.x, wheelWorld.y, wheelWorld.z);

        try {
            net.minecraft.world.level.block.state.BlockState wheelState = acc.getBlockState(wheelStructPos);
            net.minecraft.core.Direction facing = wheelState.getValue(
                    dev.simulated_team.simulated.content.blocks.steering_wheel.SteeringWheelBlock.FACING);
            boolean onFloor = wheelState.getValue(
                    dev.simulated_team.simulated.content.blocks.steering_wheel.SteeringWheelBlock.ON_FLOOR);
            chains.setWheelYaw(facing.toYRot());
            chains.setWheelOnFloor(onFloor);
            HostileSkies.debug("Wheel orientation: facing={} (yaw={}), onFloor={}",
                    facing, facing.toYRot(), onFloor);
        } catch (Exception e) {
            HostileSkies.LOGGER.warn("Could not read wheel block state at {}: {}", wheelStructPos, e.getMessage());
        }

        raid.level.addFreshEntity(chains);

        // Plot-anchor at plot-absolute coordinates (structPos + plotOffset)
        Vec3 plotPos = new Vec3(
                wheelStructPos.getX() + raid.plotOffset.getX() + 0.5,
                wheelStructPos.getY() + raid.plotOffset.getY(),
                wheelStructPos.getZ() + raid.plotOffset.getZ() + 0.5);
        ((dev.ryanhcode.sable.mixinterface.entity.entities_stick_sublevels.EntityStickExtension) chains)
                .sable$setPlotPosition(plotPos);

        raid.chainEntity = chains;
        // Wheel lock uses plot-absolute position
        BlockPos wheelAbsPos = new BlockPos(
                wheelStructPos.getX() + raid.plotOffset.getX(),
                wheelStructPos.getY() + raid.plotOffset.getY(),
                wheelStructPos.getZ() + raid.plotOffset.getZ());
        raid.lockedWheelPos = wheelAbsPos.immutable();
        lockedWheelPositions.add(raid.lockedWheelPos);
        HostileSkies.debug("Spawned chain entity at plot={}, plotAbs={}, world=({},{},{})",
                wheelStructPos, wheelAbsPos, (int) wheelWorld.x, (int) wheelWorld.y, (int) wheelWorld.z);
    }

    private static Vec3 plotToWorld(Quaterniond orientation,
                                    BlockPos plotPos, net.minecraft.core.Vec3i structureSize,
                                    Vec3 spawnOrigin) {
        double refX = structureSize.getX() / 2.0 - 0.24;
        double refY = structureSize.getY() / 2.0 - 0.58;
        double refZ = structureSize.getZ() / 2.0 - 0.03;

        double offsetX = (plotPos.getX() + 0.5) - refX;
        double offsetY = plotPos.getY() - refY;
        double offsetZ = (plotPos.getZ() + 0.5) - refZ;

        Vector3d rotated = new Vector3d(offsetX, offsetY, offsetZ);
        orientation.transform(rotated);

        return new Vec3(
                spawnOrigin.x + rotated.x,
                spawnOrigin.y + rotated.y,
                spawnOrigin.z + rotated.z
        );
    }

    // Loot

    /** Force generates and multiplies loot for Bad Omen raids. */
    private static void applyLootMultiplier(TrackedRaid raid, ServerSubLevel sl) {
        if (raid.badOmenLevel <= 0 || raid.lootContainerPositions.isEmpty()) return;

        double multiplier = RaidConfig.badOmenLootMultiplier(raid.badOmenLevel);
        if (multiplier <= 1.0) return;

        EmbeddedPlotLevelAccessor acc = sl.getPlot().getEmbeddedLevelAccessor();
        LootTable tierTable = raid.level.getServer().reloadableRegistries()
                .getLootTable(raid.ship.getLootTableKey());
        ResourceKey<LootTable> captainKey = raid.ship.getCaptainTableKey();
        LootTable captainTable = captainKey != null
                ? raid.level.getServer().reloadableRegistries().getLootTable(captainKey)
                : null;
        int multiplied = 0;

        for (BlockPos pos : raid.lootContainerPositions) {
            BlockEntity be = acc.getBlockEntity(pos);
            if (!(be instanceof RandomizableContainerBlockEntity container)) continue;

            container.setLootTable(null);

            LootParams params = new LootParams.Builder(raid.level)
                    .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                    .create(LootContextParamSets.CHEST);
            LootTable table = (captainTable != null && raid.ship.isCaptainChest(pos))
                    ? captainTable : tierTable;
            table.fill(container, params, raid.level.random.nextLong());

            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (!stack.isEmpty()) {
                    int newCount = (int) Math.ceil(stack.getCount() * multiplier);
                    stack.setCount(Math.min(newCount, stack.getMaxStackSize()));
                }
            }
            multiplied++;
        }

        HostileSkies.debug("Loot multiplier x{} applied to {} container(s) (Bad Omen {})",
                multiplier, multiplied, raid.badOmenLevel);
    }

    /** Activates captain combat AI and marks the raid as engaged. */
    public static void activateCaptain(PathfinderMob captain) {
        for (String tag : captain.getTags()) {
            if (tag.startsWith(CAPTAIN_RAID_PREFIX)) {
                try {
                    UUID raidId = UUID.fromString(tag.substring(CAPTAIN_RAID_PREFIX.length()));
                    for (TrackedRaid raid : activeRaids) {
                        if (raid.subLevelId.equals(raidId)) {
                            raid.engaged = true;
                            break;
                        }
                    }
                } catch (IllegalArgumentException ignored) {}
            }
        }

        captain.removeTag(CAPTAIN_TAG);
        captain.addTag(ACTIVATED_CAPTAIN_TAG);

        boolean wasSeated = captain.isPassenger();
        captain.stopRiding();

        // Nudge away from the seat block so the captain doesn't immediately sit back down
        if (wasSeated) {
            Vec3 look = captain.getLookAngle();
            captain.setPos(
                    captain.getX() + look.x * 1,
                    captain.getY(),
                    captain.getZ() + look.z * 1);
        }

        captain.goalSelector.removeAllGoals(g -> true);

        captain.goalSelector.addGoal(0, new net.minecraft.world.entity.ai.goal.FloatGoal(captain));
        captain.goalSelector.addGoal(3, new net.minecraft.world.entity.ai.goal.MeleeAttackGoal(captain, 0.7, false));
        captain.goalSelector.addGoal(8, new net.minecraft.world.entity.ai.goal.RandomStrollGoal(captain, 0.6));
        captain.goalSelector.addGoal(9, new net.minecraft.world.entity.ai.goal.LookAtPlayerGoal(captain, Player.class, 15.0F, 1.0F));

        captain.targetSelector.addGoal(1, new net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal(captain));
        captain.targetSelector.addGoal(2, new net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal<>(captain, Player.class, true));

        HostileSkies.LOGGER.info("Captain activated! Combat AI enabled.");
    }

    private static void playSpawnHorn(ServerLevel level, Vec3 pos) {
        if (!RaidConfig.enableSpawnHornSound.get()) return;
        String selected = HORN_SOUNDS[level.random.nextInt(HORN_SOUNDS.length)];
        SoundEvent sound = SoundEvent.createVariableRangeEvent(
                ResourceLocation.withDefaultNamespace(selected));
        float volume = (float) (RaidConfig.hornSoundRange.get() / 16.0);
        level.playSound(null, pos.x, pos.y, pos.z, sound, SoundSource.HOSTILE, volume, 1.0F);
        HostileSkies.debug("Played horn sound: {} (range ~{} blocks)",
                selected, RaidConfig.hornSoundRange.get().intValue());
    }

    enum BalloonProbe { NONE, ATTACHED, FILLED }

    private static BalloonProbe tryPreFillBalloon(TrackedRaid raid, ServerSubLevel sl) {
        if (raid.burnerPositions.isEmpty()) return BalloonProbe.FILLED;
        EmbeddedPlotLevelAccessor acc = sl.getPlot().getEmbeddedLevelAccessor();
        boolean filled = false;
        boolean attached = false;

        for (BlockPos pos : raid.burnerPositions) {
            BlockEntity be = acc.getBlockEntity(pos);
            if (be == null) continue;

            Balloon balloon = null;
            if (be instanceof HotAirBurnerBlockEntity burner) {
                balloon = burner.getBalloon();
            } else {
                // force efficiency so canOutputGas() returns true, then trigger balloon discovery with correct targets
                try {
                    Class<?> ventClass = be.getClass();

                    java.lang.reflect.Field effField = ventClass.getDeclaredField("efficiency");
                    effField.setAccessible(true);
                    effField.setDouble(be, 1.0);

                    java.lang.reflect.Method updateSignal = ventClass.getMethod("updateSignal", int.class);
                    int signal = raid.ship.controls.containsKey("lift")
                            ? raid.ship.controls.get("lift").signal : 9;
                    updateSignal.invoke(be, signal);

                    java.lang.reflect.Method tickBalloon = ventClass.getMethod("tickBalloonLogic");
                    tickBalloon.invoke(be);

                    java.lang.reflect.Method getBalloon = ventClass.getMethod("getBalloon");
                    Object result = getBalloon.invoke(be);
                    if (result instanceof Balloon b) balloon = b;
                } catch (Exception e) {
                    HostileSkies.LOGGER.warn("Steam vent init failed at {}: {}", pos, e.getMessage());
                }
            }

            if (!(balloon instanceof ServerBalloon sb)) continue;
            attached = true;

            for (LiftingGasHolder h : sb.getLiftingGasHolders()) {
                LiftingGasData d = h.data();
                if (d.target > 0 && d.amount < d.target) {
                    d.amount = d.target;
                    d.nudge = 0.0;
                    filled = true;
                }
            }
            if (filled) {
                sb.updateGasAmounts();
                HostileSkies.debug("Balloon pre-filled via {} at {}",
                        be.getClass().getSimpleName(), pos);
            }
        }
        return filled ? BalloonProbe.FILLED
                : (attached ? BalloonProbe.ATTACHED : BalloonProbe.NONE);
    }

    // Approaching

    private static void tickApproach(TrackedRaid raid, ServerSubLevel sl) {
        if (raid.navigator.tickApproach(sl)) {
            transition(raid, RaidPhase.PATROLLING, "entered circle");
        }
    }

    // Patrolling

    private static void tickPatrol(TrackedRaid raid, ServerSubLevel sl) {
        boolean playerAboard = isPlayerAboard(raid.level, sl);
        if (playerAboard) raid.engaged = true;
        if (playerAboard && !raid.playerAboardLastTick) {
            debugMessage(raid, "Player detected aboard, pausing patrol timer");
        } else if (!playerAboard && raid.playerAboardLastTick) {
            debugMessage(raid, "Player left, resuming patrol timer. (" +
                    (raid.patrolTicksRemaining / 20) + "s remaining)");
        }
        raid.playerAboardLastTick = playerAboard;

        if (!playerAboard) {
            raid.patrolTicksRemaining--;
            if (raid.patrolTicksRemaining <= 0) {
                raid.navigator.straightenWheel(sl, "depart-straighten");
                beginBoredDeparture(raid, sl, "patrol expired");
                return;
            }
        }

        raid.navigator.tickPatrol(sl);
    }

    // Mercy

    /** Checks if the dying player was aboard a patrolling raid and triggers mercy. */
    public static void onPlayerDeath(ServerPlayer player) {
        for (TrackedRaid raid : activeRaids) {
            if (raid.phase != RaidPhase.PATROLLING) continue;

            ServerSubLevelContainer container = SubLevelContainer.getContainer(raid.level);
            if (container == null) continue;
            SubLevel sl = container.getSubLevel(raid.subLevelId);
            if (sl == null || sl.isRemoved() || !(sl instanceof ServerSubLevel ssl)) continue;

            BoundingBox3dc b = sl.boundingBox();
            AABB area = new AABB(b.minX(), b.minY(), b.minZ(),
                    b.maxX(), b.maxY(), b.maxZ()).inflate(2);
            if (!area.contains(player.position().x, player.position().y, player.position().z))
                continue;

            boolean othersAboard = false;
            for (ServerPlayer other : raid.level.players()) {
                if (other == player) continue;
                if (other.isDeadOrDying()) continue;
                if (area.contains(other.position().x, other.position().y, other.position().z)) {
                    othersAboard = true;
                    break;
                }
            }

            if (!othersAboard) {
                enterMercy(raid, ssl);
            }
        }
    }

    private static void enterMercy(TrackedRaid raid, ServerSubLevel sl) {
        raid.engaged = true;
        transition(raid, RaidPhase.MERCY, "player death aboard");
        applyMercyControls(raid, sl);
    }

    private static void exitMercy(TrackedRaid raid, ServerSubLevel sl) {
        transition(raid, RaidPhase.PATROLLING, "player returned from mercy");
        restoreNormalControls(raid, sl);
    }

    private static void tickMercy(TrackedRaid raid, ServerSubLevel sl) {
        if (isPlayerAboard(raid.level, sl)) {
            exitMercy(raid, sl);
            return;
        }
        raid.navigator.tickPatrol(sl);
    }

    /** Sets levers to mercy signal and informs the navigator of the throttle override. */
    private static void applyMercyControls(TrackedRaid raid, ServerSubLevel sl) {
        EmbeddedPlotLevelAccessor acc = sl.getPlot().getEmbeddedLevelAccessor();
        for (Map.Entry<String, ShipTemplate.ControlGroup> entry : raid.ship.controls.entrySet()) {
            ShipTemplate.ControlGroup group = entry.getValue();
            if (group.mercySignal < 0) continue;

            for (int[] pos : group.levers) {
                BlockPos leverPos = new BlockPos(pos[0], pos[1], pos[2]);
                BlockEntity be = acc.getBlockEntity(leverPos);
                if (setLeverSignal(be, group.mercySignal)) {
                    HostileSkies.debug("[{}] lever at {}: mercy {} -> {}",
                            entry.getKey(), leverPos, group.signal, group.mercySignal);
                }
            }
        }

        ShipTemplate.ControlGroup throttleGroup = raid.ship.controls.get("throttle");
        if (throttleGroup != null && throttleGroup.mercySignal >= 0) {
            raid.navigator.setThrottleBaseOverride(throttleGroup.mercySignal);
        }
    }

    /** Restores levers to their normal signal. */
    private static void restoreNormalControls(TrackedRaid raid, ServerSubLevel sl) {
        EmbeddedPlotLevelAccessor acc = sl.getPlot().getEmbeddedLevelAccessor();
        for (Map.Entry<String, ShipTemplate.ControlGroup> entry : raid.ship.controls.entrySet()) {
            ShipTemplate.ControlGroup group = entry.getValue();
            if (group.mercySignal < 0) continue;

            for (int[] pos : group.levers) {
                BlockPos leverPos = new BlockPos(pos[0], pos[1], pos[2]);
                BlockEntity be = acc.getBlockEntity(leverPos);
                if (setLeverSignal(be, group.signal)) {
                    HostileSkies.debug("[{}] lever at {}: restore {} -> {}",
                            entry.getKey(), leverPos, group.mercySignal, group.signal);
                }
            }
        }

        raid.navigator.setThrottleBaseOverride(-1);
    }

    // Departing

    /** Enters bored departure. Sounds horn, broadcasts message, first throttle bump, phase transition. */
    private static void beginBoredDeparture(TrackedRaid raid, ServerSubLevel sl, String reason) {
        playShipSound(raid.level, sl, BORED_HORN_SOUND,
                (float) (RaidConfig.hornSoundRange.get() / 16.0), 0.7F);
        broadcastNearShip(raid, sl,
                "\u00a77\u00a7o" + raid.ship.name + " is departing the area...",
                RaidConfig.hornSoundRange.get().intValue());
        bumpLift(raid, 1);
        transition(raid, RaidPhase.DEPARTING_BORED, reason);
    }

    /** Enters emergency departure. Straighten course, restore mercy controls, start alarm. */
    private static void beginEmergencyDeparture(TrackedRaid raid) {
        // Reset departure counter. Captain may have been killed during bored departure
        raid.departTicks = 0;
        raid.nextVentSoundTick = -1;
        raid.nextRattleSoundTick = -1;

        ServerSubLevelContainer container = SubLevelContainer.getContainer(raid.level);
        SubLevel sl = container != null ? container.getSubLevel(raid.subLevelId) : null;
        if (sl instanceof ServerSubLevel ssl) {
            if (raid.phase == RaidPhase.MERCY) restoreNormalControls(raid, ssl);
            raid.navigator.straightenWheel(ssl, "emergency-straighten");
            playShipSound(raid.level, ssl, EMERGENCY_START_SOUND,
                    (float) (RaidConfig.hornSoundRange.get() / 16.0), 0.5F);
            playShipSound(raid.level, ssl, "minecraft:entity.generic.explode",
                    (float) (0.5), 0.5F);
            broadcastNearShip(raid, ssl,
                    "\u00a74\u00a7lCaptain was killed! \u00a7cThe ship is going down!",
                    RaidConfig.hornSoundRange.get().intValue());
        }
        transition(raid, RaidPhase.DEPARTING_EMERGENCY, "captain killed. Claim window open");
    }

    /** Raises the lift base override by steps above the ship's normal signal (capped at 15). */
    private static void bumpLift(TrackedRaid raid, int steps) {
        ShipTemplate.ControlGroup lift = raid.ship.controls.get("lift");
        if (lift == null) return;
        int target = Math.min(15, lift.signal + steps);
        raid.navigator.setLiftBaseOverride(target);
        HostileSkies.debug("[depart] lift override -> {} (+{})", target, steps);
    }

    /**
     * Bored: Fly away from the patrol center at raised throttle. Second throttle
     * bump at 10s for a smooth ramp. The 30s timer pauses while a player is aboard. */
    private static boolean tickDepartBored(TrackedRaid raid, ServerSubLevel sl) {
        boolean aboard = isPlayerAboard(raid.level, sl);
        if (aboard) raid.engaged = true;

        if (!aboard) raid.departTicks++;

        if (raid.departTicks == BORED_SECOND_BUMP_TICKS) {
            bumpLift(raid, 2);
        }

        int limit = raid.ship.departure.boredDepartTicks;
        if (raid.departTicks % 100 == 0) {
            debugMessage(raid, "Departing: " + (raid.departTicks / 20) + "s / " + (limit / 20) + "s");
        }

        raid.navigator.tickDepartBored(sl);

        return raid.departTicks >= limit && !aboard;
    }

    /**
     * Emergency: Straight course, alarm sequence, then destruction. The delay
     * before destruction is per-ship (departure.emergencyDelayTicks). This is the
     * player's window to escape or claim the helm. Claiming cancels via the
     * CAPTURED transition. No pause for players aboard. */
    private static boolean tickDepartEmergency(TrackedRaid raid, ServerSubLevel sl) {
        raid.departTicks++;

        // Alarm bell every second
        if (raid.departTicks % EMERGENCY_BELL_INTERVAL == 0) {
            playShipSound(raid.level, sl, EMERGENCY_BELL_SOUND, 3.0F, 0.5F);
        }
        // Steam vents and rattling at random intervals
        raid.nextVentSoundTick = tickRandomSound(raid, sl, EMERGENCY_VENT_SOUND,
                raid.nextVentSoundTick, VENT_MIN_INTERVAL, VENT_MAX_INTERVAL);
        raid.nextRattleSoundTick = tickRandomSound(raid, sl, "simulated:block.physics_assembler.assemble",
                raid.nextRattleSoundTick, RATTLE_MIN_INTERVAL, RATTLE_MAX_INTERVAL);

        // Stop the autopilot once the countdown is over. If Explosions are off, also cut lift and throttle
        if (raid.departTicks < raid.ship.departure.emergencyDelayTicks) {
            if ((raid.departTicks == raid.ship.departure.emergencyDelayTicks - 1) && (!RaidConfig.enableExplosions.get())) {
                raid.navigator.setLiftBaseOverride(0);
                raid.navigator.setThrottleBaseOverride(0);
            }
            raid.navigator.tickDepartEmergency(sl);
        }

        // After the countdown, start or tick the destruction chain
        if (raid.departTicks >= raid.ship.departure.emergencyDelayTicks) {
            if (sl instanceof ServerSubLevel ssl) {
                disassembleSwivels(ssl, raid.structureSize);
            }
            if (!RaidConfig.enableExplosions.get()) {
                // if explosions disabled just wait 20 seconds and despawn
                return raid.departTicks >= raid.ship.departure.emergencyDelayTicks + 400;
            }
            if (raid.destructionSequence == null) {
                raid.destructionSequence = new DestructionSequence(
                        raid.level, raid.structureSize, raid.plotOffset);
                raid.destructionSequence.ignite(
                        raid.ship.departure.detonationCount,
                        raid.ship.departure.detonationMinInterval,
                        raid.ship.departure.detonationMaxInterval,
                        raid.ship.departure.debrisPerDetonation,
                        raid.ship.departure.detonationRadius);
                HostileSkies.LOGGER.info("Emergency departure: destruction chain started for {}",
                        raid.ship.name);
            }
            return raid.destructionSequence.tick(sl);
        }
        return false;
    }

    /** Broadcasts an action bar message to all players within range of the ship. */
    private static void broadcastNearShip(TrackedRaid raid, ServerSubLevel sl, String message, int range) {
        Pose3d pose = sl.logicalPose();
        Vec3 shipPos = new Vec3(pose.position().x(), pose.position().y(), pose.position().z());
        for (ServerPlayer player : raid.level.players()) {
            if (player.position().distanceTo(shipPos) <= range) {
                player.displayClientMessage(Component.literal(message), true);
            }
        }
    }

    /** Ticks a random sound that the ship plays during emergency departure. Returns the updated nextTick value. */
    private static int tickRandomSound(TrackedRaid raid, ServerSubLevel sl, String soundId,
                                       int nextTick, int minInterval, int maxInterval) {
        if (nextTick < 0) {
            return raid.departTicks + minInterval
                    + raid.level.random.nextInt(maxInterval - minInterval + 1);
        }
        if (raid.departTicks >= nextTick) {
            float pitch = 0.5F + raid.level.random.nextFloat() * 0.2F;
            playShipSound(raid.level, sl, soundId, 3.0F, pitch);
            return raid.departTicks + minInterval
                    + raid.level.random.nextInt(maxInterval - minInterval + 1);
        }
        return nextTick;
    }

    /** Plays a sound at the ship's current world-space center (moving source). */
    private static void playShipSound(ServerLevel level, ServerSubLevel sl,
                                      String soundId, float volume, float pitch) {
        Pose3d pose = sl.logicalPose();
        SoundEvent sound = SoundEvent.createVariableRangeEvent(ResourceLocation.parse(soundId));
        level.playSound(null, pose.position().x(), pose.position().y(), pose.position().z(),
                sound, SoundSource.HOSTILE, volume, pitch);
    }

    // Fuel

    private static void tickFuel(TrackedRaid raid, ServerSubLevel sl, int ticksAlive) {
        if (raid.enginePositions.isEmpty() && raid.blazeBurnerPositions.isEmpty()) return;

        // force vent efficiency while the boiler multiblock forms.
        // Retries balloon fill every 40 ticks, extends up to 2x if no fill lands.
        if (raid.steamBridgeTicks > 0) {
            raid.steamBridgeTicks--;
            forceSteamVentEfficiency(raid, sl);

            if (!raid.bridgeFillSucceeded && raid.steamBridgeTicks % 40 == 0) {
                BalloonProbe probe = tryPreFillBalloon(raid, sl);
                if (probe != BalloonProbe.NONE) {
                    raid.bridgeFillSucceeded = true;
                    HostileSkies.debug("Steam bridge: balloon {} ({}t remaining)",
                            probe == BalloonProbe.FILLED ? "topped up" : "confirmed attached",
                            raid.steamBridgeTicks);
                }
            }

            if (raid.steamBridgeTicks == 0) {
                if (!raid.bridgeFillSucceeded && raid.bridgeExtensions < 2) {
                    raid.bridgeExtensions++;
                    raid.steamBridgeTicks = STEAM_BRIDGE_TICKS;
                    HostileSkies.debug("Steam bridge extended (attempt {}). balloon not fillable yet",
                            raid.bridgeExtensions);
                } else {
                    HostileSkies.debug("Steam bridge closed{}",
                            raid.bridgeFillSucceeded ? "" : " (balloon never needed/found a fill)");
                }
            }
        }

        if (ticksAlive % FUEL_TOPOFF_INTERVAL != 0) return;
        applyFuelTopOff(raid, sl, "periodic@tick" + ticksAlive);
    }

    /** Forces all steam vents to efficiency=1.0 during the steam bridge window. */
    private static void forceSteamVentEfficiency(TrackedRaid raid, ServerSubLevel sl) {
        EmbeddedPlotLevelAccessor acc = sl.getPlot().getEmbeddedLevelAccessor();
        for (BlockPos pos : raid.burnerPositions) {
            BlockEntity be = acc.getBlockEntity(pos);
            if (be != null && !(be instanceof HotAirBurnerBlockEntity)) {
                try {
                    java.lang.reflect.Field effField = be.getClass().getDeclaredField("efficiency");
                    effField.setAccessible(true);
                    effField.setDouble(be, 1.0);
                } catch (Exception ignored) {}
            }
        }
    }

    // Despawn

    private static void despawn(TrackedRaid raid, SubLevel sl) {
        // Disassemble swivel bearings before removing the sub-level because
        // their contraption entities live in the overworld and survive markRemoved() currently.
        if (sl instanceof ServerSubLevel ssl) {
            disassembleSwivels(ssl, raid.structureSize);
        }

        BoundingBox3dc b = sl.boundingBox();
        AABB shipArea = new AABB(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ());

        List<Entity> nearEntities = raid.level.getEntities((Entity) null, shipArea.inflate(15),
                e -> !(e instanceof Player));
        for (Entity e : nearEntities) {
            HostileSkies.debug("Despawn cleanup (near): {} at {}",
                    e.getType().toShortString(), e.blockPosition());
            e.discard();
        }
        // kills tagged sub-level entities anywhere in the plot
        // and kicks everything else, safely back into the world
        if (sl instanceof ServerSubLevel ssl2) {
            ssl2.deleteAllEntities();
        }
        unlockAndDiscardChain(raid);

        sl.markRemoved();
        HostileSkies.LOGGER.info("Despawned raid, cleaned {} entities", nearEntities.size());
    }

    /** Disassembles all swivel bearings to prevent orphaned contraption entities. */
    private static void disassembleSwivels(ServerSubLevel ssl, net.minecraft.core.Vec3i structureSize) {
        EmbeddedPlotLevelAccessor acc = ssl.getPlot().getEmbeddedLevelAccessor();
        int count = 0;

        for (int x = 0; x < structureSize.getX(); x++) {
            for (int y = 0; y < structureSize.getY(); y++) {
                for (int z = 0; z < structureSize.getZ(); z++) {
                    BlockEntity be = acc.getBlockEntity(new BlockPos(x, y, z));
                    if (be instanceof SwivelBearingBlockEntity bearing) {
                        try {
                            bearing.disassemble();
                            count++;
                            HostileSkies.debug("Disassembled swivel bearing at [{}, {}, {}]", x, y, z);
                        } catch (Exception e) {
                            HostileSkies.LOGGER.warn("Failed to disassemble swivel at [{}, {}, {}]: {}",
                                    x, y, z, e.getMessage());
                        }
                    }
                }
            }
        }

        if (count > 0) {
            HostileSkies.debug("Disassembled {} swivel bearing(s) before despawn", count);
        }
    }

    // Player checks

    private static boolean isPlayerAboard(ServerLevel level, SubLevel sl) {
        BoundingBox3dc b = sl.boundingBox();
        AABB area = new AABB(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()).inflate(2);

        for (ServerPlayer p : level.players()) {
            if (p.isDeadOrDying()) continue;
            if (area.contains(p.position().x, p.position().y, p.position().z)) return true;
        }
        return false;
    }

    // Utility

    private static void transition(TrackedRaid raid, RaidPhase phase, String reason) {
        HostileSkies.LOGGER.info("Raid {} -> {} ({})", raid.subLevelId, phase, reason);
        raid.phase = phase;
        debugMessage(raid, "Phase: " + phase + " (" + reason + ")");
    }

    private static void debugMessage(TrackedRaid raid, String message) {
        if (!RaidConfig.debugChatMessages.get()) return;
        for (ServerPlayer p : raid.level.players()) {
            p.sendSystemMessage(Component.literal("\u00a77[Raid Debug] " + message));
        }
    }

    private static Vector3d toVec(Vec3 v) { return new Vector3d(v.x, v.y, v.z); }

    // Tracked raid state

    static class TrackedRaid {
        final ServerLevel level;
        final UUID subLevelId;
        final ShipTemplate ship;
        final Vec3 spawnOrigin;
        final Vec3 patrolCenter;
        final Quaterniond spawnOrientation;
        final int spawnTick;
        final List<BlockPos> burnerPositions;
        final List<BlockPos> steeringWheelPositions;
        final List<BlockPos> enginePositions;
        final List<BlockPos> captainSeats;
        final List<BlockPos> crewSeats;
        final List<BlockPos> lootContainerPositions;
        final List<BlockPos> blazeBurnerPositions;
        final net.minecraft.core.Vec3i structureSize;
        final net.minecraft.core.Vec3i plotOffset;
        final int badOmenLevel;
        final ShipNavigator navigator;
        final List<UUID> targetedPlayerIds = new ArrayList<>();

        Entity chainEntity = null;
        BlockPos lockedWheelPos = null;

        RaidPhase phase = RaidPhase.INITIALIZING;
        int patrolTicksRemaining = PATROL_TICKS;
        boolean playerAboardLastTick = false;

        /** False while unloaded; reload rearms steam bridge and tops off fuel. */
        boolean loadedLastTick = true;
        /** Counts down during steam bridge window (armed at spawn/restore/reload). */
        int steamBridgeTicks = STEAM_BRIDGE_TICKS;
        boolean bridgeFillSucceeded = false;
        int bridgeExtensions = 0;
        int restoreHoldTicks = 0;
        Vector3d holdPos = null;
        Quaterniond holdRot = null;
        Vector3d lastKnownPos = null;
        Quaterniond lastKnownRot = null;

        /** True once a player has boarded, died aboard, or hit the captain. */
        boolean engaged = false;
        /** Evicted for cap pressure, slot surrendered, departs on next load. */
        boolean evicted = false;
        /** Marked by stopraid while unloaded, despawn immediately on next load. */
        boolean pendingRemoval = false;
        /** Ticks loaded while awaiting pendingRemoval despawn to let Sable settle. */
        int removalDelayTicks = 0;

        boolean controlsApplied = false;
        boolean balloonFilled = false;
        int balloonFilledTick = 0;
        boolean crewSpawned = false;
        int crewSpawnedTick = 0;

        int departTicks = 0;
        /** Next departTicks value at which the emergency vent sound plays; -1 = unscheduled. */
        int nextVentSoundTick = -1;
        int nextRattleSoundTick = -1;
        /** Active destruction chain. Null until the delay window expires. */
        DestructionSequence destructionSequence = null;

        TrackedRaid(ServerLevel level, UUID subLevelId, ShipTemplate ship,
                    Vec3 spawnOrigin, Vec3 patrolCenter,
                    Quaterniond spawnOrientation, int spawnTick,
                    List<BlockPos> burnerPositions,
                    List<BlockPos> steeringWheelPositions,
                    List<BlockPos> enginePositions,
                    List<BlockPos> captainSeats,
                    List<BlockPos> crewSeats,
                    List<BlockPos> lootContainerPositions,
                    List<BlockPos> blazeBurnerPositions,
                    net.minecraft.core.Vec3i structureSize,
                    net.minecraft.core.Vec3i plotOffset,
                    int badOmenLevel) {
            this.level = level;
            this.subLevelId = subLevelId;
            this.ship = ship;
            this.spawnOrigin = spawnOrigin;
            this.patrolCenter = patrolCenter;
            this.spawnOrientation = spawnOrientation;
            this.spawnTick = spawnTick;
            this.burnerPositions = burnerPositions;
            this.steeringWheelPositions = steeringWheelPositions;
            this.enginePositions = enginePositions;
            this.captainSeats = captainSeats;
            this.crewSeats = crewSeats;
            this.lootContainerPositions = lootContainerPositions;
            this.blazeBurnerPositions = blazeBurnerPositions;
            this.structureSize = structureSize;
            this.plotOffset = plotOffset;
            this.badOmenLevel = badOmenLevel;
            this.navigator = new ShipNavigator(
                    ship, patrolCenter, steeringWheelPositions, level, structureSize);
        }
    }
}
