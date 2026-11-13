package net.nestworld.compat.ae2.cost.mixin;

import appeng.me.Grid;
import appeng.me.GridNode;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.nbt.CompoundTag;
import net.nestworld.compat.ae2.glue.MachinesMemo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * AE2-COST-001-FAST candidate 2 (MACHINES-MEMO, see {@link MachinesMemo}): {@code Grid.getMachines(Class)} rebuilds an
 * ImmutableSet from {@code machines.get(class)} on every call (crazyae2addons' insertIntoCpus overwrite calls it for every
 * network insert). Its result depends only on that multimap, which AE2 mutates only in {@code Grid.add} / {@code Grid.remove}
 * (bytecode: the only writes of {@code machines}). The memo is versioned: version++ before and (finally) after every
 * add/remove; an entry is used only if it was computed entirely inside one version.
 */
@Mixin(value = Grid.class, remap = false)
public abstract class GridMachinesMemoMixin {
    @Unique private final AtomicLong nw$machinesVersion = new AtomicLong();
    @Unique private final ConcurrentHashMap<Class<?>, Object[]> nw$machinesMemo = new ConcurrentHashMap<>();

    @WrapMethod(method = "add")
    private void nw$memoAdd(GridNode node, CompoundTag data, Operation<Void> op) {
        nw$machinesVersion.incrementAndGet();
        try {
            op.call(node, data);
        } finally {
            nw$machinesVersion.incrementAndGet();
        }
    }

    @WrapMethod(method = "remove")
    private void nw$memoRemove(GridNode node, Operation<Void> op) {
        nw$machinesVersion.incrementAndGet();
        try {
            op.call(node);
        } finally {
            nw$machinesVersion.incrementAndGet();
        }
    }

    @WrapMethod(method = "getMachines")
    private Set<?> nw$memoGetMachines(Class<?> machineClass, Operation<Set<?>> op) {
        if (MachinesMemo.disabled) return op.call(machineClass);
        long v = nw$machinesVersion.get();
        Object[] e = nw$machinesMemo.get(machineClass);
        if (e != null && (long) e[0] == v) {
            MachinesMemo.HIT.increment();
            if (MachinesMemo.VERIFY > 0 && MachinesMemo.HIT.sum() % MachinesMemo.VERIFY == 0) {
                Set<?> ref = op.call(machineClass);   // read-only rebuild: same elements in the same iteration order?
                if (!new java.util.ArrayList<>(ref).equals(new java.util.ArrayList<>((Set<?>) e[1]))) {
                    MachinesMemo.mismatch(machineClass);
                    return ref;
                }
                MachinesMemo.VERIFY_OK.increment();
            }
            return (Set<?>) e[1];
        }
        Set<?> s = op.call(machineClass);
        if (nw$machinesVersion.get() == v) nw$machinesMemo.put(machineClass, new Object[]{v, s});
        MachinesMemo.MISS.increment();
        return s;
    }
}
