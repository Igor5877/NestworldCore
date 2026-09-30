package net.nestworld.module;

/**
 * `docs/MODULE0_AUDIT.md` §G/§9's failure-attribution record. Reuses `NestworldPins`'s
 * pin-reason-tracking SHAPE (attribute why something got disabled) as a template, applied to
 * module-lifecycle failures instead of mod tick-errors.
 *
 * <p>Deliberately outcome-only: this is ATTRIBUTION (what failed, where, why), never a ROLLBACK
 * mechanism (`docs/MODULE0_AUDIT.md` §9/§11) -- no code here claims or attempts to undo any
 * module mutation. It only records enough to answer "which module, which generation, which
 * lifecycle phase, which capability, what threw" after the fact.
 */
public record ModuleFailure(
        ModuleId moduleId,
        ModuleVersion moduleVersion,
        ModuleGeneration moduleGeneration,
        ModuleLifecycleState phase,
        String capability,
        Throwable cause,
        long timestampMillis
) {
    public String describe() {
        return moduleId.value() + "@" + moduleVersion.value() + " gen=" + moduleGeneration.value()
                + " phase=" + phase + " capability=" + capability
                + " cause=" + (cause == null ? "none" : cause.getClass().getSimpleName() + ": " + cause.getMessage());
    }
}
