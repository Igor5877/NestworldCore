package net.nestworld.region;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Explosion;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;

/**
 * CORE-MAILBOX-MERGE-001 (docs/contracts/CORE-MAILBOX-MERGE-001-fix-spec.md, C6/C7/C8):
 * re-resolution of ORPHANED messages — messages whose previously resolved destination region
 * was retired by split/merge. Main thread only, run by {@link NestworldDimensionRegion#tick}
 * at the start of the tick and again right after split/merge.
 *
 * <p>Each message is re-resolved by its TARGET IDENTITY, with the same rule its producer used
 * (a split has two successors — forwarding to "the" successor would be wrong half the time):
 * <ul>
 *   <li>entity-UUID types: the entity's current owner; for an entity no region owns at this
 *       instant, the region covering its live position (unowned → no region thread races the read)</li>
 *   <li>player-acting types: the region covering the player's current position
 *       ({@code PLAYER_INPUT}: the captured input position, as its producer does)</li>
 *   <li>block-position types: the region covering the block</li>
 *   <li>{@code EXPLOSION_APPLY}: positions partitioned by owner, one batch per owner (C8)</li>
 *   <li>{@code ENTITY_TRANSFER}: the region covering the entity's live position, unless a live
 *       region already owns it (explicit TRANSFER_ALREADY_OWNED — never a second owner)</li>
 * </ul>
 * Redelivery goes through the destination's normal C4 safe enqueue, and the consumer re-validates
 * on apply exactly as for a freshly produced message. Anything that cannot be redelivered gets an
 * explicit {@link MailboxLifecycle.Terminal} — never silence.
 */
final class MailboxRetargeting {
    private static final Logger DIAG = LogManager.getLogger("NestWorld/DIAG");

    private MailboxRetargeting() {}

    /** TEST-ONLY observer of explosion batch splits: (parent, children). */
    static volatile java.util.function.BiConsumer<RegionMessage<?>, List<RegionMessage<?>>> EXPLOSION_SPLIT_OBSERVER = null;

    /** Positions of a split explosion batch that no live region covers (dropped with the parent's
     *  SPLIT_INTO_CHILDREN terminal; reported, expected 0). */
    static final java.util.concurrent.atomic.AtomicLong explosionPositionsNoOwner = new java.util.concurrent.atomic.AtomicLong();

    /** Processes every orphan present at entry (orphans created while processing wait for the next
     *  pass, so the pass is bounded). */
    static void redeliver(NestworldDimensionRegion dr) {
        MailboxLifecycle.pollExitWatches();
        Queue<MailboxLifecycle.Orphan> q = MailboxLifecycle.orphanQueue(dr.getLevel().dimension());
        int n = q.size();
        for (int i = 0; i < n; i++) {
            MailboxLifecycle.Orphan o = q.poll();
            if (o == null) break;
            try {
                handle(dr, o);
            } catch (Throwable t) {
                // Re-resolution itself failing must not lose the message silently either.
                DIAG.warn("[CORE-MAILBOX] re-resolution of message {} ({}) failed: {} -- re-queued",
                        o.message().auditId(), o.message().messageType(), t.toString());
                if (o.message().redeliveries() + 1 >= NestworldTuning.MAILBOX_MAX_REDELIVERIES) {
                    terminal(o.message(), MailboxLifecycle.Terminal.REDELIVERY_LIMIT_DROPPED);
                } else {
                    q.add(new MailboxLifecycle.Orphan(o.message().redeliveredTo(o.message().destinationRegion()),
                            o.reason(), o.fromRegionId()));
                }
            }
        }
    }

