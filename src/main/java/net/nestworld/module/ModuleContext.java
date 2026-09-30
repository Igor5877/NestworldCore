package net.nestworld.module;

import com.mojang.brigadier.Command;
import net.minecraft.commands.CommandSourceStack;

/**
 * `docs/MODULE0_AUDIT.md` §D: the Core-controlled boundary a module uses to acquire resources
 * during and after {@code onEnable}. Every successful registration is recorded in this module's
 * {@link ResourceLedger} and returns a {@link ResourceHandle} the module never needs to call
 * itself -- Core revokes centrally on quiesce/failure. Deliberately narrow: only the two
 * resource types `docs/MODULE0_AUDIT.md` §D actually evidenced as low-risk and Core-precedented
 * (listeners, commands) -- per explicit instruction not to invent APIs for resource types with
 * no current use.
 *
 * <pre>
 * Responsibility:
 *   The only way a module acquires Core-tracked resources. A module must never reach for raw
 *   Forge APIs (MinecraftForge.EVENT_BUS.register directly, hand-rolled Brigadier tree edits)
 *   for anything this interface already covers -- doing so escapes ledger tracking entirely.
 *
 * Does:
 *   - registerListener(Object) -- MinecraftForge.EVENT_BUS registration, real teardown via
 *     .unregister() on revoke
 *   - registerCommand(String, Command<CommandSourceStack>) -- a top-level Brigadier literal,
 *     added as a sibling to NestWorld's own command tree, never touching it
 *   - identity() -- this module's ModuleIdentity, for the module's own logging/attribution use
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
 *   Registration methods are expected to be called from the main/server thread (the thread
 *   ModuleRegistry drives onEnable/RUNNING work from in MODULE-1's scope).
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

    /** Registers {@code listener} with the Forge event bus. Returns null if this module is no
     *  longer accepting registrations (QUIESCING or later). */
    ResourceHandle registerListener(Object listener);

    /** Registers a top-level Brigadier command literal {@code name -> executor}, as a sibling of
     *  NestWorld's own command tree (never modifies it). Returns null if this module is no
     *  longer accepting registrations. See {@code ModuleContextImpl}'s javadoc for the honest
     *  limitation on command revocation -- the executor stops running on revoke, but the
     *  Brigadier tree node itself cannot be removed with this Minecraft version's Brigadier API
     *  (confirmed: {@code CommandNode} has {@code addChild}, no {@code removeChild}/
     *  {@code removeCommand}). */
    ResourceHandle registerCommand(String name, Command<CommandSourceStack> executor);
}
