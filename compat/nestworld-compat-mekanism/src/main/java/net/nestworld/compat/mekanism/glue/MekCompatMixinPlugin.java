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
        MekGate.Result r;
        try {
            r = MekGate.evaluate();
        } catch (Throwable t) {
            MekCompat.mode = "INELIGIBLE";
            MekCompat.problems = List.of("gate failed: " + t);
            MekCompat.LOG.error("[nestworld-compat-mekanism] compatibility gate failed; M-A OFF, Mekanism runs unmodified "
                    + "(DEGRADED: MEKANISM-REGISTRY-RACE-001 NOT mitigated)", t);
            return;
        }
        MekCompat.gateMillis = r.millis();
        MekCompat.problems = r.problems();
        active = r.eligible();
        if (active) {
            MekCompat.mode = "ACTIVE";
            MekCompat.LOG.info("[nestworld-compat-mekanism] compatibility gate ELIGIBLE ({} ms): M-A active for Mekanism {}",
                    r.millis(), MekGate.VERSION);
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
