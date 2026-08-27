package minecrafttransportsimulator.baseclasses;

/**
 * Lifecycle states used while moving a vehicle between its active entity graph and a
 * parked, persistent proxy record.  PARKING_PENDING and WAKING are transactional states:
 * the currently authoritative representation is retained until the replacement has been
 * durably prepared and validated.
 */
public enum VehicleParkingState {
    ACTIVE,
    PARKING_PENDING,
    PARKED,
    WAKING
}
