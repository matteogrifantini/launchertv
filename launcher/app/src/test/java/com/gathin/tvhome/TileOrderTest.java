package com.gathin.tvhome;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Where a tile sits in the dock and in the grid, and which moves are allowed. */
public class TileOrderTest {

    private static final List<String> DOCK = Arrays.asList("netflix", "prime", "youtube", "livetv");

    // ---- dockRank --------------------------------------------------------------------------------

    @Test
    public void dockRankPrefersTheUsersOwnDockOrder() {
        List<String> saved = Arrays.asList("youtube", "netflix");

        assertEquals(0, TileOrder.dockRank(saved, DOCK, "youtube"));
        assertEquals(1, TileOrder.dockRank(saved, DOCK, "netflix"));
    }

    @Test
    public void dockRankPutsDockAppsTheUserNeverMovedAfterTheOnesHeDid() {
        List<String> saved = Arrays.asList("youtube", "netflix");

        // "prime" is not in the saved order: it follows the two moved apps, then its config position.
        assertEquals(2, TileOrder.dockRank(saved, DOCK, "prime"));
        assertEquals(3, TileOrder.dockRank(saved, DOCK, "livetv"));
    }

    @Test
    public void dockRankIgnoresAppsThatAreNotDockApps() {
        assertEquals(-1, TileOrder.dockRank(Arrays.asList("youtube"), DOCK, "kodi"));
    }

    // ---- gridRank --------------------------------------------------------------------------------

    @Test
    public void gridRankFollowsTheSavedOrderThenGivesUp() {
        List<String> saved = Arrays.asList("kodi", "webtv");

        assertEquals(0, TileOrder.gridRank(saved, "kodi"));
        assertEquals(1, TileOrder.gridRank(saved, "webtv"));
        assertEquals(-1, TileOrder.gridRank(saved, "playstore"));
        assertEquals(-1, TileOrder.gridRank(Collections.emptyList(), "kodi"));
    }

    // ---- dockCount -------------------------------------------------------------------------------

    @Test
    public void dockCountCountsTheDockAppsAtTheStartOfTheListOnly() {
        List<String> tiles = Arrays.asList("netflix", "youtube", "kodi", "prime");

        // "prime" is a dock app, but it sits after a grid app: the dock row is over.
        assertEquals(2, TileOrder.dockCount(tiles, DOCK));
    }

    @Test
    public void dockCountSkipsDockAppsTheTvDoesNotHave() {
        List<String> tiles = Arrays.asList("netflix", "kodi");

        // only "netflix" of the two configured dock apps is on this TV, and "kodi" is not one.
        assertEquals(1, TileOrder.dockCount(tiles, DOCK));
    }

    @Test
    public void dockCountIsZeroWhenNothingIsADockApp() {
        assertEquals(0, TileOrder.dockCount(Arrays.asList("kodi", "webtv"), DOCK));
        assertEquals(0, TileOrder.dockCount(Collections.emptyList(), DOCK));
    }

    // ---- canMove ---------------------------------------------------------------------------------

    @Test
    public void aMoveInsideASectionIsAllowed() {
        assertTrue(TileOrder.canMove(0, 2, 4, 10));
        assertTrue(TileOrder.canMove(3, 1, 4, 10));
        assertTrue(TileOrder.canMove(5, 9, 4, 10));
    }

    @Test
    public void aMoveThatWouldCrossDockAndGridIsRefused() {
        assertFalse(TileOrder.canMove(3, 4, 4, 10));
        assertFalse(TileOrder.canMove(4, 3, 4, 10));
    }

    @Test
    public void aMoveToTheSameSlotOrOutsideTheTilesIsRefused() {
        assertFalse(TileOrder.canMove(2, 2, 4, 10));
        assertFalse(TileOrder.canMove(-1, 2, 4, 10));
        assertFalse(TileOrder.canMove(2, -1, 4, 10));
        assertFalse(TileOrder.canMove(2, 10, 4, 10));
        assertFalse(TileOrder.canMove(10, 2, 4, 10));
    }

    // ---- moved -----------------------------------------------------------------------------------

    @Test
    public void movedReturnsTheListWithTheTileAtItsNewSlot() {
        List<String> tiles = new ArrayList<>(Arrays.asList("a", "b", "c", "d"));

        assertEquals(Arrays.asList("b", "c", "a", "d"), TileOrder.moved(tiles, 0, 2, 0));
    }

    @Test
    public void movedKeepsTheDockAndTheGridApart() {
        List<String> tiles = new ArrayList<>(Arrays.asList("a", "b", "c", "d"));

        assertEquals(Arrays.asList("a", "b", "c", "d"), TileOrder.moved(tiles, 1, 2, 2));
    }
}
