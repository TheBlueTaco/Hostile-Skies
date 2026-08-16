package dev.thebluetaco.hostileskies.raid;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.EmbeddedPlotLevelAccessor;
import dev.thebluetaco.hostileskies.HostileSkies;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.*;

/**
 * Drives the emergency departure explosion chain. Picks detonation points
 * spread through the ship's structure volume, fires them in order with
 * a delay, removes blocks, and launches pieces of debris as their own sub-levels.
 * One instance per emergency departure.
 */
public class DestructionSequence {

    private static final float DISTANCE_FALLOFF_BASE = 0.85F;
    private static final int MAX_DEBRIS_TOTAL = 150;
    private static final float DEBRIS_IMPULSE_STRENGTH = 3.0F;
    private static final float DEBRIS_UPWARD_BIAS = 1.5F;
    private static final float DEBRIS_ANGULAR_STRENGTH = 4.0F;

    // Debris tracker. Survives individual raid lifetimes
    private static final List<TrackedDebris> allDebris = new ArrayList<>();
    private record TrackedDebris(ServerLevel level, UUID subLevelId, long removalTick) {}

    private final ServerLevel level;
    private final Vec3i structureSize;
    private final Vec3i plotOffset;
    private final Random random = new Random();

    private int debrisPerDetonation;
    private float detonationRadius;
    private int settleTicks;

    private final TreeMap<Integer, List<BlockPos>> schedule = new TreeMap<>();
    private int ticksSinceStart = 0;
    private int lastDetonationTick = 0;
    private int totalDebris = 0;
    private boolean chainComplete = false;
    private final List<PendingDebrisImpulse> pendingImpulses = new ArrayList<>();

    public DestructionSequence(ServerLevel level, Vec3i structureSize, Vec3i plotOffset) {
        this.level = level;
        this.structureSize = structureSize;
        this.plotOffset = plotOffset;
    }

    /** Schedules detonation points with random intervals. */
    public void ignite(int count, int minInterval, int maxInterval, int debrisCap, float radius) {
        this.debrisPerDetonation = debrisCap;
        this.detonationRadius = radius;
        this.settleTicks = RaidConfig.debrisLifetimeSeconds.get() * 20;

        List<BlockPos> points = generateDetonationPoints(count);
        if (points.isEmpty()) return;

        int range = Math.max(1, maxInterval - minInterval);
        int currentTick = minInterval;
        for (BlockPos point : points) {
            schedule.computeIfAbsent(currentTick, k -> new ArrayList<>()).add(point);
            currentTick += minInterval + random.nextInt(range + 1);
        }

        lastDetonationTick = schedule.isEmpty() ? 0 : schedule.lastKey();
        HostileSkies.LOGGER.info("Destruction chain ignited: {} detonations over {} ticks ({} seconds)",
                points.size(), lastDetonationTick, String.format("%.1f", lastDetonationTick / 20.0));
    }

    /** Ticks the destruction chain. Returns true when the chain and settle period are complete. */
    public boolean tick(ServerSubLevel sl) {
        ticksSinceStart++;
        if (chainComplete) return ticksSinceStart >= lastDetonationTick + settleTicks;

        // Apply deferred debris impulses from the previous tick
        for (PendingDebrisImpulse imp : pendingImpulses) {
            try {
                RigidBodyHandle handle = RigidBodyHandle.of(imp.debris);
                if (handle != null) handle.applyLinearAndAngularImpulse(imp.linear, imp.angular);
            } catch (Exception e) {
                HostileSkies.LOGGER.warn("Deferred debris impulse failed: {}", e.getMessage());
            }
        }
        pendingImpulses.clear();

        // Fire all detonations due on or before this tick
        while (!schedule.isEmpty() && schedule.firstKey() <= ticksSinceStart) {
            for (BlockPos structPos : schedule.pollFirstEntry().getValue()) {
                detonate(sl, structPos);
            }
        }

        if (schedule.isEmpty()) {
            chainComplete = true;
            HostileSkies.LOGGER.info("Destruction chain complete, settling for {} ticks", settleTicks);
        }
        return false;
    }

    // Detonation

    private void detonate(ServerSubLevel sl, BlockPos structPos) {
        Vec3 worldPos = toWorldPos(sl, structPos);
        playExplosionEffects(worldPos);
        detonateBlocks(sl, structPos);
        damageNearbyEntities(worldPos, 2.0F);
    }

