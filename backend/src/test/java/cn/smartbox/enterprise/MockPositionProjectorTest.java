package cn.smartbox.enterprise;

import java.util.List;
import org.junit.jupiter.api.Test;
import cn.smartbox.enterprise.EnterpriseViews.Point;
import cn.smartbox.enterprise.EnterpriseViews.Position;
import cn.smartbox.enterprise.EnterpriseViews.Route;
import static org.junit.jupiter.api.Assertions.*;

class MockPositionProjectorTest {
    private final MockPositionProjector projector = new MockPositionProjector();
    private final Route route = new Route("TEST-01", "测试路线", "A", "C", 100_000,
        List.of(new Point(0, 0, "A"), new Point(0, 100, "B"), new Point(100, 100, "C")));

    @Test void readyStaysAtOrigin() {
        Position position = projector.project(route, "READY", null, null, 5_000, true, null);
        assertEquals(0, position.progress());
        assertEquals(0, position.x());
        assertEquals(0, position.y());
        assertEquals("A", position.place());
        assertFalse(position.stale());
    }

    @Test void activeInterpolatesByServerTime() {
        Position position = projector.project(route, "ACTIVE", 1_000L, null, 51_000L, true, null);
        assertEquals(0.5, position.progress());
        assertEquals(0, position.x());
        assertEquals(100, position.y());
        assertEquals("B", position.place());
        assertFalse(position.stale());
    }

    @Test void offlineFreezesAtLastSeenAndMarksStale() {
        Position position = projector.project(route, "ACTIVE", 1_000L, null, 90_000L, false, 21_000L);
        assertEquals(0.2, position.progress());
        assertEquals(40, position.y());
        assertTrue(position.stale());
        assertEquals(21_000L, position.position_at());
    }

    @Test void completedUsesEndedAtAndDoesNotAdvance() {
        Position position = projector.project(route, "COMPLETED", 1_000L, 51_000L, 999_000L, true, null);
        assertEquals(0.5, position.progress());
        assertEquals(100, position.y());
        assertFalse(position.finished());
        Position full = projector.project(route, "COMPLETED", 1_000L, 101_000L, 999_000L, true, null);
        assertEquals(1, full.progress());
        assertTrue(full.finished());
    }

    @Test void missingRouteHasNoPosition() {
        assertNull(projector.project(null, "ACTIVE", 1_000L, null, 2_000L, true, null));
    }
}
