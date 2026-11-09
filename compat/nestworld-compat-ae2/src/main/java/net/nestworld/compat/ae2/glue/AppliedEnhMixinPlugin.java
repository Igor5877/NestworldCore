package net.nestworld.compat.ae2.glue;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

/**
 * APPLIEDENH-PERF-001 mixin config plugin (docs/compat/APPLIEDENH-PERF-001-audit.md §4 P3, §6). Plain Java + ASM only
 * (runs inside the transformer). Applies the elision mixin only when appliedenhancements' facade mixin is present,
 * and records application; the final class shape is verified at runtime ({@link #verifyLoaded}) before any elision.
 */
public final class AppliedEnhMixinPlugin implements IMixinConfigPlugin {
    static final String APPLIEDENH_MIXIN = "com/appliedenhancements/mixin/ExternalStorageFacadeItemHandlerMixin.class";
    static final String WRAPPER_DESC_PREFIX =
            "(Lnet/minecraftforge/items/IItemHandler;IIZLcom/llamalad7/mixinextras/injector/wrapoperation/Operation;";
    static final String OUR_HANDLER = "nestworld$elideFirstRecheck";
    static final String THEIR_HANDLER_SUFFIX = "appliedenhancements$recheckExtractionType";
    /** audited instruction sequence of appliedenhancements 1.1.2 recheckExtractionType (audit §2). */
    static final String AUDITED_RECHECK = "25v7 25v0 21v1 185:net/minecraftforge/items/IItemHandler.getStackInSlot "
            + "182:appeng/api/stacks/AEItemKey.matches 154 3 172 25v4 7 189:java/lang/Object 89 3 25v0 83 89 4 21v1 "
            + "184:java/lang/Integer.valueOf 83 89 5 21v2 184:java/lang/Integer.valueOf 83 89 6 21v3 "
            + "184:java/lang/Boolean.valueOf 83 185:com/llamalad7/mixinextras/injector/wrapoperation/Operation.call "
            + "192:java/lang/Integer 182:java/lang/Integer.intValue 172";

    private static volatile boolean present;
    private static volatile boolean applied;
    private static volatile String detail = "not evaluated";

    public static boolean present() { return present; }
    public static boolean applied() { return applied; }
    public static String detail() { return detail; }

    @Override
    public void onLoad(String mixinPackage) {
        if (Boolean.getBoolean("nestworld.compatAe2.disable")) {
            detail = "disabled by -Dnestworld.compatAe2.disable=true";
            return;
        }
        try (InputStream in = MixinService.getService().getResourceAsStream(APPLIEDENH_MIXIN)) {
            present = in != null;
        } catch (Throwable t) {
            present = false;
        }
        detail = present ? "appliedenhancements present; awaiting application" : "appliedenhancements absent; mixin not applied";
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return present;
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        // Only records application. The chain shape (P3) is NOT final here: MixinExtras applies WrapOperation in a
        // late pass after postApply (pi-smoke run pos1: postApply saw the raw wrapHandlerExtract call), so P3 is
        // verified on the loaded class + live stack by AppliedEnhRecheckElision before the first elision.
        applied = true;
        detail = "applied (P3 verified at runtime on first R1)";
    }

    /** P3 on the loaded class: exactly two wrapHandlerExtract wrappers (ours + appliedenhancements' recheck), and the
     *  appliedenhancements recheck in its jar is the audited instruction sequence. */
    public static List<String> verifyLoaded(Class<?> facade) throws Exception {
        List<String> problems = new ArrayList<>();
        int ours = 0, theirs = 0, other = 0;
        String theirName = null;
        for (java.lang.reflect.Method m : facade.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length < 5 || !p[0].getName().equals("net.minecraftforge.items.IItemHandler") || p[1] != int.class
                    || p[2] != int.class || p[3] != boolean.class
                    || !p[4].getName().equals("com.llamalad7.mixinextras.injector.wrapoperation.Operation")) continue;
            if (m.getName().contains(OUR_HANDLER)) ours++;
            else if (m.getName().endsWith(THEIR_HANDLER_SUFFIX)) { theirs++; theirName = m.getName(); }
            else other++;
        }
        if (ours != 1) problems.add("our wrapper count " + ours);
        if (theirs != 1) problems.add("appliedenhancements recheck wrapper count " + theirs);
        if (other != 0) problems.add(other + " other wrapper(s) with the wrapHandlerExtract shape");
        ClassNode src = new ClassNode();
        try (InputStream in = AppliedEnhMixinPlugin.class.getClassLoader().getResourceAsStream(APPLIEDENH_MIXIN)) {
            if (in == null) { problems.add("appliedenhancements mixin class not readable"); return problems; }
            new org.objectweb.asm.ClassReader(in).accept(src, 0);
        }
        MethodNode rc = null;
        for (MethodNode m : src.methods) if (m.name.endsWith("recheckExtractionType")) rc = m;
        if (rc == null) problems.add("recheckExtractionType missing in appliedenhancements jar");
        else if (!fingerprint(rc).equals(AUDITED_RECHECK)) problems.add("recheck bytecode != audited: " + fingerprint(rc));
        return problems;
    }

    static String fingerprint(MethodNode m) {
        StringBuilder sb = new StringBuilder();
        for (AbstractInsnNode n : m.instructions) {
            int op = n.getOpcode();
            if (op < 0) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(op);
            if (n instanceof VarInsnNode v) sb.append('v').append(v.var);
            else if (n instanceof MethodInsnNode mi) sb.append(':').append(mi.owner).append('.').append(mi.name);
            else if (n instanceof TypeInsnNode ti) sb.append(':').append(ti.desc);
            else if (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH || op == Opcodes.LDC) sb.append(":?");
        }
        return sb.toString();
    }

    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}
