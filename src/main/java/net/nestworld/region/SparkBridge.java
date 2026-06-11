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

    private SparkBridge() {}

    public static int run(CommandSourceStack src, String argLine) {
        if (!ensureLoaded()) {
            src.sendFailure(Component.literal(
                    "spark не ініціалізувався — поклади " + JAR_NAME + " поряд із сервером (див. лог)"));
            return 0;
        }
        try {
            String[] args = argLine.isBlank() ? new String[0] : argLine.trim().split("\\s+");
            Object output = Proxy.newProxyInstance(
                    outputInterface.getClassLoader(), new Class<?>[]{outputInterface},
                    (proxy, method, margs) -> switch (method.getName()) {
                        case "sendMessage" -> {
                            String msg = ANSI.matcher(String.valueOf(margs[0])).replaceAll("");
                            src.getServer().execute(() ->
                                    src.sendSystemMessage(Component.literal(msg)));
                            yield null;
                        }
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == margs[0];
                        case "toString" -> "NestworldSparkOutput";
                        default -> null;
                    });
            executeMethod.invoke(plugin, args, senderCtor.newInstance(output));
            return 1;
        } catch (Throwable t) {
            LOGGER.error("/spark {} failed", argLine, t);
            src.sendFailure(Component.literal("spark помилка: " + t));
            return 0;
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
