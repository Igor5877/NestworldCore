package net.nestworld.compat.mekanism.glue;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Fail-closed activation: both mixins apply after an ELIGIBLE gate, or none does. */
public final class MekCompatMixinPlugin implements IMixinConfigPlugin {
    private static volatile boolean active;

    public static boolean isActive() {
        return active;
    }

    @Override
    public void onLoad(String mixinPackage) {
        if (Boolean.getBoolean("nestworld.compatMekanism.disable")) {
            MekCompat.mode = "DISABLED";
            MekCompat.LOG.warn("[nestworld-compat-mekanism] disabled by -Dnestworld.compatMekanism.disable=true; "
                    + "Mekanism runs unmodified: MEKANISM-REGISTRY-RACE-001 NOT mitigated");
            return;
        }
        MekGateV2.Result r;
        try {
            r = MekGateV2.evaluate();
            MekCompat.gate = r;
            if (Boolean.getBoolean("nestworld.compatMekanism.gateCompare")) {
                MekGate.Result legacy = MekGate.evaluateLegacy();
                MekCompat.compare = "legacy=" + (legacy.eligible() ? "ACTIVE" : "INELIGIBLE") + "/" + legacy.millis() + "ms new="
                        + (r.eligible() ? "ACTIVE" : "INELIGIBLE") + "/" + r.totalMs() + "ms equal=" + (legacy.eligible() == r.eligible())
                        + " legacyProblems=" + legacy.problems();
            }
        } catch (Throwable t) {
            MekCompat.mode = "INELIGIBLE";
            MekCompat.problems = List.of("gate failed: " + t);
            MekCompat.LOG.error("[nestworld-compat-mekanism] compatibility gate failed; M-A OFF, Mekanism runs unmodified "
                    + "(DEGRADED: MEKANISM-REGISTRY-RACE-001 NOT mitigated)", t);
            return;
        }
        MekCompat.gateMillis = r.totalMs();
        MekCompat.problems = r.problems();
        active = r.eligible();
        if ("legacy".equals(System.getProperty("nestworld.compatMekanism.gateImpl"))) {
            // diagnostic only: decide with the pre-MEK-GATE-PERF-001 gate shipped in 0.1.0 (never weaker than that release)
            MekGate.Result legacy = MekGate.evaluateLegacy();
            MekCompat.problems = legacy.problems();
            MekCompat.compare = "decider=legacy " + MekCompat.compare;
            active = legacy.eligible();
        }
        if (active) {
            MekCompat.mode = "ACTIVE";
            MekCompat.LOG.info("[NW-MEK] Gate ACTIVE in {} ms: M-A active for Mekanism {}", r.totalMs(), MekGate.VERSION);
            MekCompat.LOG.debug("[NW-MEK] gate phases {} configs={} mixinClassesRead={}", r.timingsMs(), r.configs(), r.mixinClassesRead());
        } else {
            MekCompat.mode = "INELIGIBLE";
            MekCompat.LOG.error("[nestworld-compat-mekanism] compatibility manifest INELIGIBLE; M-A OFF, Mekanism runs unmodified "
                    + "(DEGRADED: MEKANISM-REGISTRY-RACE-001 NOT mitigated). Problems:");
            for (String p : r.problems()) MekCompat.LOG.error("[nestworld-compat-mekanism]   {}", p);
        }
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return active;
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    /**
     * Authoritative runtime cross-check: the targets of every other prepared Mixin config. A foreign config that
     * reached the Mixin runtime after the gate ran must not leave the addon ACTIVE silently: refuse startup.
     */
    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        if (!active) return;
        for (String t : otherTargets) {
            String n = t.replace('.', '/');
            if (n.equals(MekGate.CACHE) || n.equals(MekGate.REGISTRY)) {
                MekCompat.mode = "CONFLICT";
                String msg = "[nestworld-compat-mekanism] foreign Mixin config targets " + n
                        + " but the gate was ACTIVE; refusing to start with an unverified M-A (MEK-GATE fail-closed)";
                MekCompat.LOG.error(msg);
                throw new IllegalStateException(msg);
            }
        }
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
