package net.nestworld.compat.ae2.kernel;

import java.util.List;

/**
 * What a guarded AE2 entry needs (AE2-COMPAT-DESIGN-001.1 §3, §4).
 *
 * <p>{@link #resolve()} is an optimistic read of the member locks (it may race); the kernel locks the
 * returned set in ascending serial order and then calls {@link #validate()}, which re-checks
 * membership and entry identity with the locks held. {@code false} means the set changed (overlay
 * rebuilt, node moved to another grid, grid died): the kernel releases and retries.
 */
public interface DomainSpec {
    /** The entry changes AE2 topology and must hold T0 before any domain lock. */
    boolean topology();

    /** Candidate member locks; may be empty (an entry with nothing to guard but T0). */
    List<DomainLock> resolve();

    /** Re-check with the candidates held; {@code false} = retry. */
    boolean validate();
}
