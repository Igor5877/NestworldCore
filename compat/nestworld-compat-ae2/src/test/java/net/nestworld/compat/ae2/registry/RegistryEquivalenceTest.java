package net.nestworld.compat.ae2.registry;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

/** WP1 acceptance: the registry views equal, entry for entry, the gate tables of the pre-registry addon (snapshot of
 * commit 5b3127181). Equal tables + unchanged evaluate() logic ⇒ identical decisions for every input. */
class RegistryEquivalenceTest {
    static final Map<String, List<List<String>>> LEGACY_ALLOWLIST = new LinkedHashMap<>();
    static final List<List<Object>> LEGACY_RELEASES = new ArrayList<>();
    static final Set<String> LEGACY_PARTICIPANTS = new TreeSet<>();
    static {
        LEGACY_ALLOWLIST.put("appeng/blockentity/grid/AENetworkPowerBlockEntity", List.of(List.of("ae2", "15.4.10", "fbfee05c6674cb6b00fe425e945ca4818d41eb34069b60dcb4a36221a0390fcc")));
        LEGACY_ALLOWLIST.put("appeng/blockentity/misc/ChargerBlockEntity", List.of(List.of("ae2", "15.4.10", "fbfee05c6674cb6b00fe425e945ca4818d41eb34069b60dcb4a36221a0390fcc")));
        LEGACY_ALLOWLIST.put("appeng/blockentity/misc/InscriberBlockEntity", List.of(List.of("ae2", "15.4.10", "fbfee05c6674cb6b00fe425e945ca4818d41eb34069b60dcb4a36221a0390fcc")));
        LEGACY_ALLOWLIST.put("appeng/blockentity/networking/ControllerBlockEntity", List.of(List.of("ae2", "15.4.10", "fbfee05c6674cb6b00fe425e945ca4818d41eb34069b60dcb4a36221a0390fcc")));
        LEGACY_ALLOWLIST.put("appeng/blockentity/networking/CreativeEnergyCellBlockEntity", List.of(List.of("ae2", "15.4.10", "fbfee05c6674cb6b00fe425e945ca4818d41eb34069b60dcb4a36221a0390fcc")));
        LEGACY_ALLOWLIST.put("appeng/blockentity/networking/EnergyAcceptorBlockEntity", List.of(List.of("ae2", "15.4.10", "fbfee05c6674cb6b00fe425e945ca4818d41eb34069b60dcb4a36221a0390fcc")));
        LEGACY_ALLOWLIST.put("appeng/blockentity/networking/EnergyCellBlockEntity", List.of(List.of("ae2", "15.4.10", "fbfee05c6674cb6b00fe425e945ca4818d41eb34069b60dcb4a36221a0390fcc")));
        LEGACY_ALLOWLIST.put("appeng/blockentity/powersink/AEBasePoweredBlockEntity", List.of(List.of("ae2", "15.4.10", "fbfee05c6674cb6b00fe425e945ca4818d41eb34069b60dcb4a36221a0390fcc")));
        LEGACY_ALLOWLIST.put("appeng/blockentity/powersink/IExternalPowerSink", List.of(List.of("ae2", "15.4.10", "fbfee05c6674cb6b00fe425e945ca4818d41eb34069b60dcb4a36221a0390fcc")));
        LEGACY_ALLOWLIST.put("appeng/blockentity/storage/ChestBlockEntity", List.of(List.of("ae2", "15.4.10", "fbfee05c6674cb6b00fe425e945ca4818d41eb34069b60dcb4a36221a0390fcc")));
        LEGACY_ALLOWLIST.put("appeng/me/energy/GridEnergyStorage", List.of(List.of("ae2", "15.4.10", "fbfee05c6674cb6b00fe425e945ca4818d41eb34069b60dcb4a36221a0390fcc")));
        LEGACY_ALLOWLIST.put("appeng/parts/networking/EnergyAcceptorPart", List.of(List.of("ae2", "15.4.10", "fbfee05c6674cb6b00fe425e945ca4818d41eb34069b60dcb4a36221a0390fcc")));
        LEGACY_ALLOWLIST.put("com/glodblock/github/extendedae/common/tileentities/TileCaner", List.of(List.of("expatternprovider", "1.20-1.4.15-forge", "999281e5e12efc7b2b947a05c698a35b315502a07a99be9e6ad3f9499821b29f"), List.of("expatternprovider", "1.20-1.4.21-forge", "30fc8abe66d4b96919f0fd7b33badd0bb2013b31e2ee57f6aeaa63ffae37df4b")));
        LEGACY_ALLOWLIST.put("com/glodblock/github/extendedae/common/tileentities/TileCircuitCutter", List.of(List.of("expatternprovider", "1.20-1.4.15-forge", "999281e5e12efc7b2b947a05c698a35b315502a07a99be9e6ad3f9499821b29f"), List.of("expatternprovider", "1.20-1.4.21-forge", "30fc8abe66d4b96919f0fd7b33badd0bb2013b31e2ee57f6aeaa63ffae37df4b")));
        LEGACY_ALLOWLIST.put("com/glodblock/github/extendedae/common/tileentities/TileExCharger", List.of(List.of("expatternprovider", "1.20-1.4.15-forge", "999281e5e12efc7b2b947a05c698a35b315502a07a99be9e6ad3f9499821b29f"), List.of("expatternprovider", "1.20-1.4.21-forge", "30fc8abe66d4b96919f0fd7b33badd0bb2013b31e2ee57f6aeaa63ffae37df4b")));
        LEGACY_ALLOWLIST.put("com/glodblock/github/extendedae/common/tileentities/TileExInscriber", List.of(List.of("expatternprovider", "1.20-1.4.15-forge", "999281e5e12efc7b2b947a05c698a35b315502a07a99be9e6ad3f9499821b29f"), List.of("expatternprovider", "1.20-1.4.21-forge", "30fc8abe66d4b96919f0fd7b33badd0bb2013b31e2ee57f6aeaa63ffae37df4b")));
        LEGACY_ALLOWLIST.put("net/pedroksl/advanced_ae/common/entities/QuantumCrafterEntity", List.of(List.of("advanced_ae", "1.3.5-1.20.1", "a580ee70f20c6465f3d7d68fa99fba91f6885efe1dc9df70b5af951ed64c211c"), List.of("advanced_ae", "1.3.6-1.20.1", "e216f95ac73eb4c1e8f3db22cc3d30f37f2612a7cb1762b9ebce148d0d5657b2")));
        LEGACY_ALLOWLIST.put("net/pedroksl/advanced_ae/common/entities/ReactionChamberEntity", List.of(List.of("advanced_ae", "1.3.5-1.20.1", "a580ee70f20c6465f3d7d68fa99fba91f6885efe1dc9df70b5af951ed64c211c"), List.of("advanced_ae", "1.3.6-1.20.1", "e216f95ac73eb4c1e8f3db22cc3d30f37f2612a7cb1762b9ebce148d0d5657b2")));
        LEGACY_ALLOWLIST.put("org/zeith/solarflux/mixins/compat/ae2/SolarPanelTileMixin", List.of(List.of("solarflux", "20.1.11", "b8460262e2e657af8ce5ea4dd85fae5e611c7685e363d58c0f4389765ff1d463")));
        LEGACY_ALLOWLIST.put("org/zeith/solarflux/block/SolarPanelTile", List.of(List.of("solarflux", "20.1.11", "b8460262e2e657af8ce5ea4dd85fae5e611c7685e363d58c0f4389765ff1d463")));
        LEGACY_ALLOWLIST.put("net/oktawia/crazyae2addons/entities/EnergyStorage1kBE", List.of(List.of("crazyae2addons", "2.6.2", "6a0e023beefd183fc5995b3e65b1a3392b846d8a7ff3f56c17c9092201025e22")));
        LEGACY_ALLOWLIST.put("net/oktawia/crazyae2addons/entities/EnergyStorage4kBE", List.of(List.of("crazyae2addons", "2.6.2", "6a0e023beefd183fc5995b3e65b1a3392b846d8a7ff3f56c17c9092201025e22")));
        LEGACY_ALLOWLIST.put("net/oktawia/crazyae2addons/entities/EnergyStorage16kBE", List.of(List.of("crazyae2addons", "2.6.2", "6a0e023beefd183fc5995b3e65b1a3392b846d8a7ff3f56c17c9092201025e22")));
        LEGACY_ALLOWLIST.put("net/oktawia/crazyae2addons/entities/EnergyStorage64kBE", List.of(List.of("crazyae2addons", "2.6.2", "6a0e023beefd183fc5995b3e65b1a3392b846d8a7ff3f56c17c9092201025e22")));
        LEGACY_ALLOWLIST.put("net/oktawia/crazyae2addons/entities/EnergyStorage256kBE", List.of(List.of("crazyae2addons", "2.6.2", "6a0e023beefd183fc5995b3e65b1a3392b846d8a7ff3f56c17c9092201025e22")));
        LEGACY_ALLOWLIST.put("net/oktawia/crazyae2addons/entities/EnergyStorage1mBE", List.of(List.of("crazyae2addons", "2.6.2", "6a0e023beefd183fc5995b3e65b1a3392b846d8a7ff3f56c17c9092201025e22")));
        LEGACY_ALLOWLIST.put("net/oktawia/crazyae2addons/entities/EnergyStorage4mBE", List.of(List.of("crazyae2addons", "2.6.2", "6a0e023beefd183fc5995b3e65b1a3392b846d8a7ff3f56c17c9092201025e22")));
        LEGACY_ALLOWLIST.put("net/oktawia/crazyae2addons/entities/EnergyStorage16mBE", List.of(List.of("crazyae2addons", "2.6.2", "6a0e023beefd183fc5995b3e65b1a3392b846d8a7ff3f56c17c9092201025e22")));
        LEGACY_ALLOWLIST.put("net/oktawia/crazyae2addons/entities/EnergyStorage64mBE", List.of(List.of("crazyae2addons", "2.6.2", "6a0e023beefd183fc5995b3e65b1a3392b846d8a7ff3f56c17c9092201025e22")));
        LEGACY_ALLOWLIST.put("net/oktawia/crazyae2addons/entities/EnergyStorage256mBE", List.of(List.of("crazyae2addons", "2.6.2", "6a0e023beefd183fc5995b3e65b1a3392b846d8a7ff3f56c17c9092201025e22")));
        LEGACY_ALLOWLIST.put("cn/dancingsnow/neoecoae/blocks/entity/ECOIntegratedWorkingStationBlockEntity", List.of(List.of("neoecoae", "20.4.2", "e6ba909b2bf4ccce2ab055a7293d487b8df1ae8239220181e0f9d4bf0cf95571")));
        LEGACY_ALLOWLIST.put("cn/dancingsnow/neoecoae/blocks/entity/storage/ECOEnergyCellBlockEntity", List.of(List.of("neoecoae", "20.4.2", "e6ba909b2bf4ccce2ab055a7293d487b8df1ae8239220181e0f9d4bf0cf95571")));
        LEGACY_RELEASES.add(List.of("15.4.10", "fbfee05c6674cb6b00fe425e945ca4818d41eb34069b60dcb4a36221a0390fcc", Set.of()));
        LEGACY_RELEASES.add(List.of("15.4.11", "f2ff9ee5122b037d4e93537425ab9fbe49860fc9c3977db13b20a50ca7d30f9e", Set.of("appeng/client/render/overlay/OverlayManager", "appeng/core/localization/Tooltips", "appeng/core/sync/packets/FillCraftingGridFromRecipePacket", "appeng/core/sync/packets/FillCraftingGridFromRecipePacket$DisplacedItem", "appeng/me/cluster/implementations/CraftingCPUCalculator", "appeng/server/testplots/CraftingTerminalTestPlots", "appeng/server/testplots/TestPlots")));
        LEGACY_PARTICIPANTS.add("appeng.blockentity.grid.AENetworkPowerBlockEntity");
        LEGACY_PARTICIPANTS.add("appeng.blockentity.misc.ChargerBlockEntity");
        LEGACY_PARTICIPANTS.add("appeng.blockentity.misc.InscriberBlockEntity");
        LEGACY_PARTICIPANTS.add("appeng.blockentity.networking.ControllerBlockEntity");
        LEGACY_PARTICIPANTS.add("appeng.blockentity.networking.CreativeEnergyCellBlockEntity");
        LEGACY_PARTICIPANTS.add("appeng.blockentity.networking.EnergyAcceptorBlockEntity");
        LEGACY_PARTICIPANTS.add("appeng.blockentity.networking.EnergyCellBlockEntity");
        LEGACY_PARTICIPANTS.add("appeng.blockentity.storage.ChestBlockEntity");
        LEGACY_PARTICIPANTS.add("appeng.me.energy.GridEnergyStorage");
        LEGACY_PARTICIPANTS.add("appeng.parts.networking.EnergyAcceptorPart");
        LEGACY_PARTICIPANTS.add("com.glodblock.github.extendedae.common.tileentities.TileExCharger");
        LEGACY_PARTICIPANTS.add("com.glodblock.github.extendedae.common.tileentities.TileExInscriber");
        LEGACY_PARTICIPANTS.add("com.glodblock.github.extendedae.common.tileentities.TileCaner");
        LEGACY_PARTICIPANTS.add("com.glodblock.github.extendedae.common.tileentities.TileCircuitCutter");
        LEGACY_PARTICIPANTS.add("net.pedroksl.advanced_ae.common.entities.QuantumCrafterEntity");
        LEGACY_PARTICIPANTS.add("net.pedroksl.advanced_ae.common.entities.ReactionChamberEntity");
        LEGACY_PARTICIPANTS.add("net.oktawia.crazyae2addons.entities.EnergyStorage1kBE");
        LEGACY_PARTICIPANTS.add("net.oktawia.crazyae2addons.entities.EnergyStorage4kBE");
        LEGACY_PARTICIPANTS.add("net.oktawia.crazyae2addons.entities.EnergyStorage16kBE");
        LEGACY_PARTICIPANTS.add("net.oktawia.crazyae2addons.entities.EnergyStorage64kBE");
        LEGACY_PARTICIPANTS.add("net.oktawia.crazyae2addons.entities.EnergyStorage256kBE");
        LEGACY_PARTICIPANTS.add("net.oktawia.crazyae2addons.entities.EnergyStorage1mBE");
        LEGACY_PARTICIPANTS.add("net.oktawia.crazyae2addons.entities.EnergyStorage4mBE");
        LEGACY_PARTICIPANTS.add("net.oktawia.crazyae2addons.entities.EnergyStorage16mBE");
        LEGACY_PARTICIPANTS.add("net.oktawia.crazyae2addons.entities.EnergyStorage64mBE");
        LEGACY_PARTICIPANTS.add("net.oktawia.crazyae2addons.entities.EnergyStorage256mBE");
        LEGACY_PARTICIPANTS.add("cn.dancingsnow.neoecoae.blocks.entity.ECOIntegratedWorkingStationBlockEntity");
        LEGACY_PARTICIPANTS.add("cn.dancingsnow.neoecoae.blocks.entity.storage.ECOEnergyCellBlockEntity");
        LEGACY_PARTICIPANTS.add("org.zeith.solarflux.block.SolarPanelTile");
    }

