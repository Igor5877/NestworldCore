package net.nestworld.module;

/** Package-private: Core-side bookkeeping for one discovered/loaded module. Not exposed to
 *  modules themselves -- they only ever see {@link NestWorldModule}/{@link ModuleContext}. */
final class ModuleRuntime {
    final NestWorldModule module;
    final ModuleDescriptor descriptor;
    volatile ModuleLifecycleState state;
    volatile ModuleIdentity identity; // generation assigned at LOADED -> ENABLED
    volatile ResourceLedger ledger;
    volatile ModuleContextImpl context;

    ModuleRuntime(NestWorldModule module, ModuleDescriptor descriptor) {
        this.module = module;
        this.descriptor = descriptor;
        this.state = ModuleLifecycleState.DISCOVERED;
    }
}
