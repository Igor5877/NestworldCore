package net.nestworld.module;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MODULE-1: the Core-side orchestrator driving every module through its lifecycle. Status:
 * IMPLEMENTED, NOT YET VERIFIED (this gate's own live-verification pass has not run at the time
 * this class was written).
 *
 * <pre>
 * Responsibility:
 *   Own DISCOVERED->LOADED->ENABLED->RUNNING->QUIESCING->DISABLED (+FAILED) for every module,
 *   assign ModuleGeneration at the linearization point, own each module's ResourceLedger, and
 *   revoke it centrally on quiesce/failure. This IS the "ModuleContext is a lifecycle ownership
 *   boundary, not a DI object" claim, made concrete.
 *
 * Does:
 *   - discover(NestWorldModule) -- startup-only, before server start (DISCOVERED)
 *   - enableAll(CommandDispatcher) -- drives every discovered module LOADED->ENABLED->RUNNING;
 *     wired from NestworldRegionSystem.onRegisterCommands (see that class for why this event,
 *     not onServerStarting, was chosen: it's the point a CommandDispatcher first exists)
 *   - quiesceAll() -- drives every RUNNING module QUIESCING->DISABLED (or FAILED); wired from
 *     NestworldRegionSystem.onServerStopping
 *   - quiesce(ModuleId) -- same, for one module -- exists specifically so a live verification
 *     pass can observe the QUIESCING->DISABLED transition (ledger-empty, rejection-of-new-
 *     registrations) WITHOUT waiting for a full server shutdown. This is NOT hot
 *     reload/replacement -- it exercises the exact same shutdown path server-stop uses, on an
 *     already-discovered, already-loaded module, early. No new code is loaded.
 *
 * Does NOT:
 *   - discover modules from a jar/classpath scan, a filesystem watcher, or any runtime
 *     installation mechanism -- discover() must be called before enableAll(), full stop
 *   - support re-enabling a DISABLED/FAILED module -- MODULE-1 exercises exactly one generation
 *     per server run; process restart is the only unload boundary (docs/MODULE0_AUDIT.md §8)
 *   - implement any classloader isolation -- all modules share NestWorldCore's own classloader
 *
 * Threading:
 *   Every method here is expected to be called from the main/server thread only (the same
 *   thread RegisterCommandsEvent/ServerStoppingEvent fire on) -- matches VelocityBackendRegistry's
 *   own threading discipline. Not synchronized beyond what ConcurrentHashMap gives for free.
 *
 * Invariant:
 *   A module's ModuleGeneration is assigned atomically with the LOADED->ENABLED transition that
 *   makes it able to act (MODULE-0.5 §G1's linearization point) -- never lazily, never on first
 *   registration.
 *
 * Failure:
 *   onEnable throwing -> FAILED, this module's ledger (whatever was registered before the throw)
 *   revoked in reverse order immediately, a ModuleFailure recorded with phase=ENABLED. Other
 *   modules are unaffected -- one module's failure never blocks another's enable.
 *
 * Lifecycle:
 *   One ModuleRegistry (static holder) per JVM process, matching NestworldRegionSystem.INSTANCE's
 *   own shape -- explicitly the SAME class of JVM-global-singleton risk already flagged for that
 *   class and for VelocityBackendRegistry; MODULE-1 does not attempt to solve per-server-instance
 *   isolation here (no integrated-server-restart-in-one-JVM test exists for modules yet), only
 *   documents that this is the same open risk class, not a new one.
 *
 * Evidence / tests:
 *   ExampleModule/FailingExampleModule + /nestworld module RCON commands -- this gate's own
 *   live-verification pass, NOT YET RUN.
 * </pre>
 */
public final class ModuleRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModuleRegistry.class);

    private static final Map<ModuleId, ModuleRuntime> MODULES = new LinkedHashMap<>();
    private static final Map<ModuleId, AtomicLong> GENERATION_COUNTERS = new ConcurrentHashMap<>();
    private static final List<ModuleFailure> FAILURES = new ArrayList<>();

    private ModuleRegistry() {}

    /** Startup-only discovery: call before {@link #enableAll}. */
    public static synchronized void discover(NestWorldModule module) {
        ModuleDescriptor descriptor = module.descriptor();
        if (MODULES.containsKey(descriptor.id())) {
            throw new IllegalStateException("ModuleId already discovered: " + descriptor.id().value());
        }
        MODULES.put(descriptor.id(), new ModuleRuntime(module, descriptor));
        LOGGER.info("NestWorld module DISCOVERED: {}", descriptor.id().value());
    }

    /** Drives every DISCOVERED module through LOADED -> ENABLED -> RUNNING (or FAILED). */
    public static synchronized void enableAll(CommandDispatcher<CommandSourceStack> dispatcher) {
        for (ModuleRuntime rt : MODULES.values()) {
            enableOne(rt, dispatcher);
        }
    }

    private static void enableOne(ModuleRuntime rt, CommandDispatcher<CommandSourceStack> dispatcher) {
        rt.state = ModuleLifecycleState.LOADED;
        long gen = GENERATION_COUNTERS.computeIfAbsent(rt.descriptor.id(), k -> new AtomicLong()).incrementAndGet();
        rt.identity = new ModuleIdentity(rt.descriptor.id(), rt.descriptor.version(), new ModuleGeneration(gen));
        rt.ledger = new ResourceLedger(rt.descriptor.id());
        rt.context = new ModuleContextImpl(rt.identity, rt.ledger, dispatcher);

        rt.state = ModuleLifecycleState.ENABLED;
        try {
            rt.module.onEnable(rt.context);
            rt.state = ModuleLifecycleState.RUNNING;
            LOGGER.info("NestWorld module RUNNING: {} (registered {} resource(s))",
                    rt.identity, rt.ledger.size());
        } catch (Exception e) {
            LOGGER.error("NestWorld module {} FAILED during onEnable -- revoking {} already-registered "
                    + "resource(s) in reverse order", rt.identity, rt.ledger.size(), e);
            rt.context.stopAccepting();
            rt.ledger.revokeAll();
            rt.state = ModuleLifecycleState.FAILED;
            recordFailure(rt, ModuleLifecycleState.ENABLED, "onEnable", e);
        }
    }

    /** Drives every RUNNING module through QUIESCING -> DISABLED (or FAILED). */
    public static synchronized void quiesceAll() {
        for (ModuleId id : List.copyOf(MODULES.keySet())) {
            quiesce(id);
        }
    }

    /** Drives one RUNNING module through QUIESCING -> DISABLED (or FAILED). No-op if the module
     *  isn't currently RUNNING. Exists so live verification can observe this transition without
     *  a full server shutdown -- see class javadoc. */
    public static synchronized void quiesce(ModuleId id) {
        ModuleRuntime rt = MODULES.get(id);
        if (rt == null || rt.state != ModuleLifecycleState.RUNNING) {
            return;
        }
        rt.state = ModuleLifecycleState.QUIESCING;
        rt.context.stopAccepting(); // new registrations REJECTED from this instant
        try {
            rt.ledger.revokeAll();
            rt.module.onDisable();
            rt.state = ModuleLifecycleState.DISABLED;
            LOGGER.info("NestWorld module DISABLED: {} (ledger empty={})", rt.identity, rt.ledger.isEmpty());
        } catch (Exception e) {
            LOGGER.error("NestWorld module {} FAILED during QUIESCING/onDisable -- NOT reporting "
                    + "successful shutdown", rt.identity, e);
            rt.state = ModuleLifecycleState.FAILED;
            recordFailure(rt, ModuleLifecycleState.QUIESCING, "onDisable", e);
        }
    }

    private static void recordFailure(ModuleRuntime rt, ModuleLifecycleState phase, String capability, Throwable cause) {
        FAILURES.add(new ModuleFailure(rt.descriptor.id(), rt.descriptor.version(),
                rt.identity == null ? new ModuleGeneration(-1) : rt.identity.generation(),
                phase, capability, cause, System.currentTimeMillis()));
    }

    // -------- RCON/status surface (net.nestworld.region.NestworldCommand) --------

    public static synchronized List<String> statusLines() {
        List<String> out = new ArrayList<>();
        for (ModuleRuntime rt : MODULES.values()) {
            out.add(String.format("%s state=%s identity=%s ledgerSize=%d",
                    rt.descriptor.id().value(), rt.state,
                    rt.identity == null ? "none" : rt.identity.toString(),
                    rt.ledger == null ? 0 : rt.ledger.size()));
        }
        for (ModuleFailure f : FAILURES) {
            out.add("FAILURE: " + f.describe());
        }
        return out;
    }

    /** Test-only late-registration attempt against an already-loaded module's context, to prove
     *  rejection after QUIESCING without needing the module's own code to expose this. */
    public static synchronized String testLateCommandRegistration(ModuleId id, String name) {
        ModuleRuntime rt = MODULES.get(id);
        if (rt == null || rt.context == null) {
            return "NO_CONTEXT";
        }
        ResourceHandle handle = rt.context.registerCommand(name, ctx -> {
            ctx.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal("late-test-ok"), false);
            return 1;
        });
        return handle == null ? "REJECTED" : "ACCEPTED";
    }
}
