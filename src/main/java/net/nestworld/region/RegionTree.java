package net.nestworld.region;

import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Binary Space Partitioning (BSP) tree of WorldRegions.
 *
 * Leaves  = active regions, each backed by a RegionThread.
 * Branches = internal split points (two children, one axis).
 *
 * All structural mutations (split / merge) acquire the write lock so they are
 * applied atomically between ticks.
 */
public class RegionTree {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private Node root;
    private final WorldGrid grid;

    public RegionTree(WorldGrid grid, WorldRegion initialRegion) {
        this.grid = grid;
        this.root = new Leaf(initialRegion);
        grid.register(initialRegion);
    }

    /**
     * Rebuilds a tree from a previously-saved layout (see {@link #writeNbt()}).
     * Every leaf is given a fresh region id and registered in {@code grid};
     * callers must spawn a RegionThread per active region afterwards. The tree
     * structure (split axes and nesting) is preserved so the saved topology can
     * later merge back exactly as it would have if grown from scratch — this is
     * what lets the server boot straight into a sharded layout instead of
     * spending the first ~100 ticks as one whole-world region on one thread.
     */
    public RegionTree(WorldGrid grid, CompoundTag saved) {
        this.grid = grid;
        this.root = readNode(saved.getCompound("root"));
    }

    // --- Public API ---

    /** The WorldGrid this tree keeps in sync with its own leaf set. */
    public WorldGrid getGrid() { return grid; }