    /**
     * Removes blocks and carves debris around a detonation point.
     * All accessor coordinates are STRUCTURE-RELATIVE. The accessor adds plotOffset internally.
     * Plot-absolute is used only for SubLevelAssemblyHelper.assembleBlocks.
     */
    private void detonateBlocks(ServerSubLevel sl, BlockPos structPos) {
        EmbeddedPlotLevelAccessor acc = sl.getPlot().getEmbeddedLevelAccessor();
        int radius = (int) Math.ceil(detonationRadius);
        float radiusSq = detonationRadius * detonationRadius;
        int sx = structureSize.getX(), sy = structureSize.getY(), sz = structureSize.getZ();

        List<BlockPos> affected = new ArrayList<>();
        List<BlockPos> debrisCandidates = new ArrayList<>();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    float distSq = dx * dx + dy * dy + dz * dz;
                    if (distSq > radiusSq) continue;

                    double chance = (1.0 - Math.sqrt(distSq) / detonationRadius)
                            * DISTANCE_FALLOFF_BASE + (1.0 - DISTANCE_FALLOFF_BASE);
                    if (random.nextDouble() > chance) continue;

                    int tx = structPos.getX() + dx, ty = structPos.getY() + dy, tz = structPos.getZ() + dz;
                    if (tx < 0 || tx >= sx || ty < 0 || ty >= sy || tz < 0 || tz >= sz) continue;

                    BlockPos target = new BlockPos(tx, ty, tz);
                    BlockState state = acc.getBlockState(target);
                    if (state.isAir()) continue;

                    // Never destroy steering wheels
                    if (state.getBlock().getClass().getSimpleName().equals("SteeringWheelBlock")) continue;

                    affected.add(target);

                    if (totalDebris < MAX_DEBRIS_TOTAL
                            && state.isSolidRender(acc, target)
                            && acc.getBlockEntity(target) == null) {
                        debrisCandidates.add(target);
                    }
                }
            }
        }

        // Carve debris before clearing
        if (!debrisCandidates.isEmpty() && totalDebris < MAX_DEBRIS_TOTAL) {
            Collections.shuffle(debrisCandidates, random);
            int count = Math.min(debrisCandidates.size(), debrisPerDetonation);
            for (int i = 0; i < count && totalDebris < MAX_DEBRIS_TOTAL; i++) {
                if (trySpawnDebris(sl, debrisCandidates.get(i), structPos)) {
                    affected.remove(debrisCandidates.get(i));
                    totalDebris++;
                }
            }
        }

        BlockState air = Blocks.AIR.defaultBlockState();
        for (BlockPos pos : affected) {
            acc.setBlock(pos, air, 3);
        }
    }

    private void damageNearbyEntities(Vec3 center, float damage) {
        double range = detonationRadius + 2.0;
        AABB area = AABB.ofSize(center, range * 2, range * 2, range * 2);
        for (net.minecraft.world.entity.Entity entity : level.getEntities(
                (net.minecraft.world.entity.Entity) null, area, e -> true)) {
            double dist = entity.position().distanceTo(center);
            if (dist > range) continue;
            float scaled = (float) (damage * (1.0 - dist / range));
            if (scaled > 0) entity.hurt(level.damageSources().explosion(null), scaled);
        }
    }

    // Debris
    private boolean trySpawnDebris(ServerSubLevel parentSl, BlockPos structPos, BlockPos detonationCenter) {
        BlockPos plotAbs = structPos.offset(plotOffset.getX(), plotOffset.getY(), plotOffset.getZ());
        try {
            List<BlockPos> blockList = List.of(plotAbs);
            ServerSubLevel debris = SubLevelAssemblyHelper.assembleBlocks(
                    level, plotAbs, blockList,
                    Objects.requireNonNull(BoundingBox3i.from(blockList)));
            if (debris == null) return false;

            queueDebrisImpulse(parentSl, debris, structPos, detonationCenter);
            allDebris.add(new TrackedDebris(level, debris.getUniqueId(),
                    level.getServer().getTickCount() + RaidConfig.debrisLifetimeSeconds.get() * 20L));
            return true;
        } catch (Exception e) {
            HostileSkies.LOGGER.warn("Failed to spawn debris at {}: {}", structPos, e.getMessage());
            return false;
        }
    }

    private void queueDebrisImpulse(ServerSubLevel parentSl, ServerSubLevel debris,
                                    BlockPos debrisPos, BlockPos centerPos) {
        double dx = debrisPos.getX() - centerPos.getX();
        double dy = debrisPos.getY() - centerPos.getY();
        double dz = debrisPos.getZ() - centerPos.getZ();
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 0.1) { dx = random.nextGaussian(); dy = 1.0; dz = random.nextGaussian(); len = 1.0; }

        Vector3d dir = new Vector3d(dx / len, dy / len, dz / len);
        new Quaterniond(parentSl.logicalPose().orientation()).transform(dir);

        pendingImpulses.add(new PendingDebrisImpulse(debris,
                new Vector3d(
                        dir.x * DEBRIS_IMPULSE_STRENGTH + (random.nextDouble() - 0.5) * 0.5,
                        dir.y * DEBRIS_IMPULSE_STRENGTH + DEBRIS_UPWARD_BIAS,
                        dir.z * DEBRIS_IMPULSE_STRENGTH + (random.nextDouble() - 0.5) * 0.5),
                new Vector3d(
                        (random.nextDouble() - 0.5) * DEBRIS_ANGULAR_STRENGTH,
                        (random.nextDouble() - 0.5) * DEBRIS_ANGULAR_STRENGTH,
                        (random.nextDouble() - 0.5) * DEBRIS_ANGULAR_STRENGTH)));
    }

    // Static debris cleanup

    /** Removes expired debris sub-levels. Called every server tick, independent of any raid. */
    public static void tickDebrisCleanup(ServerLevel level) {
        if (allDebris.isEmpty()) return;
        long currentTick = level.getServer().getTickCount();
        allDebris.removeIf(d -> {
            if (d.level != level || currentTick < d.removalTick) return false;
            ServerSubLevelContainer container =
                    (ServerSubLevelContainer) SubLevelContainer.getContainer(level);
            if (container != null) {
                SubLevel sl = container.getSubLevel(d.subLevelId);
                if (sl != null) {
                    sl.markRemoved();
                    HostileSkies.debug("Cleaned up debris sub-level {}", d.subLevelId);
                }
            }
            return true;
        });
    }

    /** Clears all tracked debris (call on server stop). */
    public static void clearAllDebris() {
        allDebris.clear();
    }

    private record PendingDebrisImpulse(ServerSubLevel debris, Vector3d linear, Vector3d angular) {}

    // Helpers

    private List<BlockPos> generateDetonationPoints(int count) {
        int sx = structureSize.getX(), sy = structureSize.getY(), sz = structureSize.getZ();
        if (sx <= 0 || sy <= 0 || sz <= 0) return List.of();

        List<BlockPos> points = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            points.add(new BlockPos(
                    1 + random.nextInt(Math.max(1, sx - 2)),
                    1 + random.nextInt(Math.max(1, sy - 2)),
                    1 + random.nextInt(Math.max(1, sz - 2))));
        }
        return points;
    }

    private Vec3 toWorldPos(ServerSubLevel sl, BlockPos structPos) {
        Pose3d pose = sl.logicalPose();
        Quaterniond q = new Quaterniond(pose.orientation());

        double ox = (structPos.getX() + 0.5) - (structureSize.getX() / 2.0 - 0.24);
        double oy = structPos.getY() - (structureSize.getY() / 2.0 - 0.58);
        double oz = (structPos.getZ() + 0.5) - (structureSize.getZ() / 2.0 - 0.03);

        Vector3d rotated = q.transform(new Vector3d(ox, oy, oz));
        return new Vec3(
                pose.position().x() + rotated.x,
                pose.position().y() + rotated.y,
                pose.position().z() + rotated.z);
    }

    private void playExplosionEffects(Vec3 pos) {
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, pos.x, pos.y, pos.z, 1, 0, 0, 0, 0);
        level.sendParticles(ParticleTypes.EXPLOSION, pos.x, pos.y, pos.z, 3, 1.5, 1.5, 1.5, 0);
        level.playSound(null, pos.x, pos.y, pos.z,
                SoundEvent.createVariableRangeEvent(ResourceLocation.parse("minecraft:entity.generic.explode")),
                SoundSource.BLOCKS, 3.0F, 0.8F + random.nextFloat() * 0.4F);
    }
}
