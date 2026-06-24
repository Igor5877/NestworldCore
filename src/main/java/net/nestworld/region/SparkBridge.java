package net.nestworld.region;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * In-game {@code /spark} command backed by the spark <em>standalone agent</em>.
 *
 * <p>The regular spark Forge mod cannot load in this dev environment (it is
 * compiled against production SRG names, the dev runtime uses mapped names).
 * The standalone agent however is mapping-agnostic — it profiles the JVM
 * directly. We load its (fully shaded) jar into an isolated classloader and
 * drive {@code StandaloneSparkPlugin.execute(String[], sender)} reflectively,
 * routing replies back to the command source's chat. No compile-time
 * dependency on spark.
 */
public final class SparkBridge {

    private static final Logger LOGGER = LogManager.getLogger("NestWorld/SparkBridge");
    private static final Pattern ANSI = Pattern.compile("\\[[0-9;]*m");
    private static final String JAR_NAME = "spark-standalone-agent.jar";

    private static Object plugin;            // StandaloneSparkPlugin
    private static Method executeMethod;     // execute(String[], StandaloneCommandSender)
    private static Constructor<?> senderCtor;
    private static Class<?> outputInterface; // StandaloneCommandSender.Output

    // Auto-profiler (NestworldTuning.AUTO_SPARK): the core starts a continuous all-threads profiler
    // at boot and never stops it (only `open`), then writes the resulting viewer link to
    // spark-history.txt on server stop/restart. URL pattern matches spark's upload line.
    private static final Pattern SPARK_URL = Pattern.compile("https://spark\\.lucko\\.me/\\S+");
    private static volatile String lastUrl;

    private SparkBridge() {}

    public static int run(CommandSourceStack src, String argLine) {
        if (!ensureLoaded()) {
            src.sendFailure(Component.literal(
                    "spark не ініціалізувався — поклади " + JAR_NAME + " поряд із сервером (див. лог)"));
            return 0;
        }
        boolean ok = execute(argLine, msg ->
                src.getServer().execute(() -> src.sendSystemMessage(Component.literal(msg))));
        if (!ok) {
            src.sendFailure(Component.literal("spark помилка (див. лог)"));
            return 0;
        }
        return 1;
    }

