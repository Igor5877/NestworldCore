package net.nestworld.module;

/**
 * MODULE-0.5 §G2's decision: a Module SPI-specific vocabulary, deliberately NOT a reuse or
 * extension of {@code net.nestworld.gmeil.ExecutionPolicy}. GMEIL grades empirically-earned
 * trust for opaque, uncontrolled callback code; this enum classifies the DECLARED contract of a
 * known, first-party-designed extension point -- there is nothing to "certify," Core already
 * knows what thread a hook runs on because Core wrote the invocation site. The two vocabularies
 * compose (a module hook may itself invoke opaque code, which remains independently subject to
 * GMEIL's own classification) rather than collide.
 *
 * <pre>
 * MAIN_ONLY     -- invoked on the main/server thread. Reused naming from
 *                  ModCallbackRouter's existing MAIN_THREAD_ONLY classification, not GMEIL's
 *                  MAIN_COMPAT (whose name carries a "default when we don't know" connotation
 *                  wrong for a declared contract).
 * REGION_LOCAL  -- invoked on the owning region's own thread. Deliberately NOT named
 *                  REGION_CERTIFIED (MODULE-0's draft name) to avoid implying GMEIL's
 *                  evidence-graded certification lifecycle applies -- a module hook here has
 *                  region-thread affinity by the SPI's own invocation contract, not by
 *                  observed evidence.
 * CORE_OWNED    -- module supplies a decision/callback; Core decides if/when/where it runs.
 *                  Reused VERBATIM from GMEIL, deliberately: this is the one concept genuinely
 *                  identical in both systems.
 * </pre>
 *
 * No {@code CONCURRENT}/unmediated value (MODULE-0's draft had one; MODULE-0.5 dropped it --
 * no evidenced extension point needs it). No {@code DENY} -- an unregistered extension point
 * simply has no hook to invoke, there is no "registered but denied" state to represent.
 *
 * MODULE-1 ownership invariant (per explicit instruction): {@code REGION_LOCAL} means Core
 * establishes the valid execution context for a hook -- it does NOT grant the module arbitrary
 * region/entity access. A module must still route any actual entity/region mutation through the
 * same {@code EntityMutationDispatcher}-shaped mechanisms Milestone 1 built; this enum classifies
 * WHERE a hook runs, never grants a capability to bypass ownership routing.
 */
public enum ModuleExecutionContext {
    MAIN_ONLY,
    REGION_LOCAL,
    CORE_OWNED
}
