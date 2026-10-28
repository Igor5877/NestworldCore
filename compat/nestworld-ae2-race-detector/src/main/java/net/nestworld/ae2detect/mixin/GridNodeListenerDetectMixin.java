package net.nestworld.ae2detect.mixin;

import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.me.GridNode;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.nestworld.ae2detect.BeReadyDetect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Observation only: the real node-listener invocation inside GridNode.callListener (innermost, so it sees the
 *  thread a listener actually runs on even when an addon defers/routes delivery). */
@Mixin(value = GridNode.class, remap = false)
public abstract class GridNodeListenerDetectMixin {
    @WrapOperation(method = "callListener", at = @At(value = "INVOKE",
            target = "Lappeng/me/GridNode$ListenerCallback;call(Lappeng/api/networking/IGridNodeListener;Ljava/lang/Object;Lappeng/api/networking/IGridNode;)V"))
    private void nwd$listener(GridNode.ListenerCallback<Object> cb, IGridNodeListener<Object> listener, Object owner,
                              IGridNode node, Operation<Void> op) {
        BeReadyDetect.listener(owner);
        op.call(cb, listener, owner, node);
    }
}
