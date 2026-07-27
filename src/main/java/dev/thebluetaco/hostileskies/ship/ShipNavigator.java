package dev.thebluetaco.hostileskies.ship;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.EmbeddedPlotLevelAccessor;
import dev.simulated_team.simulated.content.blocks.steering_wheel.SteeringWheelBlockEntity;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;
import dev.simulated_team.simulated.content.blocks.throttle_lever.ThrottleLeverBlockEntity;
import dev.thebluetaco.hostileskies.HostileSkies;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

import java.util.List;

/**
 * Per-raid ship navigation. One instance lives on each TrackedRaid.
 *
 * Everything here was validated empirically on the diagnostic ladder
 * (in ShipTemplate) before being trusted. The three most important things
 * that you should know, each proven through testing or by Simulated itself:
 * (in the words of claude)
 *
 * 1. THE WHEEL IS A STEP-AND-SETTLE DEVICE. updateTargetAngle() runs a
 *    timed kinetic sequence at a fixed 16 RPM; per-tick command modulation
 *    desyncs wheel from rudder. Commands are issued ONCE per STEER cadence
 *    and allowed to complete. targetAngleToUpdate (the wheel's persistent
 *    setpoint) must be written, or the wheel snaps the target back when a
 *    sequence finishes.
 *
 * 2. MEASUREMENT IS LOCAL-FRAME, NOT EULER. Heading uses swing-twist
 *    decomposition (Simulated's getClosestYaw); steering error uses the
 *    bearing to the target computed by inverse-rotating the target vector
 *    into the ship frame with the full quaternion - immune to roll/pitch.
 *
 * 3. THE CONTROL LAW IS DISCRETE PD + TRIM:
 *    wheelTarget = KP*err + KD*errRate + trim, one bounded command per
 *    cadence cycle. KD provides anticipation (no overshoot); the trim
 *    integrator (active only near zero error) removes standing bias.
 *
 * GUIDANCE is pure pursuit of a "carrot" point leading the ship around the
 * patrol circle by carrotLeadDeg of arc. One code path serves approach and
 * patrol: from far away the carrot pulls the ship onto the rim, and on the
 * circle it produces steady circular flight. The steering error is
 *
 *     err = -localBearingDegrees(target - shipPos)
 *
 * SIGN DERIVATION: for orientation q = rotation by theta about +Y,
 * twistYaw(q) = -theta, and ship forward (local -X) maps to world
 * (-cos theta, sin theta). A target 90° to the ship's local +bearing side
 * gives localBearing = +90 and HOLD-convention error = twistTgt - twistYaw
 * = -90. Hence err_HOLD = -localBearing, and the validated steering-sign
 * chain applies unchanged.
 *
 * The diagnostics (/hostileskies shipnav sense|wheel|hold|off) are the
 * permanent tuning protocol for every new ship added to the mod.
 */
public class ShipNavigator {

    // Control constants
    /** Radius error for the approach to patrol transition. */
    private static final double CIRCLE_ENTRY_THRESHOLD = 15.0;
    private static final int DIAG_LOG_INTERVAL = 20;
    private static final int PATROL_LOG_INTERVAL = 100;

    private static final double TRIM_LIMIT = 10.0;
    private static final double TRIM_ACTIVE_ERR = 15.0;
    /** Fleet wide wheel polarity. All types of rudders (front and rear)
     *  need the same sign. If a future hull's error grows during /shipnav hold,
     *  expose this as per-ship config. */
    private static final double STEERING_SIGN = -1.0;
    /** Max wheel change per cycle. A 90° swing completes in ~21 ticks at the
     *  wheel's fixed 16 RPM, inside the default 30-tick cadence. */
    private static final double MAX_STEP_PER_CYCLE = 25.0;

    // Avoidance constants

