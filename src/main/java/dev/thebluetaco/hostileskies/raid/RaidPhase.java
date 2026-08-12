package dev.thebluetaco.hostileskies.raid;

/**
 * Lifecycle phases for a raid ship encounter.
 */
public enum RaidPhase {
    /** Brief hold while balloon initializes and pre-fills. */
    INITIALIZING,
    /** Ship flies from spawn point toward the patrol center. */
    APPROACHING,
    /** Ship circles the patrol center, waiting for player engagement. */
    PATROLLING,
    /** Player died aboard — ship slows to half speed, mercy timer active. */
    MERCY,
    /** Patrol expired or evicted. Ship turns away, throttles up, and leaves. */
    DEPARTING_BORED,
    /** Captain killed, unclaimed. Alarm sounds, straight course, destruction imminent. */
    DEPARTING_EMERGENCY,
    /** Player claimed the ship — removed from raid tracking entirely. */
    CAPTURED
}
