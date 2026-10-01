package net.nestworld.modapi.inventory;

import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <p><b>PLATFORM PROVIDER SPI — not part of the public Mod API.</b> This type is a contract
 * between NestWorldCore and the Mod API provider, not a promise to mods; it may change, or move to
 * a separate artifact, without notice (docs/contracts/PKG-002-distribution-model.md, D1a). Mods
 * use the facades ({@code NestWorldEntities}, {@code NestWorldInventory}) and their value types.
 *
 * API-1B.6 (revised 2026-10-01): object-identity player incarnations.
 *
 * <p><b>Definition.</b> An incarnation is one concrete live {@link ServerPlayer} object. Two
 * requests belong to the same incarnation iff they were stamped against the same object
 * reference ({@code ==}). Forge lifecycle events play NO part in this definition or in any
 * correctness check -- an earlier, event-driven design was refuted live (API-1B.7 fixture E):
 * vanilla publishes the new ServerPlayer by UUID BEFORE PlayerRespawnEvent/PlayerLoggedInEvent
 * fire, so an event-driven counter was stale for exactly the window that mattered. It also could
 * not work on stock Forge at all, because {@code :modapi} has no access to Forge's event bus.
 * This class needs neither: it observes objects, not events.
 *
 * <p><b>Why not {@code WeakHashMap<ServerPlayer, Long>}</b>: {@code Entity.equals/hashCode}
 * compare the numeric entity id, and {@code PlayerList.respawn} copies the old id onto the new
 * object ({@code serverplayer.setId(old.getId())}) -- the old and new ServerPlayer are
 * {@code equals()} to each other. Any equality-based map conflates incarnations. Lookup here is by
 * reference identity only.
 *
 * <p><b>Correctness argument</b> (the whole reason the structure is this small):
 * <ol>
 *   <li>A token is minted exactly once ({@link AtomicLong#incrementAndGet}, never reset, never
 *       reused -- including across server restarts in the same JVM), and at mint time it is
 *       recorded against exactly one object reference.</li>
 *   <li>{@link #incarnationOf(ServerPlayer)} returns token {@code t} only if it finds an entry
 *       whose referent {@code == p}; otherwise it mints a fresh token for {@code p}.</li>
 *   <li>Therefore {@code incarnationOf(x) == incarnationOf(y)} implies {@code x == y}: a request
 *       stamped against object A can never validate against any other object B, regardless of
 *       timing, events, entity ids, or thread interleaving.</li>
 * </ol>
 * The converse can fail only in a safe direction: if A's entry was cleared (A was garbage
 * collected), A cannot be presented again anyway.
 *
 * <p><b>Stored data</b>: queued requests carry only the {@code long} token -- never a
 * ServerPlayer reference -- so a mailbox cannot keep a dead player object alive. This registry
 * holds weak references only.
 *
 * <p><b>Cleanup is not a correctness mechanism.</b> When a weak reference is found cleared, its
 * token is dead forever (rule 2 can never return it again), and the inventory version domain for
 * that token is dropped ({@link InventorySlotVersionTracker#forgetIncarnation}) purely to bound
 * memory. Pruning is lazy (on the next lookup for the same UUID); {@link #resetAll()} clears
 * everything on server stop.
 *
 * <p>Threading: safe from any thread. Per-UUID mutation happens inside
 * {@code ConcurrentHashMap.compute}, so concurrent first observations of the same object cannot
 * mint two tokens for it.
 */
public final class PlayerIncarnationRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerIncarnationRegistry.class);
    private static final AtomicLong NEXT_TOKEN = new AtomicLong();

    private record Entry(WeakReference<ServerPlayer> ref, long token) {}

    private static final ConcurrentHashMap<UUID, List<Entry>> BY_UUID = new ConcurrentHashMap<>();

    private PlayerIncarnationRegistry() {}

    /** The incarnation token of this exact object; mints one on first observation. Never 0. */
    public static long incarnationOf(ServerPlayer player) {
        UUID id = player.getUUID();
        long[] found = {0L};
        boolean[] minted = {false};
        List<Long> dead = new ArrayList<>(1);
        BY_UUID.compute(id, (k, list) -> {
            List<Entry> l = list == null ? new ArrayList<>(2) : list;
            for (Iterator<Entry> it = l.iterator(); it.hasNext(); ) {
                Entry e = it.next();
                ServerPlayer referent = e.ref().get();
                if (referent == null) {
                    dead.add(e.token());
                    it.remove();
                } else if (referent == player) {
                    found[0] = e.token();
                }
            }
            if (found[0] == 0L) {
                found[0] = NEXT_TOKEN.incrementAndGet();
                minted[0] = true;
                l.add(new Entry(new WeakReference<>(player), found[0]));
            }
            return l;
        });
        for (long t : dead) {
            InventorySlotVersionTracker.forgetIncarnation(id, t);
        }
        if (minted[0]) {
            LOGGER.info("[API1B6] INCARNATION minted player={} token={} playerObj={} prunedDead={} thread={}",
                    id, found[0], Integer.toHexString(System.identityHashCode(player)), dead,
                    Thread.currentThread().getName());
        }
        return found[0];
    }

    /** Diagnostic: number of still-reachable player objects currently known for this UUID. */
    public static int knownObjects(UUID playerId) {
        int[] n = {0};
        // computeIfPresent: same per-key lock incarnationOf's compute() mutates the list under.
        BY_UUID.computeIfPresent(playerId, (k, l) -> {
            for (Entry e : l) if (e.ref().get() != null) n[0]++;
            return l;
        });
        return n[0];
    }

    /** Server-stop cleanup. Tokens keep increasing across generations (the counter is never
     *  reset), so a token from a previous server can never collide with a new one. */
    public static void resetAll() {
        BY_UUID.clear();
    }
}
