package net.nestworld.compat.ae2.glue;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEItemKey;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import net.minecraft.world.item.Item;
import net.minecraftforge.items.IItemHandler;

/**
 * AE2-COST-001-FAST candidate 1 (SOPH-EXTRACT-INDEX): AE2's {@code ItemHandlerFacade.extractExternal} visits EVERY slot of
 * the external inventory (getStackInSlot + matches) for every network extract, including the
 * {@code MEInventoryHandler.isPreferredStorageFor} probe of every network insert. For a Sophisticated Storage chest the
 * inventory itself keeps an authoritative per-Item slot index (its {@code InventoryHandlerSlotTracker}, which Sophisticated
 * itself uses for its own insert/extract). This class replaces ONLY the slot-selection loop of the ORIGINAL body (the
 * innermost wrapper; appliedenhancements' wrapper and bookkeeping stay outside and unchanged): instead of visiting slots
 * 0..n-1 it visits, in the same ascending order, the slots that the tracker says hold the requested Item, re-reading the
 * tracker after every visit (MODULATE may move items). Every visited slot still goes through AE2's own
 * {@code extractFromHandler} on the real handler (getStackInSlot + matches + extractItem): the transaction and the returned
 * amount are always the real handler's. Slots that are skipped hold no stack of that Item (tracker invariant, audited
 * for the exact jar set), so AE2 would have returned 0 for them without any side effect.
 *
 * <p>Applies only to the audited handler chain {@code CachedFailedInsertInventoryHandler(StorageBlockEntity/Backpack lambda)
 * → [FilteredItemHandler$Modifiable →] StorageWrapper$4 | BackpackInventoryHandler} with only
 * {@code IInventoryPartHandler$Default} parts and an initialised {@code InventoryHandlerSlotTracker}; anything else ⇒ AE2's
 * original loop. Flag {@code -Dnestworld.compatAe2.sophExtractIndex=on|census|off} (default off). {@code census} = count only
 * (original loop runs). {@code -Dnestworld.compatAe2.sophExtractIndexVerify=N}: every N-th SIMULATE call also runs the
 * original loop and compares; any mismatch disables the index for the rest of the run and returns AE2's result.
 */
public final class SophExtractIndex {
    public static final String MODE = System.getProperty("nestworld.compatAe2.sophExtractIndex", "off");
    public static final boolean ON = MODE.equals("on");
    public static final boolean CENSUS = MODE.equals("census");
    public static final boolean APPLY = ON || CENSUS;
    static final boolean INIT_TRACKER = !Boolean.getBoolean("nestworld.compatAe2.sophExtractIndexNoInit");
    static final int VERIFY = Integer.getInteger("nestworld.compatAe2.sophExtractIndexVerify", 0);

    /** The original per-slot body: AE2's (transformed) {@code extractFromHandler}. */
    @FunctionalInterface
    public interface SlotExtractor {
        int extract(IItemHandler handler, int slot, AEItemKey key, int maxExtract, Actionable mode);
    }

    static final LongAdder CALLS = new LongAdder();            // extractExternal calls with an AEItemKey
    static final LongAdder INDEXED = new LongAdder();          // calls answered by the indexed loop
    static final LongAdder INDEXED_MISS = new LongAdder();     // ... of which the Item was absent (0 slots visited)
    static final LongAdder SLOTS_FULL = new LongAdder();       // slots the original loop would have visited (upper bound n)
    static final LongAdder SLOTS_VISITED = new LongAdder();    // slots visited by the indexed loop
    static final LongAdder NOT_SOPH = new LongAdder();         // handler chain not audited (other mods) ⇒ original
    static final LongAdder NOT_DEFAULT_PARTS = new LongAdder();
    static final LongAdder NO_TRACKER = new LongAdder();
    static final LongAdder TRACKER_INIT = new LongAdder();
    static final LongAdder TRACKER_RACE = new LongAdder();     // RuntimeException reading the tracker ⇒ linear remainder
    static final LongAdder VERIFY_OK = new LongAdder();
    static final LongAdder VERIFY_MISMATCH = new LongAdder();
    static final LongAdder MODE_SIM = new LongAdder(), MODE_MOD = new LongAdder();
    private static final AtomicInteger verifyTick = new AtomicInteger();
    private static volatile boolean disabled;
    private static volatile String state = "not evaluated";
    private static volatile int initState; // 0 not yet, 1 ok, -1 failed

