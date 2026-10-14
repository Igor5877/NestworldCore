package net.nestworld.compat.ae2.glue;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.me.Grid;
import appeng.me.GridNode;
import appeng.me.energy.IEnergyOverlayGridConnection;
import appeng.me.service.EnergyService;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import net.nestworld.compat.ae2.kernel.DomainLock;
import net.nestworld.compat.ae2.kernel.DomainSpec;

/**
 * Domain resolution for AE2 entries (001 §3). Membership is read optimistically as a hint, locked in
 * ascending serial order by the kernel, and recomputed under the locks by {@code validate()}.
 */
public final class Specs {
    private Specs() {
    }

    /**
     * The energy overlay of one service: the service plus every service reachable through quartz
     * fibers ({@code IEnergyOverlayGridConnection.connectedEnergyServices}), as {@code EnergyOverlayGrid.buildCache}.
     */
    static Set<EnergyService> overlay(EnergyService start, Set<EnergyService> into) {
        ArrayDeque<EnergyService> work = new ArrayDeque<>();
        work.add(start);
        while (!work.isEmpty()) {
            EnergyService s = work.pop();
            if (!into.add(s)) continue;
            for (IEnergyOverlayGridConnection c : List.copyOf(s.getOverlayGridConnections())) {
                work.addAll(c.connectedEnergyServices());
            }
        }
        return into;
    }

    static Set<EnergyService> newSet() {
        return java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    }

    /** Base: resolve a member set, lock it, recompute it under the locks; equal means valid. */
    abstract static class MemberSpec implements DomainSpec {
        private Set<EnergyService> resolved;

        abstract Set<EnergyService> members();

        public List<DomainLock> resolve() {
            for (int i = 0; ; i++) {
                try {
                    resolved = members();
                    break;
                } catch (ConcurrentModificationException e) {
                    if (i > 100) throw e;                // a hint read raced a writer: read again
                }
            }
            ArrayList<DomainLock> locks = new ArrayList<>(resolved.size());
            for (EnergyService s : resolved) locks.add(((NwEnergyService) s).nw$lock());
            return locks;
        }

        public boolean validate() {
            Set<EnergyService> now = members();
            return now.size() == resolved.size() && resolved.containsAll(now) && extraValid();
        }

        boolean extraValid() {
            return true;
        }
    }

    /** E1–E11: the overlay domain of {@code service}. */
    public static DomainSpec energy(EnergyService service) {
        return new MemberSpec() {
            public boolean topology() {
                return false;
            }

            Set<EnergyService> members() {
                return overlay(service, newSet());
            }
        };
    }

    /** T1 / T2 / phase wrappers: the single per-grid lock (the grid's EnergyService). */
    public static DomainSpec grid(Grid grid) {
        return new MemberSpec() {
            public boolean topology() {
                return false;
            }

            Set<EnergyService> members() {
                Set<EnergyService> s = newSet();
                if (grid != null) s.add((EnergyService) grid.getEnergyService());
                return s;
            }
        };
    }

    /**
     * Entry validation (001 §3, stale service): the overlay of the node's <em>current</em> grid; valid
     * only if, under the locks, the node still belongs to a live grid whose service is held.
     */
    public static DomainSpec node(Supplier<IGridNode> node) {
        return new MemberSpec() {
            public boolean topology() {
                return false;
            }

            Set<EnergyService> members() {
                IGridNode n = node.get();
                IGrid g = n == null ? null : n.getGrid();
                return g == null ? newSet() : overlay((EnergyService) g.getEnergyService(), newSet());
            }

            boolean extraValid() {
                IGridNode n = node.get();
                IGrid g = n == null ? null : n.getGrid();
                return g == null || ((Grid) g).getPivot() != null;
            }
        };
    }

    /** G1: T0 plus the overlays of every grid the topology entry can touch. */
    public static DomainSpec topology(Supplier<List<Grid>> grids) {
        return new MemberSpec() {
            public boolean topology() {
                return true;
            }

            Set<EnergyService> members() {
                Set<EnergyService> s = newSet();
                for (Grid g : grids.get()) {
                    if (g != null) overlay((EnergyService) g.getEnergyService(), s);
                }
                return s;
            }
        };
    }

    /** {@code GridNode.myGrid} without AE2's lazy grid creation ({@code getInternalGrid}). */
    public static Grid myGrid(GridNode node) {
        return ((net.nestworld.compat.ae2.mixin.GridNodeAccessor) node).nw$myGrid();
    }

    /** The grids adjacent to a node: its own and those of every connected node. */
    public static List<Grid> around(GridNode node) {
        ArrayList<Grid> out = new ArrayList<>();
        out.add(myGrid(node));
        for (var c : node.getConnections()) {
            IGridNode other = c.getOtherSide(node);
            if (other instanceof GridNode gn) out.add(myGrid(gn));
        }
        return out;
    }
}