    @Test
    void powerAllowlistIdentical() {
        Map<String, Set<List<String>>> legacy = new TreeMap<>(), now = new TreeMap<>();
        LEGACY_ALLOWLIST.forEach((c, os) -> legacy.put(c, new HashSet<>(os)));
        Ae2CompatibilityRegistry.powerAllowlist().forEach((c, os) -> now.put(c,
                new HashSet<>(os.stream().map(o -> List.of(o.modId(), o.version(), o.sha256())).toList())));
        assertEquals(legacy, now);
    }

    @Test
    void ae2ReleasesIdentical() {
        List<List<Object>> now = new ArrayList<>();
        for (var d : Ae2CompatibilityRegistry.ae2Releases()) now.add(List.of(d.version(), d.sha256(), d.ae2ChangedVsBase()));
        assertEquals(LEGACY_RELEASES, now);   // order matters: base first
    }

    @Test
    void runtimeParticipantsIdentical() {
        assertEquals(LEGACY_PARTICIPANTS, new TreeSet<>(Ae2CompatibilityRegistry.runtimeParticipants()));
    }

    /** The manifest view must reproduce the pre-registry loader (same rows, same order) and add no problem. */
    @Test
    void manifestIdenticalAndEveryRowRegistered() throws Exception {
        List<String> problems = new ArrayList<>();
        var rows = Ae2CompatibilityRegistry.manifestEntries(problems);
        assertEquals(List.of(), problems);
        List<String> legacy = new ArrayList<>();
        try (var in = Ae2CompatibilityRegistry.class.getResourceAsStream("/compat-manifest.tsv")) {
            for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] f = line.split("\t");
                legacy.add(f[0] + "|" + f[1] + "|" + f[2] + "|" + f[3] + "|" + f[4]);
            }
        }
        assertEquals(legacy, rows.stream().map(r -> r.iface() + "|" + r.internalClass() + "|" + r.owner().modId() + "|"
                + r.owner().version() + "|" + r.owner().sha256()).toList());
    }

    @Test
    void everyGateBuildHasADescriptorAndNoDuplicates() {
        Set<String> keys = new HashSet<>();
        for (var d : Ae2CompatibilityRegistry.descriptors()) assertTrue(keys.add(d.key()), d.key());
        LEGACY_ALLOWLIST.values().forEach(os -> os.forEach(o ->
                assertNotNull(Ae2CompatibilityRegistry.find(o.get(0), o.get(1), o.get(2)), o.toString())));
    }
}
