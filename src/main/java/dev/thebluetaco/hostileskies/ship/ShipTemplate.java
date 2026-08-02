package dev.thebluetaco.hostileskies.ship;

import dev.thebluetaco.hostileskies.HostileSkies;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.storage.loot.LootTable;

import java.util.LinkedHashMap;
import java.util.Map;

/** All configurable data for a ship design, loaded from a JSON file.
 * One instance per ship type (e.g. "karve_t1", "hirdskip_t2").  */
public class ShipTemplate {

    // Identity    The id is set by ShipRegistry.register(), not JSON
    public transient String id = "";
    public String name = "Unknown Ship";
    public int tier = 1;
    public String structure = "";

    public Navigation navigation = new Navigation();
    public Map<String, ControlGroup> controls = new LinkedHashMap<>();
    public Spawning spawning = new Spawning();
    public Crew crew = new Crew();
    public Loot loot = new Loot();

    /**
     * Tuning for the ShipNavigator. Defaults are validated on the Karve's
     * diagnostic ladder. Onboarding a new ship: (Only Balloon and rudder based ships so far)
     *   1. /hostileskies shipnav sense — verify a straight rudder on spawn
     *      (rate ~0) and note cruise speed.
     *   2. /hostileskies shipnav wheel sweeps — measure max turn rate; the
     *      ship's minimum turn radius is speed / maxRate (radians). Set
     *      spawning.circleRadius comfortably above it.
     *   3. /hostileskies shipnav hold — verify the closed loop converges.
     *      If error GROWS on the first cycles, the hull disagrees with the
     *      STEERING_SIGN
     *
     *      Something else worth mentioning is that ships using something other than swivel bearings
     *      will show as rudder=NaN in the diagnostics. It only checks for swivel bearings in order to
     *      disassemble them before despawning the ship, as at the current time, not doing so will
     *      result in a stray rudder surviving the despawn.
     */
    public static class Navigation {

        // Steering (discrete PD + trim law)
        /** Ticks between steering commands. Must exceed the wheel's kinetic
         *  sequence duration (~21 ticks for a 90° swing at 16 RPM). */
        public int steerCadenceTicks = 30;
        /** Proportional gain: wheel-deg per deg of bearing error. */
        public double headingKp = 1.2;
        /** Derivative gain: wheel-deg per deg/s of error rate. */
        public double headingKd = 6.0;
        /** Trim integrator: trim-deg per err-deg per cycle, active near zero
         *  error. Absorbs standing bias and the orbit's steady-state bearing. */
        public double headingKi = 0.05;

        // Orbit guidance (chase the carrot)
        /** How far ahead (degrees of arc) the carrot leads the ship. */
        public double carrotLeadDeg = 30.0;

        // Spawn
        /** Degrees added to spawn orientation. Use 180 for positive-X forward. */
        public double spawnYawOffset = 0.0;

        // Terrain avoidance
        public boolean avoidanceEnabled = true;
        /** Seconds of travel the terrain probe looks ahead (scales with speed). */
        public double lookaheadSeconds = 6.0;
        /** Required clearance (blocks) between keel and terrain. */
        public double terrainMargin = 8.0;
        /** Max signal levels added to base lift when climbing over terrain. */
        public int liftBoostMax = 3;
        /** Throttle signal during avoidance. -1 disables throttle adjustment. */
        public int avoidThrottleSignal = -1;

        // Stuck recovery
        /** Horizontal speed (blocks/tick) below which the ship counts as stuck. */
        public double stuckSpeedThreshold = 0.05;
        /** Sustained seconds below threshold before recovery triggers. */
        public int stuckSeconds = 5;
        /** Base recovery duration (seconds); actual is randomized 1x–1.5x. */
        public int unstickSeconds = 10;
        /** Signal levels added/subtracted from base lift during recovery. */
        public int unstickLiftBoost = 2;
        /** Throttle signal during recovery. 0 should stop the ship pressing into the obstacle.
         * (Though some ships' throttles are different and a signal of 0 will not stop the engine) */
        public int unstickThrottleSignal = 0;
    }

    /**
     * A named control group: one signal value applied to one or more levers.
     * Lever positions are [x, y, z] relative to the structure origin.
     */
    public static class ControlGroup {
        public int signal = 0;
        public int mercySignal = -1;
        public int[][] levers = new int[0][];
    }

    public static class Spawning {
        /** How far back the ship will spawn, basically the runway the ship has before entering the circle*/
        public double spawnDistance = 75;
        /** Determines how far the edge of the circle will be which also affects where the ship spawns.
         * Must exceed the ship's measured minimum turn radius (speed / max turn rate). */
        public double circleRadius = 80;
        /** How far above the terrain the ship will spawn*/
        public int terrainClearance = 40;
        public int minAltitudeAboveSea = 60;
    }

    public static class Crew {
        public int captain = 1;
        public int pillagers = 2;
        public int vindicators = 0;
        /** Item ID for the captain's held weapon. */
        public String captainWeapon = "minecraft:iron_axe";
        /** Fallback captain spawn positions [x,y,z] when no red seats exist. */
        public int[][] captainSpawns = new int[0][];
        /** Fallback crew spawn positions [x,y,z] when no black seats exist. */
        public int[][] crewSpawns = new int[0][];
    }

    public static class Loot {
        public String containerTable = "";
        /** Loot table for the captain's chest. If empty, ship has no captain's chest. */
        public String captainTable = "";
        /** Structure [x, y, z] of the captain's chest container. */
        public int[] captainChest = null;
    }

    /** Resolves the structure field into a ResourceLocation. Bare names inherit the ship's namespace. */
    public ResourceLocation getStructureId() {
        if (structure.contains(":")) return ResourceLocation.parse(structure);
        String namespace = id.contains(":") ? id.substring(0, id.indexOf(':')) : HostileSkies.MODID;
        return ResourceLocation.fromNamespaceAndPath(namespace, structure);
    }

    /** Resolves the loot table string into a ResourceKey. Falls back to pillager outpost loot. */
    public ResourceKey<LootTable> getLootTableKey() {
        if (loot.containerTable == null || loot.containerTable.isEmpty()) {
            return ResourceKey.create(Registries.LOOT_TABLE,
                    ResourceLocation.withDefaultNamespace("chests/pillager_outpost"));
        }
        return ResourceKey.create(Registries.LOOT_TABLE,
                ResourceLocation.parse(loot.containerTable));
    }

    /** Resolves the captain's chest loot table, or null if this ship has none. */
    public ResourceKey<LootTable> getCaptainTableKey() {
        if (loot.captainTable == null || loot.captainTable.isEmpty()) return null;
        return ResourceKey.create(Registries.LOOT_TABLE,
                ResourceLocation.parse(loot.captainTable));
    }

    /** True if pos matches the configured captain's chest position. */
    public boolean isCaptainChest(BlockPos pos) {
        return loot.captainChest != null && loot.captainChest.length == 3
                && pos.getX() == loot.captainChest[0]
                && pos.getY() == loot.captainChest[1]
                && pos.getZ() == loot.captainChest[2];
    }

    @Override
    public String toString() {
        return name + " [T" + tier + ", structure=" + structure + "]";
    }
}
