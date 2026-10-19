package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a tile belongs and which moves are allowed. Pure list arithmetic, so it can be tested on the
 * JVM: the dock is the row of configured apps the user may also have arranged himself, the grid is
 * everything else, and a tile never crosses from one to the other.
 */
final class TileOrder {

    private TileOrder() {
    }

    /**
     * Slot of a dock app: the ones the user moved come first, in his own order, then the rest of the
     * configured dock in config order. -1 for an app that is not a dock app at all.
     */
    static int dockRank(List<String> dockOrder, List<String> favourites, String pkg) {
        if (pkg == null || !favourites.contains(pkg)) return -1;
        int i = 0;
        for (String p : dockOrder) {
            if (!favourites.contains(p)) continue;   // a dock app gone from the config: not a slot
            if (p.equals(pkg)) return i;
            i++;
        }
        for (String p : favourites) {
            if (dockOrder.contains(p)) continue;
            if (p.equals(pkg)) return i;
            i++;
        }
        return -1;
    }

    /** Slot in the grid according to the arrangement the user saved by hand; -1 when he never moved it. */
    static int gridRank(List<String> order, String pkg) {
        return pkg == null ? -1 : order.indexOf(pkg);
    }

    /** How many leading tiles are dock apps: the dock row is over at the first grid tile. */
    static int dockCount(List<String> pkgsInOrder, List<String> favourites) {
        int n = 0;
        for (String pkg : pkgsInOrder) {
            if (!favourites.contains(pkg)) break;
            n++;
        }
        return n;
    }

    /** A tile only moves inside its own section: dock stays dock, grid stays grid. */
    static boolean canMove(int from, int to, int dockCount, int size) {
        if (from == to || from < 0 || to < 0) return false;
        if (from >= size || to >= size) return false;
        return (from < dockCount) == (to < dockCount);
    }

    /** The list after moving the tile at {@code from} to {@code to}; unchanged when the move is refused. */
    static <T> List<T> moved(List<T> items, int from, int to, int dockCount) {
        List<T> out = new ArrayList<>(items);
        if (!canMove(from, to, dockCount, out.size())) return out;
        out.add(to, out.remove(from));
        return out;
    }
}
