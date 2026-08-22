package net.nestworld.metrics;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Separate companion mod (deliberately NOT baked into NestworldCore's patched forge
 * jar -- lives in {@code mods/}, updatable/redeployable independently) that exposes
 * NestworldCore's live state plus JVM internals as a Prometheus {@code /metrics} HTTP
 * endpoint. Built 2026-08-21 after a night of watchdog crashes where the only way to
 * tell "CPU genuinely saturated" from "one thread serialized everything" from "one
 * call ran long" was manual crash-report archaeology -- this is the standing
 * instrument so that question never needs guessing again.
 *
 * <p>Everything it reads comes through {@code net.nestworld.api.NestworldApi} (the
 * public, stable facade) plus standard {@code java.lang.management} MXBeans -- no
 * internal NestworldCore classes, no patches, no mixins. Talks to the server only by
 * reading; never mutates state.
 *
 * <p><b>2026-08-22 correction:</b> this class itself must NEVER declare a method
 * (including a synthetic lambda) whose parameter/return type is anything from {@code
 * net.nestworld.api} -- {@code MinecraftForge.EVENT_BUS.register(this)} in the
 * constructor makes Forge's EventBus reflectively call {@code
 * Class.getDeclaredMethods()} on this exact class to find {@code @SubscribeEvent}
 * methods, and that JVM reflection call must resolve EVERY declared method's
 * signature (parameter/return/exception types) to build the {@code Method[]} array --
 * including ones never annotated, even synthetic lambda methods -- so a single
 * {@code net.nestworld.api} type anywhere in this class's compiled method signatures
 * throws {@code NoClassDefFoundError} at mod construction time on a server that
 * doesn't have NestworldCore's API (plain Forge, or an old NestworldCore build that
 * predates it) -- BEFORE {@link #onServerStarted}'s own guard below ever gets a
 * chance to run. Confirmed live: a {@code .forEach(r -> ...)} lambda over a {@code
 * List<RegionSnapshot>} inside the old single-class version's render() compiled a
 * synthetic method with {@code RegionSnapshot} as its parameter type, crashing mod
 * loading entirely on an old-core server with this exact stack trace. Fix: ALL
 * {@code net.nestworld.api} usage lives in {@link NestworldMetricsRenderer} instead
 * -- a completely separate class, never registered with EventBus, never reflected
 * over at mod-construction time, only loaded lazily when {@link #handleMetrics}
 * actually calls into it (i.e. only once an HTTP request for {@code /metrics}
 * arrives, and only after {@link #onServerStarted}'s own presence check already
 * confirmed NestworldCore is there).
 */
@Mod("nestworld_metrics")
public final class NestworldMetricsMod {

    private static volatile MinecraftServer serverRef;
    private static HttpServer http;

    public NestworldMetricsMod() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        serverRef = event.getServer();

        // NestworldCore-only: this mod's whole render() path calls net.nestworld.api
        // .NestworldApi, a class that only exists on a NestworldCore-patched server jar
        // -- on plain Forge it isn't on the classpath at all. Rather than start the HTTP
        // server anyway and throw NoClassDefFoundError on every single /metrics scrape
        // forever (200 OK with an error body, but a full stack trace to the log every
        // ~15s), detect that up front and simply never open the port. Presence-only
        // check (Class.forName), NOT NestworldApi.isActive() -- the region system may
        // still be a few ticks from finishing init at ServerStartedEvent time even on a
        // real NestworldCore server, and this runs exactly once per boot, so gating on
        // "active" here risks a false negative that skips /metrics for the whole
        // session; every NestworldApi method already returns empty/zeroed data (never
        // throws) when the region system isn't active yet, so that transient state is
        // already handled correctly inside render() itself, per-request.
        try {
            Class.forName("net.nestworld.api.NestworldApi");
        } catch (Throwable t) {
            System.out.println("[NestworldMetrics] NestworldCore not detected (plain Forge, "
                    + "or an incompatible build) -- this mod only works on NestworldCore. "
                    + "Not starting /metrics.");
            return;
        }

        int port = Integer.getInteger("nestworld.metrics.port", 9219);
        try {
            http = HttpServer.create(new InetSocketAddress(port), 0);
            http.createContext("/metrics", NestworldMetricsMod::handleMetrics);
            http.setExecutor(null);
            http.start();
            System.out.println("[NestworldMetrics] listening on :" + port + "/metrics");
        } catch (IOException e) {
            System.err.println("[NestworldMetrics] failed to start HTTP server: " + e);
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (http != null) {
            http.stop(0);
        }
    }

    private static void handleMetrics(HttpExchange ex) throws IOException {
        String body;
        try {
            body = NestworldMetricsRenderer.render(serverRef);
        } catch (Throwable e) {
            // Never let a rendering bug take the exporter thread down -- this module
            // exists to diagnose crashes, it must not become one. Catches Throwable, not
            // just Exception -- com.sun.net.httpserver silently closes the connection
            // (empty reply, no log) on an uncaught Error, which made a first version of
            // this handler's own bugs invisible.
            System.err.println("[NestworldMetrics] render() failed: " + e);
            e.printStackTrace();
            body = "# render error: " + e + "\n";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