    private static final int AVOID_SCAN_INTERVAL = 10;
    private static final int CLEAR_HOLD_TICKS = 40;
    /** Additional hold time before releasing a LIFT boost specifically (stern
     *  trails the center based probes by up to half the ship's length). */
    private static final int DESCENT_EXTRA_HOLD_TICKS = 40;
    /** Probe bearings in degrees off the nose (localBearing convention).
     *  Index 0 must be dead ahead. */
    private static final double[] PROBE_BEARINGS_DEG = {0.0, -25.0, 25.0, -50.0, 50.0};
    private static final double[] PROBE_FRACTIONS = {0.35, 0.7, 1.0};
    private static final double MIN_LOOKAHEAD = 30.0;
    private static final double MAX_LOOKAHEAD = 90.0;
    private static final int STUCK_WARMUP_TICKS = 200;
    private static final double SPEED_SMOOTHING = 0.05;

    private static final String LIFT_GROUP = "lift";
    private static final String THROTTLE_GROUP = "throttle";

    // Immutable context

    private final ShipTemplate ship;
    private final Vec3 patrolCenter;
    private final List<BlockPos> steeringWheelPositions;
    private final ServerLevel level;
    private final net.minecraft.core.Vec3i structureSize;

    // Control state

    private double currentWheelAngle = 0.0;
    private double trim = 0.0;
    private Double prevErrDeg = null;
    private long ticksActive = 0;

    /** Orbit direction (+1/-1), decided at spawn time.
     * The tangent spawn geometry shares the same handedness */
    private double orbitSign = 1.0;

    /** Captured heading (twist convention, radians) held during DEPARTING. */
    private Double departHeadingRad = null;

    // Motion estimation

    private Vec3 prevPos = null;
    private double smoothedSpeed = 0.0;

    // Avoidance state

    private int liftBoost = 0;
    /** The current keel altitude when the lift boost is engaged.
     *  release only when the forward path clears at THIS height too. */
    private double boostBaseKeelY = Double.NaN;
    private int clearTicks = 0;
    private Double avoidBearingDeg = null;
    private double avoidSeverity = 0.0;
    private boolean throttleReduced = false;

    private int lastAppliedLift = Integer.MIN_VALUE;
    private int lastAppliedThrottle = Integer.MIN_VALUE;
    /** Set by RaidManager during MERCY; -1 = none. */
    private int throttleBaseOverride = -1;

    // Stuck recovery

    private int slowTicks = 0;
    private int unstickTicksLeft = 0;
    private boolean unstickWheelCentered = false;
    private boolean unstickClimb = true;

    // Wheel/rudder

    private float lastWrittenWheelAngle = Float.NaN;
    private boolean wheelDiagLogged = false;
    private BlockPos bearingPos = null;
    private boolean bearingScanned = false;

    // Diagnostics

    /** SENSE: wheel at 0. WHEEL: fixed angle. HOLD: closed-loop heading hold. */
    public enum DiagMode { OFF, SENSE, WHEEL, HOLD }
    private DiagMode diagMode = DiagMode.OFF;
    private double diagWheelAngle = 0.0;
    private Double diagHoldHeadingRad = null;
    private long diagTicks = 0;
    private Double diagPrevTwistYaw = null;
    private Vec3 diagPrevPos = null;

    public ShipNavigator(ShipTemplate ship, Vec3 patrolCenter,
                         List<BlockPos> steeringWheelPositions,
                         ServerLevel level, net.minecraft.core.Vec3i structureSize) {
        this.ship = ship;
        this.patrolCenter = patrolCenter;
        this.steeringWheelPositions = steeringWheelPositions;
        this.level = level;
        this.structureSize = structureSize;
    }

    /** Called by RaidManager when mercy throttle is applied or restored. */
    public void setThrottleBaseOverride(int signal) {
        this.throttleBaseOverride = signal;
        this.lastAppliedThrottle = Integer.MIN_VALUE;
    }

    public void setOrbitSign(double sign) {
        this.orbitSign = sign >= 0 ? 1.0 : -1.0;
    }

    /** Enables/disables diagnostic mode. While active, all navigation is bypassed. */
    public void setDiagnostics(DiagMode mode, double value) {
        this.diagMode = mode;
        this.diagWheelAngle = value;
        this.diagHoldHeadingRad = (mode == DiagMode.HOLD && !Double.isNaN(value))
                ? Math.toRadians(value) : null;
        this.prevErrDeg = null;
        this.trim = 0.0;
        this.diagTicks = 0;
        this.diagPrevTwistYaw = null;
        this.diagPrevPos = null;
        this.currentWheelAngle = (mode == DiagMode.WHEEL) ? value : 0.0;
        this.lastWrittenWheelAngle = Float.NaN;
        HostileSkies.LOGGER.info("[NavDiag] mode={} value={}", mode, value);
    }

