package net.nestworld.compat.ae2.glue;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * AE2-COST-001-FAST mixin config plugin: each cost optimisation exists in bytecode only with its own flag.
 * SOPH-EXTRACT-INDEX: {@code -Dnestworld.compatAe2.sophExtractIndex=on|census}.
 */
public final class CostMixinPlugin implements IMixinConfigPlugin {
    private static volatile int nesting; // 0 unknown, 1 ok, -1 wrong nesting (fail closed)
    private static volatile String nestingDetail = "not evaluated";

    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (Boolean.getBoolean("nestworld.compatAe2.disable")) return false;
        if (mixinClassName.endsWith(".ItemHandlerFacadeIndexMixin")) {   // read the flag here: never load glue/AE2 classes from the transformer
            String m = System.getProperty("nestworld.compatAe2.sophExtractIndex", "off");
            return m.equals("on") || m.equals("census");
        }
        if (mixinClassName.endsWith(".GridMachinesMemoMixin")) return Boolean.getBoolean("nestworld.compatAe2.machinesMemo");
        return false;
    }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    /**
     * Our extractExternal wrapper must be INSIDE appliedenhancements' extractFromIndexedSlots (when that mod is present),
     * so that its observed-slot / slot-index bookkeeping runs exactly as without us. Checked once, on the first call,
     * from the live stack; wrong or unknown nesting ⇒ the index stays off for the run.
     */
    public static boolean nestingOk() {
        int n = nesting;
        if (n != 0) return n > 0;
        synchronized (CostMixinPlugin.class) {
            if (nesting != 0) return nesting > 0;
            boolean appliedEnh = AppliedEnhMixinPlugin.present();
            boolean outerSeen = StackWalker.getInstance().walk(s -> s.limit(12)
                    .anyMatch(f -> f.getMethodName().endsWith("appliedenhancements$extractFromIndexedSlots")));
            boolean ok = !appliedEnh || outerSeen;
            nestingDetail = "appliedenhancements=" + appliedEnh + " outerWrapperAbove=" + outerSeen + " ⇒ " + (ok ? "OK" : "WRONG (index off)");
            nesting = ok ? 1 : -1;
            Ae2Compat.LOG.info("[nestworld-compat-ae2] SOPH-EXTRACT-INDEX nesting: {}", nestingDetail);
            return ok;
        }
    }

    public static String nestingDetail() { return nestingDetail; }
}
