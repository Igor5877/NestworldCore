package net.nestworld.compat.ae2.glue;

import appeng.api.networking.IGridNode;
import appeng.parts.automation.IOBusPart;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * AE2-GRID-DOMAIN-MVP-001: which grid participants are served on MAIN through {@link MainService} (owner-approved
 * groups C crafting + I interfaces, plus the bus configurations the transit does not support).
 * <ul>
 * <li>{@link #HOSTS} — whole block hosts: NOT claimed (their BE tickers stay on MAIN); their AE2 device ticks run on MAIN
 *     as one synchronous call each;</li>
 * <li>{@link #PARTS} — parts: their host may be claimed, their device tick runs on MAIN;</li>
 * <li>managed buses whose config the transit refuses (crafting card / craft-only / requested jobs, fuzzy, random): the
 *     device tick runs on MAIN (AE2's own inline path, MAIN acting for the parked worker).</li>
 * </ul>
 * Crafting CPU calls into providers ({@code isBusy}, {@code pushPattern}) and crafted-item inserts into export buses
 * are serviced on MAIN for every provider (CraftingCpuMainServiceMixin, ExportBusCraftedMainServiceMixin).
 */
public final class MainServiced {
    private MainServiced() {}

    static final Set<String> HOSTS = Set.of(
            "appeng.blockentity.crafting.PatternProviderBlockEntity",
            "com.glodblock.github.extendedae.common.tileentities.TileExPatternProvider",
            "appeng.blockentity.crafting.MolecularAssemblerBlockEntity",
            "com.glodblock.github.extendedae.common.tileentities.TileCircuitCutter",
            "net.pedroksl.advanced_ae.common.entities.ReactionChamberEntity",
            "net.pedroksl.advanced_ae.common.entities.QuantumCrafterEntity",
            "com.almostreliable.merequester.requester.RequesterBlockEntity",
            "thelm.packagedauto.integration.appeng.blockentity.AEPackagingProviderBlockEntity",
            "thelm.packagedauto.integration.appeng.blockentity.AECrafterBlockEntity",
            "thelm.packagedexcrafting.integration.appeng.blockentity.AEAdvancedCrafterBlockEntity",
            "thelm.packagedexcrafting.integration.appeng.blockentity.AEEliteCrafterBlockEntity",
            "thelm.packagedexcrafting.integration.appeng.blockentity.AEEnderCrafterBlockEntity",
            "thelm.packagedexcrafting.integration.appeng.blockentity.AEFluxCrafterBlockEntity",
            "thelm.packagedavaritia.integration.appeng.blockentity.AESculkCrafterBlockEntity",
            "thelm.packageddraconic.integration.appeng.blockentity.AEMarkedInjectorBlockEntity",
            "thelm.packageddraconic.integration.appeng.blockentity.AEFusionCrafterBlockEntity",
            "thelm.packagedauto.integration.appeng.blockentity.AEUnpackagerBlockEntity",
            "thelm.packagedexcrafting.integration.appeng.blockentity.AEUltimateCrafterBlockEntity",
            "thelm.packagedavaritia.integration.appeng.blockentity.AENetherCrafterBlockEntity",
            "com.edgemq.bmaddon.blockentity.BloodAltarAssemblerBlockEntity",
            // MVP local fallback (not crafting): BE tick stays on MAIN as today, grid-side device ticks served on MAIN
            "net.lmor.botanicalextramachinery.blocks.tiles.mechanicalManaPool.BlockEntityManaPoolUltimate",
            "net.lmor.botanicalextramachinery.blocks.tiles.mechanicalManaPool.BlockEntityManaPoolAdvanced",
            "net.lmor.botanicalextramachinery.blocks.tiles.BlockEntityGreenhouse",
            "net.lmor.botanicalextramachinery.blocks.tiles.mechanicalAlfheimMarket.BlockEntityAlfheimMarketUltimate",
            "net.lmor.botanicalextramachinery.blocks.tiles.mechanicalRunicAltar.BlockEntityRunicAltarAdvanced",
            "net.lmor.botanicalextramachinery.blocks.tiles.mechanicalDaisy.BlockEntityDaisyUpgraded",
            "net.lmor.botanicalextramachinery.blocks.tiles.mechanicalIndustrialAgglomerationFactory.BlockEntityIndustrialAgglomerationFactoryUltimate",
            "appbot.block.FluixPoolBlockEntity",
            "net.oktawia.crazyae2addons.entities.EntropyCradleControllerBE",
            "appeng.blockentity.networking.WirelessAccessPointBlockEntity");
    static final Set<String> PARTS = Set.of(
            "appeng.parts.crafting.PatternProviderPart",
            "com.glodblock.github.extendedae.common.parts.PartExPatternProvider",
            // AdvancedAE overrides doBusWork (not the audited AE2 path): MVP local fallback, whole bus op on MAIN
            "net.pedroksl.advanced_ae.common.parts.ImportExportBusPart",
            "net.pedroksl.advanced_ae.common.parts.StockExportBusPart");
    // NOT here: me.ramidzkh.mekae2 ChemicalP2PTunnelPart — P2P forwarding runs synchronously on the PUSHING caller's
    // thread (ingress), not as a device tick; MAIN service of device ticks would not cover it (stays a blocker).

    static final LongAdder devices = new LongAdder(), crafting = new LongAdder(), refused = new LongAdder();
    private static final Map<String, LongAdder> BY_CLASS = new ConcurrentHashMap<>();

    public static boolean host(BlockEntity be) {
        return be != null && (HOSTS.contains(be.getClass().getName()) || interfaceWithCraftingCard(be));
    }

    /** owner 2026-11-13: interfaces run DIRECTLY on the worker under P(G) (InterfaceInvGuardMixin) — except with a
     *  crafting card (beginCraftingCalculation / submitJob paths): those stay MAIN-serviced. */
    static boolean interfaceWithCraftingCard(Object o) {
        return o instanceof appeng.helpers.InterfaceLogicHost h && h.getInterfaceLogic() != null
                && h.getInterfaceLogic().getUpgrades().isInstalled(appeng.core.definitions.AEItems.CRAFTING_CARD);
    }

    static boolean isInterface(Object o) {
        return o instanceof appeng.helpers.InterfaceLogicHost;
    }

    /** Buses that met a present non-item resource on a non-local source in model A (sticky, weak). */
    private static final java.util.Set<Object> STICKY = java.util.Collections.synchronizedSet(
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()));
    static final LongAdder stickyAdded = new LongAdder();

    static void requireMain(Object bus) {
        if (bus != null && STICKY.add(bus)) stickyAdded.increment();
    }

    public static boolean part(Object part) {
        if (part == null) return false;
        if (STICKY.contains(part)) return true;
        if (interfaceWithCraftingCard(part)) return true;
        if (PARTS.contains(part.getClass().getName())) return true;
        return part instanceof IOBusPart bus && BusTransit.manages(bus) && unsupportedConfig(bus);
    }

    /** Managed bus whose CONFIG the transit refuses (not "transit inactive"). */
    static boolean unsupportedConfig(IOBusPart bus) {
        var why = BusTransit.configIneligible(bus);
        return why != null && why != BusTransit.C.inlineInactive && why != BusTransit.C.inlineClass;
    }

    /** Worker, per device tick: is this node's device served on MAIN? */
    public static boolean device(IGridNode node) {
        Object o = node == null ? null : node.getOwner();
        if (o == null) return false;
        return (o instanceof BlockEntity be && host(be)) || part(o);
    }

    public static String key(IGridNode node) {
        Object o = node.getOwner();
        String k = "device " + (o == null ? "?" : o.getClass().getSimpleName());
        BY_CLASS.computeIfAbsent(k, x -> new LongAdder()).increment();
        devices.increment();
        return k;
    }
}
