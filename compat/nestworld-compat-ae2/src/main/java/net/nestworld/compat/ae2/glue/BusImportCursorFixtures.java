package net.nestworld.compat.ae2.glue;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.capabilities.*;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Fixture-only energy capability on explicitly registered test furnaces, never on production sources. */
@Mod.EventBusSubscriber(modid = "nestworld_compat_ae2", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BusImportCursorFixtures {
    private static final ConcurrentHashMap<Long, Energy> SOURCES = new ConcurrentHashMap<>();
    private BusImportCursorFixtures() {}
    static void prepare(ServerLevel level, BlockPos pos) {
        if (!BusTransitFixtures.ENABLED) throw new IllegalStateException("Fixtures disabled");
        SOURCES.put(pos.asLong(), new Energy(level));
    }
    @SubscribeEvent
    public static void attach(AttachCapabilitiesEvent<BlockEntity> event) {
        if (!BusTransitFixtures.ENABLED) return;
        Energy source = SOURCES.get(event.getObject().getBlockPos().asLong());
        if (source == null || source.be != null) return;
        source.be = event.getObject();
        LazyOptional<IEnergyStorage> optional = LazyOptional.of(() -> source);
        event.addCapability(new ResourceLocation("nestworld_compat_ae2", "cursor_fixture_energy"), new ICapabilityProvider() {
            @Override public <T> LazyOptional<T> getCapability(Capability<T> cap, net.minecraft.core.Direction side) {
                return cap == ForgeCapabilities.ENERGY ? optional.cast() : LazyOptional.empty();
            }
        });
        event.addListener(optional::invalidate);
    }
    @SubscribeEvent public static void stop(ServerStoppedEvent event) { SOURCES.clear(); }
    public static String status() {
        StringBuilder result = new StringBuilder("cursorFixture");
        SOURCES.forEach((pos, energy) -> result.append(" | ").append(BlockPos.of(pos).toShortString())
                .append(" stored=").append(energy.stored).append(" extracted=").append(energy.extracted.get())
                .append(" refunded=").append(energy.refunded.get()).append(" mainCalls=").append(energy.mainCalls.get())
                .append(" regionCalls=").append(energy.regionCalls.get()).append(" violations=").append(energy.violations.get()));
        try {
            ClassLoader cl = BusImportCursorFixtures.class.getClassLoader();
            Class<?> type = Class.forName("com.glodblock.github.appflux.common.me.key.type.EnergyType", false, cl);
            Class<?> keyClass = Class.forName("com.glodblock.github.appflux.common.me.key.FluxKey", false, cl);
            var key = (appeng.api.stacks.AEKey) keyClass.getMethod("of", type).invoke(null, type.getField("FE").get(null));
            long network = 0;
            for (var station : BusTransitFixtures.STATIONS) {
                var grid = BusTransitFixtures.gridOf(station);
                if (grid != null) network += grid.getStorageService().getInventory().extract(key, Long.MAX_VALUE,
                        appeng.api.config.Actionable.SIMULATE, appeng.api.networking.security.IActionSource.empty());
            }
            result.append(" | networkFE=").append(network);
        } catch (ReflectiveOperationException unavailable) { result.append(" | networkFE=UNAVAILABLE"); }
        return result.toString();
    }
    private static final class Energy implements IEnergyStorage {
        final ServerLevel level;
        volatile BlockEntity be;
        volatile int stored = 1_000_000;
        final AtomicLong extracted = new AtomicLong(), refunded = new AtomicLong(), mainCalls = new AtomicLong(),
                regionCalls = new AtomicLong(), violations = new AtomicLong();
        Energy(ServerLevel level) { this.level = level; }
        void check() {
            if (level.getServer().isSameThread()) mainCalls.incrementAndGet(); else regionCalls.incrementAndGet();
            if (be == null || !BeDomainReady.currentThreadIn(level, be.getBlockPos(), BeDomainReady.typeOf(be))) violations.incrementAndGet();
        }
        @Override public int extractEnergy(int amount, boolean simulate) {
            check(); int take = Math.min(stored, Math.max(0, amount));
            if (!simulate) { stored -= take; extracted.addAndGet(take); }
            return take;
        }
        @Override public int receiveEnergy(int amount, boolean simulate) {
            check(); int put = Math.min(1_000_000 - stored, Math.max(0, amount));
            if (!simulate) { stored += put; refunded.addAndGet(put); }
            return put;
        }
        @Override public int getEnergyStored() { check(); return stored; }
        @Override public int getMaxEnergyStored() { check(); return 1_000_000; }
        @Override public boolean canExtract() { check(); return true; }
        @Override public boolean canReceive() { check(); return true; }
    }
}
