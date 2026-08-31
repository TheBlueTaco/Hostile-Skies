package dev.thebluetaco.hostileskies.raid;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Common configuration for the raid spawn system.
 * Generates hostile_skies-server.toml in config/. */
public class RaidConfig {
    public static final ModConfigSpec SPEC;

    // General
    public static final ModConfigSpec.BooleanValue enableRaidSpawns;
    public static final ModConfigSpec.BooleanValue enableShipCapture;
    public static final ModConfigSpec.BooleanValue debugChatMessages;

    // Spawn timing
    public static final ModConfigSpec.IntValue spawnAttemptIntervalMinutes;
    public static final ModConfigSpec.DoubleValue spawnChanceBase;
    public static final ModConfigSpec.DoubleValue spawnChanceIncrement;
    public static final ModConfigSpec.DoubleValue spawnChanceMax;
    public static final ModConfigSpec.DoubleValue spawnChanceBlockedIncrement;
    public static final ModConfigSpec.IntValue patrolTimeMinutes;

    // Multiplayer
    public static final ModConfigSpec.IntValue maxActiveRaids;
    public static final ModConfigSpec.IntValue personalCooldownMinutes;
    public static final ModConfigSpec.IntValue playerGroupingRadius;
    public static final ModConfigSpec.DoubleValue targetWeightBase;
    public static final ModConfigSpec.DoubleValue targetWeightPerTier;
    public static final ModConfigSpec.DoubleValue badOmenWeightPerLevel;

    // Tier system
    public static final ModConfigSpec.BooleanValue enableTier1;
    public static final ModConfigSpec.BooleanValue enableTier2;
    public static final ModConfigSpec.BooleanValue enableTier3;
    public static final ModConfigSpec.BooleanValue enableTier4;

    public static final ModConfigSpec.IntValue tier2UnlockKills;
    public static final ModConfigSpec.IntValue tier3UnlockKills;
    public static final ModConfigSpec.IntValue tier4UnlockKills;

    public static final ModConfigSpec.IntValue tier1Weight;
    public static final ModConfigSpec.IntValue tier2Weight;
    public static final ModConfigSpec.IntValue tier3Weight;
    public static final ModConfigSpec.IntValue tier4Weight;

    // Bad Omen
    public static final ModConfigSpec.DoubleValue badOmenCrewMultiplierBase;
    public static final ModConfigSpec.DoubleValue badOmenCrewMultiplierStep;
    public static final ModConfigSpec.DoubleValue badOmenLootMultiplierBase;
    public static final ModConfigSpec.DoubleValue badOmenLootMultiplierStep;

    // Captain kill tracking
    public static final ModConfigSpec.BooleanValue raidCaptainKillsCounted;
    public static final ModConfigSpec.BooleanValue perPlayerKillTracking;

    // Departure
    public static final ModConfigSpec.BooleanValue enableExplosions;
    public static final ModConfigSpec.IntValue debrisLifetimeSeconds;

    // Audio
    public static final ModConfigSpec.BooleanValue enableSpawnHornSound;
    public static final ModConfigSpec.DoubleValue hornSoundRange;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        // General
        builder.comment("General settings for raid encounters");
        builder.push("general");

        enableRaidSpawns = builder
                .comment("Set to false to disable all automatic raid spawns")
                .define("enableRaidSpawns", true);

        enableShipCapture = builder
                .comment("Whether players can capture ships using Captain's Orders")
                .define("enableShipCapture", true);

        debugChatMessages = builder
                .comment("Broadcast raid debug messages in chat")
                .define("debugChatMessages", false);

        patrolTimeMinutes = builder
                .comment("Minutes the raid will patrol for until they get bored and leave.")
                .defineInRange("patrolTimeMinutes", 14, 1, 60);

        builder.pop();

        // Spawn timing
        builder.comment(".",
                "Controls when and how often raids spawn.",
                "Chance starts at base and increases by increment each failed roll.");
        builder.push("spawn_timing");

        spawnAttemptIntervalMinutes = builder
                .comment("Minutes between spawn attempts")
                .defineInRange("spawnAttemptInterval", 15, 1, 120);

