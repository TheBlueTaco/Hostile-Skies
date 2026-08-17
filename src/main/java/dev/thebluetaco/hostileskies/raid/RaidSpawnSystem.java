package dev.thebluetaco.hostileskies.raid;

import dev.thebluetaco.hostileskies.HostileSkies;
import dev.thebluetaco.hostileskies.command.SpawnRaidCommand;
import dev.thebluetaco.hostileskies.ship.ShipRegistry;
import dev.thebluetaco.hostileskies.ship.ShipTemplate;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** Automatic raid spawn system. Runs a global spawn attempt every N minutes,
 * with escalating probability, player group targeting, and tier selection.
 * Groups are built per dimension and ships are filtered by their dimensions list.
 * Called from HostileSkies.onServerTick(). */
public class RaidSpawnSystem {

    public static void tick(MinecraftServer server) {
        if (!RaidConfig.enableRaidSpawns.get()) return;

        long currentTick = server.overworld().getGameTime();
        RaidSavedData data = RaidSavedData.get(server);

        if (data.getLastSpawnAttemptTick() < 0) {
            data.setLastSpawnAttemptTick(currentTick);
            return;
        }

        if (currentTick - data.getLastSpawnAttemptTick() < RaidConfig.spawnAttemptIntervalTicks()) {
            return;
        }

        data.setLastSpawnAttemptTick(currentTick);
        HostileSkies.LOGGER.info("Spawn attempt at tick {} (chance {}%)",
                currentTick, String.format("%.1f", data.getCurrentSpawnChance()));
        attemptSpawn(server, data, currentTick);
    }

    // Spawn attempt

    private static void attemptSpawn(MinecraftServer server, RaidSavedData data, long currentTick) {
        RandomSource random = server.overworld().random;

        int activeCount = RaidManager.getActiveRaidCount();
        if (activeCount >= RaidConfig.maxActiveRaids.get()) {
            // Try to reclaim a slot from an unloaded never-engaged raid
            if (!RaidManager.tryEvictForCapacity()) {
                data.incrementSpawnChance(RaidConfig.spawnChanceBlockedIncrement.get());
                HostileSkies.debug("Spawn blocked (max raids {}/{}), chance now {}%",
                        activeCount, RaidConfig.maxActiveRaids.get(),
                        String.format("%.1f", data.getCurrentSpawnChance()));
                return;
            }
            HostileSkies.debug("Cap reached ({}/{}) but a slot was reclaimed by eviction",
                    activeCount, RaidConfig.maxActiveRaids.get());
        }

        List<PlayerGroup> groups = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            groups.addAll(buildPlayerGroups(level, data, currentTick));
        }
        if (groups.isEmpty()) {
            data.incrementSpawnChance(RaidConfig.spawnChanceBlockedIncrement.get());
            HostileSkies.debug("Spawn blocked (no eligible groups), chance now {}%",
                    String.format("%.1f", data.getCurrentSpawnChance()));
            return;
        }

        double roll = random.nextDouble() * 100.0;
        double chance = data.getCurrentSpawnChance();

        if (roll >= chance) {
            data.incrementSpawnChance(RaidConfig.spawnChanceIncrement.get());
            HostileSkies.debug("Spawn failed (rolled {}, needed < {}%), chance now {}%",
                    String.format("%.1f", roll), String.format("%.1f", chance),
                    String.format("%.1f", data.getCurrentSpawnChance()));
            return;
        }

        HostileSkies.debug("Spawn roll succeeded ({} < {}%)",
                String.format("%.1f", roll), String.format("%.1f", chance));

        PlayerGroup target = selectWeightedGroup(groups, random);
        if (target == null) return;

        ResourceKey<Level> dimension = target.level.dimension();

        int selectedTier = selectTier(target, dimension, random);
        if (selectedTier < 1) {
            HostileSkies.LOGGER.warn("No valid tier selected in {}. Aborting spawn", dimension.location());
            return;
        }

        ShipTemplate ship = pickShipForTier(selectedTier, dimension, random);
        if (ship == null) {
            HostileSkies.LOGGER.warn("No ship registered for tier {} or lower in {}. Aborting...",
                    selectedTier, dimension.location());
            return;
        }

        double randomAngle = random.nextDouble() * 2 * Math.PI;
        Vec3 lookDir = new Vec3(Math.cos(randomAngle), 0, Math.sin(randomAngle));

        HostileSkies.debug("Attempting spawn: {} (T{}) targeting group of {} at ({}, {}) in {}",
                ship.name, ship.tier, target.players.size(),
                (int) target.centroid.x, (int) target.centroid.z, dimension.location());

        boolean success = SpawnRaidCommand.spawnShipAt(target.level, target.centroid, lookDir, ship,
                target.maxBadOmenLevel);