    // Phase ticks

    /**
     * Approach: chase the orbit carrot (same behavior as patrol).
     * Returns true when within CIRCLE_ENTRY_THRESHOLD of the circle edge.
     */
    public boolean tickApproach(ServerSubLevel sl) {
        if (diagMode != DiagMode.OFF) { runDiagnostics(sl); return false; }
        NavTick t = beginTick(sl);
        if (handleUnstick(sl, t)) return false;
        runAvoidanceAndLevers(sl, t);
        steerToCarrot(sl, t);
        return Math.abs(t.distToCenter - ship.spawning.circleRadius) < CIRCLE_ENTRY_THRESHOLD;
    }

    /** Patrol: orbit the center. */
    public void tickPatrol(ServerSubLevel sl) {
        if (diagMode != DiagMode.OFF) { runDiagnostics(sl); return; }
        NavTick t = beginTick(sl);
        if (handleUnstick(sl, t)) return;
        runAvoidanceAndLevers(sl, t);
        steerToCarrot(sl, t);

        if (ticksActive % PATROL_LOG_INTERVAL == 0) {
            HostileSkies.LOGGER.info(
                    "[Nav] patrol r={} (target {}) y={} spd={} b/s whl={} trim={}",
                    String.format("%.1f", t.distToCenter),
                    String.format("%.0f", ship.spawning.circleRadius),
                    String.format("%.0f", t.y),
                    String.format("%.2f", smoothedSpeed * 20.0),
                    String.format("%+.1f", currentWheelAngle),
                    String.format("%+.1f", trim));
        }
    }

    /**
     * Departure: hold the heading the ship had when departure began. Terrain
     * avoidance stays active so a departing ship doesn't faceplant into a mountain. */
    public void tickDepart(ServerSubLevel sl) {
        if (diagMode != DiagMode.OFF) { runDiagnostics(sl); return; }
        NavTick t = beginTick(sl);
        if (handleUnstick(sl, t)) return;
        runAvoidanceAndLevers(sl, t);

        if (departHeadingRad == null) {
            departHeadingRad = t.twistYawRad;
        }
        if (ticksActive % ship.navigation.steerCadenceTicks == 0) {
            double errDeg = wrapDegrees(Math.toDegrees(departHeadingRad - t.twistYawRad));
            applySteeringLaw(sl, errDeg);
        }
    }

    public void straightenWheel(ServerSubLevel sl, String context) {
        currentWheelAngle = 0.0;
        trim = 0.0;
        prevErrDeg = null;
        setWheelAngle(sl, 0.0, context);
    }

    // Per-tick sampling

    private class NavTick {
        double x, y, z;
        Quaterniondc q;
        double twistYawRad;
        double distToCenter;
    }

    private NavTick beginTick(ServerSubLevel sl) {
        ticksActive++;

        Pose3d pose = sl.logicalPose();
        NavTick t = new NavTick();
        t.x = pose.position().x();
        t.y = pose.position().y();
        t.z = pose.position().z();
        t.q = pose.orientation();
        t.twistYawRad = twistYaw(t.q);

        double rx = t.x - patrolCenter.x;
        double rz = t.z - patrolCenter.z;
        t.distToCenter = Math.sqrt(rx * rx + rz * rz);

        if (prevPos != null) {
            double step = Math.hypot(t.x - prevPos.x, t.z - prevPos.z);
            smoothedSpeed += (step - smoothedSpeed) * SPEED_SMOOTHING;
        }
        prevPos = new Vec3(t.x, t.y, t.z);

        return t;
    }

    // Guidance: just straight up chasing a carrot