        spawnChanceBase = builder
                .comment("Starting spawn chance (%) on first attempt")
                .defineInRange("spawnChanceBase", 10.0, 0.0, 100.0);

        spawnChanceMax = builder
                .comment("Maximum spawn chance (%) Never exceeds this")
                .defineInRange("spawnChanceMax", 50.0, 0.0, 100.0);

        spawnChanceIncrement = builder
                .comment("Added to spawn chance (%) after each failed roll")
                .defineInRange("spawnChanceIncrement", 4.0, 0.0, 100.0);

        spawnChanceBlockedIncrement = builder
                .comment("Spawn chance increment (%) when blocked (max raids or no eligible groups).",
                         "Set lower than normal increment to prevent rapid back to back spawns.")
                .defineInRange("spawnChanceBlockedIncrement", 1.0, 0.0, 100.0);

        builder.pop();

        // Multiplayer
        builder.comment(".",
                "Multiplayer targeting, grouping, and cooldown settings.");
        builder.push("multiplayer");

        maxActiveRaids = builder
                .comment("Maximum active raid encounters server-wide")
                .defineInRange("maxActiveRaids", 3, 1, 20);

        personalCooldownMinutes = builder
                .comment("Minutes of cooldown per player after an encounter ends")
                .defineInRange("personalCooldownMinutes", 10, 0, 120);

        playerGroupingRadius = builder
                .comment("Radius (blocks) within which players are grouped as one target")
                .defineInRange("playerGroupingRadius", 200, 50, 1000);

        targetWeightBase = builder
                .comment("Base weight for target group selection (before tier/Bad Omen bonuses)")
                .defineInRange("targetWeightBase", 10.0, 1.0, 100.0);

        targetWeightPerTier = builder
                .comment("Additional selection weight per unlocked tier")
                .defineInRange("targetWeightPerTier", 1.0, 0.0, 50.0);

        badOmenWeightPerLevel = builder
                .comment("Additional selection weight per Bad Omen level")
                .defineInRange("badOmenWeightPerLevel", 10.0, 0.0, 100.0);

        builder.pop();

        // Tier system
        builder.comment(".",
                "Ship tier progression: unlocked by captain kill count.");
        builder.push("tier_system");

        enableTier1 = builder
                .comment("Enable tier 1 ships (Scout)")
                .define("enableTier1", true);
        enableTier2 = builder
                .comment("Enable tier 2 ships (Skiff)")
                .define("enableTier2", true);
        enableTier3 = builder
                .comment("Enable tier 3 ships (Warship)")
                .define("enableTier3", true);
        enableTier4 = builder
                .comment("Enable tier 4 ships (Flagship")
                .define("enableTier4", true);

        tier2UnlockKills = builder
                .comment("Captain kills required to unlock tier 2")
                .defineInRange("tier2UnlockKills", 8, 0, 1000);
        tier3UnlockKills = builder
                .comment("Captain kills required to unlock tier 3")
                .defineInRange("tier3UnlockKills", 25, 0, 1000);
        tier4UnlockKills = builder
                .comment("Captain kills required to unlock tier 4")
                .defineInRange("tier4UnlockKills", 50, 0, 1000);

        builder.comment("Spawn weights per tier. Higher number = more likely to be selected");
        builder.push("weights");

        tier1Weight = builder
                .comment("Tier 1 spawn weight")
                .defineInRange("tier1Weight", 50, 0, 1000);
        tier2Weight = builder
                .comment("Tier 2 spawn weight")
                .defineInRange("tier2Weight", 30, 0, 1000);
        tier3Weight = builder
                .comment("Tier 3 spawn weight")
                .defineInRange("tier3Weight", 15, 0, 1000);
        tier4Weight = builder
                .comment("Tier 4 spawn weight")
                .defineInRange("tier4Weight", 5, 0, 1000);

        builder.pop(); // weights
        builder.pop(); // tier_system

        // Bad Omen
        builder.comment(".",
                "Bad Omen effects on raid difficulty and loot.",
                "Guarantees the player's highest unlocked tier to spawn.",
                "Multiplier formula: base + (level - 1) * step.");
        builder.push("bad_omen");

