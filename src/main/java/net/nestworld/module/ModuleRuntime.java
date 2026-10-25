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
    final java.util.Set<ModuleId> requiredDependencies;
    final boolean critical;
    final ClassLoader moduleClassLoader;
    final java.util.Set<String> capabilities;
    final java.util.concurrent.atomic.AtomicBoolean failureRequested = new java.util.concurrent.atomic.AtomicBoolean();
    volatile Throwable runtimeFailure;
    volatile String runtimeFailureCapability;

    ModuleRuntime(NestWorldModule module, ModuleDescriptor descriptor) {
        this(module, descriptor, java.util.Set.of(), false, module.getClass().getClassLoader(), ModuleSpi.capabilityNames());
    }

    ModuleRuntime(NestWorldModule module, ModuleDescriptor descriptor, java.util.Set<ModuleId> requiredDependencies,
                  boolean critical, ClassLoader moduleClassLoader, java.util.Set<String> capabilities) {
        this.module = module;
        this.descriptor = descriptor;
        this.requiredDependencies = java.util.Set.copyOf(requiredDependencies);
        this.critical = critical;
        this.moduleClassLoader = moduleClassLoader;
        this.capabilities = java.util.Set.copyOf(capabilities);
        this.state = ModuleLifecycleState.DISCOVERED;
    }
}