    /**
     * The carrot sits on the patrol circle, carrotLeadDeg of arc ahead of the
     * ship's angular position, in the configured orbit direction. From far away
     * it pulls the ship onto the rim; on the circle it produces steady circular
     * flight. The trim integrator absorbs the steady-state bearing the circular
     * geometry requires.
     */
    private void steerToCarrot(ServerSubLevel sl, NavTick t) {
        if (ticksActive % ship.navigation.steerCadenceTicks != 0) return;

        double rx = t.x - patrolCenter.x;
        double rz = t.z - patrolCenter.z;
        double r = Math.max(1e-3, Math.sqrt(rx * rx + rz * rz));
        double phi = Math.atan2(rz, rx);

        double lead = Math.toRadians(ship.navigation.carrotLeadDeg) * orbitSign;
        double R = ship.spawning.circleRadius;

        double carrotX = patrolCenter.x + R * Math.cos(phi + lead);
        double carrotZ = patrolCenter.z + R * Math.sin(phi + lead);

        // err_HOLD = -localBearing (sign derivation in class doc)
        double errDeg = -localBearingDegrees(t.q,
                carrotX - t.x, 0.0, carrotZ - t.z);

        if (avoidBearingDeg != null && avoidSeverity > 0) {
            double avoidErr = -avoidBearingDeg;
            errDeg = errDeg + wrapDegrees(avoidErr - errDeg) * avoidSeverity;
        }

        applySteeringLaw(sl, errDeg);
    }

    /** The validated PD + trim law: one bounded command per cadence cycle. */
    private void applySteeringLaw(ServerSubLevel sl, double errDeg) {
        ShipTemplate.Navigation nav = ship.navigation;

        double errRateDps = 0.0;
        if (prevErrDeg != null) {
            errRateDps = wrapDegrees(errDeg - prevErrDeg)
                    / (nav.steerCadenceTicks / 20.0);
        }
        prevErrDeg = errDeg;

        if (Math.abs(errDeg) < TRIM_ACTIVE_ERR) {
            trim = Math.max(-TRIM_LIMIT, Math.min(TRIM_LIMIT,
                    trim + errDeg * nav.headingKi));
        }

        double max = wheelMaxAngle(sl);
        double target = (errDeg * nav.headingKp + errRateDps * nav.headingKd + trim)
                * STEERING_SIGN;
        target = Math.max(-max, Math.min(max, target));

        double step = Math.max(-MAX_STEP_PER_CYCLE, Math.min(MAX_STEP_PER_CYCLE,
                target - currentWheelAngle));
        currentWheelAngle = Math.max(-max, Math.min(max, currentWheelAngle + step));
        setWheelAngle(sl, currentWheelAngle, null);
    }

    // Avoidance + levers

    private void runAvoidanceAndLevers(ServerSubLevel sl, NavTick t) {
        if (ship.navigation.avoidanceEnabled && ticksActive % AVOID_SCAN_INTERVAL == 0) {
            scanTerrain(t, diagMode == DiagMode.OFF && departHeadingRad == null);
        }
        applyLiftAndThrottle(sl);
    }

