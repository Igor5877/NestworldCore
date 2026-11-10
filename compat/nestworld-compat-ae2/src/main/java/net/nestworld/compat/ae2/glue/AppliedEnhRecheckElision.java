package net.nestworld.compat.ae2.glue;

import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.CapabilityProvider;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;

/**
 * APPLIEDENH-PERF-001 glue (docs/compat/APPLIEDENH-PERF-001-audit.md §4). Decides, per {@code wrapHandlerExtract} call
 * inside AE2's {@code ItemHandlerFacade.extractFromHandler}, whether the appliedenhancements recheck may be skipped:
 * only for R1 (first call of the invocation), only when the plugin verified the chain shape (P3), the handler is in
 * the audited read-pure allowlist (P1) and the checked stack's capability serialization is a pure read (P2).
 * Default ON; {@code -Dnestworld.compatAe2.appliedEnhCacheOff=true} = negative control (counted, never elided).
 */
public final class AppliedEnhRecheckElision {
    public static final boolean CACHE_OFF_PROP = Boolean.getBoolean("nestworld.compatAe2.appliedEnhCacheOff");
    private static volatile boolean enabled = !CACHE_OFF_PROP;

    static final LongAdder CALLS = new LongAdder();
    static final LongAdder ELIDED = new LongAdder();          // "cache hits": R1 answered by AE2's own check C1
    static final LongAdder AFTER_MUTATION = new LongAdder();  // "invalidations": R2..Rn, previous call mutated the slot
    static final LongAdder INELIGIBLE = new LongAdder();      // R1 on a handler outside the audited allowlist
    static final LongAdder CAPS_NOT_PURE = new LongAdder();   // R1 on a stack with attached serializable caps
    static final LongAdder DISABLED = new LongAdder();        // R1 while off / not verified

    // ---- P1: audited handler allowlist (audit §5) ----
    static final String CACHED_FAILED = "net.p3pp3rf1y.sophisticatedcore.inventory.CachedFailedInsertInventoryHandler";
    static final String STORAGE_LAMBDA = "net.p3pp3rf1y.sophisticatedstorage.block.StorageBlockEntity$$Lambda";
    static final String BACKPACK_LAMBDA = "net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackBlockEntity$$Lambda";
    static final String SHA_CORE = "cae37affd5e766742a4bdbdd2c710544c791295acbc37647b35bf238a6e80c9b";      // 1.20.1-1.5.15.2427
    static final String SHA_STORAGE = "82c9bfff6445844fda50bdd8a856f5a0ba588559b819001070ae9428740bab6f";   // 1.20.1-1.5.7.2195
    static final String SHA_BACKPACKS = "5efea2e5d92acfbe3153e50806d2a89f4a3d4a99d6b463a7798c7a12c947bb81"; // 1.20.1-3.26.14.2226
    private static volatile int sophState; // 0 = not evaluated, bit1 = core+storage audited, bit2 = core+backpacks audited, -1 = none
    private static volatile String sophDetail = "not evaluated";

