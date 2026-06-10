package net.nestworld.region;

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

    // --- Public API ---

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
        lock.writeLock().lock();
        try {
            Leaf leaf = findLeaf(root, region);
            if (leaf == null || !region.canSplit()) return null;

            SplitAxis axis = region.preferredSplitAxis();
            int minX = region.getMinChunkX(), maxX = region.getMaxChunkX();
            int minZ = region.getMinChunkZ(), maxZ = region.getMaxChunkZ();

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
