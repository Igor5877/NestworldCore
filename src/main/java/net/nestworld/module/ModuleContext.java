package net.nestworld.module;

import com.mojang.brigadier.Command;
import net.minecraft.commands.CommandSourceStack;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;

import java.util.function.Consumer;

/**
 * `docs/MODULE0_AUDIT.md` §D: the Core-controlled boundary a module uses to acquire resources
 * during and after {@code onEnable}. Every successful registration is recorded in this module's
 * {@link ResourceLedger} and returns a {@link ResourceHandle} the module never needs to call
 * itself -- Core revokes centrally on quiesce/failure. Initially listeners and commands;
 * Fast Transition adds bounded login policies and chunk leases for its concrete use case.
 * No raw scheduler, worker pool or arbitrary Core object access is exposed.
 *
 * <pre>
 * Responsibility:
 *   The only way a module acquires Core-tracked resources. A module must never reach for raw
 *   Forge APIs (MinecraftForge.EVENT_BUS.register directly, hand-rolled Brigadier tree edits)
 *   for anything this interface already covers -- doing so escapes ledger tracking entirely.
 *
 * Does:
 *   - registerListener(type, priority, receiveCancelled, handler) -- classloader-safe Forge
 *     event registration, real teardown via .unregister() on revoke
 *   - registerCommand(String, Command<CommandSourceStack>) -- a top-level Brigadier literal,
 *     added as a sibling to NestWorld's own command tree, never touching it
 *   - identity() -- this module's ModuleIdentity, for the module's own logging/attribution use
 *   - bounded login send policies, non-blocking chunk preparation and sync-data snapshots
 *
 * Does NOT:
 *   - accept registrations once this module's generation is no longer current (QUIESCING or
 *     later) -- every method here checks liveness and returns null (registration REJECTED) if
 *     not currently ENABLED/RUNNING; a module must handle a null return, not assume acceptance
 *   - expose ANY net.nestworld.region internal type -- this is a privileged, NestWorld-specific
 *     SPI (unlike net.nestworld.modapi, this is not required to be Core-free), but it is still
 *     scoped to exactly the capabilities named here, not a general escape hatch into Core
 *   - grant arbitrary region/entity access merely because a module holds a context -- per the
 *     ownership invariant (see ModuleExecutionContext), nothing here bypasses
 *     EntityMutationDispatcher-shaped routing for entity/region mutation, because nothing here
 *     exposes entity/region mutation at all in MODULE-1's scope
 *
 * Threading:
 *   Registration/capability methods require the owning server thread; Core enforces this.
 *
 * Failure:
 *   A rejected registration (module not live) returns null -- outcome-only, never throws for
 *   that case. A genuinely invalid registration (bad Brigadier syntax etc.) may throw, which
 *   ModuleRegistry attributes as a ModuleFailure at whatever lifecycle phase it occurred.
 *
 * Lifecycle:
 *   One instance per ModuleRuntime, created at LOADED->ENABLED, valid for the module's entire
 *   RUNNING lifetime (a module may call these methods any time while RUNNING, not only inside
 *   onEnable), rejects everything from the instant QUIESCING begins.
 *
 * Evidence / tests:
 *   ExampleModule/FailingExampleModule + the RCON module test commands (API-1A.7-style live
 *   verification, this gate's own equivalent) -- MODULE-1's own verification pass, not yet run
 *   at the time this interface was written.
 * </pre>
 */
public interface ModuleContext {
    ModuleIdentity identity();

    /** Identity check for filtering global Forge events; grants no access to server internals. */
    boolean ownsServer(net.minecraft.server.MinecraftServer server);

    /** Register one bounded login send policy. Null on quiesce; a second live policy is rejected.
     * Does not authorize skipping ANY payload, mod check, acknowledgement or lifecycle hook. */
    ResourceHandle registerLoginBudget(LoginBudget budget);

    /** Prepare FULL, lit chunks under a bounded lease. Null on quiesce. All calls require the
     * owning server thread; completion is observed on server END ticks. Core revokes all leases
     * on failure/shutdown. At most 8 live preparations, radius <= 4, lifetime <= 1200 ticks. */
    ChunkPreparation prepareChunks(ChunkPreparationRequest request);

    /** Capture registry/recipe content generations on the server thread. Potentially expensive;
     * call at startup/reload, not per connection. Throws after quiesce or serialization failure. */
    SyncDataSnapshot snapshotSyncData();

    /** Registers a typed listener through EventBus' lambda path. This is the only supported
     * listener form for external modules because annotation wrappers are generated only for
     * GAME-layer classes. Returns null after quiesce. */
    <E extends Event> ResourceHandle registerListener(Class<E> type, EventPriority priority,
                                                       boolean receiveCancelled, Consumer<E> handler);

    default <E extends Event> ResourceHandle registerListener(Class<E> type, Consumer<E> handler) {
        return registerListener(type, EventPriority.NORMAL, false, handler);
    }

    /** Registers a top-level Brigadier command literal {@code name -> executor}, as a sibling of
     *  NestWorld's own command tree (never modifies it). Returns null if this module is no
     *  longer accepting registrations. See {@code ModuleContextImpl}'s javadoc for the honest
     *  limitation on command revocation -- the executor stops running on revoke, but the
     *  Brigadier tree node itself cannot be removed with this Minecraft version's Brigadier API
     *  (confirmed: {@code CommandNode} has {@code addChild}, no {@code removeChild}/
     *  {@code removeCommand}). */
    ResourceHandle registerCommand(String name, Command<CommandSourceStack> executor);
}
