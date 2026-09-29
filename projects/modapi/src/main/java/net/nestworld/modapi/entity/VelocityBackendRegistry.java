package net.nestworld.modapi.entity;

import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Core-internal backend/platform boundary. Status: IMPLEMENTED (API-1A.6), NOT YET VERIFIED
 * (API-1A.7's repeated-integrated-server-start negative test has not run). Full contract:
 * {@code docs/contracts/SetVelocityPrimitive.md}'s "Backend lifecycle" section.
 *
 * <pre>
 * Responsibility:
 *   The install/uninstall boundary between NestWorldCore and the public Mod API facade for
 *   the setVelocity primitive. Mods must never call {@link #install}/{@link #uninstall} --
 *   only net.nestworld.region.NestworldRegionSystem's own server-lifecycle hooks do.
 *
 * Does:
 *   - hold the currently-active VelocityExecutionBackend (null when none installed)
 *   - track WHICH MinecraftServer instance installed it (the "server token")
 *   - refuse to clear the backend on uninstall unless the caller's token matches the one
 *     recorded at install time
 *
 * Does NOT:
 *   - use ServiceLoader, SPI, dependency injection, or any registry keyed by more than one
 *     concurrent entry -- deliberately the smallest mechanism API-1A.5P's PoC proved
 *     sufficient; do not add more machinery without a concrete lifecycle problem forcing it
 *   - allow more than one backend to be installed at a time (a second install without an
 *     intervening uninstall logs a WARN and replaces -- treated as a bug signal, not a
 *     supported multi-backend feature)
 *
 * Threading:
 *   install/uninstall are expected to be called only from the main server thread (Forge's
 *   ServerStartingEvent/ServerStoppingEvent handlers run there). current() is read via a
 *   volatile field and is safe to call from any thread -- this is exactly what
 *   NestWorldEntities.setVelocity does, from arbitrary caller threads.
 *
 * Invariant:
 *   A backend installed for server generation N can never be uninstalled by a stale call
 *   carrying server generation N-1's token. This is the state machine's entire correctness
 *   argument -- see the class javadoc's transition diagram. It exists specifically so this
 *   new API surface does NOT repeat NestworldRegionSystem.INSTANCE's own already-flagged
 *   JVM-global-singleton lifecycle risk (relevant on integrated servers, where world restarts
 *   reuse the same JVM).
 *
 * Failure:
 *   install() over an existing backend: WARN-logged, not an exception -- replaces anyway
 *   (defensive, should not happen in normal operation; a missed uninstall would be a real bug
 *   surfaced by this log, not silently masked).
 *   uninstall() with a mismatched token: silently ignored -- this is CORRECT behavior (a
 *   stale call from a previous server generation), not a swallowed error.
 *
 * Lifecycle:
 *   UNINSTALLED -> INSTALLED(server A) -> UNINSTALLED -> INSTALLED(server B) -> ...
 *   Installed on ServerStartingEvent, uninstalled on ServerStoppingEvent, once per
 *   MinecraftServer instance -- see NestworldRegionSystem.onServerStarting/onServerStopping
 *   for the actual call sites.
 *
 * Evidence / tests:
 *   Compiles and links correctly (confirmed). The specific negative test this class exists
 *   for -- repeated integrated-server start in the same JVM, confirming no stale
 *   previous-server backend survives -- is API-1A.7's job, NOT YET RUN.
 * </pre>
 */
public final class VelocityBackendRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(VelocityBackendRegistry.class);

    private static volatile VelocityExecutionBackend backend;
    private static volatile MinecraftServer installedFor;

    private VelocityBackendRegistry() {}

    public static void install(MinecraftServer server, VelocityExecutionBackend b) {
        if (backend != null) {
            LOGGER.warn("NestWorld modapi: VelocityExecutionBackend already installed (for {}) "
                    + "-- replacing with a new install for {}. This should not happen in normal "
                    + "operation -- a prior uninstall was likely missed.", installedFor, server);
        }
        installedFor = server;
        backend = b;
        // API-1A.7 TEMP INSTRUMENTATION (lifecycle/same-JVM evidence gate, 2026-09-29): identity
        // hash lets a same-JVM double-boot test distinguish server generations even though
        // MinecraftServer itself doesn't print anything distinguishing by default.
        LOGGER.info("[API1A7-LIFECYCLE] install() server identityHash={}", System.identityHashCode(server));
    }

    public static void uninstall(MinecraftServer server) {
        boolean matched = (installedFor == server);
        LOGGER.info("[API1A7-LIFECYCLE] uninstall() server identityHash={} matchedInstalledToken={} "
                + "installedForIdentityHash={}", System.identityHashCode(server), matched,
                installedFor == null ? "null" : System.identityHashCode(installedFor));
        if (matched) {
            backend = null;
            installedFor = null;
        }
        // Mismatched token: a stale uninstall from a previous server generation -- not this
        // server's backend to clear. Ignore rather than clobber a newer install.
    }

    static VelocityExecutionBackend current() {
        return backend;
    }
}
