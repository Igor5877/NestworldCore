package net.nestworld.compat.ae2.glue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.moddiscovery.ModFileInfo;
import net.minecraftforge.forgespi.language.IModInfo;
import net.nestworld.compat.ae2.registry.Ae2CompatibilityRegistry;

/**
 * WP1 {@code /nwcompatae2 status}: addon and AE2 build, activation mode, gate summary, every loaded AE2-ecosystem mod
 * (a mod that depends on {@code ae2}, or one the registry knows) with its registry descriptor — or UNKNOWN — and the
 * key runtime counters. Read-only; hashes the jars on demand (once per call).
 */
public final class CompatStatus {
    private CompatStatus() {
    }

    public static String render() {
        StringBuilder sb = new StringBuilder();
        String self = version("nestworld_compat_ae2");
        sb.append("nestworld-compat-ae2 ").append(self).append(" | active=").append(Ae2CompatMixinPlugin.isActive())
                .append(" mode=").append(Ae2Compat.KERNEL.mode()).append(" manifest=")
                .append(Ae2Compat.manifestEligible ? "ELIGIBLE" : "NOT ELIGIBLE (" + Ae2Compat.manifestProblems.size() + ")")
                .append(" unauditedPaths=").append(Ae2Compat.KERNEL.metrics.unauditedPaths.sum()).append('\n');
        List<String> supported = new ArrayList<>(), unknown = new ArrayList<>();
        for (ModFileInfo mf : FMLLoader.getLoadingModList().getModFiles()) {
            if (mf.getMods().isEmpty()) continue;
            IModInfo mi = mf.getMods().get(0);
            String mod = mi.getModId(), ver = mi.getVersion().toString();
            boolean ecosystem = mod.equals("ae2") || mi.getDependencies().stream().anyMatch(d -> d.getModId().equals("ae2"));
            boolean known = Ae2CompatibilityRegistry.descriptors().stream().anyMatch(d -> d.modId().equals(mod));
            if (!ecosystem && !known) continue;
            String sha = sha256(mf.getFile().getFilePath());
            var d = Ae2CompatibilityRegistry.find(mod, ver, sha);
            String shortSha = sha.length() >= 8 ? sha.substring(0, 8) : sha;
            if (d == null) {
                unknown.add(mod + " " + ver + " (" + shortSha + ") → UNKNOWN / fallback KEEP_PINS");
            } else {
                supported.add(mod + " " + ver + " (" + shortSha + ") → " + d.supportStatus() + " tier=" + d.tier()
                        + " machine=" + d.machinePolicy() + " storage=" + d.storagePolicy() + " callback=" + d.callbackPolicy());
            }
        }
        sb.append("registered builds loaded (").append(supported.size()).append("):");
        for (String s : supported) sb.append("\n  ").append(s);
        sb.append("\nunregistered AE2-ecosystem mods (").append(unknown.size()).append("):");
        for (String s : unknown) sb.append("\n  ").append(s);
        var k = Ae2Compat.COUNTERS;
        sb.append("\ncallbacks: topo recorded=").append(k.cbRecorded.sum()).append(" stale=").append(k.cbStale.sum())
                .append(" dropped=").append(k.cbDropped.sum()).append(" | state emitted=").append(k.stEmitted.sum())
                .append(" | block emitted=").append(k.bkEmitted.sum()).append(" | cluster emitted=").append(k.clEmitted.sum())
                .append(" stale=").append(k.clStale.sum());
        sb.append("\n").append(ClusterTeardownCoordinator.summary());
        sb.append("\n").append(NeighborAffinity.summary());
        sb.append("\n").append(LifeAdmission.summary());
        return sb.toString();
    }

    private static String version(String modId) {
        for (ModFileInfo mf : FMLLoader.getLoadingModList().getModFiles()) {
            for (IModInfo mi : mf.getMods()) if (mi.getModId().equals(modId)) return mi.getVersion().toString();
        }
        return "?";
    }

    private static String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            return "unreadable";
        }
    }
}