    static final String CFI = "net.p3pp3rf1y.sophisticatedcore.inventory.CachedFailedInsertInventoryHandler";
    static final String FILTERED_MOD = "net.p3pp3rf1y.sophisticatedcore.inventory.FilteredItemHandler$Modifiable";
    static final String STORAGE_IH = "net.p3pp3rf1y.sophisticatedstorage.block.StorageWrapper$4";
    static final String BACKPACK_IH = "net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackInventoryHandler";
    static final String TRACKER = "net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandlerSlotTracker";
    static final String DEFAULT_PART = "net.p3pp3rf1y.sophisticatedcore.inventory.IInventoryPartHandler$Default";

    private static MethodHandle IH_GET_TRACKER, CFI_GETTER, FILTERED_INNER, IH_TRACKER, IH_PARTITIONER, PARTS, T_ITEMS, T_FULL, T_PARTIAL;
    private static Class<?> CFI_C, FILTERED_C, STORAGE_C, BACKPACK_C, TRACKER_C, DEFAULT_C;
    private static int sophBits;

    private SophExtractIndex() { }

    /** differential fixture only: A side forced through AE2's original loop (set on MAIN inside the fixture command). */
    static volatile boolean fxBypass;

    public static boolean active() {
        return APPLY && !disabled && !fxBypass && (initState > 0 || (initState == 0 && init()));
    }

    private static synchronized boolean init() {
        if (initState != 0) return initState > 0;
        try {
            int s = AppliedEnhRecheckElision.sophState();   // sha-pinned sophisticatedcore + storage/backpacks (audited)
            if (s <= 0) throw new IllegalStateException("sophisticated jar set not audited (" + s + ")");
            sophBits = s;
            ClassLoader cl = SophExtractIndex.class.getClassLoader();
            CFI_C = Class.forName(CFI, false, cl);
            FILTERED_C = Class.forName(FILTERED_MOD, false, cl);
            TRACKER_C = Class.forName(TRACKER, false, cl);
            DEFAULT_C = Class.forName(DEFAULT_PART, false, cl);
            if ((s & 1) != 0) STORAGE_C = Class.forName(STORAGE_IH, false, cl);
            if ((s & 2) != 0) BACKPACK_C = Class.forName(BACKPACK_IH, false, cl);
            Class<?> ih = Class.forName("net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler", false, cl);
            Class<?> part = Class.forName("net.p3pp3rf1y.sophisticatedcore.inventory.InventoryPartitioner", false, cl);
            Class<?> filtered = Class.forName("net.p3pp3rf1y.sophisticatedcore.inventory.FilteredItemHandler", false, cl);
            CFI_GETTER = getter(CFI_C, "wrappedHandlerGetter");
            FILTERED_INNER = getter(filtered, "inventoryHandler");
            IH_TRACKER = getter(ih, "slotTracker");
            IH_PARTITIONER = getter(ih, "inventoryPartitioner");
            IH_GET_TRACKER = MethodHandles.publicLookup().unreflect(ih.getMethod("getSlotTracker"))
                    .asType(MethodType.methodType(Object.class, Object.class));
            PARTS = getter(part, "inventoryPartHandlers");
            T_ITEMS = getter(TRACKER_C, "itemStackKeys");
            T_FULL = getter(TRACKER_C, "fullStackSlots");
            T_PARTIAL = getter(TRACKER_C, "partiallyFilledStackSlots");
            state = "ACTIVE mode=" + MODE + " verify=" + VERIFY + " soph=" + s;
            initState = 1;
            Ae2Compat.LOG.info("[nestworld-compat-ae2] SOPH-EXTRACT-INDEX {}", state);
            return true;
        } catch (Throwable t) {
            state = "DISABLED (init failed: " + t + ") — AE2 original loop used";
            initState = -1;
            Ae2Compat.LOG.warn("[nestworld-compat-ae2] SOPH-EXTRACT-INDEX {}", state);
            return false;
        }
    }

