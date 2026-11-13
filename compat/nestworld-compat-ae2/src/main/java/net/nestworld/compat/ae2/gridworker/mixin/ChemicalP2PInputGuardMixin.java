package net.nestworld.compat.ae2.gridworker.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.concurrent.locks.ReentrantLock;
import mekanism.api.Action;
import mekanism.api.chemical.ChemicalStack;
import net.nestworld.compat.ae2.glue.ChestGuard;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** AE2-GRID-DOMAIN-MVP-001: pipes push into an Applied-Mekanistics chemical P2P input on THEIR thread and the tunnel
 *  forwards through the grid's P2P state — ingress into grid-internal state, serialised by P(G) of a hot grid like
 *  NetworkStorage ingress (see {@link ChestGuard}). */
@Mixin(targets = "me.ramidzkh.mekae2.ae2.ChemicalP2PTunnelPart$InputChemicalHandler", remap = false)
public abstract class ChemicalP2PInputGuardMixin {
    @Shadow @Final private me.ramidzkh.mekae2.ae2.ChemicalP2PTunnelPart part;

    private ReentrantLock nw$enter() { return ChestGuard.enterNode(part.getGridNode()); }

    @WrapMethod(method = "getTanks()I")
    private int nw$tanks(Operation<Integer> op) {
        ReentrantLock p = nw$enter(); try { return op.call(); } finally { ChestGuard.exit(p); }
    }

    @WrapMethod(method = "getChemicalInTank(I)Lmekanism/api/chemical/ChemicalStack;")
    private ChemicalStack<?> nw$get(int t, Operation<ChemicalStack<?>> op) {
        ReentrantLock p = nw$enter(); try { return op.call(t); } finally { ChestGuard.exit(p); }
    }

    @WrapMethod(method = "setChemicalInTank(ILmekanism/api/chemical/ChemicalStack;)V")
    private void nw$set(int t, ChemicalStack<?> s, Operation<Void> op) {
        ReentrantLock p = nw$enter(); try { op.call(t, s); } finally { ChestGuard.exit(p); }
    }

    @WrapMethod(method = "getTankCapacity(I)J")
    private long nw$cap(int t, Operation<Long> op) {
        ReentrantLock p = nw$enter(); try { return op.call(t); } finally { ChestGuard.exit(p); }
    }

    @WrapMethod(method = "isValid(ILmekanism/api/chemical/ChemicalStack;)Z")
    private boolean nw$valid(int t, ChemicalStack<?> s, Operation<Boolean> op) {
        ReentrantLock p = nw$enter(); try { return op.call(t, s); } finally { ChestGuard.exit(p); }
    }

    @WrapMethod(method = "insertChemical(ILmekanism/api/chemical/ChemicalStack;Lmekanism/api/Action;)Lmekanism/api/chemical/ChemicalStack;")
    private ChemicalStack<?> nw$insert(int t, ChemicalStack<?> s, Action a, Operation<ChemicalStack<?>> op) {
        ReentrantLock p = nw$enter(); try { return op.call(t, s, a); } finally { ChestGuard.exit(p); }
    }

    @WrapMethod(method = "extractChemical(IJLmekanism/api/Action;)Lmekanism/api/chemical/ChemicalStack;")
    private ChemicalStack<?> nw$extract(int t, long amount, Action a, Operation<ChemicalStack<?>> op) {
        ReentrantLock p = nw$enter(); try { return op.call(t, amount, a); } finally { ChestGuard.exit(p); }
    }
}
