package net.nestworld.module;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** One ledger-owned service per module generation. No unmanaged workers, blocking waits or
 * entity access. Tickets and readiness polling run on the owning server thread. */
final class ModuleChunkPreparations implements ResourceHandle {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ModuleChunkPreparations.class);
    // UUID per lease, not per chunk: overlapping preparations cannot revoke each other's tickets.
    private static final TicketType<UUID> TICKET = TicketType.create("nestworld_module_prepare",
            Comparator.<UUID>naturalOrder(), 1201);
    private final MinecraftServer server;
    private final ModuleIdentity owner;
    private final Map<UUID, Lease> leases = new LinkedHashMap<>();
    private boolean live = true;

    ModuleChunkPreparations(MinecraftServer server, ModuleIdentity owner) {
        this.server = server;
        this.owner = owner;
        MinecraftForge.EVENT_BUS.register(this);
    }

    ChunkPreparation prepare(ChunkPreparationRequest request) {
        requireThread();
        if (!live) throw new IllegalStateException("preparation service revoked");
        Lease existing = leases.get(request.id());
        if (existing != null) {
            if (!existing.request.equals(request)) throw new IllegalArgumentException("id reused with different destination");
            return existing;
        }
        if (leases.size() >= 8) throw new IllegalStateException("module preparation limit reached (8)");
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, request.dimension()));
        if (level == null) throw new IllegalArgumentException("unknown dimension: " + request.dimension());
        Lease lease = new Lease(request, level);
        leases.put(request.id(), lease);
        try {
            // Nearest first. Only request non-ticking FULL chunks; never move/create a player.
            for (int ring = 0; ring <= request.radius(); ring++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    for (int dz = -ring; dz <= ring; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                        ChunkPos pos = new ChunkPos(request.x() + dx, request.z() + dz);
                        lease.positions.add(pos);
                        level.getChunkSource().addRegionTicket(TICKET, pos, 0, lease.ticketId);
                    }
                }
            }
        } catch (RuntimeException e) {
            lease.finish(ChunkPreparation.State.FAILED);
            throw e;
        }
        return lease;
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (!live || event.getServer() != server || event.phase != TickEvent.Phase.END) return;
        requireThread();
        for (Lease lease : List.copyOf(leases.values())) {
            if (--lease.remainingTicks <= 0) {
                lease.finish(ChunkPreparation.State.EXPIRED);
                continue;
            }
            if (lease.state != ChunkPreparation.State.PREPARING) continue;
            try {
                boolean ready = true;
                for (ChunkPos pos : lease.positions) {
                    // false: never add UNKNOWN tickets, never managedBlock/join. FULL future avoids
                    // getChunkNow's "currentlyLoading" shortcut reporting partially loaded chunks.
                    var future = lease.level.getChunkSource().getChunkFutureMainThread(pos.x, pos.z, ChunkStatus.FULL, false);
                    var result = future.getNow(null);
                    if (result == null || result.left().isEmpty() || !result.left().get().isLightCorrect()) {
                        ready = false;
                        break;
                    }
                }
                if (ready) {
                    lease.state = ChunkPreparation.State.READY;
                    lease.ready.complete(null);
                }
            } catch (RuntimeException e) {
                LOGGER.warn("Module {} preparation {} failed", owner, lease.request.id(), e);
                lease.finish(ChunkPreparation.State.FAILED);
            }
        }
    }

    private void requireThread() {
        if (!server.isSameThread()) throw new IllegalStateException("chunk preparation requires owning server thread");
    }

    @Override public String kind() { return "chunk-preparations"; }

    @Override public void revoke() {
        requireThread();
        if (!live) return;
        live = false;
        MinecraftForge.EVENT_BUS.unregister(this);
        for (Lease lease : List.copyOf(leases.values())) lease.finish(ChunkPreparation.State.CANCELLED);
    }

    private final class Lease implements ChunkPreparation {
        private final ChunkPreparationRequest request;
        private final ServerLevel level;
        private final UUID ticketId = UUID.randomUUID();
        private final List<ChunkPos> positions = new ArrayList<>();
        private final CompletableFuture<Void> ready = new CompletableFuture<>();
        private volatile State state = State.PREPARING;
        private int remainingTicks;

        Lease(ChunkPreparationRequest request, ServerLevel level) {
            this.request = request;
            this.level = level;
            this.remainingTicks = request.lifetimeTicks();
        }

        @Override public ChunkPreparationRequest request() { return request; }
        @Override public ModuleIdentity owner() { return owner; }
        @Override public State state() { return state; }
        @Override public CompletionStage<Void> ready() { return ready.minimalCompletionStage(); }
        @Override public String kind() { return "chunk-preparation:" + request.id(); }
        @Override public void revoke() { requireThread(); finish(State.CANCELLED); }

        void finish(State terminal) {
            if (state != State.PREPARING && state != State.READY) return;
            state = terminal;
            leases.remove(request.id(), this);
            for (ChunkPos pos : positions) {
                try {
                    level.getChunkSource().removeRegionTicket(TICKET, pos, 0, ticketId);
                } catch (RuntimeException e) {
                    // Continue releasing other tickets. The ticket's own bounded lifetime remains
                    // a backstop if an exceptional shutdown prevents immediate removal.
                    LOGGER.warn("Module {} failed to release preparation ticket {} at {}", owner, ticketId, pos, e);
                }
            }
            ready.completeExceptionally(new IllegalStateException("chunk preparation " + terminal));
        }
    }
}