    private static MethodHandle getter(Class<?> c, String name) throws ReflectiveOperationException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return MethodHandles.lookup().unreflectGetter(f).asType(MethodType.methodType(Object.class, Object.class));
    }

    /** Returns the audited tracker of the handler chain, or null (⇒ original loop). Counts the reason. */
    static Object tracker(IItemHandler h) {
        try {
            if (h.getClass() != CFI_C) { NOT_SOPH.increment(); return null; }
            Object sup = (Object) CFI_GETTER.invokeExact((Object) h);
            String sn = sup.getClass().getName();
            boolean supOk = (sn.startsWith(AppliedEnhRecheckElision.STORAGE_LAMBDA) && (sophBits & 1) != 0)
                    || (sn.startsWith(AppliedEnhRecheckElision.BACKPACK_LAMBDA) && (sophBits & 2) != 0);
            if (!supOk) { NOT_SOPH.increment(); return null; }
            Object inner = ((Supplier<?>) sup).get();    // the same call CachedFailedInsertInventoryHandler makes per access
            if (inner != null && inner.getClass() == FILTERED_C) inner = (Object) FILTERED_INNER.invokeExact(inner);
            if (inner == null || (inner.getClass() != STORAGE_C && inner.getClass() != BACKPACK_C)) { NOT_SOPH.increment(); return null; }
            Object partitioner = (Object) IH_PARTITIONER.invokeExact(inner);
            Object[] parts = (Object[]) (Object) PARTS.invokeExact(partitioner);
            for (Object p : parts) if (p == null || p.getClass() != DEFAULT_C) { NOT_DEFAULT_PARTS.increment(); return null; }
            Object t = (Object) IH_TRACKER.invokeExact(inner);
            if (t != null && t.getClass() != TRACKER_C && INIT_TRACKER) {
                // Noop tracker = nobody inserted/extracted since load. InventoryHandler.getSlotTracker() builds it exactly
                // as Sophisticated's own first insert/extract does (listeners are still the no-op defaults then).
                t = (Object) IH_GET_TRACKER.invokeExact(inner);
                TRACKER_INIT.increment();
            }
            if (t == null || t.getClass() != TRACKER_C) { NO_TRACKER.increment(); return null; }
            return t;
        } catch (Throwable e) {
            NOT_SOPH.increment();
            return null;
        }
    }

    /** Smallest tracked slot &gt; prev holding a stack of {@code item}, -1 if none, -2 if the tracker could not be read. */
    @SuppressWarnings("unchecked")
    static int nextSlot(Object tracker, Item item, int prev) {
        try {
            Map<Item, Set<Object>> items = (Map<Item, Set<Object>>) (Object) T_ITEMS.invokeExact(tracker);
            Set<Object> keys = items.get(item);
            if (keys == null || keys.isEmpty()) return -1;
            Map<Object, Set<Integer>> full = (Map<Object, Set<Integer>>) (Object) T_FULL.invokeExact(tracker);
            Map<Object, Set<Integer>> partial = (Map<Object, Set<Integer>>) (Object) T_PARTIAL.invokeExact(tracker);
            int best = Integer.MAX_VALUE;
            for (Object k : keys) {
                best = Math.min(best, higher(partial.get(k), prev));
                best = Math.min(best, higher(full.get(k), prev));
            }
            return best == Integer.MAX_VALUE ? -1 : best;
        } catch (Throwable t) {
            return -2;
        }
    }

    private static int higher(Set<Integer> s, int prev) {
        if (s == null || s.isEmpty()) return Integer.MAX_VALUE;
        Integer h = ((NavigableSet<Integer>) s).higher(prev);  // ClassCastException ⇒ -2 path (not a TreeSet: unaudited)
        return h == null ? Integer.MAX_VALUE : h;
    }

    /**
     * Called by the innermost extractExternal wrapper. Returns -1 when the original loop must run (not applicable),
     * otherwise the extracted amount.
     */
    public static int extract(IItemHandler handler, AEItemKey key, int amount, Actionable mode, SlotExtractor body,
                              java.util.function.IntSupplier original) {
        CALLS.increment();
        if (mode == Actionable.SIMULATE) MODE_SIM.increment(); else MODE_MOD.increment();
        Object tracker = tracker(handler);
        if (tracker == null) return -1;
        int n = handler.getSlots();
        SLOTS_FULL.add(n);
        if (CENSUS) {
            int visited = 0;
            for (int s = nextSlot(tracker, key.getItem(), -1); s >= 0 && s < n; s = nextSlot(tracker, key.getItem(), s)) visited++;
            SLOTS_VISITED.add(visited);
            if (visited == 0) INDEXED_MISS.increment();
            return -1;                                                    // count only: AE2's loop runs
        }
        Item item = key.getItem();
        int total = 0, prev = -1, visited = 0;
        while (true) {
            int s = nextSlot(tracker, item, prev);
            if (s == -2) {                                                // concurrent mutation by another thread: finish
                TRACKER_RACE.increment();                                  // exactly like AE2 (linear from prev + 1)
                for (int i = prev + 1; i < handler.getSlots(); i++) {
                    total += body.extract(handler, i, key, amount - total, mode);
                    visited++;
                    if (amount == total) break;
                }
                break;
            }
            if (s < 0 || s >= handler.getSlots()) break;                  // AE2 never visits slots >= getSlots()
            total += body.extract(handler, s, key, amount - total, mode);
            visited++;
            if (amount == total) break;
            prev = s;
        }
        INDEXED.increment();
        SLOTS_VISITED.add(visited);
        if (visited == 0) INDEXED_MISS.increment();
        if (VERIFY > 0 && mode == Actionable.SIMULATE && verifyTick.incrementAndGet() % VERIFY == 0) {
            int ref = original.getAsInt();                                // SIMULATE: no side effects
            if (ref != total) {
                VERIFY_MISMATCH.increment();
                disabled = true;
                state = "DISABLED (verify mismatch: index=" + total + " ae2=" + ref + " item=" + key + " amount=" + amount + ")";
                Ae2Compat.LOG.error("[nestworld-compat-ae2] SOPH-EXTRACT-INDEX {}", state);
                return ref;
            }
            VERIFY_OK.increment();
        }
        return total;
    }

    public static void reset() {
        for (LongAdder a : new LongAdder[]{CALLS, INDEXED, INDEXED_MISS, SLOTS_FULL, SLOTS_VISITED, NOT_SOPH, NOT_DEFAULT_PARTS,
                NO_TRACKER, TRACKER_INIT, TRACKER_RACE, VERIFY_OK, VERIFY_MISMATCH, MODE_SIM, MODE_MOD}) a.reset();
    }

    public static String summary() {
        long full = SLOTS_FULL.sum(), vis = SLOTS_VISITED.sum();
        return "sophExtractIndex " + (APPLY ? state : "OFF (flag)") + (disabled ? " [DISABLED]" : "")
                + " | calls=" + CALLS.sum() + " sim=" + MODE_SIM.sum() + " mod=" + MODE_MOD.sum()
                + " indexed=" + INDEXED.sum() + " absentItem=" + INDEXED_MISS.sum()
                + " slotsAe2Loop=" + full + " slotsVisited=" + vis
                + (full > 0 ? String.format(" (%.2f%%)", 100.0 * vis / full) : "")
                + " | fallback notSoph=" + NOT_SOPH.sum() + " nonDefaultParts=" + NOT_DEFAULT_PARTS.sum()
                + " noTracker=" + NO_TRACKER.sum() + " trackerInit=" + TRACKER_INIT.sum() + " trackerRace=" + TRACKER_RACE.sum()
                + " | verify ok=" + VERIFY_OK.sum() + " mismatch(must be 0)=" + VERIFY_MISMATCH.sum();
    }
}
