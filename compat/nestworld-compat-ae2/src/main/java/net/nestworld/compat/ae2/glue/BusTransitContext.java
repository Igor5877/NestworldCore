package net.nestworld.compat.ae2.glue;

import appeng.api.behaviors.StackTransferContext;
import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.networking.IGridNode;
import appeng.api.networking.energy.IEnergySource;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.IStorageProvider;
import appeng.api.storage.MEStorage;
import appeng.util.prioritylist.IPartitionList;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.network.chat.Component;

/**
 * WP3-B import, neighbour half: the {@link StackTransferContext} handed to AE2's own import facade on the
 * neighbour's thread. Same semantics as AE2's {@code StackTransferContextImpl} (ops, filter, key types, inversion),
 * except that the "network" is the record's transit buffer: insert SIMULATE accepts everything (optimistic; the MAIN
 * commit returns what the real network refuses to the source), insert MODULATE moves the amount into transit under the
 * record monitor. Nothing here reaches AE2 grid state; any other use of the storage / energy service is refused and
 * counted (the audited strategies never make one).
 */
final class BusTransitContext implements StackTransferContext {
    private final BusTransit.Rec r;
    private final int tick;
    private final IPartitionList filter;
    private final Set<AEKeyType> keyTypes = new HashSet<>();
    private final int initialOperations;
    private int operationsRemaining;
    private boolean inverted;
    private final IStorageService storage;

    BusTransitContext(BusTransit.Rec r, int tick) {
        this.r = r;
        this.tick = tick;
        this.filter = r.filter;
        this.initialOperations = r.ops;
        this.operationsRemaining = r.ops;
        this.inverted = r.inverted;
        for (AEKey k : filter.getItems()) keyTypes.add(k.getType());
        this.storage = new TransitStorage();
    }

    @Override
    public IStorageService getInternalStorage() {
        return storage;
    }

    @Override
    public IEnergySource getEnergySource() {
        BusTransit.C.regionEnergyCalls.increment();
        return NO_ENERGY;
    }

    @Override
    public IActionSource getActionSource() {
        return r.source;
    }

    @Override
    public int getOperationsRemaining() {
        return operationsRemaining;
    }

    @Override
    public void setOperationsRemaining(int operationsRemaining) {
        this.operationsRemaining = operationsRemaining;
    }

    @Override
    public boolean hasOperationsLeft() {
        return operationsRemaining > 0;
    }

    @Override
    public boolean hasDoneWork() {
        return initialOperations > operationsRemaining;
    }

    @Override
    public boolean isKeyTypeEnabled(AEKeyType space) {
        return keyTypes.isEmpty() || keyTypes.contains(space);
    }

    @Override
    public boolean isInFilter(AEKey key) {
        return filter.isEmpty() || filter.isListed(key);
    }

    @Override
    public IPartitionList getFilter() {
        return filter;
    }

    @Override
    public void setInverted(boolean inverted) {
        this.inverted = inverted;
    }

    @Override
    public boolean isInverted() {
        return !filter.isEmpty() && inverted;
    }

    @Override
    public boolean canInsert(AEItemKey what, long amount) {
        return amount > 0;
    }

    @Override
    public void reduceOperationsRemaining(long inserted) {
        operationsRemaining -= inserted;
    }

    private static final IEnergySource NO_ENERGY = new IEnergySource() {
        @Override
        public double extractAEPower(double amt, Actionable mode, PowerMultiplier usePowerMultiplier) {
            return 0;
        }
    };

    private final class TransitStorage implements IStorageService, MEStorage {
        @Override
        public MEStorage getInventory() {
            return this;
        }

        @Override
        public KeyCounter getCachedInventory() {
            BusTransit.C.regionStorageMisuse.increment();
            return new KeyCounter();
        }

        @Override
        public void addGlobalStorageProvider(IStorageProvider cc) {
            BusTransit.C.regionStorageMisuse.increment();
        }

        @Override
        public void removeGlobalStorageProvider(IStorageProvider cc) {
            BusTransit.C.regionStorageMisuse.increment();
        }

        @Override
        public void refreshNodeStorageProvider(IGridNode node) {
            BusTransit.C.regionStorageMisuse.increment();
        }

        @Override
        public void invalidateCache() {
            BusTransit.C.regionStorageMisuse.increment();
        }

        @Override
        public void refreshGlobalStorageProvider(IStorageProvider cc) {
            BusTransit.C.regionStorageMisuse.increment();
        }

        @Override
        public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
            if (amount <= 0) return 0;
            if (mode == Actionable.MODULATE) {
                synchronized (r) {
                    r.addHeld(what, amount, tick);
                    r.extracted += amount;
                }
                BusTransit.C.impFromSource.add(amount);
            }
            return amount;
        }

        @Override
        public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
            BusTransit.C.regionStorageMisuse.increment();
            return 0;
        }

        @Override
        public Component getDescription() {
            return Component.literal("NestWorld bus transit");
        }
    }
}
