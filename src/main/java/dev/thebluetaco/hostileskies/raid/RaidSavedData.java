package dev.thebluetaco.hostileskies.raid;

import dev.thebluetaco.hostileskies.HostileSkies;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Persistent world data for the raid spawn system.
 * Stored in the overworld's data/ folder as "hostile_skies_data.dat". */
public class RaidSavedData extends SavedData {

    private static final String DATA_ID = "hostile_skies_data";

    private final Map<UUID, Integer> captainKills = new HashMap<>();
    private int serverWideKills = 0;
    private final Map<UUID, Long> personalCooldowns = new HashMap<>();
    private double currentSpawnChance;
    private long lastSpawnAttemptTick = -1;
    private ListTag activeRaidsNbt = new ListTag();

    public RaidSavedData() {
        this.currentSpawnChance = RaidConfig.spawnChanceBase.get();
    }

    // Captain kills
    public int getCaptainKills(UUID playerId) {
        if (RaidConfig.perPlayerKillTracking.get()) {
            return captainKills.getOrDefault(playerId, 0);
        }
        return serverWideKills;
    }

    public void addCaptainKill(UUID playerId) {
        if (RaidConfig.perPlayerKillTracking.get()) {
            captainKills.merge(playerId, 1, Integer::sum);
            int total = captainKills.get(playerId);
            HostileSkies.LOGGER.info("Captain kill recorded for {}: {} total (per-player)", playerId, total);
        } else {
            serverWideKills++;
            HostileSkies.LOGGER.info("Captain kill recorded: {} total (server-wide)", serverWideKills);
        }
        setDirty();
    }

    /** Returns the highest tier unlocked by the given kill count. */
    public int getHighestUnlockedTier(int kills) {
        int tier = 0;
        if (RaidConfig.isTierEnabled(1)) tier = 1;
        if (kills >= RaidConfig.tier2UnlockKills.get() && RaidConfig.isTierEnabled(2)) tier = 2;
        if (kills >= RaidConfig.tier3UnlockKills.get() && RaidConfig.isTierEnabled(3)) tier = 3;
        if (kills >= RaidConfig.tier4UnlockKills.get() && RaidConfig.isTierEnabled(4)) tier = 4;
        return tier;
    }

    // Personal cooldowns

    public boolean isOnCooldown(UUID playerId, long currentTick) {
        Long expiry = personalCooldowns.get(playerId);
        if (expiry == null) return false;
        if (currentTick >= expiry) {
            personalCooldowns.remove(playerId);
            return false;
        }
        return true;
    }

    public void applyCooldown(UUID playerId, long currentTick) {
        personalCooldowns.put(playerId, currentTick + RaidConfig.personalCooldownTicks());
        setDirty();
    }

    // Spawn chance

    public double getCurrentSpawnChance() {
        return currentSpawnChance;
    }

    public void incrementSpawnChance(double amount) {
        currentSpawnChance = Math.min(currentSpawnChance + amount, RaidConfig.spawnChanceMax.get());
        setDirty();
    }

    public void resetSpawnChance() {
        currentSpawnChance = RaidConfig.spawnChanceBase.get();
        setDirty();
    }

    public long getLastSpawnAttemptTick() {
        return lastSpawnAttemptTick;
    }

    public void setLastSpawnAttemptTick(long tick) {
        this.lastSpawnAttemptTick = tick;
        setDirty();
    }

    // Active raids persistence

    public ListTag getStoredRaidsNbt() {
        return activeRaidsNbt;
    }

    public void setActiveRaidsNbt(ListTag nbt) {
        this.activeRaidsNbt = nbt;
        setDirty();
    }

    public void clearStoredRaidsNbt() {
        this.activeRaidsNbt = new ListTag();
    }

    // Serialization

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag killsList = new ListTag();
        for (Map.Entry<UUID, Integer> entry : captainKills.entrySet()) {
            CompoundTag e = new CompoundTag();
            e.putUUID("player", entry.getKey());
            e.putInt("kills", entry.getValue());
            killsList.add(e);
        }
        tag.put("captainKills", killsList);
        tag.putInt("serverWideKills", serverWideKills);

        ListTag cooldownsList = new ListTag();
        for (Map.Entry<UUID, Long> entry : personalCooldowns.entrySet()) {
            CompoundTag e = new CompoundTag();
            e.putUUID("player", entry.getKey());
            e.putLong("expiry", entry.getValue());
            cooldownsList.add(e);
        }
        tag.put("cooldowns", cooldownsList);

        tag.putDouble("spawnChance", currentSpawnChance);
        tag.putLong("lastAttemptTick", lastSpawnAttemptTick);
        tag.put("activeRaids", activeRaidsNbt);

        return tag;
    }

    private static RaidSavedData load(CompoundTag tag, HolderLookup.Provider provider) {
        RaidSavedData data = new RaidSavedData();

        if (tag.contains("captainKills")) {
            ListTag killsList = tag.getList("captainKills", Tag.TAG_COMPOUND);
            for (int i = 0; i < killsList.size(); i++) {
                CompoundTag e = killsList.getCompound(i);
                data.captainKills.put(e.getUUID("player"), e.getInt("kills"));
            }
        }
        data.serverWideKills = tag.getInt("serverWideKills");

        if (tag.contains("cooldowns")) {
            ListTag cooldownsList = tag.getList("cooldowns", Tag.TAG_COMPOUND);
            for (int i = 0; i < cooldownsList.size(); i++) {
                CompoundTag e = cooldownsList.getCompound(i);
                data.personalCooldowns.put(e.getUUID("player"), e.getLong("expiry"));
            }
        }

        data.currentSpawnChance = tag.getDouble("spawnChance");
        if (data.currentSpawnChance < RaidConfig.spawnChanceBase.get()) {
            data.currentSpawnChance = RaidConfig.spawnChanceBase.get();
        }
        data.lastSpawnAttemptTick = tag.getLong("lastAttemptTick");

        if (tag.contains("activeRaids")) {
            data.activeRaidsNbt = tag.getList("activeRaids", Tag.TAG_COMPOUND);
        }

        return data;
    }

    // Access

    private static final Factory<RaidSavedData> FACTORY = new Factory<>(
            RaidSavedData::new,
            RaidSavedData::load
    );

    public static RaidSavedData get(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage().computeIfAbsent(FACTORY, DATA_ID);
    }
}
