package net.nestworld.module;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MODULE-1 lifecycle orchestrator, as redefined by MODULE-LIFECYCLE-001
 * (docs/contracts/MODULE-LIFECYCLE-001-spec.md).
 *
 * <pre>
 * Ownership:
 *   One ModuleRegistry PER MinecraftServer lifetime -- a server-owned object, created at the
 *   server's ServerStartingEvent and disposed at its ServerStoppedEvent. Global lookup goes through
 *   a weak, server-keyed facade ({@link #forServer}); nothing here is "reset" between servers.
 *   The only JVM-lifetime state is {@link #GENERATION_COUNTERS} (monotonic: a ModuleGeneration is
 *   never reused within a JVM).
 *
 * Lifecycle (exactly once per server, I1/I2):
 *   discover -> enableAll (LOADED -> ENABLED -> RUNNING / FAILED) -> quiesceAll at stop ->
 *   dispose. No module can reach ENABLED/RUNNING without an owning server. RegisterCommandsEvent
 *   is NOT a module lifecycle event.
 *
 * Commands (I3/I4):
 *   A command is a module-owned declaration; a binding is per dispatcher generation. At enable,
 *   declarations are bound into the server's active dispatcher; on every later
 *   RegisterCommandsEvent of this server (/reload), {@link #bindCommands} binds the RUNNING
 *   modules' live declarations into the new dispatcher -- no rediscovery, no re-enable, no new
 *   generation.
 *
 * Threading (I6):
 *   Every lifecycle and binding method must run on the owning server's thread; enforced.
 *
 * Does NOT:
 *   classpath/jar discovery, hot reload/replacement, re-enabling a DISABLED/FAILED module, or
 *   classloader isolation (physical unload remains restart-only, MODULE-3/4).
 * </pre>
 */
public final class ModuleRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModuleRegistry.class);

    /** JVM lifetime, monotonic per ModuleId (never reset). */
    private static final Map<ModuleId, AtomicLong> GENERATION_COUNTERS = new ConcurrentHashMap<>();

    /** Weak, server-keyed facade (MinecraftServer has identity equality). An entry also disappears
     *  on its own if a server object becomes unreachable without a clean dispose. */
    private static final Map<MinecraftServer, ModuleRegistry> BY_SERVER = Collections.synchronizedMap(new WeakHashMap<>());

    /** Diagnostics (JVM lifetime): how many registries were created / disposed. */
    private static final AtomicLong REGISTRIES_CREATED = new AtomicLong();
    private static final AtomicLong REGISTRIES_DISPOSED = new AtomicLong();

    private final MinecraftServer server;
    private final Map<ModuleId, ModuleRuntime> modules = new LinkedHashMap<>();
    private final List<ModuleFailure> failures = new ArrayList<>();
    private long discoveries, enables, dispatcherBindings;
    private volatile boolean disposed;

    private ModuleRegistry(MinecraftServer server) {
        this.server = server;
    }

    // ------------------------------------------------------------------ facade

    /** Creates the registry of {@code server}. One per server lifetime; a second create throws. */
    public static ModuleRegistry create(MinecraftServer server) {
        if (server == null) throw new IllegalArgumentException("a ModuleRegistry needs an owning MinecraftServer (I1)");
        synchronized (BY_SERVER) {
            if (BY_SERVER.containsKey(server)) {
                throw new IllegalStateException("ModuleRegistry already exists for this server lifetime");
            }
            ModuleRegistry r = new ModuleRegistry(server);
            BY_SERVER.put(server, r);
            REGISTRIES_CREATED.incrementAndGet();
            return r;
        }
    }

    /** The registry of {@code server}, or null if it has none (not started, or already disposed). */
    public static ModuleRegistry forServer(MinecraftServer server) {
        return server == null ? null : BY_SERVER.get(server);
    }

    /** ServerStoppedEvent: the registry leaves the facade and becomes unreachable (I5). */
    public static void dispose(MinecraftServer server) {
        ModuleRegistry r = BY_SERVER.remove(server);
        if (r != null) {
            r.disposed = true;
            REGISTRIES_DISPOSED.incrementAndGet();
            LOGGER.info("NestWorld module registry disposed for server {} ({} module(s), {} dispatcher binding(s))",
                    Integer.toHexString(System.identityHashCode(server)), r.modules.size(), r.dispatcherBindings);
        }
    }

    /** Diagnostics for lifecycle verification. */
    public static String jvmSummary() {
        return "registriesCreated=" + REGISTRIES_CREATED.get() + " registriesDisposed=" + REGISTRIES_DISPOSED.get()
                + " liveRegistries=" + BY_SERVER.size();
    }

    public static int liveRegistries() {
        return BY_SERVER.size();
    }

    // ------------------------------------------------------------------ lifecycle (owning server's thread)

    private void requireServerThread(String what) {
        if (disposed) throw new IllegalStateException("ModuleRegistry." + what + " on a disposed registry");
        if (!server.isSameThread()) {
            throw new IllegalStateException("ModuleRegistry." + what + " must run on the owning server thread (I6), was "
                    + Thread.currentThread().getName());
        }
    }

    /** DISCOVERED. Exactly once per module per server lifetime. */
    public synchronized void discover(NestWorldModule module) {
        requireServerThread("discover");
        ModuleDescriptor descriptor = module.descriptor();
        if (modules.containsKey(descriptor.id())) {
            throw new IllegalStateException("ModuleId already discovered in this server lifetime: " + descriptor.id().value());
        }
        modules.put(descriptor.id(), new ModuleRuntime(module, descriptor));
        discoveries++;
        LOGGER.info("NestWorld module DISCOVERED: {}", descriptor.id().value());
    }

    /** Drives every DISCOVERED module through LOADED -> ENABLED -> RUNNING (or FAILED). */
    public synchronized void enableAll() {
        requireServerThread("enableAll");
        for (ModuleRuntime rt : modules.values()) {
            if (rt.state == ModuleLifecycleState.DISCOVERED) enableOne(rt);
        }
    }

    private void enableOne(ModuleRuntime rt) {
        rt.state = ModuleLifecycleState.LOADED;
        long gen = GENERATION_COUNTERS.computeIfAbsent(rt.descriptor.id(), k -> new AtomicLong()).incrementAndGet();
        rt.identity = new ModuleIdentity(rt.descriptor.id(), rt.descriptor.version(), new ModuleGeneration(gen));
        rt.ledger = new ResourceLedger(rt.descriptor.id());
        // I4: a declaration made during enable is bound into the server's ACTIVE dispatcher at once.
        rt.context = new ModuleContextImpl(rt.identity, rt.ledger,
                declaration -> declaration.bindInto(server.getCommands().getDispatcher()));

        rt.state = ModuleLifecycleState.ENABLED;
        enables++;
        try {
            rt.module.onEnable(rt.context);
            rt.state = ModuleLifecycleState.RUNNING;
            LOGGER.info("NestWorld module RUNNING: {} (registered {} resource(s))", rt.identity, rt.ledger.size());
        } catch (Exception e) {
            LOGGER.error("NestWorld module {} FAILED during onEnable -- revoking {} already-registered "
                    + "resource(s) in reverse order", rt.identity, rt.ledger.size(), e);
            rt.context.stopAccepting();
            rt.ledger.revokeAll();
            rt.state = ModuleLifecycleState.FAILED;
            recordFailure(rt, ModuleLifecycleState.ENABLED, "onEnable", e);
        }
    }

    /**
     * RegisterCommandsEvent of THIS server (a new dispatcher generation, e.g. /reload): bind the live
     * command declarations of every RUNNING module into {@code dispatcher}. Never discovers, enables
     * or changes a ModuleGeneration (I3).
     */
    public synchronized int bindCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        requireServerThread("bindCommands");
        int bound = 0;
        for (ModuleRuntime rt : modules.values()) {
            if (rt.state != ModuleLifecycleState.RUNNING) continue;
            for (ModuleContextImpl.CommandDeclaration d : rt.context.liveDeclarations()) {
                d.bindInto(dispatcher);
                bound++;
            }
        }
        dispatcherBindings++;
        LOGGER.info("NestWorld modules: bound {} command declaration(s) into dispatcher generation {}", bound, dispatcherBindings + 1);
        return bound;
    }

    /** Drives every RUNNING module through QUIESCING -> DISABLED (or FAILED). */
    public synchronized void quiesceAll() {
        requireServerThread("quiesceAll");
        for (ModuleId id : List.copyOf(modules.keySet())) {
            quiesce(id);
        }
    }

    /** Drives one RUNNING module through QUIESCING -> DISABLED (or FAILED). No-op if not RUNNING.
     *  Exists so live verification can observe this transition without a full server shutdown. */
    public synchronized void quiesce(ModuleId id) {
        requireServerThread("quiesce");
        ModuleRuntime rt = modules.get(id);
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

    private void recordFailure(ModuleRuntime rt, ModuleLifecycleState phase, String capability, Throwable cause) {
        failures.add(new ModuleFailure(rt.descriptor.id(), rt.descriptor.version(),
                rt.identity == null ? new ModuleGeneration(-1) : rt.identity.generation(),
                phase, capability, cause, System.currentTimeMillis()));
    }

    // ------------------------------------------------------------------ status / verification surface

    public synchronized List<String> statusLines() {
        List<String> out = new ArrayList<>();
        out.add(String.format("registry server=%s discoveries=%d enables=%d dispatcherBindings=%d | %s",
                Integer.toHexString(System.identityHashCode(server)), discoveries, enables, dispatcherBindings, jvmSummary()));
        for (ModuleRuntime rt : modules.values()) {
            out.add(String.format("%s state=%s identity=%s ledgerSize=%d",
                    rt.descriptor.id().value(), rt.state,
                    rt.identity == null ? "none" : rt.identity.toString(),
                    rt.ledger == null ? 0 : rt.ledger.size()));
        }
        for (ModuleFailure f : failures) {
            out.add("FAILURE: " + f.describe());
        }
        return out;
    }

    public synchronized long discoveries() { return discoveries; }
    public synchronized long enables() { return enables; }
    public synchronized long dispatcherBindings() { return dispatcherBindings; }

    public synchronized int runningCount() {
        int n = 0;
        for (ModuleRuntime rt : modules.values()) if (rt.state == ModuleLifecycleState.RUNNING) n++;
        return n;
    }

    /** Generation of one module in this server lifetime, or -1. */
    public synchronized long generationOf(ModuleId id) {
        ModuleRuntime rt = modules.get(id);
        return rt == null || rt.identity == null ? -1 : rt.identity.generation().value();
    }

    /** Test-only late-registration attempt against an already-loaded module's context, to prove
     *  rejection after QUIESCING without needing the module's own code to expose this. */
    public synchronized String testLateCommandRegistration(ModuleId id, String name) {
        ModuleRuntime rt = modules.get(id);
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