    /** Returns all active (leaf) regions in an unordered list. */
    public List<WorldRegion> getActiveRegions() {
        lock.readLock().lock();
        try {
            List<WorldRegion> out = new ArrayList<>();
            collectLeaves(root, out);
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Splits {@code region} into two child regions along its preferred axis.
     * Returns the pair [childA, childB], or null if the region cannot be split
     * (size already 1×1) or is not found in this tree.
     */
    public WorldRegion[] split(WorldRegion region) {
        return split(region, Integer.MIN_VALUE);
    }

    /**
     * Splits {@code region} along its preferred axis at the given cut: the
     * last chunk coordinate (on that axis) that goes to child A. Pass
     * {@link Integer#MIN_VALUE} for the spatial midpoint. Callers should
     * prefer the load median: a midpoint cut of a world-sized region around a
     * single hotspot just peels off an empty half each time, producing a
     * degenerate comb of idle regions whose tree siblings are all hot — so
     * they can never merge back.
     */
    public WorldRegion[] split(WorldRegion region, int cut) {
        return split(region, region.preferredSplitAxis(), cut);
    }

    /**
     * Splits {@code region} along an explicit {@code axis} at the given cut.
     * Identical to {@link #split(WorldRegion, int)} but lets the load-aware
     * scorer pick the perpendicular (shorter) axis when that yields a cleaner
     * separation than the preferred one — e.g. a machine elongated along the
     * long axis is best cut across its short axis. Returns null if that axis
     * has only one chunk to give (no room to cut).
     */
    public WorldRegion[] split(WorldRegion region, SplitAxis axis, int cut) {
        lock.writeLock().lock();
        try {
            Leaf leaf = findLeaf(root, region);
            if (leaf == null || !region.canSplit()) return null;

            int minX = region.getMinChunkX(), maxX = region.getMaxChunkX();
            int minZ = region.getMinChunkZ(), maxZ = region.getMaxChunkZ();
            // The chosen axis must have at least two chunks to divide.
            if (axis == SplitAxis.X ? maxX == minX : maxZ == minZ) return null;

            WorldRegion childA, childB;
            if (axis == SplitAxis.X) {
                int mid = cut == Integer.MIN_VALUE ? (minX + maxX) / 2
                        : Math.max(minX, Math.min(cut, maxX - 1));
                childA = new WorldRegion(grid.nextId(), minX,   minZ, mid,  maxZ);
                childB = new WorldRegion(grid.nextId(), mid + 1, minZ, maxX, maxZ);
            } else {
                int mid = cut == Integer.MIN_VALUE ? (minZ + maxZ) / 2
                        : Math.max(minZ, Math.min(cut, maxZ - 1));
                childA = new WorldRegion(grid.nextId(), minX, minZ,   maxX, mid);
                childB = new WorldRegion(grid.nextId(), minX, mid + 1, maxX, maxZ);
            }

            grid.unregister(region);
            grid.register(childA);
            grid.register(childB);

            Branch branch = new Branch(axis, new Leaf(childA), new Leaf(childB));
            replaceNode(root, null, leaf, branch);

            return new WorldRegion[]{childA, childB};
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Merges two sibling leaf regions back into one parent region.
     * Returns the merged region, or null if they are not siblings in this tree.
     */
    public WorldRegion merge(WorldRegion a, WorldRegion b) {
        lock.writeLock().lock();
        try {
            Leaf leafA = findLeaf(root, a);
            Leaf leafB = findLeaf(root, b);
            if (leafA == null || leafB == null) return null;

            Branch parent = findParent(root, leafA, leafB);
            if (parent == null) return null;

            int minX = Math.min(a.getMinChunkX(), b.getMinChunkX());
            int minZ = Math.min(a.getMinChunkZ(), b.getMinChunkZ());
            int maxX = Math.max(a.getMaxChunkX(), b.getMaxChunkX());
            int maxZ = Math.max(a.getMaxChunkZ(), b.getMaxChunkZ());

            WorldRegion merged = new WorldRegion(grid.nextId(), minX, minZ, maxX, maxZ);
            grid.unregister(a);
            grid.unregister(b);
            grid.register(merged);

            replaceNode(root, null, parent, new Leaf(merged));
            return merged;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // --- Tree traversal ---

    private void collectLeaves(Node node, List<WorldRegion> out) {
        if (node instanceof Leaf l) {
            out.add(l.region);
        } else if (node instanceof Branch b) {
            collectLeaves(b.left, out);
            collectLeaves(b.right, out);
        }
    }

    private Leaf findLeaf(Node node, WorldRegion target) {
        if (node instanceof Leaf l) return l.region == target ? l : null;
        if (node instanceof Branch b) {
            Leaf found = findLeaf(b.left, target);
            return found != null ? found : findLeaf(b.right, target);
        }
        return null;
    }

    private Branch findParent(Node node, Leaf targetA, Leaf targetB) {
        if (node instanceof Branch b) {
            if ((b.left == targetA && b.right == targetB)
                    || (b.left == targetB && b.right == targetA)) return b;
            Branch found = findParent(b.left, targetA, targetB);
            return found != null ? found : findParent(b.right, targetA, targetB);
        }
        return null;
    }

    /** Replaces {@code target} with {@code replacement} anywhere in the subtree rooted at {@code node}. */
    private void replaceNode(Node node, Branch parentOfNode, Node target, Node replacement) {
        if (node == target) {
            if (parentOfNode == null) { root = replacement; return; }
            if (parentOfNode.left == target) parentOfNode.left = replacement;
            else parentOfNode.right = replacement;
            return;
        }
        if (node instanceof Branch b) {
            replaceNode(b.left, b, target, replacement);
            replaceNode(b.right, b, target, replacement);
        }
    }

    // --- Persistence ---

    /**
     * Serialises the whole BSP structure (branch axes + leaf rectangles and
     * their pinned flag) to NBT. Region ids and live tick/ownership state are
     * deliberately NOT saved — they are recreated fresh on load.
     */
    public CompoundTag writeNbt() {
        lock.readLock().lock();
        try {
            CompoundTag tag = new CompoundTag();
            tag.put("root", writeNode(root));
            return tag;
        } finally {
            lock.readLock().unlock();
        }
    }

    private CompoundTag writeNode(Node node) {
        CompoundTag t = new CompoundTag();
        if (node instanceof Leaf l) {
            WorldRegion r = l.region;
            t.putString("type", "leaf");
            t.putInt("minX", r.getMinChunkX());
            t.putInt("minZ", r.getMinChunkZ());
            t.putInt("maxX", r.getMaxChunkX());
            t.putInt("maxZ", r.getMaxChunkZ());
            t.putBoolean("pinned", r.pinned);
        } else if (node instanceof Branch b) {
            t.putString("type", "branch");
            t.putString("axis", b.axis.name());
            t.put("left", writeNode(b.left));
            t.put("right", writeNode(b.right));
        }
        return t;
    }

    private Node readNode(CompoundTag t) {
        if ("branch".equals(t.getString("type"))) {
            SplitAxis axis = SplitAxis.valueOf(t.getString("axis"));
            Node left = readNode(t.getCompound("left"));
            Node right = readNode(t.getCompound("right"));
            return new Branch(axis, left, right);
        }
        WorldRegion r = new WorldRegion(grid.nextId(),
                t.getInt("minX"), t.getInt("minZ"), t.getInt("maxX"), t.getInt("maxZ"));
        r.pinned = t.getBoolean("pinned");
        grid.register(r);
        return new Leaf(r);
    }

    // --- Self-test (NESTWORLD_LAYOUT_TEST) ---

    /**
     * Deterministic round-trip check of {@link #writeNbt()} /
     * {@link #RegionTree(WorldGrid, CompoundTag)}: a saved layout must reload
     * to the identical set of leaf rectangles AND preserve the branch nesting
     * (so reloaded siblings still merge). Guards the persistent-layout feature.
     */
    public static void selfTest() {
        org.apache.logging.log4j.Logger log =
                org.apache.logging.log4j.LogManager.getLogger("NestWorld/RegionTree");

        // Case 1: single split -> reload -> the two leaves still merge (nesting).
        WorldGrid g1 = new WorldGrid();
        WorldRegion root1 = new WorldRegion(g1.nextId(), -100, -100, 100, 100);
        RegionTree t1 = new RegionTree(g1, root1);
        t1.split(root1, SplitAxis.X, 0);
        WorldGrid g1b = new WorldGrid();
        RegionTree t1b = new RegionTree(g1b, t1.writeNbt());
        List<WorldRegion> leaves1 = t1b.getActiveRegions();
        boolean twoLeaves = leaves1.size() == 2;
        WorldRegion merged = twoLeaves ? t1b.merge(leaves1.get(0), leaves1.get(1)) : null;
        boolean mergeRestoresRoot = merged != null
                && merged.getMinChunkX() == -100 && merged.getMaxChunkX() == 100
                && merged.getMinChunkZ() == -100 && merged.getMaxChunkZ() == 100;

        // Case 2: two-level tree -> reload -> identical leaf rectangle set.
        WorldGrid g2 = new WorldGrid();
        WorldRegion root2 = new WorldRegion(g2.nextId(), -100, -100, 100, 100);
        RegionTree t2 = new RegionTree(g2, root2);
        WorldRegion[] kids = t2.split(root2, SplitAxis.X, 0);
        t2.split(kids[0], SplitAxis.Z, 0);
        java.util.Set<String> before = rectSet(t2.getActiveRegions());
        WorldGrid g2b = new WorldGrid();
        RegionTree t2b = new RegionTree(g2b, t2.writeNbt());
        java.util.Set<String> after = rectSet(t2b.getActiveRegions());
        boolean rectsMatch = before.size() == 3 && before.equals(after);

        // Case 3: pinned flag survives the round-trip.
        WorldGrid g3 = new WorldGrid();
        WorldRegion root3 = new WorldRegion(g3.nextId(), -50, -50, 50, 50);
        RegionTree t3 = new RegionTree(g3, root3);
        WorldRegion[] k3 = t3.split(root3, SplitAxis.X, 0);
        k3[0].pinned = true;
        RegionTree t3b = new RegionTree(new WorldGrid(), t3.writeNbt());
        boolean pinnedKept = t3b.getActiveRegions().stream().filter(r -> r.pinned).count() == 1;

        boolean ok = twoLeaves && mergeRestoresRoot && rectsMatch && pinnedKept;
        log.info("[LAYOUT SELF-TEST] {} (twoLeaves={}, mergeRestoresRoot={}, rectsMatch={}, "
                + "pinnedKept={})", ok ? "PASS" : "FAIL", twoLeaves, mergeRestoresRoot,
                rectsMatch, pinnedKept);
    }

    private static java.util.Set<String> rectSet(List<WorldRegion> regions) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (WorldRegion r : regions) {
            out.add(r.getMinChunkX() + "," + r.getMinChunkZ() + ","
                    + r.getMaxChunkX() + "," + r.getMaxChunkZ());
        }
        return out;
    }

    // --- Node types ---

    private abstract static class Node {}

    private static final class Leaf extends Node {
        WorldRegion region;
        Leaf(WorldRegion r) { this.region = r; }
    }

    private static final class Branch extends Node {
        final SplitAxis axis;
        Node left, right;
        Branch(SplitAxis axis, Node left, Node right) {
            this.axis = axis; this.left = left; this.right = right;
        }
    }
}