    private static void handle(NestworldDimensionRegion dr, MailboxLifecycle.Orphan o) {
        RegionMessage<?> msg = o.message();
        if (msg.redeliveries() >= NestworldTuning.MAILBOX_MAX_REDELIVERIES) {
            terminal(msg, MailboxLifecycle.Terminal.REDELIVERY_LIMIT_DROPPED);
            return;
        }
        ServerLevel level = dr.getLevel();
        WorldGrid grid = dr.getGrid();
        switch (msg.messageType()) {
            case EXPLOSION_APPLY -> redeliverExplosion(grid, msg);
            case ENTITY_TRANSFER -> {
                UUID id = (UUID) msg.payload();
                if (grid.findOwningRegion(id) != null) {
                    terminal(msg, MailboxLifecycle.Terminal.TRANSFER_ALREADY_OWNED);
                    return;
                }
                deliverOrTerminal(msg, regionAtEntity(level, grid, id));
            }
            case ENTITY_IMPACT -> deliverOrTerminal(msg, entityOwner(level, grid, ((RegionMessage.EntityImpact) msg.payload()).entityId()));
            case ENTITY_PUSH -> deliverOrTerminal(msg, entityOwner(level, grid, ((RegionMessage.EntityPush) msg.payload()).entityId()));
            case ENTITY_MUTATE -> deliverOrTerminal(msg, entityOwner(level, grid, ((RegionMessage.EntityMutate) msg.payload()).entityId()));
            case PLAYER_ENTITY_INTERACT ->
                    // Producer rule (EntityInteractionDispatcher.resolveByTarget): the target's owner only.
                    deliverOrTerminal(msg, grid.findOwningRegion(((RegionMessage.EntityInteract) msg.payload()).targetId()));
            case PLAYER_INPUT -> {
                RegionMessage.PlayerInput in = (RegionMessage.PlayerInput) msg.payload();
                deliverOrTerminal(msg, regionAt(grid, in.x(), in.z()));
            }
            case PLAYER_ATTACK -> deliverOrTerminal(msg, regionAtPlayer(level, grid, ((RegionMessage.PlayerAttack) msg.payload()).attackerId()));
            case PLAYER_USE_ITEM -> deliverOrTerminal(msg, regionAtPlayer(level, grid, ((RegionMessage.UseItem) msg.payload()).playerId()));
            case PLAYER_USE_CONTROL -> deliverOrTerminal(msg, regionAtPlayer(level, grid, ((RegionMessage.UseControl) msg.payload()).playerId()));
            case BLOCK_WRITE -> deliverOrTerminal(msg, regionAt(grid, ((RegionMessage.BlockWrite) msg.payload()).pos()));
            case PLAYER_BLOCK_INTERACTION -> deliverOrTerminal(msg, regionAt(grid, ((RegionMessage.BlockInteraction) msg.payload()).pos()));
            case PLAYER_USE_ITEM_ON_BLOCK -> {
                RegionMessage.UseItemOnBlock u = (RegionMessage.UseItemOnBlock) msg.payload();
                ServerPlayer player = livePlayer(level, u.playerId());
                if (player == null) {
                    terminal(msg, MailboxLifecycle.Terminal.TARGET_NOT_FOUND);
                    return;
                }
                UseOnContext ctx = new UseOnContext(player, u.hand(), u.hit());
                deliverOrTerminal(msg, regionAt(grid, PlayerUseItemOnBlockDispatcher.resolveOwnerPos(ctx.getItemInHand(), ctx)));
            }
            // Never a mailbox message (deferred-wire queue, main thread) -- defensive only.
            case WIRE_UPDATE -> terminal(msg, MailboxLifecycle.Terminal.TARGET_NOT_FOUND);
        }
    }

    private static void deliverOrTerminal(RegionMessage<?> msg, WorldRegion dest) {
        if (dest == null) {
            terminal(msg, MailboxLifecycle.Terminal.TARGET_NOT_FOUND);
            return;
        }
        RegionMessage<?> moved = msg.redeliveredTo(dest);
        MailboxAudit.recordRedirected(msg.auditId(), dest);
        MailboxLifecycle.redelivered.incrementAndGet();
        if (msg.payload() instanceof RegionMessage.EntityMutate em) {
            if (ApiOpTrace.ENABLED) ApiOpTrace.LOG.info("[API1A7] opId={} phase=REDELIVER from={} to={} redelivery={} target={}",
                    em.opId(), msg.destinationRegion() == null ? "null" : msg.destinationRegion().getId(),
                    dest.getId(), moved.redeliveries(), em.entityId());
        }
        dest.nestworldRequeueMessage(moved); // C4 safe enqueue; "sent" already counted once
    }

