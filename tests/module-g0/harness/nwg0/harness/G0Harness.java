package nwg0.harness;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.nestworld.module.ModuleRegistry;
import net.nestworld.module.NestWorldModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

/** MODULE-2 G0 only: stands in for the not-yet-written loader. Loads one jar from
 *  nestworld-modules-g0/ in a child URLClassLoader (parent = SPI's loader) and hands the instance
 *  to the real ModuleRegistry, so registerListener(Object) is the real ModuleContextImpl path. */
@Mod("nwg0harness")
public final class G0Harness {
    static final Logger LOG = LoggerFactory.getLogger("G0PROBE");

    public G0Harness() {
        MinecraftForge.EVENT_BUS.addListener(G0Harness::onStarted);
    }

    static String cl(ClassLoader l) {
        return l == null ? "null(bootstrap)" : l.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(l))
                + (l.getName() != null ? "[" + l.getName() + "]" : "");
    }

    private static void onStarted(ServerStartedEvent e) {
        Thread t = Thread.currentThread();
        try {
            Path jar = FMLPaths.GAMEDIR.get().resolve("nestworld-modules-g0").resolve("g0-probe.jar");
            ClassLoader parent = NestWorldModule.class.getClassLoader();
            LOG.info("G0PROBE harness thread={} TCCL={} spiLoader={} harnessLoader={} jarExists={}",
                    t.getName(), cl(t.getContextClassLoader()), cl(parent), cl(G0Harness.class.getClassLoader()), Files.isRegularFile(jar));
            URLClassLoader loader = new URLClassLoader("g0-module", new URL[]{jar.toUri().toURL()}, parent);
            Class<?> c = Class.forName("dev.nestworld.g0.G0ProbeModule", true, loader);
            NestWorldModule m = (NestWorldModule) c.getConstructor().newInstance();
            LOG.info("G0PROBE loaded entry={} entryLoader={} module(javaModule)={}", c.getName(), cl(c.getClassLoader()), c.getModule());
            ModuleRegistry r = ModuleRegistry.forServer(e.getServer());
            r.discover(m);
            r.enableAll();
            for (String line : r.statusLines()) LOG.info("G0PROBE status {}", line);
        } catch (Throwable x) {
            LOG.error("G0PROBE harness FAILED", x);
        }
    }
}