    /**
     * Scans the world heightmap along a fan of bearings off the nose, scaled
     * by measured speed. Probe directions are built by rotating local bearing
     * vectors into the world frame, same convention as the steering error.
     */
    private void scanTerrain(NavTick t, boolean allowSteer) {
        ShipTemplate.Navigation nav = ship.navigation;

        double lookahead = Math.max(MIN_LOOKAHEAD, Math.min(MAX_LOOKAHEAD,
                smoothedSpeed * 20.0 * nav.lookaheadSeconds));
        double keelY = t.y - structureSize.getY() / 2.0;
        double safeY = keelY - nav.terrainMargin;

        double[] clearance = new double[PROBE_BEARINGS_DEG.length];
        double nearForwardClearance = Double.POSITIVE_INFINITY;
        double worstForwardTerrain = Double.NEGATIVE_INFINITY;

        for (int i = 0; i < PROBE_BEARINGS_DEG.length; i++) {
            double b = Math.toRadians(PROBE_BEARINGS_DEG[i]);
            Vector3d dir = new Vector3d(-Math.cos(b), 0.0, Math.sin(b));
            t.q.transform(dir);

            double worstTerrain = Double.NEGATIVE_INFINITY;
            for (double f : PROBE_FRACTIONS) {
                int sx = (int) Math.floor(t.x + dir.x * lookahead * f);
                int sz = (int) Math.floor(t.z + dir.z * lookahead * f);
                if (!level.hasChunkAt(new BlockPos(sx, 0, sz))) continue;

                int terrain = level.getHeight(Heightmap.Types.MOTION_BLOCKING, sx, sz);
                worstTerrain = Math.max(worstTerrain, terrain);

                if (i == 0 && f == PROBE_FRACTIONS[0]) {
                    nearForwardClearance = safeY - terrain;
                }
            }
            if (i == 0) worstForwardTerrain = worstTerrain;
            clearance[i] = (worstTerrain == Double.NEGATIVE_INFINITY)
                    ? Double.POSITIVE_INFINITY
                    : safeY - worstTerrain;
        }

        double forwardClearance = clearance[0];

        // Descend safely: only safe when the forward path clears at the pre-boost height too.
        double releaseClearance = Double.POSITIVE_INFINITY;
        if (liftBoost > 0 && !Double.isNaN(boostBaseKeelY)
                && worstForwardTerrain != Double.NEGATIVE_INFINITY) {
            releaseClearance = Math.min(keelY, boostBaseKeelY)
                    - nav.terrainMargin - worstForwardTerrain;
        }

        if (forwardClearance < 0) {
            clearTicks = 0;
            if (liftBoost == 0) {
                boostBaseKeelY = keelY;
            }
            liftBoost = nav.liftBoostMax;

            if (allowSteer) {
                int best = 0;
                for (int i = 1; i < clearance.length; i++) {
                    if (clearance[i] > clearance[best]) best = i;
                }
                if (best != 0 && clearance[best] > 0) {
                    avoidBearingDeg = PROBE_BEARINGS_DEG[best];
                    avoidSeverity = Math.max(0.4, Math.min(1.0,
                            0.4 + (-forwardClearance) / 20.0));
                    HostileSkies.LOGGER.info(
                            "[Nav] terrain ahead (clearance {}), steering {} deg and climbing",
                            String.format("%.1f", forwardClearance), PROBE_BEARINGS_DEG[best]);
                } else {
                    avoidBearingDeg = null;
                    avoidSeverity = 0.0;
                    HostileSkies.LOGGER.info(
                            "[Nav] terrain ahead on all bearings (clearance {}), climbing",
                            String.format("%.1f", forwardClearance));
                }
            }

            throttleReduced = nav.avoidThrottleSignal >= 0 && nearForwardClearance < 0;
        } else if (releaseClearance < 0) {
            // Clear at current altitude but not after descending, hold the boost
            clearTicks = 0;
        } else {
            clearTicks += AVOID_SCAN_INTERVAL;
            int holdRequired = CLEAR_HOLD_TICKS
                    + (liftBoost > 0 ? DESCENT_EXTRA_HOLD_TICKS : 0);
            if (clearTicks >= holdRequired) {
                if (liftBoost != 0 || avoidBearingDeg != null || throttleReduced) {
                    HostileSkies.LOGGER.info("[Nav] path clear. Resuming normal flight");
                }
                liftBoost = 0;
                boostBaseKeelY = Double.NaN;
                avoidBearingDeg = null;
                avoidSeverity = 0.0;
                throttleReduced = false;
            }
        }
    }

    private void applyLiftAndThrottle(ServerSubLevel sl) {
        ShipTemplate.Navigation nav = ship.navigation;

        ShipTemplate.ControlGroup lift = ship.controls.get(LIFT_GROUP);
        if (lift != null) {
            int boost = unstickTicksLeft > 0
                    ? (unstickClimb ? nav.unstickLiftBoost : -nav.unstickLiftBoost)
                    : liftBoost;
            int target = Math.min(15, Math.max(0, lift.signal + boost));
            if (target != lastAppliedLift) {
                applyGroupSignal(sl, lift, target, LIFT_GROUP);
                lastAppliedLift = target;
            }
        }

        ShipTemplate.ControlGroup throttle = ship.controls.get(THROTTLE_GROUP);
        if (throttle != null) {
            int base = throttleBaseOverride >= 0 ? throttleBaseOverride : throttle.signal;
            int target;
            if (unstickTicksLeft > 0) {
                // Stop pressing into the obstacle (un-deadlocks collided ships)
                target = Math.min(base, nav.unstickThrottleSignal);
            } else if (nav.avoidThrottleSignal >= 0 && throttleReduced) {
                target = Math.min(base, nav.avoidThrottleSignal);
            } else if (nav.avoidThrottleSignal >= 0 || lastAppliedThrottle != Integer.MIN_VALUE) {
                target = base;
            } else {
                return;
            }
            if (target != lastAppliedThrottle) {
                applyGroupSignal(sl, throttle, target, THROTTLE_GROUP);
                lastAppliedThrottle = target;
            }
        }
    }