    /** C8: one child batch per owner; same Explosion (identity/context) and spawnDrops in every
     *  child; parent closed with SPLIT_INTO_CHILDREN, each child separately audited. */
    private static void redeliverExplosion(WorldGrid grid, RegionMessage<?> msg) {
        Explosion.NestworldExplosionBatch batch = (Explosion.NestworldExplosionBatch) msg.payload();
        Map<WorldRegion, List<BlockPos>> byOwner = new IdentityHashMap<>();
        List<WorldRegion> order = new ArrayList<>();
        long noOwner = 0;
        for (BlockPos pos : batch.positions()) {
            WorldRegion r = regionAt(grid, pos);
            if (r == null) {
                noOwner++;
                continue;
            }
            List<BlockPos> l = byOwner.get(r);
            if (l == null) {
                l = new ArrayList<>();
                byOwner.put(r, l);
                order.add(r);
            }
            l.add(pos);
        }
        if (order.isEmpty()) {
            terminal(msg, MailboxLifecycle.Terminal.TARGET_NOT_FOUND);
            return;
        }
        if (order.size() == 1 && noOwner == 0) {
            deliverOrTerminal(msg, order.get(0));
            return;
        }
        explosionPositionsNoOwner.addAndGet(noOwner);
        List<RegionMessage<?>> children = new ArrayList<>(order.size());
        for (WorldRegion r : order) {
            long childId = MailboxAudit.nextId();
            RegionMessage<Explosion.NestworldExplosionBatch> child = msg.childFor(r,
                    new Explosion.NestworldExplosionBatch(batch.explosion(), List.copyOf(byOwner.get(r)), batch.spawnDrops()),
                    childId);
            MailboxAudit.recordSent(childId, msg.sourceRegion(), r, msg.messageType());
            children.add(child);
        }
        terminal(msg, MailboxLifecycle.Terminal.SPLIT_INTO_CHILDREN);
        MailboxLifecycle.explosionChildBatches.addAndGet(children.size());
        var observer = EXPLOSION_SPLIT_OBSERVER;
        if (observer != null) observer.accept(msg, List.copyOf(children));
        for (RegionMessage<?> child : children) {
            MailboxLifecycle.redelivered.incrementAndGet();
            child.destinationRegion().nestworldRequeueMessage(child);
        }
    }

    static void terminal(RegionMessage<?> msg, MailboxLifecycle.Terminal kind) {
        MailboxLifecycle.terminal(kind);
        MailboxAudit.recordTerminal(msg.auditId(), kind, msg.messageType());
        if (msg.payload() instanceof RegionMessage.EntityMutate em) {
            MailboxLifecycleTestHooks.onEntityTerminal(em.opId(), kind.name());
            if (ApiOpTrace.ENABLED) ApiOpTrace.LOG.warn("[API1A7] opId={} phase=TERMINAL result={} target={} redeliveries={}",
                    em.opId(), kind, em.entityId(), msg.redeliveries());
            if (em.op() instanceof EntityMutationOp.SetInventorySlot) {
                switch (kind) {
                    case TARGET_NOT_FOUND -> InventorySetTelemetry.targetNotFound.incrementAndGet();
                    case REDELIVERY_LIMIT_DROPPED -> InventorySetTelemetry.redeliveryLimitDropped.incrementAndGet();
                    default -> { }
                }
            }
        }
    }

    // --- resolution rules (mirroring the producers) ---

    private static WorldRegion regionAt(WorldGrid grid, BlockPos pos) {
        return grid.getRegionForChunk(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private static WorldRegion regionAt(WorldGrid grid, double x, double z) {
        return grid.getRegionForChunk(((int) Math.floor(x)) >> 4, ((int) Math.floor(z)) >> 4);
    }

    private static ServerPlayer livePlayer(ServerLevel level, UUID id) {
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
        if (p == null || p.isRemoved() || p.serverLevel() != level) return null;
        return p;
    }

    private static WorldRegion regionAtPlayer(ServerLevel level, WorldGrid grid, UUID id) {
        ServerPlayer p = livePlayer(level, id);
        return p == null ? null : regionAt(grid, p.getX(), p.getZ());
    }

    private static WorldRegion regionAtEntity(ServerLevel level, WorldGrid grid, UUID id) {
        Entity e = level.getEntity(id);
        if (e == null || e.isRemoved()) return null;
        return regionAt(grid, e.getX(), e.getZ());
    }

    /** Current owner; for an entity no region owns right now, the region at its live position
     *  (reading an UNOWNED entity's position on main races no region thread). */
    private static WorldRegion entityOwner(ServerLevel level, WorldGrid grid, UUID id) {
        WorldRegion owner = grid.findOwningRegion(id);
        return owner != null ? owner : regionAtEntity(level, grid, id);
    }
}
