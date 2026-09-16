package net.nestworld.racedetect;

/**
 * Access-operation taxonomy for a single field access site, as found by
 * {@link StaticCandidateAnalyzer}. One physical bytecode access (a {@code GETFIELD}/
 * {@code PUTFIELD}/{@code GETSTATIC}/{@code PUTSTATIC} plus whatever call immediately follows it
 * on the operand stack) gets classified into exactly one of these -- except {@link #RMW}, which
 * is an ADDITIONAL tag applied on top of a site's primary classification when the analyzer finds
 * a matching read-then-write pair on the same field within one method (see
 * {@code StaticCandidateAnalyzer}'s RMW detection).
 */
public enum HazardType {
    READ,
    WRITE,
    MUTATE,
    ITERATE,
    RMW,
    PUBLICATION,
    REFERENCE_ESCAPE
}
