package dev.nestworld.g0;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

final class Probe {
    static final Logger LOG = LoggerFactory.getLogger("G0PROBE");
    static final Map<String, AtomicLong> COUNTS = new ConcurrentHashMap<>();
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    static String cl(ClassLoader l) {
        return l == null ? "null(bootstrap)" : l.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(l))
                + (l.getName() != null ? "[" + l.getName() + "]" : "");
    }

    static String kind(Thread t) {
        String n = t.getName();
        return n.equals("Server thread") ? "main" : (t.getClass().getName().contains("RegionThread") || n.contains("Region") ? "region" : "other");
    }

    /** One detailed line for the first 3 hits per (path, thread kind); counted always. */
    static void hit(String path, Object self, String event) {
        Thread t = Thread.currentThread();
        String key = path + "/" + kind(t);
        long n = COUNTS.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
        if (n > 3) return;
        Class<?> caller = WALKER.walk(s -> s.map(StackWalker.StackFrame::getDeclaringClass)
                .filter(c -> !c.getName().startsWith("dev.nestworld.g0.")).findFirst().orElse(null));
        ClassLoader tccl = t.getContextClassLoader();
        String tcclSeesListener;
        try {
            Class<?> seen = Class.forName(self.getClass().getName(), false, tccl);
            tcclSeesListener = "FOUND same=" + (seen == self.getClass());
        } catch (Throwable x) {
            tcclSeesListener = "NOT_FOUND(" + x.getClass().getSimpleName() + ")";
        }
        LOG.info("G0PROBE HIT path={} n={} event={} thread={} threadClass={} TCCL={} moduleLoader={} listenerClass={} listenerLoader={} caller={} callerLoader={} TCCLseesListener={}",
                path, n, event, t.getName(), t.getClass().getName(), cl(tccl), cl(Probe.class.getClassLoader()),
                self.getClass().getName(), cl(self.getClass().getClassLoader()),
                caller == null ? "?" : caller.getName(), caller == null ? "?" : cl(caller.getClassLoader()), tcclSeesListener);
    }
}