        if (success) {
            data.resetSpawnChance();

            List<UUID> targetedIds = new ArrayList<>();
            for (ServerPlayer player : target.players) {
                targetedIds.add(player.getUUID());
                if (player.hasEffect(MobEffects.BAD_OMEN)) {
                    player.removeEffect(MobEffects.BAD_OMEN);
                }
            }

            RaidManager.registerTargetedPlayers(targetedIds);

            HostileSkies.LOGGER.info("Raid spawned: {} (T{}) in {}, {} player(s) targeted, chance reset to {}%",
                    ship.name, ship.tier, dimension.location(), target.players.size(),
                    String.format("%.1f", data.getCurrentSpawnChance()));
        } else {
            data.incrementSpawnChance(RaidConfig.spawnChanceIncrement.get());
            HostileSkies.LOGGER.warn("Ship spawn failed for {}, chance now {}%",
                    ship.name, String.format("%.1f", data.getCurrentSpawnChance()));
        }
    }

    // Player grouping

    /** Clusters eligible players in one dimension by proximity using union find. */
    static List<PlayerGroup> buildPlayerGroups(ServerLevel level,
                                                RaidSavedData data, long currentTick) {
        List<ServerPlayer> eligible = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) continue;
            if (player.isCreative()) continue;
            if (data.isOnCooldown(player.getUUID(), currentTick)) continue;
            if (RaidManager.isPlayerInActiveRaid(player.getUUID())) continue;
            eligible.add(player);
        }

        if (eligible.isEmpty()) return List.of();

        int n = eligible.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        int radius = RaidConfig.playerGroupingRadius.get();
        double radiusSq = (double) radius * radius;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double dx = eligible.get(i).getX() - eligible.get(j).getX();
                double dz = eligible.get(i).getZ() - eligible.get(j).getZ();
                if (dx * dx + dz * dz <= radiusSq) {
                    union(parent, i, j);
                }
            }
        }

        Map<Integer, List<ServerPlayer>> groupMap = new HashMap<>();
        for (int i = 0; i < n; i++) {
            groupMap.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(eligible.get(i));
        }

        List<PlayerGroup> groups = new ArrayList<>();
        for (List<ServerPlayer> members : groupMap.values()) {
            groups.add(new PlayerGroup(level, members, data));
        }

        return groups;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) parent[ra] = rb;
    }

    // Weighted group selection

    private static PlayerGroup selectWeightedGroup(List<PlayerGroup> groups, RandomSource random) {
        double totalWeight = 0;
        for (PlayerGroup g : groups) totalWeight += g.weight;
        if (totalWeight <= 0) return null;

        double roll = random.nextDouble() * totalWeight;
        double cumulative = 0;
        for (PlayerGroup g : groups) {
            cumulative += g.weight;
            if (roll < cumulative) return g;
        }
        return groups.getLast();
    }

    // Tier selection

    private static int selectTier(PlayerGroup group, ResourceKey<Level> dimension, RandomSource random) {
        int maxTier = group.highestUnlockedTier;
        if (maxTier < 1) return -1;

        // Bad Omen guarantees the player's highest unlocked tier
        if (group.maxBadOmenLevel > 0) {
            HostileSkies.LOGGER.info("[Tier] Bad Omen {}. Guaranteed tier {}", group.maxBadOmenLevel, maxTier);
            return maxTier;
        }

        // Build weighted pool of enabled tiers that have ships registered for this dimension
        List<int[]> pool = new ArrayList<>();
        for (int t = 1; t <= maxTier; t++) {
            if (!RaidConfig.isTierEnabled(t)) continue;
            if (ShipRegistry.getForTier(t, dimension).isEmpty()) continue;
            pool.add(new int[]{t, RaidConfig.tierWeight(t)});
        }

        if (pool.isEmpty()) return -1;

        int totalWeight = 0;
        for (int[] entry : pool) totalWeight += entry[1];
        if (totalWeight <= 0) return pool.getFirst()[0];

        int roll = random.nextInt(totalWeight);
        int cumulative = 0;
        for (int[] entry : pool) {
            cumulative += entry[1];
            if (roll < cumulative) {
                HostileSkies.LOGGER.info("[Tier] selected T{} (roll {} of {}, pool: {})",
                        entry[0], roll, totalWeight, poolToString(pool));
                return entry[0];
            }
        }
        return pool.getLast()[0];
    }

    private static String poolToString(List<int[]> pool) {
        StringBuilder sb = new StringBuilder();
        for (int[] e : pool) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append("T").append(e[0]).append("=").append(e[1]);
        }
        return sb.toString();
    }

    // Ship selection

    /** Picks a random ship allowed in this dimension for the given tier, falling back to the next lower tier. */
    private static ShipTemplate pickShipForTier(int tier, ResourceKey<Level> dimension, RandomSource random) {
        for (int t = tier; t >= 1; t--) {
            ShipTemplate ship = ShipRegistry.getRandomForTier(t, dimension, random);
            if (ship != null) return ship;
        }
        return null;
    }

    // Player group data

    static class PlayerGroup {
        final ServerLevel level;
        final List<ServerPlayer> players;
        final Vec3 centroid;
        final int highestUnlockedTier;
        final int maxBadOmenLevel;
        final double weight;

        PlayerGroup(ServerLevel level, List<ServerPlayer> players, RaidSavedData data) {
            this.level = level;
            this.players = players;

            double cx = 0, cy = 0, cz = 0;
            int maxKills = 0;
            int maxOmen = 0;

            for (ServerPlayer p : players) {
                cx += p.getX();
                cy += p.getY();
                cz += p.getZ();

                int kills = data.getCaptainKills(p.getUUID());
                maxKills = Math.max(maxKills, kills);

                MobEffectInstance omen = p.getEffect(MobEffects.BAD_OMEN);
                if (omen != null) {
                    maxOmen = Math.max(maxOmen, omen.getAmplifier() + 1);
                }
            }

            int count = players.size();
            this.centroid = new Vec3(cx / count, cy / count, cz / count);
            this.highestUnlockedTier = data.getHighestUnlockedTier(maxKills);
            this.maxBadOmenLevel = maxOmen;

            double w = RaidConfig.targetWeightBase.get();
            w += this.highestUnlockedTier * RaidConfig.targetWeightPerTier.get();
            w += this.maxBadOmenLevel * RaidConfig.badOmenWeightPerLevel.get();
            this.weight = w;
        }
    }
}
