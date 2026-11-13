package net.nestworld.compat.ae2.glue;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** AE2-GRID-DOMAIN-001 D0: the grid-worker mixins exist only with -Dnestworld.compatAe2.gridWorker=true. */
public final class GridWorkerMixinPlugin implements IMixinConfigPlugin {
    private static final boolean ENABLED = Boolean.getBoolean("nestworld.compatAe2.gridWorker");

    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!ENABLED) return false;
        // optional foreign targets (ExtendedAE): apply only when the target class exists
        if (mixinClassName.contains(".ExtendedAe") || mixinClassName.contains(".Sophisticated") || mixinClassName.contains(".ChemicalP2P")
                || mixinClassName.contains(".Mekanism") || mixinClassName.contains(".Titanium") || mixinClassName.contains(".AppFlux")) {
            return GridWorkerMixinPlugin.class.getClassLoader().getResource(targetClassName.replace('.', '/') + ".class") != null;
        }
        return true;
    }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