    /** Drives the standalone agent's {@code execute(String[], sender)}, routing each (de-ANSI'd)
     *  output line to {@code onLine}. The sender/output proxy outlives this call, so spark's async
     *  upload can still deliver the result URL line to {@code onLine} later. */
    private static boolean execute(String argLine, java.util.function.Consumer<String> onLine) {
        if (!ensureLoaded()) return false;
        try {
            String[] args = argLine.isBlank() ? new String[0] : argLine.trim().split("\\s+");
            Object output = Proxy.newProxyInstance(
                    outputInterface.getClassLoader(), new Class<?>[]{outputInterface},
                    (proxy, method, margs) -> switch (method.getName()) {
                        case "sendMessage" -> {
                            onLine.accept(ANSI.matcher(String.valueOf(margs[0])).replaceAll(""));
                            yield null;
                        }
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == margs[0];
                        case "toString" -> "NestworldSparkOutput";
                        default -> null;
                    });
            executeMethod.invoke(plugin, args, senderCtor.newInstance(output));
            return true;
        } catch (Throwable t) {
            LOGGER.error("spark execute '{}' failed", argLine, t);
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // Auto-profiler — drive whatever /spark is present (mod or our agent command) via the server
    // command dispatcher; capture the upload URL from the log. The spark mod already runs a
    // background profiler at boot; we never stop it, only `open`, and write the link on shutdown.
    // -----------------------------------------------------------------------

    private static volatile boolean captureInstalled = false;
    // For the "can't be stopped" guard: re-assert the profiler if it is cancelled or reverts to the
    // background one, unless the server is actually shutting down.
    private static volatile net.minecraft.server.MinecraftServer serverRef;
    private static volatile boolean shuttingDown = false;

    private static boolean sparkPresent(net.minecraft.server.MinecraftServer server) {
        try {
            return server.getCommands().getDispatcher().getRoot().getChild("spark") != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Add a log4j appender that records every spark viewer URL the server logs (background profiler,
     *  manual `/spark`, or our shutdown open) into {@link #lastUrl}. Installed once. */
    private static synchronized void installCapture() {
        if (captureInstalled) return;
        try {
            org.apache.logging.log4j.core.LoggerContext ctx =
                    (org.apache.logging.log4j.core.LoggerContext) LogManager.getContext(false);
            org.apache.logging.log4j.core.appender.AbstractAppender appender =
                    new org.apache.logging.log4j.core.appender.AbstractAppender(
                            "NestworldSparkUrl", null, null, true,
                            org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY) {
                        @Override public void append(org.apache.logging.log4j.core.LogEvent e) {
                            try {
                                String text = e.getMessage().getFormattedMessage();
                                java.util.regex.Matcher m = SPARK_URL.matcher(text);
                                if (m.find()) lastUrl = m.group();
                                // "Can't be stopped" guard: a manual `cancel` kills the profiler with no
                                // restart, and a manual `stop` makes spark revert to its (non --thread *)
                                // background profiler. In both cases re-assert our all-threads profiler —
                                // unless the server is genuinely shutting down. Our own re-start logs
                                // neither phrase, so this cannot loop. Dispatched on the main thread
                                // (never from this log-append thread).
                                net.minecraft.server.MinecraftServer s = serverRef;
                                if (!shuttingDown && s != null) {
                                    String low = text.toLowerCase();
                                    if (low.contains("profiler has been cancelled")
                                            || low.contains("restarted the background profiler")) {
                                        s.execute(() -> {
                                            if (!shuttingDown) dispatch(s, "spark profiler start --thread *");
                                        });
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                    };
            appender.start();
            ctx.getConfiguration().getRootLogger().addAppender(appender, null, null);
            ctx.updateLoggers();
            captureInstalled = true;
        } catch (Throwable t) {
            LOGGER.warn("auto-spark: could not install URL log capture (will rely on shutdown open output)", t);
        }
    }

    private static void dispatch(net.minecraft.server.MinecraftServer server, String cmd) {
        try {
            server.getCommands().performPrefixedCommand(
                    server.createCommandSourceStack().withSuppressedOutput(), cmd);
        } catch (Throwable t) {
            LOGGER.warn("auto-spark: command '{}' failed", cmd, t);
        }
    }

    /** At boot: ensure an all-threads profiler is running (the spark mod's background profiler usually
     *  already is; a duplicate `start` is harmless) and start capturing URLs. Never stops anything. */
    public static void autoStart(net.minecraft.server.MinecraftServer server) {
        if (!NestworldTuning.AUTO_SPARK) return;
        if (!sparkPresent(server)) {
            LOGGER.info("auto-spark: /spark not present — disabled");
            return;
        }
        serverRef = server;
        shuttingDown = false;
        installCapture();
        dispatch(server, "spark profiler start --thread *");
        LOGGER.info("auto-spark: profiler running (all threads); re-asserted if cancelled; link -> spark-history.txt on stop");
    }

    /** On server stop/restart: open the running profile and append "{date}  {url}" to
     *  spark-history.txt. Does NOT stop the profiler — only opens it. */
    public static void writeHistoryOnShutdown(net.minecraft.server.MinecraftServer server) {
        if (!NestworldTuning.AUTO_SPARK || !sparkPresent(server)) return;
        shuttingDown = true; // stop the re-assert guard from fighting the shutdown
        try {
            installCapture();
            String before = lastUrl;
            dispatch(server, "spark profiler open");
            long deadline = System.currentTimeMillis() + 12_000L;
            while ((lastUrl == null || java.util.Objects.equals(lastUrl, before))
                    && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(150L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
            if (lastUrl == null) {
                LOGGER.warn("auto-spark: no profile link captured on shutdown");
                return;
            }
            String stamp = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            Path file = Path.of("spark-history.txt");
            Files.writeString(file, stamp + "  " + lastUrl + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            LOGGER.info("auto-spark: profile link written to {} -> {}", file.toAbsolutePath(), lastUrl);
        } catch (Throwable t) {
            LOGGER.error("auto-spark: failed to write spark-history.txt", t);
        }
    }

    private static synchronized boolean ensureLoaded() {
        if (plugin != null) return true;
        Path jar = findAgentJar();
        if (jar == null) {
            LOGGER.warn("{} not found (looked in CWD, repo root, -Dnestworld.spark.jar)", JAR_NAME);
            return false;
        }
        try {
            // Isolated loader: the agent jar is fully shaded and touches no MC
            // classes, so nothing can clash with the TRANSFORMER classloader.
            ClassLoader cl = new URLClassLoader(
                    new URL[]{jar.toUri().toURL()}, SparkBridge.class.getClassLoader());
            Class<?> pluginClass = Class.forName("me.lucko.spark.standalone.StandaloneSparkPlugin", true, cl);
            Class<?> senderClass = Class.forName("me.lucko.spark.standalone.StandaloneCommandSender", true, cl);
            outputInterface = Class.forName("me.lucko.spark.standalone.StandaloneCommandSender$Output", true, cl);

            // Instrumentation is only used by spark's optional class finder;
            // the profiler itself (async-profiler) does not need it.
            plugin = pluginClass
                    .getConstructor(java.lang.instrument.Instrumentation.class, Map.class)
                    .newInstance(null, Map.of());
            executeMethod = pluginClass.getMethod("execute", String[].class, senderClass);
            senderCtor = senderClass.getConstructor(outputInterface);
            LOGGER.info("spark standalone bridge initialised from {}", jar.toAbsolutePath());
            return true;
        } catch (Throwable t) {
            LOGGER.error("spark bridge init failed", t);
            plugin = null;
            return false;
        }
    }

    private static Path findAgentJar() {
        String prop = System.getProperty("nestworld.spark.jar");
        Path[] candidates = {
                prop != null ? Path.of(prop) : null,
                Path.of(JAR_NAME),                      // server CWD
                Path.of("../../../" + JAR_NAME),        // repo root from projects/forge/run
        };
        for (Path p : candidates) {
            if (p != null && Files.isRegularFile(p)) return p;
        }
        return null;
    }
}
