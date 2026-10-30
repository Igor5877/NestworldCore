package net.nestworld.compat.ae2.mixin;

import static net.nestworld.compat.ae2.glue.Ae2Compat.COUNTERS;
import static net.nestworld.compat.ae2.glue.Ae2Compat.KERNEL;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.nestworld.compat.ae2.glue.BeDomainReady;
import net.nestworld.compat.ae2.glue.LifeHooks;
import net.nestworld.compat.ae2.glue.NwAeBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * QUEUE-001.1 §4.3: first-tick initialization of an AE2 block entity runs under its lifecycle lock and
 * revalidates INSIDE it (never check-then-lock). Removal takes the same lock (LevelChunkMixin), so
 * onReady/create and setRemoved/destroy are mutually exclusive per block entity.
 *
 * <p>AE2-BE-READY-001 R1-a scope 2: the whole body runs in the host's block-entity execution domain
 * ({@link BeDomainReady}); revalidation happens there, at execution time. Hosts that are not AE2 base block
 * entities (e.g. SolarFlux panels) have no lifecycle lock and are revalidated by identity only.
 */
@Mixin(targets = "appeng.hooks.ticking.ServerBlockEntityRepo$FirstTickInfo", remap = false)
public abstract class FirstTickInfoMixin {

    @Shadow
    public abstract BlockEntity blockEntity();

    @Shadow
    public abstract Consumer<?> initFunction();

    @WrapMethod(method = "callInit")
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void nw$callInit(Operation<Void> op) {
        BlockEntity be = blockEntity();
        Consumer init = initFunction();
        BeDomainReady.ready(be, () -> nw$init(be, op), () -> appeng.api.networking.GridHelper.onFirstTick(be, init));
    }

    private static void nw$init(BlockEntity be, Operation<Void> op) {
        if (!(be instanceof NwAeBlockEntity life)) {
            String reason = nw$invalidIdentity(be);
            if (reason != null) {
                COUNTERS.initCancelled.increment();
                return;
            }
            op.call();
            return;
        }
        if (KERNEL.inSection()) {                         // order is nw$life → T0 → domains
            COUNTERS.lifeOrderViolation.increment();
            throw KERNEL.unaudited("AE2 block-entity initialization inside a guarded section");
        }
        LifeHooks.Hook h = LifeHooks.hook;
        if (h != null) h.at("init-prelock", be);
        life.nw$life().lock();
        net.nestworld.compat.ae2.glue.LifeAdmission.enterLife();
        try {
            String reason = nw$invalid(be, life);
            if (reason != null) {
                COUNTERS.initCancelled.increment();
                if (h != null) h.at("init-cancelled:" + reason, be);
                return;
            }
            if (h != null) h.at("init-locked", be);
            try {
                op.call();
            } finally {
                // AE2-PIN-LEAK-001 D1 probe: the host's block changed while onReady ran (the S1 window)
                if (be.getLevel() != null && be.getLevel().getBlockState(be.getBlockPos()).getBlock() != be.getBlockState().getBlock()) {
                    COUNTERS.lifeWindowObserved.increment();
                }
            }
            if (h != null) h.at("init-done", be);
        } finally {
            net.nestworld.compat.ae2.glue.LifeAdmission.exitLife();
            life.nw$life().unlock();
        }
    }

    private static String nw$invalid(BlockEntity be, NwAeBlockEntity life) {
        if (life.nw$removed()) return "removed";
        String reason = nw$invalidIdentity(be);
        if (reason != null) return reason;
        if (be.getLevel().getBlockState(be.getBlockPos()).getBlock() != be.getBlockState().getBlock()) return "block changed";
        return null;
    }

    private static String nw$invalidIdentity(BlockEntity be) {
        if (be.isRemoved()) return "removed";
        Level level = be.getLevel();
        if (level == null) return "no level";
        BlockPos pos = be.getBlockPos();
        if (!level.isLoaded(pos)) return "chunk not loaded";
        if (level.getBlockEntity(pos) != be) return "not the block entity at its position";
        return null;
    }
}