    private void applyGroupSignal(ServerSubLevel sl, ShipTemplate.ControlGroup group,
                                  int signal, String label) {
        EmbeddedPlotLevelAccessor acc = sl.getPlot().getEmbeddedLevelAccessor();
        for (int[] pos : group.levers) {
            BlockEntity be = acc.getBlockEntity(new BlockPos(pos[0], pos[1], pos[2]));
            if (be instanceof ThrottleLeverBlockEntity lever) {
                lever.setSignal(signal);
            } else {
                HostileSkies.LOGGER.warn("[Nav] {} lever at [{},{},{}] returned {}",
                        label, pos[0], pos[1], pos[2],
                        be != null ? be.getClass().getSimpleName() : "null");
            }
        }
        HostileSkies.debug("[Nav] {} signal -> {}", label, signal);
    }

    // Stuck recovery
    /** Sustained low speed triggers recovery: lift boost and wheel centered
     * until the ship moves again or the window expires.  */
    private boolean handleUnstick(ServerSubLevel sl, NavTick t) {
        ShipTemplate.Navigation nav = ship.navigation;

        // Mercy aware threshold: hardcoded to base / 4 for now.
        double threshold = nav.stuckSpeedThreshold;
        if (throttleBaseOverride >= 0) {
            threshold = nav.stuckSpeedThreshold / 4.0;
        }

        if (unstickTicksLeft > 0) {
            unstickTicksLeft--;
            applyLiftAndThrottle(sl);
            if (!unstickWheelCentered) {
                currentWheelAngle = 0.0;
                setWheelAngle(sl, 0.0, "unstick-center");
                unstickWheelCentered = true;
            }

            if (smoothedSpeed > threshold * 1.5 || unstickTicksLeft == 0) {
                unstickTicksLeft = 0;
                lastAppliedLift = Integer.MIN_VALUE;
                prevErrDeg = null;
                HostileSkies.LOGGER.info("[Nav] unstick complete (speed={})",
                        String.format("%.3f", smoothedSpeed));
            }
            return true;
        }

        if (ticksActive > STUCK_WARMUP_TICKS) {
            if (smoothedSpeed < threshold) {
                slowTicks++;
                if (slowTicks >= nav.stuckSeconds * 20) {
                    slowTicks = 0;
                    // Rare edge case safeguard to break symmetry between two deadlocked ships
                    // (Randomized direction + duration) (rocket league rule 1 iykyk)
                    unstickClimb = level.random.nextBoolean();
                    unstickTicksLeft = nav.unstickSeconds * 20
                            + level.random.nextInt(nav.unstickSeconds * 10);
                    unstickWheelCentered = false;
                    HostileSkies.LOGGER.info(
                            "[Nav] ship appears stuck (speed={}) — {} to recover ({}t)",
                            String.format("%.3f", smoothedSpeed),
                            unstickClimb ? "climbing" : "descending",
                            unstickTicksLeft);
                    return true;
                }
            } else {
                slowTicks = 0;
            }
        }
        return false;
    }

