package net.nestworld.region;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Binary Space Partitioning tree of WorldRegions.
 *
 * Leaves are active regions (each gets a RegionThread).
 * Internal nodes record the axis and coordinate of a past split.
 * All structural mutations (split / merge) must happen while all
 * region threads are paused at the tick barrier.
 */
public class RegionTree {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private Node root;
    private final WorldGrid grid;

    public RegionTree(WorldGrid grid, WorldRegion initial) {
        this.grid = grid;
        this.root = new Leaf(initial);
        grid.register(initial);
    }

    // -----------------------------------------------------------------------
    // Public API (called from RegionSplitManager while threads are at barrier)
    // -----------------------------------------------------------------------

    /** Returns all currently active (leaf) regions. Thread-safe read. */
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
     * Splits the given region along its preferred axis.
     * @return array of [childA, childB], or null if the region cannot be split.
     */
    public WorldRegion[] split(WorldRegion region) {
        lock.writeLock().lock();
        try {
            Leaf leaf = findLeaf(root, region);
            if (leaf == null || !region.canSplit()) return null;

            SplitAxis axis = region.preferredSplitAxis();
            int minX = region.getMinChunkX(), maxX = region.getMaxChunkX();
            int minZ = region.getMinChunkZ(), maxZ = region.getMaxChunkZ();

            WorldRegion a, b;
            if (axis == SplitAxis.X) {
                int midX = (minX + maxX) / 2;
                a = new WorldRegion(grid.nextId(), minX, minZ, midX,   maxZ);
                b = new WorldRegion(grid.nextId(), midX + 1, minZ, maxX, maxZ);
            } else {
                int midZ = (minZ + maxZ) / 2;
                a = new WorldRegion(grid.nextId(), minX, minZ, maxX, midZ);
                b = new WorldRegion(grid.nextId(), minX, midZ + 1, maxX, maxZ);
            }

            grid.unregister(region);
            grid.register(a);
            grid.register(b);

            replaceNode(root, null, leaf, new Branch(axis, new Leaf(a), new Leaf(b)));
            return new WorldRegion[]{a, b};
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Merges two sibling leaf regions back into their parent.
     * The caller must ensure both regions have no active threads before calling.
     * @return the merged region, or null if the two regions are not siblings.
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

    // -----------------------------------------------------------------------
    // Internal tree traversal
    // -----------------------------------------------------------------------

    private void collectLeaves(Node node, List<WorldRegion> out) {
        if (node instanceof Leaf l)        out.add(l.region);
        else if (node instanceof Branch b) { collectLeaves(b.left, out); collectLeaves(b.right, out); }
    }

    private Leaf findLeaf(Node node, WorldRegion target) {
        if (node instanceof Leaf l)        return l.region == target ? l : null;
        else if (node instanceof Branch b) {
            Leaf found = findLeaf(b.left, target);
            return found != null ? found : findLeaf(b.right, target);
        }
        return null;
    }

    private Branch findParent(Node node, Leaf targetA, Leaf targetB) {
        if (node instanceof Branch b) {
            if ((b.left == targetA && b.right == targetB) ||
                (b.left == targetB && b.right == targetA)) return b;
            Branch found = findParent(b.left, targetA, targetB);
            return found != null ? found : findParent(b.right, targetA, targetB);
        }
        return null;
    }

    /** Replaces oldNode with newNode anywhere in the subtree rooted at current. */
    private void replaceNode(Node current, Branch parent, Node oldNode, Node newNode) {
        if (current == oldNode) { root = newNode; return; }
        if (current instanceof Branch b) {
            if (b.left  == oldNode) { b.left  = newNode; return; }
            if (b.right == oldNode) { b.right = newNode; return; }
            replaceNode(b.left,  b, oldNode, newNode);
            replaceNode(b.right, b, oldNode, newNode);
        }
    }

    // -----------------------------------------------------------------------
    // Node types
    // -----------------------------------------------------------------------

    private abstract static class Node {}

    private static final class Leaf extends Node {
        WorldRegion region;
        Leaf(WorldRegion r) { region = r; }
    }

    private static final class Branch extends Node {
        final SplitAxis axis;
        Node left, right;
        Branch(SplitAxis axis, Node left, Node right) { this.axis = axis; this.left = left; this.right = right; }
    }
}
