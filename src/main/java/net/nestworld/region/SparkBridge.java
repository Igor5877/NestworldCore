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
    // Auto-profiler — start at boot (never stop, only open), write link on shutdown
    // -----------------------------------------------------------------------

    /** True if the spark standalone agent is present and loaded. */
    public static boolean isAvailable() {
        return ensureLoaded();
    }

    /** Start the always-on all-threads profiler at boot. No-op unless enabled + spark present.
     *  Never stopped — the only interaction afterwards is {@code open} (see {@link #writeHistoryOnShutdown}). */
    public static void autoStart() {
        if (!NestworldTuning.AUTO_SPARK) return;
        if (!isAvailable()) {
            LOGGER.info("auto-spark: spark agent not present — disabled (put {} next to the server to enable)", JAR_NAME);
            return;
        }
        // Capture any URL spark emits (e.g. if someone runs /spark profiler open) so we always have a
        // recent link ready even before our shutdown open.
        boolean ok = execute("profiler start --thread *", line -> {
            var m = SPARK_URL.matcher(line);
            if (m.find()) lastUrl = m.group();
        });
        LOGGER.info("auto-spark: continuous all-threads profiler {} — link will be written to spark-history.txt on stop",
                ok ? "STARTED" : "FAILED to start");
    }

    /** Runs {@code profiler open} and waits up to {@code waitMs} for spark's async upload to deliver
     *  the viewer URL. Returns the URL or null. Does NOT stop the profiler. */
    private static String openAndCapture(long waitMs) {
        final String[] got = {null};
        boolean ok = execute("profiler open", line -> {
            var m = SPARK_URL.matcher(line);
            if (m.find()) { got[0] = m.group(); lastUrl = m.group(); }
        });
        if (!ok) return null;
        long deadline = System.currentTimeMillis() + waitMs;
        while (got[0] == null && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(100L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        return got[0] != null ? got[0] : lastUrl;
    }

    /** On server stop/restart: open the profile and append "{date} {url}" to spark-history.txt. */
    public static void writeHistoryOnShutdown() {
        if (!NestworldTuning.AUTO_SPARK || plugin == null) return;
        try {
            String url = openAndCapture(10_000L);
            if (url == null) {
                LOGGER.warn("auto-spark: no profile link captured on shutdown");
                return;
            }
            String stamp = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            Path file = Path.of("spark-history.txt");
            Files.writeString(file, stamp + "  " + url + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            LOGGER.info("auto-spark: profile link written to {} -> {}", file.toAbsolutePath(), url);
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