    // Wheel/rudder I/O
    /** Writes a target angle to every steering wheel. Sets targetAngleToUpdate
     * (the persistent setpoint) and starts the sequence. Skips unchanged commands. */
    private void setWheelAngle(ServerSubLevel sl, double angle, String context) {
        if (context == null && (float) angle == lastWrittenWheelAngle) return;
        lastWrittenWheelAngle = (float) angle;

        EmbeddedPlotLevelAccessor acc = sl.getPlot().getEmbeddedLevelAccessor();
        boolean shouldLog = !wheelDiagLogged || context != null;

        for (BlockPos wheelPos : steeringWheelPositions) {
            BlockEntity be = acc.getBlockEntity(wheelPos);
            if (be instanceof SteeringWheelBlockEntity wheel) {
                float maxAngle = wheel.angleInput.getValue();
                float clamped = (float) Math.max(-maxAngle, Math.min(maxAngle, angle));
                wheel.targetAngleToUpdate = clamped;
                wheel.updateTargetAngle(clamped);

                if (shouldLog) {
                    HostileSkies.debug("Wheel at {}: target={}, max={}, clamped={}{}",
                            wheelPos, angle, maxAngle, clamped,
                            context != null ? " [" + context + "]" : "");
                }
            } else if (shouldLog) {
                HostileSkies.LOGGER.warn("Wheel at {} returned {} instead of SteeringWheelBlockEntity",
                        wheelPos, be != null ? be.getClass().getSimpleName() : "null");
            }
        }

        if (shouldLog && context == null) {
            wheelDiagLogged = true;
        }
    }

    private float readWheelAngle(ServerSubLevel sl) {
        if (steeringWheelPositions.isEmpty()) return Float.NaN;
        BlockEntity be = sl.getPlot().getEmbeddedLevelAccessor()
                .getBlockEntity(steeringWheelPositions.get(0));
        return (be instanceof SteeringWheelBlockEntity wheel) ? wheel.getAngle() : Float.NaN;
    }

    private double wheelMaxAngle(ServerSubLevel sl) {
        if (steeringWheelPositions.isEmpty()) return 45.0;
        BlockEntity be = sl.getPlot().getEmbeddedLevelAccessor()
                .getBlockEntity(steeringWheelPositions.get(0));
        return (be instanceof SteeringWheelBlockEntity wheel) ? wheel.angleInput.getValue() : 45.0;
    }

    /** The swivel bearing's angle. The authoritative rudder state. NaN if none. */
    private double readRudderAngle(ServerSubLevel sl) {
        EmbeddedPlotLevelAccessor acc = sl.getPlot().getEmbeddedLevelAccessor();
        if (!bearingScanned) {
            bearingScanned = true;
            outer:
            for (int bx = 0; bx < structureSize.getX(); bx++) {
                for (int by = 0; by < structureSize.getY(); by++) {
                    for (int bz = 0; bz < structureSize.getZ(); bz++) {
                        BlockPos p = new BlockPos(bx, by, bz);
                        if (acc.getBlockEntity(p) instanceof SwivelBearingBlockEntity) {
                            bearingPos = p;
                            HostileSkies.LOGGER.info("[Nav] swivel bearing found at {}", p);
                            break outer;
                        }
                    }
                }
            }
            if (bearingPos == null) {
                HostileSkies.LOGGER.info("[Nav] no swivel bearing found in structure");
            }
        }
        if (bearingPos == null) return Double.NaN;
        BlockEntity be = acc.getBlockEntity(bearingPos);
        return (be instanceof SwivelBearingBlockEntity bearing)
                ? wrapDegrees(bearing.getTargetAngleDegrees()) : Double.NaN;
    }

    // Diagnostics