    /** per handler class: null = not eligible; NO_GETTER = always eligible; otherwise the supplier getter. */
    private static final MethodHandle NO_GETTER = MethodHandles.constant(Object.class, null);
    private static final ClassValue<MethodHandle> HANDLER_KIND = new ClassValue<>() {
        @Override
        protected MethodHandle computeValue(Class<?> c) {
            if (c == ItemStackHandler.class) return NO_GETTER; // Forge: validateSlotIndex + stacks.get
            if (!c.getName().equals(CACHED_FAILED)) return null;
            try {
                Field f = c.getDeclaredField("wrappedHandlerGetter");
                f.setAccessible(true);
                return MethodHandles.lookup().unreflectGetter(f).asType(MethodType.methodType(Object.class, Object.class));
            } catch (Throwable t) {
                Ae2Compat.LOG.warn("[nestworld-compat-ae2] APPLIEDENH-PERF-001: cannot read {}.wrappedHandlerGetter ({}); handler not elided", CACHED_FAILED, t.toString());
                return null;
            }
        }
    };
    private static final ClassValue<Boolean> SUPPLIER_OK = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> c) {
            String n = c.getName();
            int s = sophState();
            if (s <= 0) return false; // -1 = no audited sophisticated jar set (all bits set: must not reach the bit tests)
            if (n.startsWith(STORAGE_LAMBDA)) return (s & 1) != 0;
            if (n.startsWith(BACKPACK_LAMBDA)) return (s & 2) != 0;
            return false;
        }
    };

    // ---- P2: CapabilityProvider fields (Forge) ----
    private static final MethodHandle CP_IS_LAZY, CP_INITIALIZED, CP_CAPS;
    static final String CAPS_DETAIL;
    static {
        MethodHandle a = null, b = null, c = null;
        String d;
        try {
            a = getter("isLazy", boolean.class);
            b = getter("initialized", boolean.class);
            c = getter("capabilities", Object.class);
            d = "ok";
        } catch (Throwable t) {
            a = b = c = null;
            d = "unavailable (" + t + "); nothing elided";
        }
        CP_IS_LAZY = a; CP_INITIALIZED = b; CP_CAPS = c; CAPS_DETAIL = d;
    }

    private static MethodHandle getter(String name, Class<?> ret) throws ReflectiveOperationException {
        Field f = CapabilityProvider.class.getDeclaredField(name);
        f.setAccessible(true);
        return MethodHandles.lookup().unreflectGetter(f).asType(MethodType.methodType(ret, Object.class));
    }

    private AppliedEnhRecheckElision() { }

    /** Called by the mixin for every wrapHandlerExtract call in extractFromHandler. */
    public static boolean mayElide(boolean first, IItemHandler handler, ItemStack checkedStack) {
        CALLS.increment();
        if (!first) {
            AFTER_MUTATION.increment();
            return false;
        }
        if (!enabled || !runtimeVerified()) {
            DISABLED.increment();
            return false;
        }
        if (!handlerReadPure(handler)) {
            INELIGIBLE.increment();
            return false;
        }
        if (!capsSerializationPure(checkedStack)) {
            CAPS_NOT_PURE.increment();
            return false;
        }
        ELIDED.increment();
        return true;
    }

    // ---- P3, checked once at runtime on the first R1 (Mixin postApply runs before MixinExtras' late WrapOperation
    //      pass, so the final chain is only observable on the loaded class / live stack) ----
    private static volatile int runtimeState; // 0 = not yet, 1 = verified, -1 = failed
    private static volatile String runtimeDetail = "not yet evaluated (no R1 seen)";

    private static boolean runtimeVerified() {
        int st = runtimeState;
        if (st != 0) return st > 0;
        // frames: 0 = runtimeVerified, 1 = mayElide, then our handler and its MixinExtras sugar bridge(s) (names contain
        // the handler name; rig-pos2: "...$nestworld$elideFirstRecheck$mixinextras$bridge$23"), then the real caller
        StackWalker.StackFrame caller = StackWalker.getInstance().walk(f -> f.skip(2)
                .filter(fr -> !fr.getMethodName().contains(AppliedEnhMixinPlugin.OUR_HANDLER)).findFirst()).orElse(null);
        synchronized (AppliedEnhRecheckElision.class) {
            if (runtimeState != 0) return runtimeState > 0;
            java.util.List<String> problems = new java.util.ArrayList<>();
            if (!AppliedEnhMixinPlugin.applied()) problems.add("mixin not applied");
            if (caller == null || !caller.getMethodName().equals("extractFromHandler") || !caller.getClassName().endsWith("ExternalStorageFacade$ItemHandlerFacade"))
                problems.add("our wrapper is not called directly by extractFromHandler (not outermost): caller=" + caller);
            try {
                problems.addAll(AppliedEnhMixinPlugin.verifyLoaded(Class.forName("appeng.me.storage.ExternalStorageFacade$ItemHandlerFacade", false,
                        AppliedEnhRecheckElision.class.getClassLoader())));
            } catch (Throwable t) {
                problems.add("loaded-class check threw " + t);
            }
            runtimeDetail = problems.isEmpty() ? "verified at runtime (outermost; exactly ours + audited appliedenhancements recheck)"
                    : "NOT verified, elision off: " + String.join("; ", problems);
            runtimeState = problems.isEmpty() ? 1 : -1;
            Ae2Compat.LOG.info("[nestworld-compat-ae2] APPLIEDENH-PERF-001 {}", runtimeDetail);
            return runtimeState > 0;
        }
    }

    static boolean handlerReadPure(IItemHandler h) {
        if (h == null) return false;
        MethodHandle g = HANDLER_KIND.get(h.getClass());
        if (g == null) return false;
        if (g == NO_GETTER) return true;
        try {
            Object sup = (Object) g.invokeExact((Object) h);
            return sup != null && SUPPLIER_OK.get(sup.getClass());
        } catch (Throwable t) {
            return false;
        }
    }

    /** Forge CapabilityProvider.serializeCaps is a pure read iff (lazy and not initialized) or no dispatcher. */
    static boolean capsSerializationPure(ItemStack s) {
        if (s == null || CP_CAPS == null) return false;
        try {
            Object o = s;
            if ((boolean) CP_IS_LAZY.invokeExact(o) && !(boolean) CP_INITIALIZED.invokeExact(o)) return true;
            return (Object) CP_CAPS.invokeExact(o) == null;
        } catch (Throwable t) {
            return false;
        }
    }

    static int sophState() {
        int s = sophState;
        if (s != 0) return s;
        synchronized (AppliedEnhRecheckElision.class) {
            if (sophState != 0) return sophState;
            boolean core = jarIs("sophisticatedcore", SHA_CORE);
            boolean storage = jarIs("sophisticatedstorage", SHA_STORAGE);
            boolean backpacks = jarIs("sophisticatedbackpacks", SHA_BACKPACKS);
            int v = (core && storage ? 1 : 0) | (core && backpacks ? 2 : 0);
            sophDetail = "core=" + core + " storage=" + storage + " backpacks=" + backpacks;
            sophState = v == 0 ? -1 : v;
            Ae2Compat.LOG.info("[nestworld-compat-ae2] APPLIEDENH-PERF-001 sophisticated allowlist: {} (state {})", sophDetail, sophState);
            return sophState;
        }
    }

    private static boolean jarIs(String modId, String sha) {
        try {
            var mf = ModList.get().getModFileById(modId);
            if (mf == null) return false;
            Path p = mf.getFile().getFilePath();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(p)) {
                byte[] buf = new byte[1 << 16];
                for (int r; (r = in.read(buf)) > 0; ) md.update(buf, 0, r);
            }
            return HexFormat.of().formatHex(md.digest()).equals(sha);
        } catch (Throwable t) {
            return false;
        }
    }

    // ---- fixture trace hook (off unless a fixture installs a sink) ----
    public static volatile boolean tracing;
    private static volatile Consumer<long[]> sink;

    public static void trace(IItemHandler h, int slot, int amount, boolean simulate, boolean first, boolean elided, int result) {
        Consumer<long[]> s = sink;
        if (s != null) s.accept(new long[] {System.identityHashCode(h), slot, amount, simulate ? 1 : 0, first ? 1 : 0, elided ? 1 : 0, result});
    }

    static void setSink(Consumer<long[]> s) {
        sink = s;
        tracing = s != null;
    }

    /** the -D negative-control property is a hard off: a runtime "on" cannot override it. */
    static String setEnabled(boolean on) {
        enabled = on && !CACHE_OFF_PROP;
        return "appliedenh elision " + (enabled ? "ON" : "OFF") + (on && CACHE_OFF_PROP ? " (hard-off by -Dnestworld.compatAe2.appliedEnhCacheOff=true)" : "");
    }

    static boolean enabled() { return enabled; }

    public static String status() {
        return "APPLIEDENH-PERF-001 present=" + AppliedEnhMixinPlugin.present() + " applied=" + AppliedEnhMixinPlugin.applied()
                + " verified=" + (runtimeState > 0) + " enabled=" + enabled + " (cacheOffProp=" + CACHE_OFF_PROP + ")"
                + " | calls=" + CALLS.sum() + " elided=" + ELIDED.sum() + " afterMutation=" + AFTER_MUTATION.sum()
                + " ineligibleHandler=" + INELIGIBLE.sum() + " capsNotPure=" + CAPS_NOT_PURE.sum() + " disabled=" + DISABLED.sum()
                + " | sophisticated: " + (sophState == 0 ? "not evaluated" : sophDetail) + " | caps: " + CAPS_DETAIL
                + " | plugin: " + AppliedEnhMixinPlugin.detail() + " | runtime: " + runtimeDetail;
    }

    static void resetCounters() {
        CALLS.reset(); ELIDED.reset(); AFTER_MUTATION.reset(); INELIGIBLE.reset(); CAPS_NOT_PURE.reset(); DISABLED.reset();
    }
}