        badOmenCrewMultiplierBase = builder
                .comment("Crew size multiplier at Bad Omen I")
                .defineInRange("badOmenCrewMultiplierBase", 1.5, 1.0, 10.0);
        badOmenCrewMultiplierStep = builder
                .comment("Additional crew multiplier per Bad Omen level above I")
                .defineInRange("badOmenCrewMultiplierStep", 0.5, 0.0, 5.0);

        badOmenLootMultiplierBase = builder
                .comment("Loot quantity multiplier at Bad Omen I")
                .defineInRange("badOmenLootMultiplierBase", 1.5, 1.0, 10.0);
        badOmenLootMultiplierStep = builder
                .comment("Additional loot multiplier per Bad Omen level above I")
                .defineInRange("badOmenLootMultiplierStep", 0.5, 0.0, 5.0);

        builder.pop();

        // Captain kill tracking
        builder.comment(".",
                "Captain kill tracking settings.");
        builder.push("captain_kills");

        raidCaptainKillsCounted = builder
                .comment("Whether kills from raid ship captains count toward tier progression")
                .define("raidCaptainKillsCounted", true);

        perPlayerKillTracking = builder
                .comment("Per-player tracking (true) or shared server-wide total (false)")
                .define("perPlayerKillTracking", true);

        builder.pop();

        // Departure
        builder.comment(".",
                "Emergency departure and destruction settings.");
        builder.push("departure");

        enableExplosions = builder
                .comment("Enable the explosion destruction sequence on emergency departures.",
                         "When false, unclaimed ships still depart with alarm sounds but despawn cleanly.")
                .define("enableExplosions", true);

        debrisLifetimeSeconds = builder
                .comment("How long (in seconds) debris fragments persist before being cleaned up")
                .defineInRange("debrisLifetimeSeconds", 30, 5, 600);

        builder.pop();

        // Audio
        builder.comment(".",
                "Sound settings for raid encounters.");
        builder.push("audio");

        enableSpawnHornSound = builder
                .comment("Play a horn sound when a raid ship spawns nearby")
                .define("enableSpawnHornSound", true);

        hornSoundRange = builder
                .comment("Range in blocks at which the horn sound is audible")
                .defineInRange("hornSoundRange", 200.0, 10.0, 1000.0);

        builder.pop();

        SPEC = builder.build();
    }

    // Helpers

    public static int spawnAttemptIntervalTicks() {
        return spawnAttemptIntervalMinutes.get() * 60 * 20;
    }

    public static int patrolTimeTicks() {
        return patrolTimeMinutes.get() * 60 * 20;
    }

    public static int personalCooldownTicks() {
        return personalCooldownMinutes.get() * 60 * 20;
    }

    public static boolean isTierEnabled(int tier) {
        return switch (tier) {
            case 1 -> enableTier1.get();
            case 2 -> enableTier2.get();
            case 3 -> enableTier3.get();
            case 4 -> enableTier4.get();
            default -> false;
        };
    }

    public static int tierUnlockKills(int tier) {
        return switch (tier) {
            case 1 -> 0;
            case 2 -> tier2UnlockKills.get();
            case 3 -> tier3UnlockKills.get();
            case 4 -> tier4UnlockKills.get();
            default -> Integer.MAX_VALUE;
        };
    }

    public static int tierWeight(int tier) {
        return switch (tier) {
            case 1 -> tier1Weight.get();
            case 2 -> tier2Weight.get();
            case 3 -> tier3Weight.get();
            case 4 -> tier4Weight.get();
            default -> 0;
        };
    }

    public static double badOmenCrewMultiplier(int level) {
        if (level <= 0) return 1.0;
        return badOmenCrewMultiplierBase.get() + (level - 1) * badOmenCrewMultiplierStep.get();
    }

    public static double badOmenLootMultiplier(int level) {
        if (level <= 0) return 1.0;
        return badOmenLootMultiplierBase.get() + (level - 1) * badOmenLootMultiplierStep.get();
    }
}
