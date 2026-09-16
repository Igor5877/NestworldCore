package net.nestworld.racedetect;

/**
 * One static race candidate, produced by {@link StaticCandidateAnalyzer}. Immutable by design --
 * later stages that escalate a candidate's confidence never mutate this record, they create a
 * new, richer evidence record referencing this one's {@link CandidateId}.
 *
 * @param descriptor           the field's raw JVM type descriptor (e.g. {@code Ljava/util/Map;})
 * @param kind                 coarse storage-class tag, from the field declaration alone
 * @param jdkCollectionTypeOrNull the matched concrete/interface collection type name if {@code kind}
 *                             indicates a collection shape (e.g. {@code java/util/HashMap}), else null
 * @param status               always {@link HazardStatus#STATIC_ONLY_CANDIDATE} for Stage A output
 * @param detailNote           short human-debuggable explanation of why this site was flagged
 *                             (e.g. "PUTFIELD items; nearby INVOKEINTERFACE Map.clear") -- answers
 *                             "why did the analyzer flag this" without needing a debugger
 * @param modId                denormalized mod attribution ("minecraft"/"forge"/"nestworldcore" or
 *                             a real third-party modId) so registry queries don't need a second lookup
 */
public record Candidate(
        CandidateId id,
        String descriptor,
        SharedStateKind kind,
        String jdkCollectionTypeOrNull,
        HazardStatus status,
        String detailNote,
        String modId
) {
}
