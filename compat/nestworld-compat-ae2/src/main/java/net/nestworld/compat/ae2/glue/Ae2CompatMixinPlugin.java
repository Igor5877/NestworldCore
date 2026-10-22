package net.nestworld.compat.ae2.glue;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Fail-closed activation: every mixin of the addon applies, or none does (001.2 §1a). */
public final class Ae2CompatMixinPlugin implements IMixinConfigPlugin {
    private static volatile boolean active;

    public static boolean isActive() {
        return active;
    }

    @Override
    public void onLoad(String mixinPackage) {
        if (Boolean.getBoolean("nestworld.compatAe2.disable")) {
            Ae2Compat.LOG.warn("[nestworld-compat-ae2] disabled by -Dnestworld.compatAe2.disable=true; AE2 runs unmodified (keep the pins)");
            return;
        }
        StartupGate.Result r;
        try {
            r = StartupGate.evaluate();
        } catch (Throwable t) {
            Ae2Compat.LOG.error("[nestworld-compat-ae2] startup gate failed; addon INACTIVE, AE2 runs unmodified (keep the pins)", t);
            return;
        }
        active = r.active();
        if (active && !r.manifestProblems().isEmpty()) {
            // eligibility failure: the layer stays on (no fail-open) but runs DEGRADED and unpin is refused
            Ae2Compat.manifestEligible = false;
            Ae2Compat.manifestProblems = r.manifestProblems();
            for (String m : r.manifestProblems()) Ae2Compat.LOG.error("[nestworld-compat-ae2] {}", m);
            Ae2Compat.KERNEL.unaudited("compatibility manifest eligibility failure (" + r.manifestProblems().size()
                    + " problem(s)); parallel mode not proven: DEGRADED, keep pins");
        }
        if (active) {
            Ae2Compat.LOG.info("[nestworld-compat-ae2] startup gate PASS ({} classes scanned in {} ms); energy concurrency domain active; "
                    + "compatibility manifest {}", r.scannedClasses(), r.millis(),
                    r.manifestProblems().isEmpty() ? "ELIGIBLE" : "NOT ELIGIBLE (" + r.manifestProblems().size() + ")");
        } else {
            Ae2Compat.LOG.error("[nestworld-compat-ae2] startup gate FAIL; addon INACTIVE, AE2 runs unmodified (keep the pins). Problems:");
            for (String p : r.problems()) Ae2Compat.LOG.error("[nestworld-compat-ae2]   {}", p);
        }
    }

    /** Fixture / probe-only mixins: not applied at all unless -Dnestworld.compatAe2.fixtures=true, so a
     *  production run carries no coupling to their target classes (release hygiene, owner 2026-10-15). */
    private static final java.util.Set<String> FIXTURE_ONLY = java.util.Set.of(
            "net.nestworld.compat.ae2.mixin.ArmorStandMixin",
            "net.nestworld.compat.ae2.mixin.RedstoneP2PProbeMixin");

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (FIXTURE_ONLY.contains(mixinClassName)) return active && Boolean.getBoolean("nestworld.compatAe2.fixtures");
        return active;
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
