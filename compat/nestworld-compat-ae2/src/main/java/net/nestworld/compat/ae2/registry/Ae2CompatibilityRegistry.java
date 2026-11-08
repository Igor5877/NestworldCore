package net.nestworld.compat.ae2.registry;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * WP1 (owner ТЗ "Unified AE2 Ecosystem Compatibility Runtime", 2026-11-07): the ONE source of truth for which AE2
 * ecosystem builds the addon knows and under which policy. A descriptor per exact build (mod id, version, jar
 * sha256). The startup gate's tables are VIEWS of this registry — {@link #ae2Releases()}, {@link #powerAllowlist()},
 * {@link #runtimeParticipants()}, {@link #manifestEntries()} — so a gate decision can only change through a data change
 * here (equivalence with the pre-registry tables is pinned by {@code RegistryEquivalenceTest}).
 *
 * <p>Pure Java: no Forge / AE2 types, so it is unit-testable and usable before mixins apply. Policies other than the
 * gate data are declarative today (the runtime does not branch on them yet); unknown builds are never listed and
 * therefore fail closed (gate FAIL or manifest DEGRADED, unchanged).
 */
public final class Ae2CompatibilityRegistry {
    public enum Tier { UNKNOWN, API_ONLY, CUSTOM_MACHINES, GRID_INTERNAL, UNSUPPORTED }

    /** Where this build's AE2 machines may execute. Today every build is MAIN_ONLY (pins + grid ticks on main). */
    public enum MachinePolicy { MAIN_ONLY, HOST_DOMAIN_ALLOWLISTED }

    public enum CallbackPolicy { INHERITS_AE2, AUDITED, UNKNOWN }

    public enum StoragePolicy { MAIN_ONLY, NOT_APPLICABLE, UNKNOWN }

    public enum TopologyPolicy { KERNEL_GUARDED, INHERITS_AE2, UNKNOWN }

    public enum FallbackPolicy { KEEP_PINS, FAIL_CLOSED }

    public enum SupportStatus { SUPPORTED, ENGINEERING_VERIFIED, PRODUCTION_VERIFIED, PARTIAL, MAIN_ONLY, UNSUPPORTED, UNKNOWN }

    /** One exact build. {@code ae2ChangedVsBase}: for an AE2 release other than the audited base, the classes whose
     *  bytecode differs from the base (AE2-RELEASE-EQUIV-001); empty for the base and for non-AE2 builds. */
    public record Descriptor(String modId, String version, String sha256, Tier tier, MachinePolicy machinePolicy,
                             CallbackPolicy callbackPolicy, StoragePolicy storagePolicy, TopologyPolicy topologyPolicy,
                             FallbackPolicy fallbackPolicy, SupportStatus supportStatus, Set<String> ae2ChangedVsBase,
                             String evidence) {
        public String key() {
            return modId + "@" + version + "#" + sha256;
        }
    }

    /** Gate view: an audited owner (mod, version, sha) of a class. Same shape as the pre-registry {@code Allowed}. */
    public record Owner(String modId, String version, String sha256) {
        static Owner of(Descriptor d) {
            return new Owner(d.modId(), d.version(), d.sha256());
        }
    }

    /** Gate view of a manifest row (interface simple name + internal class → audited owner). */
    public record ManifestRow(String iface, String internalClass, Owner owner, String auditLevel) {
    }

    public static final String AE2_BASE_VERSION = "15.4.10";
    public static final String AE2_BASE_SHA256 = "fbfee05c6674cb6b00fe425e945ca4818d41eb34069b60dcb4a36221a0390fcc";

    // ------------------------------------------------------------------ descriptors

    private static final Map<String, Descriptor> BY_KEY = new LinkedHashMap<>();

    private static Descriptor d(String mod, String version, String sha, Tier tier, MachinePolicy m, CallbackPolicy c,
                                StoragePolicy s, TopologyPolicy t, SupportStatus status, Set<String> changed, String evidence) {
        Descriptor x = new Descriptor(mod, version, sha, tier, m, c, s, t, FallbackPolicy.KEEP_PINS, status, changed, evidence);
        if (BY_KEY.put(x.key(), x) != null) throw new IllegalStateException("duplicate descriptor " + x.key());
        return x;
    }

    private static Descriptor addon(String mod, String version, String sha, Tier tier, SupportStatus status, String evidence) {
        return d(mod, version, sha, tier, MachinePolicy.MAIN_ONLY, CallbackPolicy.UNKNOWN,
                tier == Tier.API_ONLY ? StoragePolicy.NOT_APPLICABLE : StoragePolicy.UNKNOWN, TopologyPolicy.INHERITS_AE2,
                status, Set.of(), evidence);
    }

    public static final Descriptor AE2 = d("ae2", AE2_BASE_VERSION, AE2_BASE_SHA256, Tier.GRID_INTERNAL,
            MachinePolicy.MAIN_ONLY, CallbackPolicy.AUDITED, StoragePolicy.MAIN_ONLY, TopologyPolicy.KERNEL_GUARDED,
            SupportStatus.ENGINEERING_VERIFIED, Set.of(), "audited base: AE2-COMPAT-DESIGN-001.*, GRID-PHASE-001, AE2-PIN-LEAK-001");
    public static final Descriptor AE2_15411 = d("ae2", "15.4.11",
            "f2ff9ee5122b037d4e93537425ab9fbe49860fc9c3977db13b20a50ca7d30f9e", Tier.GRID_INTERNAL,
            MachinePolicy.MAIN_ONLY, CallbackPolicy.AUDITED, StoragePolicy.MAIN_ONLY, TopologyPolicy.KERNEL_GUARDED,
            SupportStatus.ENGINEERING_VERIFIED, Set.of(
                    "appeng/client/render/overlay/OverlayManager",
                    "appeng/core/localization/Tooltips",
                    "appeng/core/sync/packets/FillCraftingGridFromRecipePacket",
                    "appeng/core/sync/packets/FillCraftingGridFromRecipePacket$DisplacedItem",
                    "appeng/me/cluster/implementations/CraftingCPUCalculator",
                    "appeng/server/testplots/CraftingTerminalTestPlots",
                    "appeng/server/testplots/TestPlots"),
            "equivalent release: AE2-RELEASE-EQUIV-001 (5 changed + 2 added classes, none a mixin target)");

    public static final Descriptor EXTENDEDAE_1415 = addon("expatternprovider", "1.20-1.4.15-forge",
            "999281e5e12efc7b2b947a05c698a35b315502a07a99be9e6ad3f9499821b29f", Tier.CUSTOM_MACHINES, SupportStatus.PARTIAL,
            "ALLOWLIST-001, manifest (Airis)");
    public static final Descriptor EXTENDEDAE_1421 = addon("expatternprovider", "1.20-1.4.21-forge",
            "30fc8abe66d4b96919f0fd7b33badd0bb2013b31e2ee57f6aeaa63ffae37df4b", Tier.CUSTOM_MACHINES, SupportStatus.PARTIAL,
            "ALLOWLIST-002, manifest screen");
    public static final Descriptor ADVANCEDAE_135 = addon("advanced_ae", "1.3.5-1.20.1",
            "a580ee70f20c6465f3d7d68fa99fba91f6885efe1dc9df70b5af951ed64c211c", Tier.GRID_INTERNAL, SupportStatus.PARTIAL,
            "ALLOWLIST-001");
    public static final Descriptor ADVANCEDAE_136 = addon("advanced_ae", "1.3.6-1.20.1",
            "e216f95ac73eb4c1e8f3db22cc3d30f37f2612a7cb1762b9ebce148d0d5657b2", Tier.GRID_INTERNAL, SupportStatus.PARTIAL,
            "ALLOWLIST-002, manifest screen");
    public static final Descriptor CRAZYAE2_262 = addon("crazyae2addons", "2.6.2",
            "6a0e023beefd183fc5995b3e65b1a3392b846d8a7ff3f56c17c9092201025e22", Tier.GRID_INTERNAL, SupportStatus.PARTIAL,
            "ALLOWLIST-002 (EnergyStorage*BE), manifest screen");
    public static final Descriptor NEOECOAE_2042 = addon("neoecoae", "20.4.2",
            "e6ba909b2bf4ccce2ab055a7293d487b8df1ae8239220181e0f9d4bf0cf95571", Tier.GRID_INTERNAL, SupportStatus.PARTIAL,
            "ALLOWLIST-002, manifest screen");
    public static final Descriptor SOLARFLUX_20111 = d("solarflux", "20.1.11",
            "b8460262e2e657af8ce5ea4dd85fae5e611c7685e363d58c0f4389765ff1d463", Tier.GRID_INTERNAL,
            MachinePolicy.MAIN_ONLY, CallbackPolicy.UNKNOWN, StoragePolicy.NOT_APPLICABLE, TopologyPolicy.INHERITS_AE2,
            SupportStatus.PARTIAL, Set.of(), "ALLOWLIST-001 / DESIGN-001.1 (M1 path); SOLARFLUX-COMPAT-001 OPEN");

    static {
        addon("megacells", "2.4.6-1.20.1", "19f59cc793950a3fa80dc1b29ef3bdad587344cd5efe25c5e010f4db1ea4a79c",
                Tier.API_ONLY, SupportStatus.UNKNOWN, "manifest (Airis)");
        addon("ae2_ftbquest_detector", "1.1-SNAPSHOT", "513d5be943e94c4edd201fe0c04e4b576b16f6177a41b65406e5006ee40f8c3f",
                Tier.API_ONLY, SupportStatus.UNKNOWN, "manifest (Airis)");
        addon("aeinfinity", "0.7.0", "dfd0dbfd615113e8f325d67ef652778e40b474171098eb024d35cfee5351e3cb",
                Tier.CUSTOM_MACHINES, SupportStatus.UNKNOWN, "manifest (Airis)");
        addon("unae2addon", "1.0.0", "e3ebb9b6ca49fbdd7071302f50696e99d6c54e52a3b67f92f870de1b16441a41",
                Tier.CUSTOM_MACHINES, SupportStatus.UNKNOWN, "manifest (Airis)");
        addon("extendedae_plus", "1.5.4.1", "9e7b1cf9eef9ca97c0c6c70aa456bd172cd18f2093e3149e2617e1f80ab05909",
                Tier.CUSTOM_MACHINES, SupportStatus.UNKNOWN, "manifest (Airis)");
        addon("extendedae_plus", "1.6.3", "d8634db4ca56fe725799084b3c2c67b17874db50377ea497670379221d374764",
                Tier.CUSTOM_MACHINES, SupportStatus.UNKNOWN, "manifest screen (ALLOWLIST-002)");
        addon("appflux", "1.20-1.3.7-forge", "7673b0e44ee5f247fe224c09b8178f500a01a96e7d07baa77a4a5a110462eaea",
                Tier.GRID_INTERNAL, SupportStatus.UNKNOWN, "manifest screen (ALLOWLIST-002)");
        addon("appliede", "0.14.3-fix", "36d6b413513b49c579ce31bb89b7515a2190ffa0902327e0915f326139ec1a2d",
                Tier.GRID_INTERNAL, SupportStatus.UNKNOWN, "manifest screen (ALLOWLIST-002)");
        addon("mae2", "2.1.0", "b931dcaa01b9703320d1461cebb2a9dea5dbfc2105f7e202004616232528b8d4",
                Tier.GRID_INTERNAL, SupportStatus.UNKNOWN, "manifest screen (ALLOWLIST-002); P2P-001 OPEN");
        addon("molecularmanipulator", "2.0.9-forge", "b3513a2a042c61d33e267bd35329acdcbad01653457d6f16be5e0a02a1ee3a73",
                Tier.GRID_INTERNAL, SupportStatus.UNKNOWN, "manifest screen (ALLOWLIST-002)");
        addon("bmaddon", "1.0.4", "d84ad182155ed4da81056ffd1e8ae81b7de66641d1cc9a9c641c0f9bac0d7ab0",
                Tier.CUSTOM_MACHINES, SupportStatus.UNKNOWN, "manifest screen depth 2 (ALLOWLIST-002)");
        addon("packagedauto", "3.4.15.46", "5889725f84797e88a66864fff5e20c8dec8a3f1bee6872ae83fd544e607f03fb",
                Tier.CUSTOM_MACHINES, SupportStatus.UNKNOWN, "manifest (Airis build)");
        addon("packagedauto", "3.4.15.46", "e312eb48793643b09421d1e97227e10b8323c5620b287daf0114d2b9906b1643",
                Tier.CUSTOM_MACHINES, SupportStatus.UNKNOWN, "manifest screen (owner pack build, ALLOWLIST-002)");
        addon("aeallpattern", "0.2.6-beta.1", "250fb541f635e242d234d8a5ffa433c24b50e3467306d619b2976368df38e873",
                Tier.API_ONLY, SupportStatus.UNKNOWN, "manifest screen (ALLOWLIST-002)");
    }

    // ------------------------------------------------------------------ gate data

    /** {@code IAEPowerStorage} closure participants (internal class name → audited builds); ALLOWLIST-001 / -002. */
    private static final Map<String, List<Descriptor>> POWER = new LinkedHashMap<>();
    /** Runtime barrier: concrete runtime classes (binary names) whose power path is audited. */
    private static final Set<String> RUNTIME = new TreeSet<>();

    private static void power(String internalClass, Descriptor... builds) {
        if (POWER.put(internalClass, List.of(builds)) != null) throw new IllegalStateException("duplicate " + internalClass);
    }

    static {
        for (String c : new String[]{"appeng/blockentity/grid/AENetworkPowerBlockEntity", "appeng/blockentity/misc/ChargerBlockEntity",
                "appeng/blockentity/misc/InscriberBlockEntity", "appeng/blockentity/networking/ControllerBlockEntity",
                "appeng/blockentity/networking/CreativeEnergyCellBlockEntity", "appeng/blockentity/networking/EnergyAcceptorBlockEntity",
                "appeng/blockentity/networking/EnergyCellBlockEntity", "appeng/blockentity/powersink/AEBasePoweredBlockEntity",
                "appeng/blockentity/powersink/IExternalPowerSink", "appeng/blockentity/storage/ChestBlockEntity",
                "appeng/me/energy/GridEnergyStorage", "appeng/parts/networking/EnergyAcceptorPart"}) {
            power(c, AE2);
        }
        for (String c : new String[]{"TileCaner", "TileCircuitCutter", "TileExCharger", "TileExInscriber"}) {
            power("com/glodblock/github/extendedae/common/tileentities/" + c, EXTENDEDAE_1415, EXTENDEDAE_1421);
        }
        for (String c : new String[]{"QuantumCrafterEntity", "ReactionChamberEntity"}) {
            power("net/pedroksl/advanced_ae/common/entities/" + c, ADVANCEDAE_135, ADVANCEDAE_136);
        }
        power("org/zeith/solarflux/mixins/compat/ae2/SolarPanelTileMixin", SOLARFLUX_20111);
        power("org/zeith/solarflux/block/SolarPanelTile", SOLARFLUX_20111);
        for (String s : new String[]{"1k", "4k", "16k", "64k", "256k", "1m", "4m", "16m", "64m", "256m"}) {
            power("net/oktawia/crazyae2addons/entities/EnergyStorage" + s + "BE", CRAZYAE2_262);
        }
        power("cn/dancingsnow/neoecoae/blocks/entity/ECOIntegratedWorkingStationBlockEntity", NEOECOAE_2042);
        power("cn/dancingsnow/neoecoae/blocks/entity/storage/ECOEnergyCellBlockEntity", NEOECOAE_2042);

        // runtime barrier = the concrete participants (a mixin's runtime class is its target; the abstract
        // AEBasePoweredBlockEntity and the IExternalPowerSink interface are closure members, not runtime classes)
        for (String c : POWER.keySet()) {
            if (c.endsWith("/AEBasePoweredBlockEntity") || c.endsWith("/IExternalPowerSink")
                    || c.endsWith("/SolarPanelTileMixin")) continue;
            RUNTIME.add(c.replace('/', '.'));
        }
    }

    private Ae2CompatibilityRegistry() {
    }

    // ------------------------------------------------------------------ views

    public static List<Descriptor> descriptors() {
        return List.copyOf(BY_KEY.values());
    }

    public static Descriptor find(String modId, String version, String sha256) {
        return BY_KEY.get(modId + "@" + version + "#" + sha256);
    }

    /** Admitted AE2 releases (base first). */
    public static List<Descriptor> ae2Releases() {
        List<Descriptor> l = new ArrayList<>();
        for (Descriptor x : BY_KEY.values()) if (x.modId().equals("ae2")) l.add(x);
        return Collections.unmodifiableList(l);
    }

    /** Gate view: {@code IAEPowerStorage} closure class (internal name) → audited owners. */
    public static Map<String, List<Owner>> powerAllowlist() {
        Map<String, List<Owner>> m = new LinkedHashMap<>();
        POWER.forEach((c, ds) -> m.put(c, ds.stream().map(Owner::of).toList()));
        return Collections.unmodifiableMap(m);
    }

    /** Runtime barrier view: concrete runtime class binary names. */
    public static Set<String> runtimeParticipants() {
        return Collections.unmodifiableSet(RUNTIME);
    }

    /** Manifest view (compat-manifest.tsv, contract AE2-COMPAT-MANIFEST-1); every row must name a registered build. */
    public static List<ManifestRow> manifestEntries(List<String> problems) {
        List<ManifestRow> rows = new ArrayList<>();
        try (InputStream in = Ae2CompatibilityRegistry.class.getResourceAsStream("/compat-manifest.tsv")) {
            if (in == null) {
                problems.add("COMPAT_MANIFEST_MISSING");
                return rows;
            }
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] f = line.split("\t");
                Owner o = new Owner(f[2], f[3], f[4]);
                if (find(o.modId(), o.version(), o.sha256()) == null) {
                    problems.add("COMPAT_MANIFEST_UNREGISTERED_BUILD " + o.modId() + " " + o.version() + " " + o.sha256());
                }
                rows.add(new ManifestRow(f[0], f[1], o, f.length > 5 ? f[5] : ""));
            }
        } catch (IOException e) {
            problems.add("COMPAT_MANIFEST_UNREADABLE " + e);
        }
        return rows;
    }
}
