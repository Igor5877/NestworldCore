package net.nestworld.compat.ae2.glue;

import appeng.api.stacks.AEItemKey;
import appeng.parts.automation.ExportBusPart;
import appeng.parts.automation.IOBusPart;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Exact audited bytecode only: these foreign strategies do no work for item-only operations.
 * This does not admit FE/soul transfers. Unknown versions and unfiltered imports stay inline.
 */
final class ItemNoopStrategies {
    private static final Map<String, String> HASHES = Map.of(
            "com.glodblock.github.appflux.common.me.strategy.FEStackExportStrategy", "c83a8ca4914f292a095f3b1b0557d5a985a07404649823261862d2e5c96ad052",
            "com.glodblock.github.appflux.common.me.strategy.FEStackImportStrategy", "5fae5a8d1489006bc3215d46b67c9afaeced330e9e4f50da0b5e4a1dd37317a6",
            "com.rerurate.ifsoulsdisks.applied.stack.SoulsStackExportStrategy", "f153907db3576eb6316ca0e3db3b7a7c12048e3ddb3fc30d629082deeb90e4b7",
            "com.rerurate.ifsoulsdisks.applied.stack.SoulsStackImportStrategy", "3195fc3a42a2ea282f5e9ca08e7017dcc72aceaabdc898bf538a6bf6baaed3b4",
            "com.buuz135.industrialforegoingsouls.soulplied_energistics.applied.strategies.SoulExportStrategy", "6aaac600e55dbf74c9447e236d5f5e0087dadb589b1c895ba5af56d6c3c620a9");
    private static final Map<Class<?>, Boolean> VERIFIED = new ConcurrentHashMap<>();

    private ItemNoopStrategies() {}

    static boolean allowed(IOBusPart bus, Object strategy) {
        String name = strategy.getClass().getName();
        if (!HASHES.containsKey(name)) return false;
        // AppliedFlux FEStackExportStrategy (audited bytecode): transfer/push return 0 for ANY key that is not a FluxKey —
        // not only items. An export bus with no configured FluxKey never reaches its FE path (MVP-001, fluids/chemicals).
        String ownKey = OWN_KEY.get(name);
        if (ownKey != null ? !noConfiguredKey(bus, ownKey) : !itemsOnly(bus)) return false;
        return VERIFIED.computeIfAbsent(strategy.getClass(), type -> {
            try (var in = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                return in != null && HASHES.get(name).equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes())));
            } catch (Exception unavailable) { return false; }
        });
    }

    /** Export strategies whose transfer/push return 0 for every key that is not their own key type (audited bytecode:
     *  `!(key instanceof K) ⇒ return 0`). An export bus with no configured K never reaches their work. */
    private static final Map<String, String> OWN_KEY = Map.of(
            "com.glodblock.github.appflux.common.me.strategy.FEStackExportStrategy", "com.glodblock.github.appflux.common.me.key.FluxKey",
            "com.rerurate.ifsoulsdisks.applied.stack.SoulsStackExportStrategy", "com.buuz135.industrialforegoingsouls.soulplied_energistics.applied.SoulKey");

    private static boolean noConfiguredKey(IOBusPart bus, String keyClass) {
        if (!(bus instanceof ExportBusPart)) return false;
        for (int slot = 0; slot < ((NwTransitBus) bus).nw$availableSlots(); slot++) {
            var key = bus.getConfig().getKey(slot);
            if (key != null && key.getClass().getName().equals(keyClass)) return false;
        }
        return true;
    }

    private static boolean itemsOnly(IOBusPart bus) {
        boolean any = false;
        if (bus instanceof ExportBusPart) {
            for (int slot = 0; slot < ((NwTransitBus) bus).nw$availableSlots(); slot++) {
                var key = bus.getConfig().getKey(slot);
                if (key == null) continue;
                if (!(key instanceof AEItemKey)) return false;
                any = true;
            }
        } else {
            // Both AE2's reference context and BusTransitContext restrict key TYPES from this filter.
            // Require a positive item filter rather than changing unfiltered/multi-resource import semantics.
            if (bus.isUpgradedWith(appeng.core.definitions.AEItems.INVERTER_CARD)) return false;
            for (var key : ((NwTransitBus) bus).nw$filter().getItems()) {
                if (!(key instanceof AEItemKey)) return false;
                any = true;
            }
        }
        return any;
    }
}
