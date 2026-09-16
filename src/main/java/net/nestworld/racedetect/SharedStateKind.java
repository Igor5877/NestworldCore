package net.nestworld.racedetect;

/**
 * Coarse storage-class tag for a candidate field, as determined purely from its bytecode
 * descriptor by {@link StaticCandidateAnalyzer} -- deliberately a single flat enum rather than a
 * two-axis (storage-class x content-shape) taxonomy. Richer typing belongs to a later classifier
 * stage; Stage A's job is just "is this worth treating as a shared-mutable-state candidate at
 * all," not precise categorization.
 */
public enum SharedStateKind {
    INSTANCE_FIELD,
    STATIC_FIELD,
    COLLECTION,
    ARRAY,
    MAP,
    SET,
    LIST,
    QUEUE,
    REGISTRY_LIKE,
    SINGLETON_LAZY_INIT,
    MUTABLE_REFERENCE
}
