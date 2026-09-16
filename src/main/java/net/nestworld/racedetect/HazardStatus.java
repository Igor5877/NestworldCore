package net.nestworld.racedetect;

/**
 * Full race-classification taxonomy, reserved in full now so later detector stages (runtime
 * instrumentation, synchronization/ownership tracking, evidence engine, classifier) never need a
 * breaking enum change. Stage A (pure static analysis, no runtime data) only ever produces
 * {@link #STATIC_ONLY_CANDIDATE} -- every other value here is unreachable until a later stage
 * exists to earn it. Never assign a "CONFIRMED_*" value without an actual evidence chain backing
 * it; that discipline is the whole point of this system per the user's own ТЗ.
 */
public enum HazardStatus {
    /** The only status Stage A can ever produce: a real static access pattern, no runtime proof yet. */
    STATIC_ONLY_CANDIDATE,
    UNKNOWN_SYNCHRONIZATION,
    HIGH_CONFIDENCE_SUSPECT,
    CONFIRMED_DATA_RACE,
    CONFIRMED_ITERATION_MUTATION_RACE,
    CONFIRMED_RMW_RACE,
    CONFIRMED_OWNERSHIP_VIOLATION,
    FALSE_POSITIVE
}
