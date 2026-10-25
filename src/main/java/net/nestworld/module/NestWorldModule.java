package net.nestworld.module;

/**
 * The interface a privileged NestWorld module implements. Built-ins are handed to
 * {@link ModuleRegistry#discover}; external implementations are discovered at server start from
 * {@code nestworld-modules/*.jar}. Discovery remains startup-only with no filesystem watcher.
 *
 * <pre>
 * Responsibility:
 *   Supply the module's static identity (descriptor) and its ENABLED-transition behavior
 *   (onEnable, which registers whatever the module needs through the ModuleContext it's given).
 *
 * Does:
 *   - descriptor() -- static metadata, called once, before this module is ever loaded
 *   - onEnable(ModuleContext) -- called once per generation, at LOADED -> ENABLED. May register
 *     resources through the given context. May throw -- ModuleRegistry attributes the failure
 *     and revokes everything registered before the throw, in reverse order.
 *   - onDisable() -- called once per generation, during QUIESCING, AFTER this module's
 *     ResourceLedger has already been revoked. Best-effort cleanup hook for anything the module
 *     tracks itself outside ModuleContext (which ModuleRegistry cannot see or guarantee --
 *     see docs/MODULE0_AUDIT.md §D's module-owned-resources row). Exceptions here are logged,
 *     attributed, and do not prevent the DISABLED transition.
 *
 * Does NOT:
 *   - get any guarantee about WHICH thread onEnable/onDisable run on beyond "the thread
 *     ModuleRegistry drives lifecycle from" (main/server thread in MODULE-1's scope) -- a
 *     future capability with its own ModuleExecutionContext may run module-supplied CALLBACKS
 *     elsewhere, but onEnable/onDisable themselves are lifecycle-management, not a registered
 *     capability with its own execution-context declaration
 *   - get a mechanism to veto or delay QUIESCING -- per docs/MODULE0_AUDIT.md §F, a module may
 *     observe QUIESCING (via onDisable) but not block it
 *
 * Threading:
 *   Main/server thread, in MODULE-1's scope.
 *
 * Failure:
 *   onEnable throwing -> FAILED, ledger revoked in reverse order, ModuleFailure attributed with
 *   phase=ENABLED. onDisable throwing -> logged/attributed, module still reaches DISABLED (a
 *   module cannot hold itself hostage in QUIESCING by throwing).
 *
 * Lifecycle:
 *   One NestWorldModule instance may be re-enabled across multiple generations in a future gate
 *   (not MODULE-1 -- process restart is MODULE-1's only unload boundary); MODULE-1 itself only
 *   exercises exactly one generation per server run.
 * </pre>
 */
public interface NestWorldModule {
    ModuleDescriptor descriptor();

    void onEnable(ModuleContext context) throws Exception;

    default void onDisable() {
    }
}
