package net.nestworld.racedetect;

/**
 * Identity of a single static race candidate. Deliberately NOT just class+field: one field can
 * have multiple independent candidates (different callsites/methods touching the same field are
 * separate hazards with separate evidence), and the same class+field+method+callsite combination
 * analyzed under a different analyzer/ruleset version is a distinct candidate too -- so version
 * numbers live IN the identity, not just alongside it, making the candidate's own identity tuple
 * and the on-disk content-addressable cache key the same thing.
 *
 * {@code callsiteBytecodeOffset} is the instruction index (not a source line number -- Stage A
 * reads with {@code ClassReader.SKIP_DEBUG}) of the field-access opcode within its method's
 * instruction list. Stable within one exact class file; not expected to survive a recompile, but
 * that's fine since {@code jarSha256} already scopes this identity to one exact jar build.
 */
public record CandidateId(
        String jarSha256,
        String className,
        String fieldName,
        String methodName,
        int callsiteBytecodeOffset,
        HazardType hazardType,
        int analyzerSchemaVersion,
        String rulesetVersion
) {
}