    private void runDiagnostics(ServerSubLevel sl) {
        diagTicks++;
        ticksActive++;

        Pose3d pose = sl.logicalPose();
        double x = pose.position().x();
        double y = pose.position().y();
        double z = pose.position().z();
        Quaterniondc q = pose.orientation();
        double yawTwistRad = twistYaw(q);
        double yawTwistDeg = Math.toDegrees(yawTwistRad);

        double holdErrDeg = 0.0;
        if (diagMode == DiagMode.HOLD) {
            if (diagHoldHeadingRad == null) {
                diagHoldHeadingRad = yawTwistRad;
                HostileSkies.LOGGER.info("[NavDiag] HOLD captured current heading: {}",
                        String.format("%.1f", yawTwistDeg));
            }
            holdErrDeg = wrapDegrees(Math.toDegrees(diagHoldHeadingRad) - yawTwistDeg);

            if (diagTicks % ship.navigation.steerCadenceTicks == 0) {
                applySteeringLaw(sl, holdErrDeg);
            }
        } else {
            double hold = (diagMode == DiagMode.WHEEL) ? diagWheelAngle : 0.0;
            currentWheelAngle = hold;
            setWheelAngle(sl, hold, null);
        }

        if (diagTicks % DIAG_LOG_INTERVAL != 0) return;

        double bearingDeg = localBearingDegrees(q,
                patrolCenter.x - x, patrolCenter.y - y, patrolCenter.z - z);
        double tiltDeg = tiltDegrees(q);

        double speedBps = 0.0;
        double yawRateDps = 0.0;
        if (diagPrevPos != null) {
            speedBps = Math.hypot(x - diagPrevPos.x, z - diagPrevPos.z);
        }
        if (diagPrevTwistYaw != null) {
            yawRateDps = wrapDegrees(yawTwistDeg - diagPrevTwistYaw);
        }
        diagPrevPos = new Vec3(x, y, z);
        diagPrevTwistYaw = yawTwistDeg;

        String line = String.format(
                "[NavDiag] whlCmd=%+.1f whlAct=%+.1f rudder=%+.1f | spd=%.2f b/s | yawTwist=%+.1f (rate %+.2f\u00b0/s) | brgCtr=%+.1f | tilt=%.1f",
                currentWheelAngle, readWheelAngle(sl), readRudderAngle(sl),
                speedBps, yawTwistDeg, yawRateDps, bearingDeg, tiltDeg);
        if (diagMode == DiagMode.HOLD) {
            line += String.format(" | HOLD tgt=%+.1f err=%+.1f trim=%+.1f",
                    Math.toDegrees(diagHoldHeadingRad), holdErrDeg, trim);
        }
        HostileSkies.LOGGER.info(line);
        for (ServerPlayer p : level.players()) {
            p.sendSystemMessage(Component.literal("\u00a7b" + line));
        }
    }

    // Math

    /** Legacy Euler yaw, retained only for crew spawn facing in RaidManager. */
    public static double extractYaw(Quaterniondc q) {
        double sinYaw = 2.0 * (q.w() * q.y() + q.x() * q.z());
        double cosYaw = 1.0 - 2.0 * (q.y() * q.y() + q.z() * q.z());
        return Math.atan2(sinYaw, cosYaw);
    }

    /** Swing-twist yaw about UP (Simulated's getClosestYaw), roll/pitch immune. */
    public static double twistYaw(Quaterniondc q) {
        return 2.0 * Math.atan2(-q.y(), q.w());
    }

    /** Bearing to a world-space offset, in degrees off the nose (forward = -X). */
    private static double localBearingDegrees(Quaterniondc q, double dx, double dy, double dz) {
        Vector3d v = new Vector3d(dx, dy, dz);
        q.transformInverse(v);
        return Math.toDegrees(Math.atan2(v.z, -v.x));
    }

    /** Total tilt (roll+pitch) magnitude in degrees. */
    private static double tiltDegrees(Quaterniondc q) {
        Vector3d down = new Vector3d(0.0, -1.0, 0.0);
        q.transformInverse(down);
        return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, -down.y))));
    }

    private static double wrapDegrees(double a) {
        while (a > 180.0) a -= 360.0;
        while (a < -180.0) a += 360.0;
        return a;
    }

    // Persistence

    /** Wheel angle, trim, and orbit sign persist. Everything else reconverges. */
    public void save(CompoundTag tag) {
        tag.putDouble("steeringAngle", currentWheelAngle);
        tag.putDouble("navTrim", trim);
        tag.putDouble("orbitSign", orbitSign);
    }

    public void load(CompoundTag tag) {
        if (tag.contains("steeringAngle")) {
            currentWheelAngle = tag.getDouble("steeringAngle");
        }
        if (tag.contains("navTrim")) {
            trim = tag.getDouble("navTrim");
        }
        if (tag.contains("orbitSign")) {
            orbitSign = tag.getDouble("orbitSign");
        }
    }
}
