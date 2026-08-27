package minecrafttransportsimulator.baseclasses;

/**Structured reasons that keep an active vehicle out of the parked proxy state.*/
public enum VehicleParkingBlocker {
    NONE,
    FEATURE_DISABLED,
    PLATFORM_UNSUPPORTED,
    CLIENT_WORLD,
    INVALID_ENTITY,
    TRANSITION_IN_PROGRESS,
    RIDER_PRESENT,
    LINEAR_MOTION,
    ANGULAR_MOTION,
    COLLISION_ACTIVITY,
    TOWING_CONNECTION,
    ENGINE_ACTIVITY,
    ELECTRICAL_ACTIVITY,
    NAVIGATION_ACTIVITY,
    DAMAGE_ACTIVITY,
    PART_ACTIVITY
}
